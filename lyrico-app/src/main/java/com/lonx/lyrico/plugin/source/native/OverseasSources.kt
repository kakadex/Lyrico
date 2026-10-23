package com.lonx.lyrico.plugin.source.native

import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import kotlinx.serialization.json.JsonObject

/**
 * 境外元数据源：MusicBrainz（锚点）/ Deezer / iTunes（补充）。
 *
 * 三者均为匿名只读接口，零 API Key。移植自 MusicOrganizer 的 `net/Sources.java`，
 * 差异只有两处：时长的秒/毫秒换算在这里一次做对，歌手分隔符改从宿主设置读取。
 */

/**
 * MusicBrainz —— 数据最规范，但官方限流 1 req/s，只能靠票制限流慢慢排队。
 * 批量匹配时它通常不是最快返回的那个，所以别把 [NativeRateLimiter] 的间隔调小。
 */
class MusicBrainzSource(
    http: NativeHttp
) : NativeSearchSource(http) {

    override val key: String = "musicbrainz"
    override val name: String = "MusicBrainz"

    private val limiter = NativeRateLimiter(MIN_INTERVAL_MS)

    override suspend fun searchSongs(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int
    ): List<SongSearchResult> = safeQuery(keyword) {
        if (keyword.isBlank()) return@safeQuery emptyList()
        limiter.acquire()
        val payload = http.getJson(
            url = ENDPOINT,
            params = mapOf(
                "query" to "recording:\"${luceneEscape(keyword)}\" OR artist:\"${luceneEscape(keyword)}\"",
                "fmt" to "json",
                "limit" to pageSize.coerceIn(1, MAX_LIMIT).toString()
            )
        ).asObject() ?: return@safeQuery emptyList()

        payload.array("recordings").mapNotNull { element ->
            val recording = element.asObject() ?: return@mapNotNull null
            val title = recording.str("title")
            val artist = recording.joinArtistCredit()
            // 歌名或歌手为空的记录无法参与匹配，直接丢弃（与源实现的 R-01 约束一致）
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null

            val release = recording.array("releases").firstOrNull().asObject()
            val date = release?.str("date").orEmpty()
                .ifBlank { recording.str("first-release-date") }
            buildResult(
                remoteId = recording.str("id").ifBlank { title },
                title = title,
                artist = artist,
                album = release?.str("title").orEmpty(),
                date = date.take(4),
                internal = mapOf("score" to recording.str("score"))
            )
        }
    }

    /** artist-credit 是数组，且带 joinphrase（如 " & " / " feat. "），拼起来才是完整歌手名。 */
    private fun JsonObject.joinArtistCredit(): String =
        array("artist-credit").mapNotNull { element ->
            val credit = element.asObject() ?: return@mapNotNull null
            val name = credit.str("name").ifBlank { credit.obj("artist")?.str("name").orEmpty() }
            if (name.isBlank()) return@mapNotNull null
            name + credit.str("joinphrase")
        }.joinToString("").trim()

    private fun luceneEscape(text: String): String =
        text.replace(LUCENE_SPECIAL, "\\\\$0")

    private companion object {
        const val ENDPOINT = "https://musicbrainz.org/ws/2/recording"
        const val MIN_INTERVAL_MS = 1_050L
        const val MAX_LIMIT = 25

        /** Lucene 查询语法里的保留字符，不转义会被当成操作符。 */
        val LUCENE_SPECIAL = Regex("""[+\-!(){}\[\]^"~*?:\\/]""")
    }
}

/** Deezer —— 元数据干净、稳定，境外曲库覆盖好，无需鉴权。 */
class DeezerSource(
    http: NativeHttp
) : NativeSearchSource(http) {

    override val key: String = "deezer"
    override val name: String = "Deezer"

    /** 搜索结果已带 cover_xl（1000px），无需再换大图。 */
    override val coverEnabled: Boolean = true

    override suspend fun searchSongs(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int
    ): List<SongSearchResult> = safeQuery(keyword) {
        if (keyword.isBlank()) return@safeQuery emptyList()
        val payload = http.getJson(
            url = ENDPOINT,
            params = mapOf(
                "q" to keyword,
                "limit" to pageSize.coerceIn(1, MAX_LIMIT).toString(),
                "index" to ((page - 1).coerceAtLeast(0) * pageSize).toString()
            )
        ).asObject() ?: return@safeQuery emptyList()

        payload.array("data").mapNotNull { element ->
            val track = element.asObject() ?: return@mapNotNull null
            val title = track.str("title")
            if (title.isBlank()) return@mapNotNull null
            val album = track.obj("album")
            buildResult(
                remoteId = track.str("id").ifBlank { title },
                title = title,
                artist = track.obj("artist")?.str("name").orEmpty(),
                album = album?.str("title").orEmpty(),
                durationMs = secondsToMillis(track.doubleOrNull("duration")),
                coverUrl = album?.str("cover_xl", "cover_big", "cover_medium").orEmpty()
            )
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.deezer.com/search"
        const val MAX_LIMIT = 25
    }
}

/** iTunes Search —— 召回率高，且直接给 100~600 像素封面，取封面时很实用。 */
class ITunesSource(
    http: NativeHttp
) : NativeSearchSource(http) {

    override val key: String = "itunes"
    override val name: String = "iTunes"

    /** 搜索时已把 artworkUrl100 换成 600×600，封面搜索直接用。 */
    override val coverEnabled: Boolean = true

    override suspend fun searchSongs(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int
    ): List<SongSearchResult> = safeQuery(keyword) {
        if (keyword.isBlank()) return@safeQuery emptyList()
        val payload = http.getJson(
            url = ENDPOINT,
            params = mapOf(
                "term" to keyword,
                "media" to "music",
                "entity" to "song",
                "limit" to pageSize.coerceIn(1, MAX_LIMIT).toString()
            )
        ).asObject() ?: return@safeQuery emptyList()

        payload.array("results").mapNotNull { element ->
            val track = element.asObject() ?: return@mapNotNull null
            val title = track.str("trackName")
            if (title.isBlank()) return@mapNotNull null
            buildResult(
                remoteId = track.str("trackId", "collectionId").ifBlank { title },
                title = title,
                artist = track.str("artistName"),
                album = track.str("collectionName"),
                date = track.str("releaseDate").take(4),
                trackNumber = track.str("trackNumber"),
                durationMs = track.longOrNull("trackTimeMillis") ?: 0L,
                coverUrl = track.str("artworkUrl100").replace("100x100", "600x600")
            )
        }
    }

    private companion object {
        const val ENDPOINT = "https://itunes.apple.com/search"
        const val MAX_LIMIT = 25
    }
}
