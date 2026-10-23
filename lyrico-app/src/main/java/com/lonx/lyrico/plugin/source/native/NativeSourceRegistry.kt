package com.lonx.lyrico.plugin.source.native

import com.lonx.lyrico.data.model.lyrics.SearchSource

/**
 * 内置源清单。
 *
 * V0.01 接入 7 个在线标签源 + 1 个歌词源，让 LyricoX 不装任何插件也能完成
 * 「批量匹配标签 / 批量补歌词」——这正是 MusicOrganizer 原本的核心能力。
 *
 * 默认清单（顺序即默认优先级，宿主还会按 [NativeSearchSource.SORT_ORDER] 排一次）。
 * 语言感知排序：[preferDomesticForChinese] 在检索词含中文（CJK）时把境内源
 * （网易 / QQ / 酷狗 / 咪咕）排到前面优先查询；非中文检索词保持「全球库在前」兜底。
 */
object NativeSourceRegistry {

    fun create(http: NativeHttp): List<SearchSource> = listOf(
        MusicBrainzSource(http),
        DeezerSource(http),
        ITunesSource(http),
        NeteaseSource(http),
        QQMusicSource(http),
        KugouSource(http),
        MiguSource(http),
        LrclibSource(http)
    )
}
