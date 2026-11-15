package io.github.hazymorning.amsel.ui.screens.player

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.ActionTarget
import io.github.hazymorning.amsel.app.PlaylistEdit
import io.github.hazymorning.amsel.graph
import io.github.hazymorning.amsel.ui.AppSheet
import io.github.hazymorning.amsel.ui.LocalAppActions
import io.github.hazymorning.amsel.ui.components.ActionRow
import io.github.hazymorning.amsel.ui.components.Artwork
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.RowLead
import io.github.hazymorning.amsel.ui.components.SheetQuestion
import io.github.hazymorning.amsel.ui.components.label
import io.github.hazymorning.amsel.ui.components.overhang
import io.github.hazymorning.amsel.ui.components.rowInkBottom
import io.github.hazymorning.amsel.ui.components.rowInkTop
import io.github.hazymorning.amsel.ui.components.sheetEnd
import io.github.hazymorning.amsel.ui.model.ItemAction
import io.github.hazymorning.amsel.ui.model.ItemActionsUiState
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi
import io.github.hazymorning.amsel.ui.navigation.Navigator
import io.github.hazymorning.amsel.ui.navigation.Route
import io.github.hazymorning.amsel.ui.theme.Sizes
import io.github.hazymorning.amsel.ui.theme.Space
import kotlinx.coroutines.flow.first

@Composable
fun ItemActionsSheetContent(state: ItemActionsUiState, onAction: (ItemAction) -> Unit, modifier: Modifier = Modifier) {
    val coverTop = rowInkTop(RowLead.Cover, twoLines = true)
    val coverBottom = rowInkBottom(RowLead.Cover, twoLines = true)
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = sheetEnd(RowLead.GlyphUnderCover, twoLines = false)),
    ) {
        ListRow(
            title = state.title,
            subtitle = if (state.djBreak) stringResource(R.string.dj) else state.subtitle,
            modifier = Modifier
                .overhang(top = coverTop, bottom = coverBottom)
                .semantics(mergeDescendants = true) { heading() },
            lead = RowLead.Cover,
            leading = { Artwork(state.art, Modifier.size(Sizes.RowArt), state.kind, name = state.title) },
        )
        Spacer(Modifier.height(Space.BeforeList))
        state.actions.forEach { action ->
            ActionRow(action.icon, stringResource(action.label), { onAction(action) }, lead = RowLead.GlyphUnderCover)
        }
    }
}

/** "Remove “Lanterns” from Sunday Morning?": Remove, or Cancel back to the actions. */
@Composable
fun RemoveFromPlaylistQuestion(
    song: String,
    playlist: String?,
    onRemove: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetQuestion(
        title = stringResource(R.string.player_action_remove_from_playlist),
        question = if (playlist.isNullOrBlank()) {
            stringResource(R.string.player_confirm_remove_from_this_playlist, song)
        } else {
            stringResource(R.string.player_confirm_remove_from_playlist, song, playlist)
        },
        primary = stringResource(R.string.remove),
        onPrimary = onRemove,
        secondary = stringResource(R.string.player_confirm_cancel),
        onSecondary = onBack,
        modifier = modifier,
    )
}

@get:DrawableRes
private val ItemAction.icon: Int
    get() = when (this) {
        ItemAction.Play -> R.drawable.ic_play
        ItemAction.Shuffle -> R.drawable.ic_shuffle
        ItemAction.PlayNext -> R.drawable.ic_play_next
        ItemAction.AddToUpNext -> R.drawable.ic_queue
        ItemAction.AddToFavorites -> R.drawable.ic_favorite
        ItemAction.RemoveFromFavorites -> R.drawable.ic_favorite
        ItemAction.AddToPlaylist -> R.drawable.ic_playlist_add
        ItemAction.GoToArtist -> R.drawable.ic_artist
        ItemAction.GoToAlbum -> R.drawable.ic_album
        ItemAction.RemoveFromUpNext, ItemAction.RemoveFromPlaylist -> R.drawable.ic_remove
        ItemAction.PinToHome -> R.drawable.ic_pin
        ItemAction.UnpinFromHome -> R.drawable.ic_unpin
        ItemAction.MarkFinished, ItemAction.MarkPlayed -> R.drawable.ic_played
        ItemAction.MarkUnfinished, ItemAction.MarkUnplayed -> R.drawable.ic_unplayed
        ItemAction.GoToAuthor -> R.drawable.ic_artist
        ItemAction.GoToAudiobook -> R.drawable.ic_audiobook
        ItemAction.GoToPodcast -> R.drawable.ic_podcast
        ItemAction.Bookmarks -> R.drawable.ic_bookmark
        ItemAction.Download -> R.drawable.ic_download
        ItemAction.CancelDownload -> R.drawable.ic_download_stop
        ItemAction.RemoveDownload -> R.drawable.ic_remove
        ItemAction.SleepTimer -> R.drawable.ic_sleep
        ItemAction.TransferPlayback -> R.drawable.ic_speaker
        ItemAction.EndlessMix -> R.drawable.ic_mix
        ItemAction.SimilarSongs -> R.drawable.ic_similar
        ItemAction.Dj -> R.drawable.ic_dj
    }

