package io.github.hazymorning.amsel.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import io.github.hazymorning.amsel.ui.model.DOT_SEPARATOR
import kotlin.math.roundToInt

private const val ELLIPSIS = "…"

/** Characters that separate names inside a title ("Live | Remastered", "Bach – Suites"). */
private val Separators = setOf('|', '•', '·', '–', '—', '/', '-')

/**
 * Text that never cuts a word: when it runs out of [maxLines], it ends after the last whole word
 * that fits, with an ellipsis; only a single word too long for a line is cut. Separators standing
 * between spaces are drawn in the faint tone, so the words carry the line. A [tail] follows on
 * the same line after a quiet dot, in the secondary tone (a guest artist after a title). When [text] needs
 * more than one line, [shorter] stands in for it (TalkBack still reads [text]). Baselines are the
 * text's, so it lines up as [androidx.compose.material3.Text] does.
 */
@Composable
fun Words(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    textAlign: TextAlign = TextAlign.Start,
    tail: String? = null,
    shorter: String? = null,
) {
    val ink = if (color == Color.Unspecified) LocalContentColor.current else color
    val quiet = MaterialTheme.colorScheme.outline
    val tailColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    // The colour is drawn, not laid out: a colour that animates measures the text once.
    val styled = remember(style, textAlign) { style.copy(textAlign = textAlign) }
    // Text that runs out of lines fills each one before it is cut; balanced breaking would end the
    // first line half way and cut far too early.
    val filled = remember(styled) { styled.copy(lineBreak = LineBreak.Simple) }
    fun joined(base: String) = if (tail.isNullOrBlank()) base else base + DOT_SEPARATOR + tail
    val spoken = joined(text)
    var shown by remember { mutableStateOf<TextLayoutResult?>(null) }
    Layout(
        modifier = modifier
            .semantics { this.text = AnnotatedString(spoken) }
            .drawBehind { shown?.let { drawText(it, color = ink) } },
    ) { _, constraints ->
        val fixed = if (textAlign == TextAlign.Start || !constraints.hasBoundedWidth) {
            Constraints(maxWidth = constraints.maxWidth)
        } else {
            Constraints.fixedWidth(constraints.maxWidth)
        }
        var tailStart = Int.MAX_VALUE
        fun measure(words: String, overflow: TextOverflow, greedy: Boolean = true, lines: Int = maxLines) =
            measurer.measure(
                words.annotated(quiet, tailStart, tailColor),
                if (greedy) filled else styled,
                overflow = overflow,
                maxLines = lines,
                constraints = fixed,
                layoutDirection = layoutDirection,
                density = this,
            )
        val base = if (shorter != null && measure(spoken, TextOverflow.Clip, lines = 1).hasVisualOverflow) {
            shorter
        } else {
            text
        }
        val whole = joined(base)
        if (!tail.isNullOrBlank()) tailStart = base.length + DOT_SEPARATOR.length
        var result = measure(whole, TextOverflow.Clip, greedy = false)
        if (result.hasVisualOverflow) {
            val full = measure(whole, TextOverflow.Clip)
            result = wordCut(whole, full, maxLines) { words, overflow -> measure(words, overflow) }
                ?: measure(whole, TextOverflow.Ellipsis)
        }
        shown = result
        val w = result.size.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val h = result.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(
            w,
            h,
            mapOf(FirstBaseline to result.firstBaseline.roundToInt(), LastBaseline to result.lastBaseline.roundToInt()),
        ) {}
    }
}

/** Cuts [text] after the last word that still fits with an ellipsis; null when no word boundary helps. */
private fun wordCut(
    text: String,
    full: TextLayoutResult,
    maxLines: Int,
    measure: (String, TextOverflow) -> TextLayoutResult,
): TextLayoutResult? {
    val lastLine = (maxLines - 1).coerceAtMost(full.lineCount - 1)
    val lineStart = full.getLineStart(lastLine)
    var end = full.getLineEnd(lastLine, visibleEnd = true)
    // The line may end on a whole word; keep it when the ellipsis still fits after it.
    if (end in (lineStart + 1) until text.length && text[end] == ' ') {
        val result = measure(text.substring(0, end).cutShort(), TextOverflow.Clip)
        if (!result.hasVisualOverflow) return result
    }
    repeat(MAX_TRIES) {
        val space = text.lastIndexOf(' ', (end - 1).coerceAtLeast(0))
        if (space <= lineStart) return null
        val cut = text.substring(0, space).cutShort()
        if (cut.length - ELLIPSIS.length <= lineStart) return null
        val result = measure(cut, TextOverflow.Clip)
        if (!result.hasVisualOverflow) return result
        end = space
    }
    return null
}

/**
 * This text, cut here, ended with an ellipsis. What stood after its last word goes with the cut: a
 * space, a separator, a comma or colon, and a full stop, which before an ellipsis reads as a
 * fourth dot ("1 Std…", not "1 Std.…").
 */
internal fun String.cutShort(): String =
    trimEnd { it == ' ' || it == ',' || it == ':' || it == '.' || it in Separators } + ELLIPSIS

private const val MAX_TRIES = 6

private fun String.annotated(quiet: Color, tailStart: Int, tailColor: Color): AnnotatedString {
    if (none { it in Separators } && tailStart >= length) return AnnotatedString(this)
    return buildAnnotatedString {
        this@annotated.forEachIndexed { i, c ->
            val between = c in Separators &&
                getOrNull(i - 1) == ' ' &&
                (getOrNull(i + 1) == ' ' || i == lastIndex)
            when {
                between -> {
                    pushStyle(SpanStyle(color = quiet))
                    append(c)
                    pop()
                }

                i >= tailStart -> {
                    pushStyle(SpanStyle(color = tailColor))
                    append(c)
                    pop()
                }

                else -> append(c)
            }
        }
    }
}
