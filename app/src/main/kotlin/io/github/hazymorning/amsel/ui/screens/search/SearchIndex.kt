package io.github.hazymorning.amsel.ui.screens.search

import io.github.hazymorning.amsel.ui.model.ArtRef
import io.github.hazymorning.amsel.ui.model.MediaKind
import io.github.hazymorning.amsel.ui.model.MediaUi

/** [covers]: the index's artist covers by uri, for a server result sent without a picture. */
internal class SearchIndex private constructor(
    private val items: List<Pair<List<String>, MediaUi>>,
    val covers: Map<String, ArtRef>,
) {
    /** [query] as [FuzzyMatcher.words]; best match first. */
    fun near(query: List<String>, limit: Int): List<MediaUi> = items
        .mapNotNull { (name, item) -> FuzzyMatcher.score(query, name).takeIf { it > 0 }?.let { it to item } }
        .sortedWith(compareByDescending<Pair<Int, MediaUi>> { it.first }.thenBy { it.second.title.length })
        .take(limit)
        .map { it.second }

    companion object {
        /** Reuses [last]'s words by title: progress and likes rewrite the index without changing names. */
        fun of(library: List<MediaUi>, last: SearchIndex?): SearchIndex {
            val split = last?.items?.associate { (words, item) -> item.title to words }.orEmpty()
            val covers = library.filter { it.kind == MediaKind.Artist }.mapNotNull { a -> a.art?.let { a.key to it } }
            return SearchIndex(library.map { (split[it.title] ?: FuzzyMatcher.words(it.title)) to it }, covers.toMap())
        }
    }
}
