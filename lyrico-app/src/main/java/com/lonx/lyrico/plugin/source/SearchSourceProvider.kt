package com.lonx.lyrico.plugin.source

import com.lonx.lyrico.data.model.lyrics.SearchSource
import com.lonx.lyrico.data.model.lyrics.isEnabledFor
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.data.model.plugin.supportsSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 搜索源的唯一出口：**插件源 + 内置源**合并后的列表。
 *
 * 内置源（MusicBrainz / Deezer / iTunes / 网易 / QQ / 酷狗 / 咪咕 / LRCLIB）不走插件仓库，
 * 因此在这里直接拼进去，而不是塞进数据库——它们随版本发布、不可卸载，也不需要 manifest 同步。
 * 合并后统一按 [forSourceType] 排序，插件源 sortOrder 更小所以排在前面，内置源做兜底。
 */
class SearchSourceProvider(
    private val pluginManager: PluginSearchSourceManager,
    private val nativeSources: List<SearchSource> = emptyList()
) {
    fun observeAllSources(): Flow<List<SearchSource>> {
        return pluginManager.observeSources().map { it + nativeSources }
    }

    suspend fun getAllSources(): List<SearchSource> {
        return pluginManager.getSources() + nativeSources
    }

    fun observeSources(sourceType: PluginSourceType): Flow<List<SearchSource>> {
        return pluginManager.observeSources(sourceType).map { sources ->
            (sources + nativeSources).forSourceType(sourceType)
        }
    }

    suspend fun getSources(sourceType: PluginSourceType): List<SearchSource> {
        return (pluginManager.getSources(sourceType) + nativeSources).forSourceType(sourceType)
    }

    suspend fun getSourceWithState(pluginId: String): SearchSourceWithState? {
        return pluginManager.getSourceWithState(pluginId)
    }

}

internal fun List<SearchSource>.forSourceType(
    sourceType: PluginSourceType
): List<SearchSource> = filter { source ->
    source.capabilities.supportsSourceType(sourceType) && source.isEnabledFor(sourceType)
}.sortedWith(
    compareBy<SearchSource> { source ->
        when (sourceType) {
            PluginSourceType.METADATA -> source.metadataSortOrder
            PluginSourceType.LYRICS -> source.lyricsSortOrder
            PluginSourceType.COVER -> source.coverSortOrder
        }
    }.thenBy { it.name }
)
