package io.github.hazymorning.amsel.api

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/*
 * Providers fill int fields with floats, strings or values beyond Int (Audiobookshelf's episode
 * `position` is epoch milliseconds). Such a value reads as null instead of failing the item or queue.
 */

private fun Decoder.element(): JsonElement? = (this as? JsonDecoder)?.decodeJsonElement()

private fun JsonElement?.asLong(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull
        ?: primitive.doubleOrNull?.takeIf { it.isFinite() && it in LONG_RANGE }?.toLong()
}

private val LONG_RANGE = Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()

internal object LenientLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG).nullable

    override fun deserialize(decoder: Decoder): Long? = decoder.element().asLong()

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Long?) =
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
}

internal object LenientIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT).nullable

    override fun deserialize(decoder: Decoder): Int? =
        decoder.element().asLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Int?) =
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
}

/** MA also sends `unknown`; a mode not in [RepeatMode] reads as off. */
internal object RepeatModeSerializer : KSerializer<RepeatMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RepeatMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RepeatMode {
        val name = (decoder.element() as? JsonPrimitive)?.contentOrNull
        return RepeatMode.entries.firstOrNull { it.api == name } ?: RepeatMode.Off
    }

    override fun serialize(encoder: Encoder, value: RepeatMode) = encoder.encodeString(value.api)
}

internal open class OrNullSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.nullable.descriptor

    override fun deserialize(decoder: Decoder): T? {
        val element = decoder.element() as? JsonObject ?: return null
        return decodeLeniently(inner, element)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: T?) =
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(inner, value)
}

internal open class SkipInvalidSerializer<T>(private val inner: KSerializer<T>) : KSerializer<List<T>> {
    private val list = ListSerializer(inner)

    override val descriptor: SerialDescriptor = list.descriptor

    override fun deserialize(decoder: Decoder): List<T> {
        val array = decoder.element() as? JsonArray ?: return emptyList()
        return array.mapNotNull { decodeLeniently(inner, it) }
    }

    override fun serialize(encoder: Encoder, value: List<T>) = encoder.encodeSerializableValue(list, value)
}

/** An author or narrator: an ItemMapping with a uri from library items, a plain name from 2.10 local files. */
@kotlinx.serialization.Serializable(with = CreditSerializer::class)
public data class Credit(val name: String, val uri: String? = null)

internal object CreditSerializer : KSerializer<Credit> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Credit", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Credit = when (val element = decoder.element()) {
        is JsonObject -> Credit(
            name = (element["name"] as? JsonPrimitive)?.contentOrNull
                ?: throw SerializationException("A credit without a name"),
            uri = (element["uri"] as? JsonPrimitive)?.contentOrNull,
        )

        is JsonPrimitive -> {
            val name = if (element.isString) element.content else ""
            Credit(name.ifBlank { throw SerializationException("Not a name: $element") })
        }

        else -> throw SerializationException("Not a credit: $element")
    }

    // As the server sends it, so an item stored on the phone reads back with its author's uri.
    override fun serialize(encoder: Encoder, value: Credit) {
        val json = encoder as? JsonEncoder ?: return encoder.encodeString(value.name)
        json.encodeJsonElement(
            buildJsonObject {
                put("name", value.name)
                value.uri?.let { put("uri", it) }
            },
        )
    }
}

internal object CreditListSerializer : SkipInvalidSerializer<Credit>(CreditSerializer)

internal object MappingListSerializer : SkipInvalidSerializer<ItemMapping>(ItemMapping.serializer())

internal object ProviderMappingListSerializer : SkipInvalidSerializer<ProviderMapping>(ProviderMapping.serializer())

internal object ImageListSerializer : SkipInvalidSerializer<MediaItemImage>(MediaItemImage.serializer())

internal object ChapterListSerializer : SkipInvalidSerializer<Chapter>(Chapter.serializer())

internal object MappingOrNullSerializer : OrNullSerializer<ItemMapping>(ItemMapping.serializer())

internal object MetadataOrNullSerializer : OrNullSerializer<MediaItemMetadata>(MediaItemMetadata.serializer())

internal object MediaItemOrNullSerializer : OrNullSerializer<MediaItem>(MediaItem.serializer())

internal object MediaItemListSerializer : SkipInvalidSerializer<MediaItem>(MediaItem.serializer())

internal object TaskListSerializer : SkipInvalidSerializer<BackgroundTask>(BackgroundTask.serializer())

internal object QueueItemOrNullSerializer : OrNullSerializer<QueueItem>(QueueItem.serializer())
