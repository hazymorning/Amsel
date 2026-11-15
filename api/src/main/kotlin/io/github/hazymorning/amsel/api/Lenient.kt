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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * A provider can fill a field the models declare as int with a float, a string or a number far
 * beyond Int (Audiobookshelf's episode `position` is a publish time in epoch milliseconds). One
 * such value must cost that field, never the item, and never the queue that holds the item.
 */

private fun Decoder.element(): JsonElement? = (this as? JsonDecoder)?.decodeJsonElement()

private fun JsonElement?.asLong(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull
        ?: primitive.doubleOrNull?.takeIf { it.isFinite() && it in LONG_RANGE }?.toLong()
}

private val LONG_RANGE = Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()

/** A whole number sent as an int, a float or a numeric string; anything else reads as null. */
internal object LenientLong : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG).nullable

    override fun deserialize(decoder: Decoder): Long? = decoder.element().asLong()

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Long?) =
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
}

/** As [LenientLong]; a value outside Int reads as null. */
internal object LenientInt : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT).nullable

    override fun deserialize(decoder: Decoder): Int? =
        decoder.element().asLong()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: Int?) =
        if (value == null) encoder.encodeNull() else encoder.encodeInt(value)
}

/** A repeat mode by its name; one this app does not know (MA's `unknown`) reads as off. */
internal object RepeatModeSerializer : KSerializer<RepeatMode> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("RepeatMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): RepeatMode {
        val name = (decoder.element() as? JsonPrimitive)?.contentOrNull
        return RepeatMode.entries.firstOrNull { it.api == name } ?: RepeatMode.Off
    }

    override fun serialize(encoder: Encoder, value: RepeatMode) = encoder.encodeString(value.api)
}

/** An object that reads as null when it does not decode, instead of failing what holds it. */
internal open class OrNull<T : Any>(private val inner: KSerializer<T>) : KSerializer<T?> {
    override val descriptor: SerialDescriptor = inner.nullable.descriptor

    override fun deserialize(decoder: Decoder): T? {
        val element = decoder.element() as? JsonObject ?: return null
        return decodeLeniently(inner, element)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: T?) =
        if (value == null) encoder.encodeNull() else encoder.encodeSerializableValue(inner, value)
}

/** A list whose entries that do not decode are left out, instead of failing the list. */
internal open class SkipBad<T>(private val inner: KSerializer<T>) : KSerializer<List<T>> {
    private val list = ListSerializer(inner)

    override val descriptor: SerialDescriptor = list.descriptor

    override fun deserialize(decoder: Decoder): List<T> {
        val array = decoder.element() as? JsonArray ?: return emptyList()
        return array.mapNotNull { decodeLeniently(inner, it) }
    }

    override fun serialize(encoder: Encoder, value: List<T>) = encoder.encodeSerializableValue(list, value)
}

/**
 * An author or narrator. Library items name them as artists (an ItemMapping with a uri), while
 * some providers send plain names (2.10 local files).
 */
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

    override fun serialize(encoder: Encoder, value: Credit) = encoder.encodeString(value.name)
}

internal object CreditList : SkipBad<Credit>(CreditSerializer)

internal object MappingList : SkipBad<ItemMapping>(ItemMapping.serializer())

internal object ProviderMappingList : SkipBad<ProviderMapping>(ProviderMapping.serializer())

internal object ImageList : SkipBad<MediaItemImage>(MediaItemImage.serializer())

internal object ChapterList : SkipBad<Chapter>(Chapter.serializer())

internal object MappingOrNull : OrNull<ItemMapping>(ItemMapping.serializer())

internal object MetadataOrNull : OrNull<MediaItemMetadata>(MediaItemMetadata.serializer())

internal object MediaItemOrNull : OrNull<MediaItem>(MediaItem.serializer())

internal object MediaItemList : SkipBad<MediaItem>(MediaItem.serializer())

internal object TaskList : SkipBad<BackgroundTask>(BackgroundTask.serializer())

internal object QueueItemOrNull : OrNull<QueueItem>(QueueItem.serializer())
