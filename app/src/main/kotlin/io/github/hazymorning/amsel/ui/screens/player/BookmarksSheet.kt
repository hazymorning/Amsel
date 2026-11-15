package io.github.hazymorning.amsel.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import io.github.hazymorning.amsel.R
import io.github.hazymorning.amsel.app.Bookmark
import io.github.hazymorning.amsel.ui.components.LabelledField
import io.github.hazymorning.amsel.ui.components.ListRow
import io.github.hazymorning.amsel.ui.components.SheetHeader
import io.github.hazymorning.amsel.ui.components.TextAction
import io.github.hazymorning.amsel.ui.components.dateText
import io.github.hazymorning.amsel.ui.components.formatDuration
import io.github.hazymorning.amsel.ui.components.inkStart
import io.github.hazymorning.amsel.ui.components.textInk
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import io.github.hazymorning.amsel.ui.theme.Keyline
import io.github.hazymorning.amsel.ui.theme.Space
import java.time.Instant
import java.time.ZoneId

/**
 * Bookmarks, newest first: each names its chapter, time and date, and its note. A tap plays from
 * there, a swipe deletes it, a tap on the note edits it. [titles] adds the item's title, where the
 * list holds several items (a podcast's episodes).
 */
@Composable
fun BookmarksSheetContent(
    bookmarks: List<Bookmark>,
    onJump: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onNote: (Bookmark, String) -> Unit,
    modifier: Modifier = Modifier,
    titles: Boolean = false,
) {
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    Column(modifier.fillMaxWidth().padding(bottom = Space.Group)) {
        SheetHeader(stringResource(R.string.bookmarks))
        Spacer(Modifier.height(Space.HeaderToContent))
        if (bookmarks.isEmpty()) {
            val body = MaterialTheme.typography.bodyLarge
            val sentence = stringResource(R.string.bookmarks_empty)
            Text(
                sentence,
                style = body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Keyline.Margin).textInk(body).inkStart(sentence, body),
            )
            return@Column
        }
        LazyColumn {
            items(bookmarks, key = { it.id }) { bookmark ->
                if (editing == bookmark.id) {
                    NoteField(bookmark, onSave = { onNote(bookmark, it) }, onFinish = { editing = null })
                } else {
                    BookmarkRow(bookmark, titles, onJump, onDelete, onEditNote = { editing = bookmark.id })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    titles: Boolean,
    onJump: (Bookmark) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onEditNote: () -> Unit,
) {
    val where = listOfNotNull(
        bookmark.itemTitle.takeIf { titles },
        bookmark.chapter?.let { stringResource(R.string.bookmark_chapter, it) },
        formatDuration((bookmark.positionMs / 1000).toInt()),
    ).joinToString(DOT_SEPARATOR)
    val date = dateText(
        remember(bookmark.createdAtMs) {
            Instant.ofEpochMilli(bookmark.createdAtMs).atZone(ZoneId.systemDefault()).toLocalDate()
        },
    )
    val delete = stringResource(R.string.bookmark_delete)
    val state = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = Keyline.Margin),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    delete,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it != SwipeToDismissBoxValue.Settled) onDelete(bookmark) },
    ) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(delete) {
                            onDelete(bookmark)
                            true
                        },
                    )
                },
        ) {
            ListRow(
                title = where,
                subtitle = date,
                onClick = { onJump(bookmark) },
            )
            Box(Modifier.padding(start = Keyline.Margin, end = Keyline.Margin, bottom = Space.S3)) {
                TextAction(
                    bookmark.note.ifBlank { stringResource(R.string.bookmark_note_add) },
                    onEditNote,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    icon = if (bookmark.note.isBlank()) R.drawable.ic_edit else null,
                )
            }
        }
    }
}

/** The note is kept however the field goes: Done, another note, or the sheet closing. */
@Composable
private fun NoteField(bookmark: Bookmark, onSave: (String) -> Unit, onFinish: () -> Unit) {
    var text by rememberSaveable(bookmark.id) { mutableStateOf(bookmark.note) }
    val typed by rememberUpdatedState(text)
    val save by rememberUpdatedState(onSave)
    DisposableEffect(bookmark.id) { onDispose { if (typed != bookmark.note) save(typed) } }
    LabelledField(
        text,
        { text = it },
        stringResource(R.string.bookmark_note),
        Modifier.padding(horizontal = Keyline.Margin, vertical = Space.S2),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onFinish() }),
    )
}