@get:StringRes
private val ItemAction.label: Int
    get() = when (this) {
        ItemAction.Play -> R.string.play
        ItemAction.Shuffle -> R.string.shuffle
        ItemAction.PlayNext -> R.string.player_action_play_next
        ItemAction.AddToUpNext -> R.string.player_action_add_to_up_next
        ItemAction.AddToFavorites -> R.string.player_action_add_favorite
        ItemAction.RemoveFromFavorites -> R.string.player_action_remove_favorite
        ItemAction.AddToPlaylist -> R.string.add_to_playlist
        ItemAction.GoToArtist -> R.string.player_action_go_to_artist
        ItemAction.GoToAlbum -> R.string.player_action_go_to_album
        ItemAction.RemoveFromUpNext -> R.string.player_action_remove_from_up_next
        ItemAction.RemoveFromPlaylist -> R.string.player_action_remove_from_playlist
        ItemAction.PinToHome -> R.string.home_action_pin
        ItemAction.UnpinFromHome -> R.string.home_action_unpin
        ItemAction.MarkFinished -> R.string.player_action_mark_finished
        ItemAction.MarkUnfinished -> R.string.player_action_mark_unfinished
        ItemAction.MarkPlayed -> R.string.player_action_mark_played
        ItemAction.MarkUnplayed -> R.string.player_action_mark_unplayed
        ItemAction.GoToAuthor -> R.string.player_action_go_to_author
        ItemAction.GoToAudiobook -> R.string.player_action_go_to_audiobook
        ItemAction.GoToPodcast -> R.string.player_action_go_to_podcast
        ItemAction.Bookmarks -> R.string.bookmarks
        ItemAction.Download -> R.string.download
        ItemAction.CancelDownload -> R.string.download_cancel
        ItemAction.RemoveDownload -> R.string.download_remove
        ItemAction.SleepTimer -> R.string.sleep_timer
        ItemAction.TransferPlayback -> R.string.player_transfer
        ItemAction.EndlessMix -> R.string.endless_mix
        ItemAction.SimilarSongs -> R.string.player_action_similar_songs
        ItemAction.Dj -> R.string.dj
    }

/**
 * The ⋮ sheet. An action that needs more (a playlist, a bookmark, the sleep timer, a speaker to move
 * to, the DJ, the question before a removal) closes it and names the sheet that comes next ([onClose]);
 * the others run in the app's scope and close it. Moving the music shows only when another speaker
 * is in reach, the DJ only while the server has one to choose.
 */
