package io.github.hazymorning.amsel.ui.screens.search

import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi

/**
 * The library index as Search reads it: each item with the words of its name ([FuzzyMatcher]), and the
 * [covers] its artists wear by uri, for the server's copy of one sent without a picture.
 */
internal class SearchIndex private constructor(
    private val items: List<Pair<List<String>, MediaUi>>,
    val covers: Map<String, ArtRef>,
) {
    /** The items [query], in [FuzzyMatcher.words], fits: best first, at most [limit]. */
    fun near(query: List<String>, limit: Int): List<MediaUi> = items
        .mapNotNull { (name, item) -> FuzzyMatcher.score(query, name).takeIf { it > 0 }?.let { it to item } }
        .sortedWith(compareByDescending<Pair<Int, MediaUi>> { it.first }.thenBy { it.second.title.length })
        .take(limit)
        .map { it.second }

    companion object {
        /**
         * [library] with its names in words. A name [last] already split keeps its words: a book's
         * progress or a like rewrites the index without changing any name.
         */
        fun of(library: List<MediaUi>, last: SearchIndex?): SearchIndex {
            val split = last?.items?.associate { (words, item) -> item.title to words }.orEmpty()
            val covers = library.filter { it.kind == MediaKind.Artist }.mapNotNull { a -> a.art?.let { a.key to it } }
            return SearchIndex(library.map { (split[it.title] ?: FuzzyMatcher.words(it.title)) to it }, covers.toMap())
        }
    }
}
