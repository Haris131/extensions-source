package eu.kanade.tachiyomi.extension.all.beauty3600000

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Instant

@Source
abstract class Beauty3600000 : KeiSource() {

    override val supportsLatest = false

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(1)

    // ========================= Popular =========================

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(
        client.get(listingUrl(page), Fetch.DOCUMENT).asJsoup(),
    )

    // ========================= Latest =========================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getPopularManga(page)

    // ========================= Search =========================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val category = filters.firstInstanceOrNull<CategoryFilter>()?.takeIf { it.state > 0 }
        if (category != null) {
            return parseMangaList(client.get(categoryUrl(category, page), Fetch.DOCUMENT).asJsoup())
        }
        if (query.isBlank()) {
            return getPopularManga(page)
        }

        // The site's own keyword search returns a 404 page, so queries are matched against the
        // tag index instead; most entries are tag archives (models, magazines, studios).
        val tagUrl = resolveTagUrl(query) ?: return MangasPage(emptyList(), false)
        val url = tagUrl.toHttpUrl().newBuilder().apply {
            if (page > 1) {
                addPathSegment("page")
                addPathSegment(page.toString())
            }
        }.build()
        return parseMangaList(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!isSameSite(url)) return null

        val response = client.get(url, Fetch.DOCUMENT)
        val finalUrl = response.request.url
        val document = response.asJsoup()
        if (document.selectFirst("h1.entry-title") == null) return null

        return parseDetails(document).apply {
            setUrlWithoutDomain(finalUrl.toString())
        }
    }

    override suspend fun getMangasByUrl(url: HttpUrl, page: Int): MangasPage {
        if (!isSameSite(url)) return MangasPage(emptyList(), false)

        val segments = url.pathSegments.filter { it.isNotEmpty() }
        val isListing = url.queryParameter("p") == null &&
            (segments.isEmpty() || segments[0] == "category" || segments[0] == "tag" || segments[0] == "page")
        if (!isListing) {
            val manga = getMangaByUrl(url) ?: return MangasPage(emptyList(), false)
            return MangasPage(listOf(manga), false)
        }

        val pagedUrl = url.newBuilder().apply {
            if (page > 1) {
                if (segments[0] == "page" && segments.size > 1) {
                    setPathSegment(1, page.toString())
                } else {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
            }
        }.build()
        return parseMangaList(client.get(pagedUrl, Fetch.DOCUMENT).asJsoup())
    }

    // ========================= Details & Chapters =========================

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(getMangaUrl(manga), Fetch.DOCUMENT)
        val path = response.request.url.encodedPath
        val document = response.asJsoup()

        val chapter = SChapter.create().apply {
            name = "Gallery"
            url = path
            date_upload = Instant.tryParse(document.selectFirst("time.entry-date")?.attr("datetime"))
        }

        return SMangaUpdate(parseDetails(document), listOf(chapter))
    }

    private fun parseDetails(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1.entry-title")?.text() ?: throw Exception("Title is mandatory")
        genre = document.select(".cat-links a").joinToString { it.text() }
            .ifBlank { null }
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        thumbnail_url = firstImage(document.selectFirst(".entry-content"))
    }

    // ========================= Pages =========================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
        val images = imageUrls(document.selectFirst(".entry-content") ?: document)
        return images.mapIndexed { index, imageUrl -> Page(index, imageUrl = imageUrl) }
    }

    // ========================= Filters =========================

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("NOTE: Category is ignored when searching by text"),
        Filter.Separator(),
        CategoryFilter(),
    )

    // ========================= Helpers =========================

    private fun listingUrl(page: Int): HttpUrl = if (page <= 1) {
        "$baseUrl/".toHttpUrl()
    } else {
        "$baseUrl/page/$page/".toHttpUrl()
    }

    private fun categoryUrl(filter: CategoryFilter, page: Int): HttpUrl = "$baseUrl/category/${filter.toUriPart()}/".toHttpUrl().newBuilder().apply {
        if (page > 1) {
            addPathSegment("page")
            addPathSegment(page.toString())
        }
    }.build()

    private fun parseMangaList(document: Document): MangasPage {
        val mangas = document.select("article.bam-entry").mapNotNull { element ->
            val link = element.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            SManga.create().apply {
                title = link.text()
                setUrlWithoutDomain(link.absUrl("href"))
                thumbnail_url = firstImage(element)
            }
        }
        return MangasPage(mangas, document.selectFirst("a.next.page-numbers") != null)
    }

    private fun firstImage(container: Element?): String? = container?.selectFirst("img[file]")?.attr("file")
        ?: container?.selectFirst("noscript img")?.attr("src")

    private fun imageUrls(container: Element): List<String> {
        val lazy = container.select("img[file]").map { it.attr("file") }
        if (lazy.isNotEmpty()) return lazy

        return container.select("img").mapNotNull { it.absUrl("src") }
            .filter { it.isNotBlank() && !it.contains("/blank_") }
            .distinct()
    }

    private var lastTagQuery: String? = null
    private var lastTagUrl: String? = null

    private suspend fun resolveTagUrl(query: String): String? {
        val needle = query.normalizeForMatch()
        if (needle.isBlank()) return null
        if (needle == lastTagQuery) return lastTagUrl

        val tagUrl = client.get("$baseUrl/tags/", Fetch.DOCUMENT).asJsoup()
            .select("a[href*=/tag/]")
            .firstOrNull { needle in it.text().normalizeForMatch() }
            ?.absUrl("href")

        lastTagQuery = needle
        lastTagUrl = tagUrl
        return tagUrl
    }

    override fun getMangaUrl(manga: SManga): String = absoluteUrl(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = absoluteUrl(chapter.url)

    private fun absoluteUrl(stored: String): String = when {
        stored.startsWith("http") -> stored
        stored.toIntOrNull() != null -> "$baseUrl/?p=$stored&post_type=post"
        stored.startsWith("?") -> "$baseUrl/$stored"
        stored.startsWith("/") -> "$baseUrl$stored"
        else -> "$baseUrl/$stored"
    }

    private fun isSameSite(url: HttpUrl): Boolean = url.host.removePrefix("www.") == baseUrl.toHttpUrl().host.removePrefix("www.")
}

private val NON_SLUG_CHARS = Regex("""[^\p{L}\p{N}]+""")

private fun String.normalizeForMatch(): String = lowercase().replace(NON_SLUG_CHARS, " ").trim()
