package io.github.hazymorning.amsel.ui.screens.search

import java.text.Normalizer

/**
 * Typo-tolerant matching over the library index ("beatls", "sigur ros", "motorhead"). Ignores case,
 * accents and punctuation; each typed word must start a word of the name or be a few edits from one.
 */
internal object FuzzyMatcher {
    private val marks = Regex("\\p{Mn}+")
    private val apart = Regex("[^\\p{L}\\p{N}]+")

    fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
            .replace(marks, "")
            .split(apart)
            .filter(String::isNotEmpty)

    /** [query] and [name] as [words]; 0 is no match, higher is closer. */
    fun score(query: List<String>, name: List<String>): Int {
        if (query.isEmpty() || name.isEmpty()) return 0
        var total = 0
        for (word in query) {
            val best = name.maxOf { wordScore(word, it) }
            if (best == 0) return 0
            total += best
        }
        return total
    }

    private fun wordScore(typed: String, word: String): Int {
        if (word.startsWith(typed)) return EXACT
        val allowed = when {
            typed.length < SHORT -> 0
            typed.length < LONG -> 1
            else -> 2
        }
        if (allowed == 0) return 0
        // Also against the word's prefix of the same length: the query may be half typed.
        val apart = minOf(distance(typed, word), distance(typed, word.take(typed.length)))
        return when {
            apart == 0 -> EXACT
            apart <= allowed -> EXACT - apart
            else -> 0
        }
    }

    /** Edit distance in which swapping two neighbouring letters counts as one edit. */
    fun distance(a: String, b: String): Int {
        var twoBack = IntArray(b.length + 1)
        var oneBack = IntArray(b.length + 1) { it }
        var row = IntArray(b.length + 1)
        for (i in 1..a.length) {
            row[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var best = minOf(oneBack[j] + 1, row[j - 1] + 1, oneBack[j - 1] + cost)
                val swapped = i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]
                if (swapped) best = minOf(best, twoBack[j - 2] + 1)
                row[j] = best
            }
            val spare = twoBack
            twoBack = oneBack
            oneBack = row
            row = spare
        }
        return oneBack[b.length]
    }

    private const val EXACT = 3
    private const val SHORT = 4
    private const val LONG = 7
}
