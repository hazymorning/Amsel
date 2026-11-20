package io.github.hazymorning.amsel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.screens.downloads.AbsSignInRoute
import io.github.hazymorning.amsel.ui.screens.downloads.RemoveDownloadRoute
import io.github.hazymorning.amsel.ui.screens.player.BookmarksRoute
import io.github.hazymorning.amsel.ui.screens.player.ChaptersRoute
import io.github.hazymorning.amsel.ui.screens.player.DjRoute
import io.github.hazymorning.amsel.ui.screens.player.ItemActionsRoute
import io.github.hazymorning.amsel.ui.screens.player.PlayOnRoute
import io.github.hazymorning.amsel.ui.screens.player.PlaylistsRoute
import io.github.hazymorning.amsel.ui.screens.player.RemoveFromPlaylistRoute
import io.github.hazymorning.amsel.ui.screens.player.SleepRoute
import io.github.hazymorning.amsel.ui.screens.player.TransferRoute
import io.github.hazymorning.amsel.ui.screens.player.VolumeRoute
import io.github.hazymorning.amsel.ui.theme.AmselSheet

/** The sheet on screen. One shows at a time: a choice that needs another sheet closes its own first. */
sealed interface AppSheet {
    data class Actions(val target: ActionTarget) : AppSheet

    data class Playlists(val target: ActionTarget) : AppSheet

    data class Bookmarks(val item: MediaUi) : AppSheet

    data class RemoveFromPlaylist(val target: ActionTarget.Track) : AppSheet

    data object PlayOn : AppSheet

    /** Play on at its group step. */
    data object Group : AppSheet

    data object SleepTimer : AppSheet

    data object Volume : AppSheet

    data object Chapters : AppSheet

    data object Transfer : AppSheet

    /** Which of the server's DJ hosts talks between the songs on the speaker that plays. */
    data object Dj : AppSheet

    /** Signs in to Audiobookshelf, then downloads [then]. */
    data class AbsSignIn(val then: MediaUi) : AppSheet

    data class RemoveDownload(val item: MediaUi) : AppSheet
}

/** Each sheet slides in and out; [AppSheet] changes wait for the one before to be gone. */
@Composable
internal fun AppSheets(sheet: MutableState<AppSheet?>, navigator: Navigator) {
    val shown = sheet.value ?: return
    key(shown) {
        AmselSheet(onDismiss = { if (sheet.value == shown) sheet.value = null }) {
            when (shown) {
                is AppSheet.Actions -> ItemActionsRoute(shown.target, navigator, onClose = { next ->
                    close { sheet.value = next }
                })

                is AppSheet.Playlists -> PlaylistsRoute(shown.target, navigator, onClose = { close() })

                is AppSheet.Bookmarks -> BookmarksRoute(shown.item, onClose = { close() })

                is AppSheet.RemoveFromPlaylist -> RemoveFromPlaylistRoute(shown.target, navigator) { close() }

                AppSheet.PlayOn -> PlayOnRoute(onDone = { close() })

                AppSheet.Group -> PlayOnRoute(onDone = { close() }, grouping = true)

                AppSheet.SleepTimer -> SleepRoute()

                AppSheet.Volume -> VolumeRoute()

                AppSheet.Chapters -> ChaptersRoute(onClose = { close() })

                AppSheet.Transfer -> TransferRoute(onDone = { close() })

                AppSheet.Dj -> DjRoute(onClose = { close() })

                is AppSheet.AbsSignIn -> AbsSignInRoute(shown.then, onDone = { close() })

                is AppSheet.RemoveDownload -> RemoveDownloadRoute(shown.item, onClose = { close() })
            }
        }
    }
}
