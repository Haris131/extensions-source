package eu.kanade.tachiyomi.extension.all.beauty3600000

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(
    displayName: String,
    private val valuePair: Array<Pair<String, String>>,
) : Filter.Select<String>(displayName, valuePair.map { it.first }.toTypedArray()) {
    fun toUriPart() = valuePair[state].second
}

class CategoryFilter :
    UriPartFilter(
        "Category",
        arrayOf(
            Pair("Any", ""),
            Pair("Aidol", "aidol"),
            Pair("China", "china"),
            Pair("Chinese", "chinese"),
            Pair("Cosplay", "cosplay"),
            Pair("Gravure", "gravure"),
            Pair("Japan", "japan"),
            Pair("Korea", "korea"),
            Pair("Magazine", "magazine"),
            Pair("Photobook", "photobook"),
            Pair("Thailand", "thailand"),
            Pair("Uncategorized", "uncategorized"),
            Pair("Western", "western"),
        ),
    )
