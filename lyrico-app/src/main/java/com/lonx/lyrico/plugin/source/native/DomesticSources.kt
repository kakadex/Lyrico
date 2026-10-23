package com.lonx.lyrico.plugin.source.native

import com.lonx.lyrico.data.model.lyrics.LyricsResult
import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import kotlinx.serialization.json.JsonObject

/**
 * 境内元数据源：网易云 / QQ 音乐 / 酷狗 / 咪咕。
 *
 * 这四家没有公开文档，接口是社区长期沿用的匿名只读端点，全部零 API Key。
 * 移植自 MusicOrganizer 的 `net/Sources.java`，注意事项：
 * - 网易与咪咕校验 Referer，不带会直接 403；
 * - 时长的原始单位各家不同（网易是毫秒、QQ/酷狗是秒），统一在这里换算成毫秒；
 * - 这类接口随时可能改版，所以每个源都走 [safeQuery]，改版只影响自己一家。
 */

/** 网易云音乐 —— 中文曲库覆盖最好，封面也最全。 */
class NeteaseSource(
    http: NativeHttp
) : NativeSearchSource(http) {

    override val key: String = "netease"
    override val name: String = "网易云音乐"

    /** 既能匹配元数据，也能取歌词（含翻译），这是境内源里唯一有翻译轨的一家。 */
    override val lyricsEnabled: Boolean = true
    override val coverEnabled: Boolean = true

    /**
     * 网易的搜索接口按 IP 计频，密集请求会返回 `405 操作频繁`。
     * 实测连续几次就会触发，批量匹配时每首歌都要查一次，不加节流会成片失败。
     */
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
                "s" to keyword,
                "type" to "1",
                "offset" to ((page - 1).coerceAtLeast(0) * pageSize).toString(),
                "limit" to pageSize.coerceIn(1, MAX_LIMIT).toString()
            ),
            headers = mapOf("Referer" to "https://music.163.com/"),
            timeoutMs = NativeHttp.TIMEOUT_DOMESTIC_MS
        ).asObject() ?: return@safeQuery emptyList()

        payload.obj("result")?.array("songs").orEmpty().mapNotNull { element ->
            val song = element.asObject() ?: return@mapNotNull null
            val title = song.str("name")
            if (title.isBlank()) return@mapNotNull null
            val album = song.obj("album")
            buildResult(
                remoteId = song.str("id").ifBlank { title },
                title = title,
                artist = song.array("artists")
                    .mapNotNull { it.asObject()?.str("name")?.takeIf(String::isNotBlank) }
                    .joinToString(separator.ifBlank { DEFAULT_SEPARATOR }),
                album = album?.str("name").orEmpty(),
                durationMs = song.longOrNull("duration") ?: 0L,
                coverUrl = album?.str("picUrl").orEmpty()
            )
        }
    }

    /**
     * 歌词取 `lrc` + `tlyric` 两份，按时间戳合成带翻译轨的 LRC。
     *
     * 参数 `lv=-1&kv=-1&tv=-1` 是 MusicPlayer2 客户端的用法（`-1` 表示「有就给我」），
     * 只传 `lv=1` 时 tlyric 常常整段为空。实测日文曲能拿到完整中文翻译。
     */
    override suspend fun getLyrics(song: SongSearchResult): LyricsResult? {
        val id = song.id.trim()
        if (id.isBlank()) return null
        return swallowErrors("歌词 id=$id") {
            limiter.acquire()
            val payload = http.getJson(
                url = LYRIC_ENDPOINT,
                params = mapOf("os" to "osx", "id" to id, "lv" to "-1", "kv" to "-1", "tv" to "-1"),
                headers = mapOf("Referer" to REFERER),
                timeoutMs = NativeHttp.TIMEOUT_DOMESTIC_MS
            ).asObject() ?: return@swallowErrors null
            val text = buildBilingualLrc(
                original = payload.obj("lrc")?.str("lyric").orEmpty(),
                translation = payload.obj("tlyric")?.str("lyric").orEmpty()
            )
            decodeLyrics(text)
        }
    }

    /** 搜索结果里的 picUrl 无尺寸参数，补 `?param=` 取大图（原图可达 3000px，太大）。 */
    override fun upgradeCoverUrl(url: String): String =
        if (url.contains("music.126.net") && !url.contains("?")) "$url?param=800y800" else url

    private companion object {
        const val ENDPOINT = "https://music.163.com/api/search/get/web"
        const val LYRIC_ENDPOINT = "https://music.163.com/api/song/lyric"
        const val REFERER = "https://music.163.com/"
        const val MAX_LIMIT = 30
        const val DEFAULT_SEPARATOR = "/"
        const val MIN_INTERVAL_MS = 350L
    }
}

