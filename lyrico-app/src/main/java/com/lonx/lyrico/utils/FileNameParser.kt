package com.lonx.lyrico.utils

import kotlin.math.abs

/**
 * 文件名解析引擎（V0.08）—— 移植 MusicOrganizer 的 `NameParser`。
 *
 * 供「文件名清理」（去污染、去音轨前缀）与「目录整理」（按艺人/专辑归位）复用，
 * 也用于文件名缺失标签时反解歌手/歌名。
 */
object FileNameParser {

    /** 居中分隔符（- / – / — / ～ …）。 */
    private val DASH = Regex("""\s*(?:-|–|—|－|~|～)\s*""")

    /** 去掉开头的音轨号（如「01 - 」「01. 」「1. 」「01_ 」）。 */
    fun stripTrackPrefix(text: String): String {
        return text.replace(Regex("""^\s*\d{1,3}\s*[-.、_]\s*"""), "").trim()
    }

    /** 去污染后缀 + 音轨前缀 + 首尾书名号/方括号。 */
    fun stripNameNoise(stem: String?): String {
        var text = DuplicateDetector.stripTrailingNumericSuffix(stem?.trim() ?: "")
        text = stripTrackPrefix(text)
        if (text.length >= 2) {
            val first = text[0]
            val last = text[text.length - 1]
            if ((first == '[' && last == ']') || (first == '《' && last == '》')) {
                text = text.substring(1, text.length - 1).trim()
            }
        }
        return text
    }

    data class Split(val left: String, val right: String, val style: String)

    data class Candidate(val artist: String?, val title: String, val style: String)

    /** 拆成 (左, 右, 风格)：居中分隔符优先；无分隔符返回 null。 */
    fun splitArtistTitle(stem: String): Split? {
        val text = stripNameNoise(stem)
        val ranges = DASH.findAll(text).map { it.range.first to (it.range.last + 1) }.toList()
        if (ranges.isEmpty()) return null
        val center = text.length / 2.0
        var chosen: Pair<Int, Int>? = null
        var best = Double.NEGATIVE_INFINITY
        for ((start, end) in ranges) {
            val left = text.substring(0, start).trim()
            val right = text.substring(end).trim()
            if (left.isEmpty() || right.isEmpty()) continue
            val score = -abs((start + end) / 2.0 - center)
            if (score > best) {
                best = score
                chosen = start to end
            }
        }
        val c = chosen ?: return null
        val spaced = Regex("""\s[-–—－~～]\s""").containsMatchIn(text)
        return Split(
            left = text.substring(0, c.first).trim(),
            right = text.substring(c.second).trim(),
            style = if (spaced) "F1" else "F3"
        )
    }

    /** 文件名候选：有分隔符给双向两个，否则单段歌名。 */
    fun nameCandidates(stem: String): List<Candidate> {
        val normalized = stripNameNoise(stem)
        if (normalized.isEmpty()) return emptyList()
        val split = splitArtistTitle(stem)
        return if (split != null) {
            val reverse = if (split.style == "F1") "F2" else "F4"
            listOf(
                Candidate(split.left, split.right, split.style),
                Candidate(split.right, split.left, reverse)
            )
        } else {
            listOf(Candidate(null, normalized, "F5/F6"))
        }
    }

    /** 反解出 (artist, title) 候选对，供聚合与匹配使用。 */
    fun candidatePairs(stem: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (c in nameCandidates(stem)) {
            val artist = c.artist?.trim() ?: ""
            val title = c.title.trim()
            if (title.isNotEmpty() && (artist to title) !in out) out.add(artist to title)
        }
        return out
    }
}
