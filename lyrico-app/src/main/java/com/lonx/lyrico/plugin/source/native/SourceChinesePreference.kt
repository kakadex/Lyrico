package com.lonx.lyrico.plugin.source.native

import com.lonx.lyrico.data.model.lyrics.SearchSource

/**
 * 中日韩统一表意文字（含假名、兼容区、半角片假名）。
 * 命中即视为「中文检索词」，触发境内源优先。
 */
private val CJK_REGEX =
    Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff\\uff66-\\uff9f]")

/** 检索词（标题 / 歌手）是否含中文（含日文假名也算，这类曲目境内源覆盖更好）。 */
fun String.containsCJK(): Boolean = CJK_REGEX.containsMatchIn(this)

/**
 * 语言感知排序（强制）：检索词含中文时，把境内源（[NativeSearchSource.isDomestic] 为 true，
 * 即网易 / QQ / 酷狗 / 咪咕）强制排到前面优先查询；非中文检索词保持原顺序（全球库在前兜底）。
 *
 * 分组后组内顺序不变（稳定分区）。无论用户是否自定义过源顺序，中文曲目都强制境内源优先——
 * 这是产品硬要求：中文曲在境内源的命中率与准确度显著更高。英文等曲目则完全尊重既定顺序。
 */
fun List<SearchSource>.preferDomesticForChinese(text: String): List<SearchSource> {
    if (!text.containsCJK()) return this
    val (domestic, others) = partition { it is NativeSearchSource && it.isDomestic }
    return domestic + others
}
