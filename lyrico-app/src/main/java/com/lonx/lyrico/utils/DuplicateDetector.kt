package com.lonx.lyrico.utils

/**
 * 文件名查重引擎（V0.07）—— 移植 MusicOrganizer 的 `Duplicates`（UnionFind + bigram 倒排 + 归一 + 保优）。
 *
 * 纯函数、无 IO/UI，供查重扫描与预览界面复用。
 * 归一规则：去掉「(1) / -副本 / copy / _2」这类复制痕迹、去尾数字、小写、去标点空格；
 * 繁简归一待接入（见 TODO）。相似度复用 [MusicMatchUtils.smartSimilarity]，阈值 0.90，
 * 短名（≤4 字）一字之差因综合分不足阈值而天然不判重（避免「晴天/雨天」误删）。
 */
object DuplicateDetector {

    private const val SIMILARITY_THRESHOLD = 0.90
    private const val MIN_LENGTH_RATIO = 0.58

    /** 可多次匹配的复制后缀（(1) / -副本 / copy / _2 …）。 */
    private val COPY_SUFFIX = Regex(
        """(?:[\s（(]\s*\d+\s*[)）]|[\s\-_]\s*(?:副本|拷贝|copy|複製|复制)\s*\d*)*$""",
        RegexOption.IGNORE_CASE
    )