@Composable
fun ItemActionsRoute(target: ActionTarget, navigator: Navigator, onClose: (next: AppSheet?) -> Unit) {
    val graph = LocalContext.current.graph
    val app = LocalAppActions.current
    // A removal from this playlist failed: its page reads the playlist again, so a position counted
    // before then could name another song.
    LaunchedEffect(target) {
        val playlist = (target as? ActionTarget.Track)?.playlistKey ?: return@LaunchedEffect
        graph.playlists.edits.first { it is PlaylistEdit.Failed && it.playlistKey == playlist }
        onClose(null)
    }
    val transferable by graph.playOn.transferTargets.collectAsStateWithLifecycle()
    val dj = djOffered(target)
    val state = graph.actions.state(target).let { all ->
        val gone = setOfNotNull(
            ItemAction.TransferPlayback.takeIf { transferable.isEmpty() },
            ItemAction.Dj.takeIf { !dj },
        )
        all.copy(actions = all.actions - gone)
    }
    ItemActionsSheetContent(
        state = state,
        onAction = { action ->
            val next = when (action) {
                ItemAction.AddToPlaylist -> AppSheet.Playlists(target)
                ItemAction.Bookmarks -> (target as? ActionTarget.Media)?.let { AppSheet.Bookmarks(it.item) }
                ItemAction.SleepTimer -> AppSheet.SleepTimer
                ItemAction.TransferPlayback -> AppSheet.Transfer
                ItemAction.Dj -> AppSheet.Dj
                ItemAction.RemoveFromPlaylist -> (target as? ActionTarget.Track)?.let(AppSheet::RemoveFromPlaylist)
                ItemAction.RemoveDownload -> (target as? ActionTarget.Media)?.let { AppSheet.RemoveDownload(it.item) }
                ItemAction.Download -> (target as? ActionTarget.Media)?.let { app.beforeDownload(it.item) }
                else -> null
            }
            if (next == null) graph.actions.perform(target, action, navigator)
            when (action) {
                ItemAction.AddToFavorites -> graph.hints.liked(target.song()) { app.open(AppSheet.Playlists(target)) }
                ItemAction.RemoveFromFavorites -> graph.hints.unliked(target.key, target.song().title)
                else -> Unit
            }
            onClose(next)
        },
    )
}

/** "Add to playlist": the playlists the person may change, once they are read; the one picked is a tap away after. */
@Composable
fun PlaylistsRoute(target: ActionTarget, navigator: Navigator, onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val playlists by produceState<List<MediaUi>?>(null, target) { value = graph.playlists.editable() }
    PlaylistPicker(
        playlists,
        onPick = { playlist ->
            graph.playlists.add(target.key, playlist, open = { navigator.open(Route.Playlist(playlist.key)) })
            onClose()
        },
    )
}

/** The item an action is about, as a row or a tile would show it. */
private fun ActionTarget.song(): MediaUi = when (this) {
    is ActionTarget.Media -> item
    is ActionTarget.Track -> MediaUi(track.key, MediaKind.Track, track.title, track.artist, track.art)
}

@Composable
fun RemoveFromPlaylistRoute(target: ActionTarget.Track, navigator: Navigator, onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    RemoveFromPlaylistQuestion(
        song = target.track.title,
        playlist = target.playlistName,
        onRemove = {
            graph.actions.perform(target, ItemAction.RemoveFromPlaylist, navigator)
            onClose()
        },
        onBack = onClose,
    )
}

/** The bookmarks of a book or an episode, or of all of a podcast's episodes. */
@Composable
fun BookmarksRoute(item: MediaUi, onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val all by graph.bookmarks.all.collectAsStateWithLifecycle()
    val podcast = item.kind == MediaKind.Podcast
    val shown =
        remember(all, item.key) { all.filter { if (podcast) it.parentKey == item.key else it.itemKey == item.key } }
    BookmarksSheetContent(
        shown,
        onJump = {
            graph.bookmarks.play(it)
            onClose()
        },
        onDelete = graph.bookmarks::delete,
        onNote = { bookmark, note -> graph.bookmarks.setNote(bookmark.id, note) },
        titles = podcast,
    )
}

/** The sleep timer of the speaker that plays. */
@Composable
fun SleepRoute() {
    val graph = LocalContext.current.graph
    val now by graph.playing.nowPlaying.collectAsStateWithLifecycle()
    val timers by graph.sleepTimer.timers.collectAsStateWithLifecycle()
    val np = now ?: return
    SleepSheetContent(
        speaker = np.speaker.label(),
        timer = timers[np.speaker.id],
        endLabel = when {
            np.kind == MediaKind.Episode -> R.string.sleep_end_of_episode
            np.spoken && np.hasChapters -> R.string.sleep_end_of_chapter
            else -> null
        },
        onSet = { graph.sleepTimer.set(np.speaker.id, it) },
        onOff = { graph.sleepTimer.clear(np.speaker.id) },
    )
}
