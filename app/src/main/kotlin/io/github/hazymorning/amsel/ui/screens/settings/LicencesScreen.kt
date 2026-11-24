package io.github.hazymorning.amsel.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.MaterialScreen
import io.github.hazymorning.amsel.ui.components.PageTopBar
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.ScrollEdges
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.Words
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.theme.AmselSheet
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kept in step with NOTICE; [file] is in `assets/licences`. */
internal enum class ThirdParty(@StringRes val title: Int, @StringRes val licence: Int, val file: String) {
    Sendspin(R.string.licence_sendspin, R.string.licence_apache, "Apache-2.0.txt"),
    Commissioner(R.string.licence_commissioner, R.string.licence_ofl, "OFL-Commissioner.txt"),
    Phosphor(R.string.licence_phosphor, R.string.licence_mit, "MIT-Phosphor.txt"),
    Concentus(R.string.licence_concentus, R.string.licence_bsd, "BSD-Concentus.txt"),
    Slf4j(R.string.licence_slf4j, R.string.licence_mit, "MIT-SLF4J.txt"),
    BouncyCastle(R.string.licence_bouncy_castle, R.string.licence_mit, "MIT-BouncyCastle.txt"),
    Libraries(R.string.licence_libraries, R.string.licence_apache, "Apache-2.0.txt"),
    Protobuf(R.string.licence_protobuf, R.string.licence_bsd, "BSD-Protobuf.txt"),
}

/** Kept in step with PRIVACY.md. */
private val PrivacyRows = listOf(
    R.string.music_assistant to R.string.privacy_music_assistant,
    R.string.audiobookshelf to R.string.privacy_audiobookshelf,
    R.string.home_assistant to R.string.privacy_home_assistant,
    R.string.github to R.string.privacy_github,
    R.string.privacy_tracking to R.string.privacy_tracking_detail,
    R.string.privacy_phone to R.string.privacy_phone_detail,
    R.string.privacy_sign_out to R.string.privacy_sign_out_detail,
)

private const val SOURCE_URL = "https://github.com/hazymorning/Amsel"

@Composable
fun LicencesScreen(onBack: () -> Unit, onOpenSource: () -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    var reading by rememberSaveable { mutableStateOf<ThirdParty?>(null) }
    MaterialScreen(
        modifier,
        bar = {
            PageTopBar(
                stringResource(R.string.licences),
                onBack,
                shorterTitle = stringResource(R.string.licences_short),
            )
        },
    ) { insets ->
        ScrollEdges(scroll)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(insets).padding(bottom = Space.ListEnd)) {
            SettingsGroup(stringResource(R.string.app_name), above = Space.Sibling) {
                row { shape ->
                    TextRow(stringResource(R.string.licences_gpl), stringResource(R.string.licences_gpl_detail), shape)
                }
                row { shape ->
                    SettingRow(
                        stringResource(R.string.licences_source),
                        shape,
                        subtitle = SOURCE_URL.removePrefix("https://"),
                        onClick = onOpenSource,
                        trailing = { ExternalGlyph() },
                    )
                }
            }
            SettingsGroup(stringResource(R.string.privacy), above = BetweenGroups) {
                for ((title, text) in PrivacyRows) {
                    row { shape -> TextRow(stringResource(title), stringResource(text), shape) }
                }
            }
            SettingsGroup(stringResource(R.string.licences_others), above = BetweenGroups) {
                for (part in ThirdParty.entries) {
                    row { shape ->
                        SettingRow(
                            stringResource(part.title),
                            shape,
                            subtitle = stringResource(part.licence),
                            onClick = { reading = part },
                        )
                    }
                }
            }
        }
    }
    reading?.let { part -> AmselSheet(onDismiss = { reading = null }) { LicenceText(part) } }
}

@Composable
private fun TextRow(title: String, text: String, shape: Shape) =
    SettingRow(title, shape, Modifier.semantics(mergeDescendants = true) {}, subtitle = text)

@Composable
private fun LicenceText(part: ThirdParty) {
    val assets = LocalContext.current.assets
    val text by produceState(emptyList<String>(), part) {
        value = withContext(Dispatchers.IO) {
            paragraphs(assets.open("licences/${part.file}").bufferedReader().use { it.readText() })
        }
    }
    val style = MaterialTheme.typography.bodyMedium
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = sheetEnd(RowLead.None, twoLines = false)),
    ) {
        SheetHeader(stringResource(part.licence))
        Spacer(Modifier.height(Space.HeaderToContent))
        for (paragraph in text) {
            Words(
                paragraph,
                style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = Int.MAX_VALUE,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Keyline.Margin, vertical = Space.S2),
            )
        }
    }
}

/** The files wrap at 80 columns; joining each paragraph's lines lets it wrap at any font size. */
internal fun paragraphs(text: String): List<String> =
    text.trim().split(Regex("""\n\s*\n""")).map { paragraph -> paragraph.lines().joinToString(" ") { it.trim() } }

@Composable
fun LicencesRoute(navigator: Navigator) {
    val actions = LocalAppActions.current
    LicencesScreen(onBack = navigator::back, onOpenSource = { actions.openLink(SOURCE_URL) })
}
