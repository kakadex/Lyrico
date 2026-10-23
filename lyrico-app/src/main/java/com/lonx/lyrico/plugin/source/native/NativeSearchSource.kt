package com.lonx.lyrico.plugin.source.native

import android.util.Log
import com.lonx.lyrico.data.model.lyrics.LyricsPayloadType
import com.lonx.lyrico.data.model.lyrics.LyricsResult
import com.lonx.lyrico.data.model.lyrics.SearchSource
import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import com.lonx.lyrico.data.model.metadata.StandardPluginField
import com.lonx.lyrico.data.model.plugin.PluginCapability
import com.lonx.lyrico.utils.LyricDecoder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

/**
 * 内置原生搜索源基类。
 *
 * 设计取舍：
 * - **id 统一加 `native.` 前缀**，保证与插件 id 永不相撞（插件 id 由 manifest 决定，没法保证不叫 musicbrainz）；
 * - **打分交给宿主**：Lyrico 自己有一套 `MusicMatchUtils.calculateMatchScore`（片段匹配 + 时长 + 名次加分），
 *   内置源只负责把字段取干净、时长统一成毫秒，不重复实现 MusicOrganizer 那套置信度聚合。
 *   这样内置源和插件源在同一套排序规则下比较，谁更准一目了然。
 * - **失败即空**：单个源超时/改版/被限流都只损失这一家，不影响整批匹配。
 */
