package io.github.hazymorning.amsel.ui.screens.search

import io.github.hazymorning.amsel.ui.model.MediaUi

/** The library index as Search reads it: each item with the words of its name ([Fuzzy]). */
internal class SearchIndex private constructor(private val items: List<Pair<List<String>, MediaUi>>) {
    /** The items [query], in [Fuzzy.words], fits: best first, at most [limit]. */
    fun near(query: List<String>, limit: Int): List<MediaUi> = items
        .mapNotNull { (name, item) -> Fuzzy.score(query, name).takeIf { it > 0 }?.let { it to item } }
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
            return SearchIndex(library.map { (split[it.title] ?: Fuzzy.words(it.title)) to it })
        }
    }
}