    /** 判断「是否带复制痕迹」用（不能复用 COPY_SUFFIX，它会匹配空串）。 */
    private val COPY_MARK = Regex(
        """[（(]\s*\d+\s*[)）]|[\-_]\s*(?:副本|拷贝|copy|複製|复制)\s*\d*\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** 去掉末尾数字尾巴（如「晴天 2」「晴天(1)」→「晴天」）。 */
    fun stripTrailingNumericSuffix(s: String): String {
        return s.replace(Regex("""\s*[（(]?\s*\d+\s*[)）]?\s*$"""), "").trim()
    }

    /** 归一成查重键：去复制后缀 + 小写 + 去标点空格，只保留字母数字。 */
    fun normalizeDuplicateKey(stem: String?): String {
        var text = stripTrailingNumericSuffix(stem ?: "")
        text = COPY_SUFFIX.replace(text, "")
        // TODO: 接入繁简转换（MusicOrganizer 用 toSimplified），当前先按原文小写归一
        text = text.lowercase()
        text = text.replace(Regex("""\s+"""), "")
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c.isLetterOrDigit()) sb.append(c)
        }
        return sb.toString()
    }

    private fun bigrams(text: String): Set<String> {
        if (text.length < 2) return setOf(text)
        val out = HashSet<String>(text.length - 1)
        for (i in 0 until text.length - 1) {
            out.add(text.substring(i, i + 2))
        }
        return out
    }

    /** 建议保留组内哪一个；-1 表示没有明显更优的。规则：无复制痕迹 > 体积更大 > 路径更短。 */
    fun recommendKeep(group: DuplicateGroup): Int {
        if (group.files.isEmpty()) return -1
        var best = 0
        var bestScore = -1.0
        val maxSize = maxOf(1L, group.files.maxOfOrNull { it.size } ?: 1L)
        group.files.forEachIndexed { i, it ->
            var score = 0.0
            if (!COPY_MARK.containsMatchIn(it.stem())) score += 1.0
            score += 0.40 * (it.size.toDouble() / maxSize)
            score -= 0.20 * minOf(1.0, it.relativePath.length / 120.0)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** 摘要文案：「3 组 · 5 个多余文件 · 约 12.3 MB」。 */
    fun summary(groups: List<DuplicateGroup>): String {
        if (groups.isEmpty()) return "没有发现相似文件"
        var extra = 0
        var wasted = 0L
        for (g in groups) {
            extra += maxOf(0, g.files.size - 1)
            wasted += g.wastedBytes
        }
        return "${groups.size} 组 · $extra 个多余文件 · 约 ${formatSize(wasted)}"
    }

    /** 在 [items] 中找相似文件名分组（完全相同或相似度 ≥ 0.90）。 */
    fun detect(items: List<DuplicateItem>): List<DuplicateGroup> {
        val total = items.size
        if (total < 2) return emptyList()

        val keys = items.map { normalizeDuplicateKey(it.stem()) }
        val uf = UnionFind(total)

        // ① 归一键完全相同的直接并组
        val byKey = HashMap<String, MutableList<Int>>()
        for (i in 0 until total) {
            val key = keys[i]
            if (key.isNotEmpty()) byKey.getOrPut(key) { mutableListOf() }.add(i)
        }
        for (list in byKey.values) {
            for (j in 1 until list.size) uf.union(list[0], list[j])
        }

        // ② bigram 倒排 + 相似度阈值，抓「只差一个复制尾巴/一个字的近重复」
        val inverted = HashMap<String, MutableList<Int>>()
        for (i in 0 until total) {
            val key = keys[i]
            if (key.length < 3) continue
            for (gram in bigrams(key)) inverted.getOrPut(gram) { mutableListOf() }.add(i)
        }
        val compared = HashSet<Long>()
        for (i in 0 until total) {
            val key = keys[i]
            if (key.length < 3) continue
            val candidates = HashSet<Int>()
            for (gram in bigrams(key)) {
                inverted[gram]?.let { list ->
                    val limit = minOf(list.size, 400)
                    for (j in 0 until limit) candidates.add(list[j])
                }
            }
            var count = 0
            for (other in candidates.sorted()) {
                if (other == i) continue
                val pair = if (i < other) (i.toLong() shl 32) or other.toLong() else (other.toLong() shl 32) or i.toLong()
                if (!compared.add(pair)) continue
                val otherKey = keys[other]
                if (otherKey.isEmpty()) continue
                val minLen = minOf(key.length, otherKey.length)
                val maxLen = maxOf(key.length, otherKey.length)
                if (minLen == 0 || minLen.toDouble() / maxLen < MIN_LENGTH_RATIO) continue
                if (key == otherKey) {
                    uf.union(i, other)
                    continue
                }
                if (MusicMatchUtils.smartSimilarity(key, otherKey) >= SIMILARITY_THRESHOLD) {
                    uf.union(i, other)
                }
                count++
                if (count >= 500) break
            }
        }

        // ③ 收拢成组，只保留 ≥2 个文件的组
        val clusters = HashMap<Int, DuplicateGroup>()
        for (i in 0 until total) {
            val root = uf.find(i)
            val group = clusters.getOrPut(root) { DuplicateGroup(key = keys[i]) }
            group.files.add(items[i])
        }
        val out = clusters.values.filter { it.files.size >= 2 }.toMutableList()
        out.sortWith(compareByDescending<DuplicateGroup> { it.files.size }
            .thenByDescending { it.wastedBytes }
            .thenBy { it.key })
        return out
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "$bytes B" else String.format("%.1f %s", v, units[i])
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = IntArray(size)

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) {
                parent[r] = parent[parent[r]]
                r = parent[r]
            }
            return r
        }

        fun union(a: Int, b: Int) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            if (rank[ra] < rank[rb]) {
                parent[ra] = rb
            } else if (rank[ra] > rank[rb]) {
                parent[rb] = ra
            } else {
                parent[rb] = ra
                rank[ra]++
            }
        }
    }
}

/** 查重扫描的一个文件条目。 */
data class DuplicateItem(
    val id: String,
    val fileName: String,
    val size: Long,
    val relativePath: String = ""
) {
    fun stem(): String = fileName.substringBeforeLast(".", fileName)
}

/** 一组相似文件。 */
data class DuplicateGroup(
    val files: MutableList<DuplicateItem> = mutableListOf(),
    val key: String,
    val score: Double = 1.0
) {
    /** 这一组最多能省多少空间（全删只留一个）。 */
    val wastedBytes: Long
        get() {
            val total = files.sumOf { it.size }
            val keep = DuplicateDetector.recommendKeep(this)
            val keepSize = if (keep >= 0) files[keep].size else 0L
            return maxOf(0L, total - keepSize)
        }

    val keepIndex: Int get() = DuplicateDetector.recommendKeep(this)
}
