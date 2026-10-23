package com.lonx.lyrico.plugin.source.native

import com.lonx.lyrico.data.model.lyrics.SearchSource

/**
 * 内置源清单。
 *
 * V0.01 接入 7 个在线标签源 + 1 个歌词源，让 LyricoX 不装任何插件也能完成
 * 「批量匹配标签 / 批量补歌词」——这正是 MusicOrganizer 原本的核心能力。
 *
 * 顺序即默认优先级（宿主还会按 [NativeSearchSource.SORT_ORDER] 排一次）：
 * 先把全球库（MusicBrainz / Deezer / iTunes）排在前面，再用境内三家补齐中文曲库。
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
