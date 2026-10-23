package com.lonx.lyrico.plugin.source.native

import com.lonx.lyrico.data.model.lyrics.LyricsPayloadType
import com.lonx.lyrico.data.model.lyrics.LyricsResult
import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import com.lonx.lyrico.utils.LyricDecoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * LRCLIB 歌词源（移植自 MusicOrganizer 的 `net/LrcClient`）。
 *
 * 策略与源实现一致：**精确接口优先，失败回退搜索接口**。
 * - `/api/get` 要求 track_name 精确命中，命中率高但只回一条；
 * - `/api/search` 是模糊检索，用来兜住「标题带 remaster 后缀」这类不一致。
 *
 * 另外两点经验：
 * - 该接口对 404 的语义是「库里没有这首」，属于正常结果，不能当故障处理；
 * - 官方没有公布限流值，实测 350ms 间隔不会被拒，票制限流避免批量任务被掐。
 */
class LrclibSource(
    http: NativeHttp
) : NativeSearchSource(http) {

    override val key: String = "lrclib"
    override val name: String = "LRCLIB"

    /** 它是纯歌词源，不该出现在「元数据匹配」的源列表里抢位置。 */
    override val metadataEnabled: Boolean = false
    override val lyricsEnabled: Boolean = true

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
            url = SEARCH_ENDPOINT,
            params = mapOf("q" to keyword)
        )
        (payload as? JsonArray).orEmpty()
            .mapNotNull { it.asObject()?.toSearchResult() }
            .take(pageSize.coerceIn(1, MAX_RESULTS))
    }

    override suspend fun getLyrics(song: SongSearchResult): LyricsResult? {
        val title = song.title.trim()
        if (title.isBlank()) return null

        val candidates = buildList {
            addAll(lookupExact(song, title))
            // 精确接口要么一条要么没有；没有拿到同步歌词时才值得再花一次配额去搜
            if (none { it.synced.isNotBlank() }) addAll(lookupSearch(song, title))
        }
        return candidates
            .filter { it.hasLyrics() }
            .sortedWith(
                compareByDescending<LrcRecord> { it.synced.isNotBlank() }
                    .thenByDescending { it.exact }
                    .thenBy { it.durationDeltaMs(song.duration) }
            )
            .firstOrNull()
            ?.toLyricsResult()
    }

    private suspend fun lookupExact(song: SongSearchResult, title: String): List<LrcRecord> {
        val params = buildMap {
            put("track_name", title)
            song.artist.takeIf { it.isNotBlank() }?.let { put("artist_name", it) }
            song.album.takeIf { it.isNotBlank() }?.let { put("album_name", it) }
            if (song.duration > 0L) put("duration", (song.duration / 1000L).toString())
        }
        limiter.acquire()
        val payload = http.getJson(GET_ENDPOINT, params).asObject() ?: return emptyList()
        val record = payload.toRecord() ?: return emptyList()
        return listOf(record.copy(exact = true))
    }

    private suspend fun lookupSearch(song: SongSearchResult, title: String): List<LrcRecord> {
        limiter.acquire()
        val payload = http.getJson(
            url = SEARCH_ENDPOINT,
            params = mapOf("q" to listOf(title, song.artist).filter { it.isNotBlank() }.joinToString(" "))
        )
        return (payload as? JsonArray).orEmpty()
            .mapNotNull { it.asObject()?.toRecord() }
            .take(MAX_SEARCH_CANDIDATES)
    }

    private fun JsonObject.toSearchResult(): SongSearchResult? {
        val record = toRecord() ?: return null
        if (record.title.isBlank()) return null
        return buildResult(
            remoteId = record.id.ifBlank { record.title },
            title = record.title,
            artist = record.artist,
            album = record.album,
            durationMs = record.durationMs,
            internal = mapOf("instrumental" to record.instrumental.toString())
        )
    }

    private fun JsonObject.toRecord(): LrcRecord? {
        val title = str("trackName", "name")
        if (title.isBlank()) return null
        return LrcRecord(
            id = str("id"),
            title = title,
            artist = str("artistName"),
            album = str("albumName"),
            durationMs = secondsToMillis(doubleOrNull("duration")),
            synced = str("syncedLyrics"),
            plain = str("plainLyrics"),
            instrumental = boolOrNull("instrumental") == true
        )
    }

    private fun LrcRecord.toLyricsResult(): LyricsResult? {
        // 纯音乐没有歌词文本，但仍然是一条有效结果，交给上层决定怎么标记
        if (instrumental && synced.isBlank() && plain.isBlank()) return null
        val text = synced.ifBlank { plain }
        if (text.isBlank()) return null
        // 宿主已有完整的 LRC 解析链（逐字/翻译/音译都能识别），先走它
        LyricDecoder.decode(text)?.let { return it }
        return LyricsResult(
            tags = emptyMap(),
            original = emptyList(),
            translated = null,
            romanization = null,
            payloadType = LyricsPayloadType.RAW_PLAIN_LRC,
            isWordByWord = false,
            rawPlainLrc = text
        )
    }

    private fun LrcRecord.durationDeltaMs(localMs: Long): Long =
        if (localMs <= 0L || durationMs <= 0L) Long.MAX_VALUE else kotlin.math.abs(durationMs - localMs)

    private data class LrcRecord(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long,
        val synced: String,
        val plain: String,
        val instrumental: Boolean,
        val exact: Boolean = false
    ) {
        fun hasLyrics(): Boolean = synced.isNotBlank() || plain.isNotBlank() || instrumental
    }

    private companion object {
        const val GET_ENDPOINT = "https://lrclib.net/api/get"
        const val SEARCH_ENDPOINT = "https://lrclib.net/api/search"
        const val MIN_INTERVAL_MS = 350L
        const val MAX_RESULTS = 30
        const val MAX_SEARCH_CANDIDATES = 10
    }
}