/**
 * QQ 音乐 —— 华语曲库与网易互补，部分冷门曲目只有它有。
 *
 * 这里有两级：先走 `client_search_cp`（字段最全：专辑、时长、专辑封面 mid），
 * 拿不到结果再退到 `smartbox_new`（只剩歌名 + 歌手，但可用性最好）。
 * 实测在当前网络下前者返回 500、后者正常，两种状态在用户手机上都会出现，
 * 所以做成互补而不是二选一。
 */
class QQMusicSource(
    http: NativeHttp
) : NativeSearchSource(http) {

    override val key: String = "qqmusic"
    override val name: String = "QQ 音乐"

    /** QQ 也提供歌词；翻译轨（trans）只有部分曲目有，有就带上，没有不影响原文。 */
    override val lyricsEnabled: Boolean = true
    override val coverEnabled: Boolean = true

    override suspend fun searchSongs(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int
    ): List<SongSearchResult> = safeQuery(keyword) {
        if (keyword.isBlank()) return@safeQuery emptyList()
        queryFullSearch(keyword, page, separator, pageSize)
            .ifEmpty { querySmartBox(keyword, page, separator, pageSize) }
    }

    private suspend fun queryFullSearch(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int
    ): List<SongSearchResult> {
        val payload = http.getJson(
            url = SEARCH_ENDPOINT,
            params = mapOf(
                "w" to keyword,
                "format" to "json",
                "p" to page.coerceAtLeast(1).toString(),
                "n" to pageSize.coerceIn(1, MAX_LIMIT).toString()
            ),
            headers = mapOf("Referer" to "https://y.qq.com/"),
            timeoutMs = NativeHttp.TIMEOUT_DOMESTIC_MS
        ).asObject() ?: return emptyList()

        return payload.obj("data")?.obj("song")?.array("list").orEmpty().mapNotNull { element ->
            val song = element.asObject() ?: return@mapNotNull null
            val title = song.str("songname", "title")
            if (title.isBlank()) return@mapNotNull null
            val albumMid = song.str("albummid")
            buildResult(
                remoteId = song.str("songmid", "songid").ifBlank { title },
                title = title,
                artist = song.array("singer")
                    .mapNotNull { it.asObject()?.str("name")?.takeIf(String::isNotBlank) }
                    .joinToString(separator.ifBlank { DEFAULT_SEPARATOR }),
                album = song.str("albumname"),
                durationMs = secondsToMillis(song.doubleOrNull("interval")),
                coverUrl = if (albumMid.isBlank()) "" else "$COVER_PREFIX$albumMid.jpg"
            )
        }
    }

    private suspend fun querySmartBox(
        keyword: String,
        page: Int,
        separator: String,
        pageSize: Int
    ): List<SongSearchResult> {
        val payload = http.getJson(
            url = SMARTBOX_ENDPOINT,
            params = mapOf("key" to keyword, "format" to "json"),
            headers = mapOf("Referer" to "https://y.qq.com/"),
            timeoutMs = NativeHttp.TIMEOUT_DOMESTIC_MS
        ).asObject() ?: return emptyList()

        return payload.obj("data")?.obj("song")?.array("itemlist").orEmpty()
            .mapNotNull { element ->
                val song = element.asObject() ?: return@mapNotNull null
                val title = song.str("name")
                if (title.isBlank()) return@mapNotNull null
                buildResult(
                    remoteId = song.str("mid", "id").ifBlank { title },
                    // smartbox 的 singer 是已拼好的字符串，用 "、" 或 "/" 分隔
                    artist = song.str("singer")
                        .split(ARTIST_SPLIT)
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .joinToString(separator.ifBlank { DEFAULT_SEPARATOR }),
                    title = title
                )
            }
            .drop((page - 1).coerceAtLeast(0) * pageSize)
            .take(pageSize.coerceIn(1, MAX_LIMIT))
    }

    /**
     * 歌词接口需要提供 `songmid`，也就是搜索结果里的 `id`。
     * `nobase64=1` 让服务端直接返回明文，省掉一次 base64 解码。
     */
    override suspend fun getLyrics(song: SongSearchResult): LyricsResult? {
        val songMid = song.id.trim()
        if (songMid.isBlank()) return null
        return swallowErrors("歌词 mid=$songMid") {
            val payload = http.getJson(
                url = LYRIC_ENDPOINT,
                params = mapOf("songmid" to songMid, "format" to "json", "nobase64" to "1"),
                headers = mapOf("Referer" to REFERER),
                timeoutMs = NativeHttp.TIMEOUT_DOMESTIC_MS
            ).asObject() ?: return@swallowErrors null
            val text = buildBilingualLrc(
                original = payload.str("lyric"),
                translation = payload.str("trans")
            )
            decodeLyrics(text)
        }
    }

    /** 搜索用 300×300 缩略图，挑封面时换成 800×800（实测 500/800 均可用）。 */
    override fun upgradeCoverUrl(url: String): String =
        url.replace("T002R300x300M000", "T002R800x800M000")

    private companion object {
        const val SEARCH_ENDPOINT = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
        const val SMARTBOX_ENDPOINT = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg"
        const val LYRIC_ENDPOINT = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        const val REFERER = "https://y.qq.com/"
        const val COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R300x300M000"
        const val MAX_LIMIT = 30
        const val DEFAULT_SEPARATOR = "/"
        val ARTIST_SPLIT = Regex("""[、,，&/]""")
    }
}

