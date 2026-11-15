package io.github.hazymorning.amsel.ui.screens.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.data.downloads.AbsAccount
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SecondaryButton
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.screens.downloads.AbsIntro
import io.github.hazymorning.amsel.ui.screens.downloads.AbsSignIn
import io.github.hazymorning.amsel.ui.screens.downloads.AbsSignInForm
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space

/** Downloads as Settings shows them: the Audiobookshelf account, what is on the phone, Wi-Fi only. */
@Immutable
data class DownloadSettings(
    val account: AbsAccount? = null,
    val usedBytes: Long? = null,
    val wifiOnly: Boolean = true,
    /** The server and name signed in with before, to sign in again. */
    val last: AbsAccount? = null,
)

/** The Downloads row's second line: who is signed in to Audiobookshelf where, or that no one is. */
@Composable
internal fun downloadsLine(account: AbsAccount?): String = if (account == null) {
    stringResource(R.string.abs_not_connected)
} else {
    listOf(account.username, hostOf(account.url)).filter { it.isNotBlank() }.joinToString(DOT_SEPARATOR)
}

/**
 * Downloads, in their own sheet: signed out, what Audiobookshelf is for and its sign-in; signed in,
 * where, how much is on the phone, and Sign out; then whether downloads wait for Wi-Fi.
 */
@Composable
internal fun DownloadsSheetContent(
    state: DownloadSettings,
    onWifiOnlyChange: (Boolean) -> Unit,
    onSignIn: AbsSignIn,
    onSignOut: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = sheetEnd(RowLead.None, twoLines = true)),
    ) {
        SheetHeader(stringResource(R.string.downloads))
        Spacer(Modifier.height(Space.HeaderToContent))
        val account = state.account
        if (account == null) {
            AbsIntro()
            Spacer(Modifier.height(Space.Group))
            AbsSignInForm(stringResource(R.string.abs_sign_in), onSignIn, onSignedIn = {}, last = state.last)
        } else {
            val where = stringResource(R.string.abs_signed_in, account.username, hostOf(account.url))
            val used = state.usedBytes?.takeIf { it > 0 }?.let {
                stringResource(R.string.downloads_used, Formatter.formatShortFileSize(LocalContext.current, it)) + "."
            }
            AbsIntro(listOfNotNull(where, used).joinToString(" "))
            Spacer(Modifier.height(Space.Group))
            SecondaryButton(
                stringResource(R.string.settings_sign_out),
                onSignOut,
                Modifier.padding(horizontal = Keyline.Margin).fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Space.Group))
        PlainToggleRow(
            stringResource(R.string.downloads_wifi),
            stringResource(R.string.downloads_wifi_detail),
            state.wifiOnly,
            onWifiOnlyChange,
        )
    }
}

/** The host alone, as Settings names Music Assistant's: `books.example.com`, `192.168.1.20`. */
private fun hostOf(url: String): String = url.toUri().host ?: url