abstract class NativeSearchSource(
    protected val http: NativeHttp
) : SearchSource {

    /** 稳定的源标识，拼在 id 后面；一旦发布不可再改，否则已保存的源顺序配置会失效。 */
    protected abstract val key: String

    override val id: String get() = "$ID_PREFIX$key"

    /**
     * 能力声明必须跟着开关走。
     *
     * `SearchSourceProvider.forSourceType()` 是按 `capabilities.supportsSourceType(type)` 过滤的：
     * 只声明 SEARCH_SONGS，则 `coverEnabled = true` 的源在「封面」列表里会被整体过滤掉——
     * 这正是内置封面搜索（含批量匹配封面）长期「有代码但永远是 0 个源」的原因。
     * 用 getter 而不是常量，子类只改 `*Enabled` 开关即可，不必再重写 capabilities。
     */
    override val capabilities: Set<PluginCapability>
        get() = buildSet {
            add(PluginCapability.SEARCH_SONGS)
            if (lyricsEnabled) add(PluginCapability.GET_LYRICS)
            if (coverEnabled) add(PluginCapability.SEARCH_COVERS)
        }

    override val metadataSortOrder: Int get() = SORT_ORDER
    override val lyricsSortOrder: Int get() = SORT_ORDER
    override val coverSortOrder: Int get() = SORT_ORDER

    override val coverEnabled: Boolean get() = false
    override val lyricsEnabled: Boolean get() = false

    /**
     * 是否为境内（中国大陆）数据源。
     * 语言感知排序 [preferDomesticForChinese] 用它判断：检索词含中文时境内源优先查询。
     * 默认 false（海外源 / 第三方插件源），境内四家各自覆写为 true。
     */
    open val isDomestic: Boolean get() = false

    override suspend fun getLyrics(song: SongSearchResult): LyricsResult? = null

    /**
     * 封面搜索默认复用歌曲搜索：搜索结果里本来就带封面，不另开一套接口。
     * 只保留有封面的条目，并把封面换成大图（[upgradeCoverUrl]）——
     * 元数据流程里 300×300 够用，但用户是来挑封面当专辑图的，太小没法看。
     */
    override suspend fun searchCovers(
        keyword: String,
        page: Int,
        pageSize: Int
    ): List<SongSearchResult> = searchSongs(keyword, page, DEFAULT_SEPARATOR, pageSize)
        .filter { it.picUrl.isNotBlank() }
        .map { it.withCover(upgradeCoverUrl(it.picUrl)) }

    /** 搜索结果里的封面是缩略图，挑封面时用大图。子类按需重写。 */
    protected open fun upgradeCoverUrl(url: String): String = url

    private fun SongSearchResult.withCover(url: String): SongSearchResult {
        if (url.isBlank() || url == picUrl) return this
        return copy(
            picUrl = url,
            fields = fields.toMutableMap().apply { put(StandardPluginField.COVER_URL.key, url) }
        )
    }

    /**
     * 把「原文 LRC + 翻译 LRC」合成一份带翻译轨的 LRC 文本交给宿主解析。
     *
     * 宿主 `LrcDocumentFormat.separateLrcTracks` 按时间戳分组：同一时间点的第二行会被
     * 认作翻译轨，所以我们只要按时间点把翻译行插到原文行后面即可，不用自己造
     * `LyricsResult`（那样还要维护 tags / 逐字 / 音译一堆字段）。
     *
     * **不能拿时间戳字符串当 key**：网易的原文是 `[00:19.629]`、翻译是 `[00:19.64]`，
     * 小数位不同直接对不上。这里改成按毫秒取「最近且不超过 ±150ms」的翻译行，
     * 歌词行之间通常隔好几秒，最近邻足够稳。
     */
    protected fun buildBilingualLrc(original: String, translation: String): String {
        if (original.isBlank()) return ""
        val translatedLines = translation.lineSequence().mapNotNull { line ->
            val ms = line.lrcTimeMs() ?: return@mapNotNull null
            val text = line.substringAfter(']').trim()
            if (text.isBlank()) null else ms to text
        }.toList()
        if (translatedLines.isEmpty()) return original

        return original.lineSequence().joinToString(separator = "\n") { line ->
            val ms = line.lrcTimeMs()
            if (ms == null) return@joinToString line
            val best = translatedLines.minByOrNull { abs(it.first - ms) }
                ?.takeIf { abs(it.first - ms) <= LRC_MERGE_TOLERANCE_MS }
            if (best == null) line else "$line\n[${best.first.lrcTimestamp()}]${best.second}"
        }
    }

    /** 取该行的时间戳（毫秒）；非歌词行（标题、空行）返回 null。 */
    private fun String.lrcTimeMs(): Long? {
        val match = LRC_TIMED_LINE.matchEntire(trim()) ?: return null
        val (_, minute, second, fraction, _) = match.destructured
        return minute.toLong() * 60_000L +
            second.toLong() * 1_000L +
            fraction.padEnd(3, '0').take(3).toLong()
    }

    /** 回写成 [mm:ss.xx] 形式（网易/QQ 都是两位小数，统一输出两位即可）。 */
    private fun Long.lrcTimestamp(): String = buildString {
        val minute = this@lrcTimestamp / 60_000L
        val second = (this@lrcTimestamp % 60_000L) / 1_000L
        val centi = (this@lrcTimestamp % 1_000L) / 10L
        append(minute.toString().padStart(2, '0'))
        append(':').append(second.toString().padStart(2, '0'))
        append('.').append(centi.toString().padStart(2, '0'))
    }

    /** 交给宿主的 LRC 解析链；解析不出结构化结果时退化成纯文本 LRC 原样透传。 */
    protected fun decodeLyrics(text: String): LyricsResult? {
        if (text.isBlank()) return null
        return LyricDecoder.decode(text) ?: LyricsResult(
            tags = emptyMap(),
            original = emptyList(),
            translated = null,
            romanization = null,
            payloadType = LyricsPayloadType.RAW_PLAIN_LRC,
            isWordByWord = false,
            rawPlainLrc = text
        )
    }

    /**
     * 把一次具体查询包起来：吞掉单源异常，但保留协程取消语义
     * （批量任务被用户取消时必须能立刻停下，不能当成「这个源没结果」继续跑）。
     */
    protected suspend fun safeQuery(
        keyword: String,
        block: suspend () -> List<SongSearchResult>
    ): List<SongSearchResult> = try {
        block()
    } catch (throwable: Exception) {
        if (throwable is CancellationException) throw throwable
        Log.w(TAG, "$id 查询失败 keyword=$keyword: ${throwable.message}")
        emptyList()
    }

    /** 子类（尤其是 getLyrics）记日志用，统一 tag 方便按源筛选。 */
    protected fun logW(message: String, throwable: Throwable? = null) {
        Log.w(TAG, "$id: $message", throwable)
    }

    /** 把子类里「非取消异常一律吞掉」的写法收进来，避免每处手抄。 */
    protected inline fun <T> swallowErrors(name: String, block: () -> T): T? = try {
        block()
    } catch (throwable: Exception) {
        if (throwable is CancellationException) throw throwable
        logW("$name 失败: ${throwable.message}", throwable)
        null
    }

    /** 把所有字段都走一遍 StandardPluginField 的 key，避免写出宿主不认识的字段名。 */
    protected fun buildResult(
        remoteId: String,
        title: String,
        artist: String,
        album: String = "",
        date: String = "",
        trackNumber: String = "",
        durationMs: Long = 0L,
        coverUrl: String = "",
        extraFields: Map<String, String> = emptyMap(),
        internal: Map<String, String> = emptyMap()
    ): SongSearchResult {
        val fields = buildMap {
            putIfNotBlank(StandardPluginField.TITLE.key, title)
            putIfNotBlank(StandardPluginField.ARTIST.key, artist)
            putIfNotBlank(StandardPluginField.ALBUM.key, album)
            putIfNotBlank(StandardPluginField.DATE.key, date)
            putIfNotBlank(StandardPluginField.TRACK_NUMBER.key, trackNumber)
            putIfNotBlank(StandardPluginField.COVER_URL.key, coverUrl)
            extraFields.forEach { (fieldKey, value) ->
                if (StandardPluginField.fromKey(fieldKey) != null) putIfNotBlank(fieldKey, value)
            }
        }
        return SongSearchResult(
            id = remoteId,
            pluginId = id,
            pluginName = name,
            title = title,
            artist = artist,
            album = album,
            duration = durationMs,
            date = date,
            trackNumber = trackNumber,
            picUrl = coverUrl,
            fields = fields,
            internal = internal
        )
    }

    private fun MutableMap<String, String>.putIfNotBlank(fieldKey: String, value: String) {
        if (value.isNotBlank()) put(fieldKey, value)
    }

    companion object {
        private const val TAG = "NativeSource"

        const val ID_PREFIX = "native."

        const val DEFAULT_SEPARATOR = "/"

        /** 歌词行时间戳匹配容差（见 [buildBilingualLrc]）。 */
        private const val LRC_MERGE_TOLERANCE_MS = 150L

        private val LRC_TIMED_LINE = Regex("""^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\](.*)$""")

        /**
         * 排在插件源之后：用户自己装了插件，说明更信任插件的源，
         * 内置源只做兜底。没有任何插件时它们就是唯一可用的源。
         */
        const val SORT_ORDER = 500
    }
}