/** 酷狗音乐 —— 召回补充位，字段较少但接口最宽松。 */
class KugouSource(
    http: NativeHttp
) : NativeSearchSource(http) {

    override val key: String = "kugou"
    override val name: String = "酷狗音乐"

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
                "keyword" to keyword,
                "page" to page.coerceAtLeast(1).toString(),
                "pagesize" to pageSize.coerceIn(1, MAX_LIMIT).toString()
            ),
            timeoutMs = NativeHttp.TIMEOUT_DOMESTIC_MS
        ).asObject() ?: return@safeQuery emptyList()

        payload.obj("data")?.array("info").orEmpty().mapNotNull { element ->
            val song = element.asObject() ?: return@mapNotNull null
            val title = song.str("songname")
            if (title.isBlank()) return@mapNotNull null
            buildResult(
                remoteId = song.str("hash", "album_id").ifBlank { title },
                title = title,
                artist = song.str("singername")
                    .split(ARTIST_SPLIT)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .joinToString(separator.ifBlank { DEFAULT_SEPARATOR }),
                album = song.str("album_name"),
                durationMs = secondsToMillis(song.doubleOrNull("duration"))
            )
        }
    }

    private companion object {
        const val ENDPOINT = "https://mobilecdn.kugou.com/api/v3/search/song"
        const val MAX_LIMIT = 30
        const val DEFAULT_SEPARATOR = "/"
        val ARTIST_SPLIT = Regex("""[、,，&]""")
    }
}

/**
 * 咪咕音乐 —— 最后一道召回，正版曲库的补充。
 *
 * 端点用的是移动端 `search_all.do`（MIGUM2.0）。原先桌面站的
 * `scr_search_tag` 现在会 301 到 H5 页面，拿到的是 HTML 而不是 JSON，已废弃。
 */
class MiguSource(
    http: NativeHttp
) : NativeSearchSource(http) {

    override val key: String = "migu"
    override val name: String = "咪咕音乐"

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
                "text" to keyword,
                "pageNo" to page.coerceAtLeast(1).toString(),
                "pageSize" to pageSize.coerceIn(1, MAX_LIMIT).toString(),
                // 只搜歌曲，不要把彩铃/振铃一起拉回来
                "searchSwitch" to """{"song":1}"""
            ),
            headers = mapOf("Referer" to "https://m.music.migu.cn/"),
            timeoutMs = NativeHttp.TIMEOUT_DOMESTIC_MS
        ).asObject() ?: return@safeQuery emptyList()

        payload.obj("songResultData")?.array("result").orEmpty().mapNotNull { element ->
            val song = element.asObject() ?: return@mapNotNull null
            val title = song.str("name")
            if (title.isBlank()) return@mapNotNull null
            buildResult(
                remoteId = song.str("id", "copyrightId").ifBlank { title },
                title = title,
                artist = song.array("singers")
                    .mapNotNull { it.asObject()?.str("name")?.takeIf(String::isNotBlank) }
                    .joinToString(separator.ifBlank { DEFAULT_SEPARATOR }),
                album = song.array("albums").firstOrNull().asObject()?.str("name").orEmpty(),
                coverUrl = song.pickCover()
            )
        }
    }

    /** imgSizeType 01/02/03 对应不同尺寸，优先取 03（最大），没有就取最后一个。 */
    private fun JsonObject.pickCover(): String {
        val items = array("imgItems").mapNotNull { it.asObject() }
        if (items.isEmpty()) return ""
        val largest = items.firstOrNull { it.str("imgSizeType") == "03" } ?: items.last()
        return largest.str("img")
    }

    private companion object {
        const val ENDPOINT = "https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do"
        const val MAX_LIMIT = 30
        const val DEFAULT_SEPARATOR = "/"
    }
}
