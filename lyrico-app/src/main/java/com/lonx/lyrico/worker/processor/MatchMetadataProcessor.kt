package com.lonx.lyrico.worker.processor

import android.util.Log
import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.model.BatchMatchConfig
import com.lonx.lyrico.data.model.ScoredSearchResult
import com.lonx.lyrico.data.model.entity.BatchTaskEntity
import com.lonx.lyrico.data.model.entity.BatchTaskItemEntity
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.data.model.match.MatchPreviewPayload
import com.lonx.lyrico.data.model.match.buildPreviewEntries
import com.lonx.lyrico.data.model.log.AppLogLevel
import com.lonx.lyrico.data.model.log.AppLogType
import com.lonx.lyrico.data.model.lyrics.LyricRenderConfig
import com.lonx.lyrico.data.model.lyrics.SearchSource
import com.lonx.lyrico.data.model.lyrics.SongSearchResult
import com.lonx.lyrico.data.model.lyrics.SourceRuntimeConfig
import com.lonx.lyrico.data.model.metadata.MetadataApplyPolicy
import com.lonx.lyrico.data.model.metadata.StandardPluginField
import com.lonx.lyrico.data.model.plugin.GlobalFieldProcessSettings
import com.lonx.lyrico.data.model.plugin.PluginSourceType
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.metadata.MetadataWriteMode
import com.lonx.lyrico.data.model.metadata.SearchResultApplier
import com.lonx.lyrico.data.model.plugin.defaultPluginFieldProcessConfig
import com.lonx.lyrico.data.repository.SettingsRepository
import com.lonx.lyrico.data.repository.AppLogRepository
import com.lonx.lyrico.data.song.library.SongLibraryRepository
import com.lonx.lyrico.data.song.tag.AudioTagReadOptions
import com.lonx.lyrico.data.song.tag.AudioTagRepository
import com.lonx.lyrico.domain.song.usecase.PatchSongTagsUseCase
import com.lonx.lyrico.domain.song.usecase.SaveAudioTagsResult
import com.lonx.lyrico.plugin.source.SearchSourceProvider
import com.lonx.lyrico.utils.LyricEncoder
import com.lonx.lyrico.utils.MatchScoreDetail
import com.lonx.lyrico.utils.MusicMatchUtils
import com.lonx.lyrico.utils.PluginFieldPostProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

class MatchMetadataProcessor(
    private val audioTagRepository: AudioTagRepository,
    private val patchSongTagsUseCase: PatchSongTagsUseCase,
    private val songLibraryRepository: SongLibraryRepository,
    private val settingsRepository: SettingsRepository,
    private val searchSourceProvider: SearchSourceProvider,
    private val appLogRepository: AppLogRepository
) : BatchTaskProcessor {
    override suspend fun process(
        task: BatchTaskEntity,
        item: BatchTaskItemEntity,
        onProgress: suspend (Float) -> Unit
    ): BatchTaskProcessResult {
        val config = task.configJson?.let {
            Json.decodeFromString<MatchMetadataTaskConfig>(it)
        } ?: throw BatchTaskSkippedException("No config")

        val matchConfig = config.matchConfig
        val sources = searchSourceProvider.getSources(PluginSourceType.METADATA)

        sources.forEach { source ->
            val values = config.sourceSettings[source.id].orEmpty()
            source.applyConfig(SourceRuntimeConfig(values))
        }

        val song = songLibraryRepository.getSongByUri(item.songUri)
            ?: throw BatchTaskSkippedException("Song not found")

        val plan = buildPlan(
            matchConfig = matchConfig
        )

        if (!plan.requiresSearch) {
            throw BatchTaskSkippedException("No fields need processing")
        }

        onProgress(0.05f)

        val separator = config.separator
        val currentTag = audioTagRepository.read(song.uri, AudioTagReadOptions(strict = true))

        val shouldWriteLyrics = when (plan.targetModes[MetadataFieldTarget.LYRICS]) {
            MetadataWriteMode.OVERWRITE -> true
            MetadataWriteMode.SUPPLEMENT -> currentTag.lyrics.isNullOrBlank()
            MetadataWriteMode.DISABLED,
            null -> false
        }

        // 封面和歌词不一样：SUPPLEMENT 下要看当前是否真的没有图。
        val shouldWriteCover = when (plan.targetModes[MetadataFieldTarget.COVER]) {
            MetadataWriteMode.OVERWRITE -> true
            MetadataWriteMode.SUPPLEMENT ->
                currentTag.picUrl.isNullOrBlank() && currentTag.pictures.isEmpty()

            MetadataWriteMode.DISABLED,
            null -> false
        }

        val lyricConfig = if (shouldWriteLyrics) {
            config.lyricRenderConfig ?: settingsRepository.getLyricRenderConfig()
        } else {
            null
        }

        val fieldProcessor = PluginFieldPostProcessor(
            GlobalFieldProcessSettings(
                scriptConversion = lyricConfig?.conversionMode
                    ?: settingsRepository.conversionMode.first(),
                removeEmptyLines = lyricConfig?.removeEmptyLines
                    ?: settingsRepository.removeEmptyLines.first()
            )
        )

        val enabledSourceOrder = config.enabledSourceOrderIds
        val queries = MusicMatchUtils.buildSearchQueries(
            song = song,
            preferFileName = matchConfig.preferFileName
        )

        val orderedSources = sources
            .filter { source ->
                enabledSourceOrder.isEmpty() || source.id in enabledSourceOrder
            }
            .sortedBy { source ->
                enabledSourceOrder.indexOf(source.id).let { index ->
                    if (index == -1) Int.MAX_VALUE else index
                }
            }

        var bestMatch: ScoredSearchResult? = null
        var bestMatchDetail: MatchScoreDetail? = null
        val allScoredResults = mutableListOf<ScoredSearchResult>()

        searchLoop@ for ((queryIndex, query) in queries.withIndex()) {
            var queryBest: ScoredSearchResult? = null
            var queryBestDetail: MatchScoreDetail? = null

            for ((sourceIndex, source) in orderedSources.withIndex()) {
                val sourceResults = try {
                    source.searchSongs(
                        keyword = query,
                        separator = separator,
                        pageSize = 2
                    ).mapIndexed { index, result ->
                        val detail = MusicMatchUtils.calculateMatchScoreDetail(
                            result = result,
                            song = song,
                            preferFileName = matchConfig.preferFileName,
                            rankIndex = index
                        )

                        ScoredSearchResult(
                            result = result,
                            score = detail.finalScore,
                            source = source
                        ) to detail
                    }
                } catch (throwable: Exception) {
                    if (throwable is CancellationException) throw throwable
                    Log.w(
                        TAG,
                        "Search source failed. songUri=${song.uri}, source=${source.id}, query=$query",
                        throwable
                    )
                    logPluginBatchException(
                        message = "Batch metadata match source failed\n" +
                                "task=${task.taskId}\nitem=${item.itemId}\n" +
                                "songUri=${song.uri}\nsource=${source.id}\nquery=$query",
                        throwable = throwable,
                        relatedId = source.id
                    )
                    emptyList()
                }

                Log.d(
                    TAG,
                    "Search source results. songUri=${song.uri}, source=${source.id}, " +
                            "query=$query, count=${sourceResults.size}, " +
                            "top=${sourceResults.take(3).map { (scored, detail) ->
                        "${scored.result.id}:${scored.result.title}|score=${detail.finalScore}" +
                                        "|text=${detail.textScore}|keys=${scored.result.normalizedFields().keys}" +
                                        "|commentBlank=${scored.result.normalizedFields()["comment"].isNullOrBlank()}"
                            }}"
                )
                logPluginBatch(
                    level = AppLogLevel.DEBUG,
                    message = "Batch metadata match source returned ${sourceResults.size} result(s)",
                    detail = buildString {
                        appendLine("task=${task.taskId}")
                        appendLine("item=${item.itemId}")
                        appendLine("songUri=${song.uri}")
                        appendLine("source=${source.id}")
                        appendLine("query=$query")
                        appendLine("resultCount=${sourceResults.size}")
                        appendLine("top=${sourceResults.take(3).map { (scored, detail) -> "${scored.result.id}:${scored.result.title}|score=${detail.finalScore}" }}")
                    },
                    relatedId = source.id
                )

                allScoredResults += sourceResults.map { (scoredResult, _) ->
                    scoredResult
                }

                val sourceBest = sourceResults.maxByOrNull { (_, detail) ->
                    detail.finalScore
                }

                if (sourceBest != null) {
                    val currentScoredResult = sourceBest.first
                    val currentDetail = sourceBest.second

                    if (
                        queryBest == null ||
                        currentDetail.finalScore > (queryBestDetail?.finalScore ?: 0.0)
                    ) {
                        queryBest = currentScoredResult
                        queryBestDetail = currentDetail
                    }

                    if (
                        bestMatch == null ||
                        currentDetail.finalScore > (bestMatchDetail?.finalScore ?: 0.0)
                    ) {
                        bestMatch = currentScoredResult
                        bestMatchDetail = currentDetail
                    }

                    if (
                        currentDetail.finalScore >= 0.92 &&
                        currentDetail.textScore >= 0.86
                    ) {
                        bestMatch = currentScoredResult
                        bestMatchDetail = currentDetail
                        break@searchLoop
                    }
                }

                val sourceCount = orderedSources.size.coerceAtLeast(1)
                val totalSteps = queries.size.coerceAtLeast(1) * sourceCount
                val currentStep = queryIndex * sourceCount + sourceIndex + 1
                onProgress(0.05f + 0.45f * currentStep / totalSteps.toFloat())
            }

            if (
                queryBest != null &&
                queryBestDetail != null &&
                (
                        bestMatch == null ||
                                queryBestDetail.finalScore > (bestMatchDetail?.finalScore ?: 0.0)
                        )
            ) {
                bestMatch = queryBest
                bestMatchDetail = queryBestDetail
            }
        }

        val finalMatch = bestMatch ?: throw BatchTaskSkippedException("No match found")
        val finalDetail = bestMatchDetail ?: throw BatchTaskSkippedException("No match detail found")

        Log.d(
            TAG,
            "Selected match. songUri=${song.uri}, source=${finalMatch.source?.id}, " +
                    "result=${finalMatch.result.id}:${finalMatch.result.title}, " +
                    "score=${finalDetail.finalScore}, textScore=${finalDetail.textScore}, " +
                    "normalizedKeys=${finalMatch.result.normalizedFields().keys}, " +
                    "commentBlank=${finalMatch.result.normalizedFields()["comment"].isNullOrBlank()}, " +
                    "targetModes=${plan.targetModes}"
        )
        logPluginBatch(
            level = AppLogLevel.DEBUG,
            message = "Batch metadata match selected result",
            detail = buildString {
                appendLine("task=${task.taskId}")
                appendLine("item=${item.itemId}")
                appendLine("songUri=${song.uri}")
                appendLine("source=${finalMatch.source?.id}")
                appendLine("result=${finalMatch.result.id}:${finalMatch.result.title}")
                appendLine("score=${finalDetail.finalScore}")
                appendLine("textScore=${finalDetail.textScore}")
                appendLine("targetModes=${plan.targetModes}")
                appendLine("normalizedKeys=${finalMatch.result.normalizedFields().keys}")
            },
            relatedId = finalMatch.source?.id
        )

        if (finalDetail.finalScore < 0.76 || finalDetail.textScore < 0.72) {
            throw BatchTaskSkippedException(
                "Match score too low: final=${finalDetail.finalScore}, text=${finalDetail.textScore}"
            )
        }

        onProgress(0.55f)

        val newLyrics = if (shouldWriteLyrics && lyricConfig != null) {
            try {
                coroutineScope {
                    val deferred = async(Dispatchers.Default) {
                        finalMatch.source?.getLyrics(finalMatch.result)?.let { result ->
                            val sourceId = finalMatch.source.id

                            val processed = fieldProcessor.processLyrics(
                                lyrics = result,
                                config = defaultPluginFieldProcessConfig(sourceId)
                            )

                            LyricEncoder.encode(
                                result = processed,
                                config = lyricConfig.copy(
                                    conversionMode = com.lonx.lyrico.data.model.ConversionMode.NONE
                                )
                            )
                        }
                    }
                    deferred.await()
                }
            } catch (throwable: Exception) {
                if (throwable is CancellationException) throw throwable
                logPluginBatchException(
                    message = "Batch metadata match lyrics fetch failed\n" +
                            "task=${task.taskId}\nitem=${item.itemId}\n" +
                            "songUri=${song.uri}\nsource=${finalMatch.source?.id}\n" +
                            "result=${finalMatch.result.id}:${finalMatch.result.title}",
                    throwable = throwable,
                    relatedId = finalMatch.source?.id
                )
                null
            }
        } else {
            null
        }

        onProgress(0.75f)

        // 封面：先看刚才那条候选自带的封面，没有再去封面源里按歌名重搜一遍。
        val coverUrl = if (shouldWriteCover) {
            resolveCoverUrl(
                config = config,
                song = song,
                preferredUrl = finalMatch.result.picUrl,
                onProgress = onProgress
            )
        } else {
            null
        }

        val sourceId = finalMatch.source?.id.orEmpty()
        val candidateFields = fieldProcessor.processFields(
            pluginId = sourceId,
            fields = finalMatch.result.normalizedFields() +
                    newLyrics?.takeIf { it.isNotBlank() }?.let { mapOf("lyrics" to it) }.orEmpty() +
                    coverUrl?.takeIf { it.isNotBlank() }
                        ?.let { mapOf(StandardPluginField.COVER_URL.key to it) }.orEmpty(),
            config = defaultPluginFieldProcessConfig(sourceId),
            fieldDefinitions = emptyList(),
            writeRules = emptyList()
        )

        val tagDataToWrite = SearchResultApplier.buildPatch(
            current = currentTag,
            fields = candidateFields,
            policy = MetadataApplyPolicy(plan.targetModes)
        )

        if (tagDataToWrite.isEmpty()) {
            Log.w(
                TAG,
                "Skipping match metadata with empty patch. " +
                        "songUri=${song.uri}, source=$sourceId, " +
                        "targetModes=${plan.targetModes}, " +
                        "normalizedKeys=${finalMatch.result.normalizedFields().keys}, " +
                        "candidateKeys=${candidateFields.keys}, " +
                        "candidateCommentBlank=${candidateFields["comment"].isNullOrBlank()}, " +
                        "currentCommentBlank=${currentTag.comment.isNullOrBlank()}, " +
                        "databaseCommentBlank=${song.comment.isNullOrBlank()}, " +
                        "score=${finalDetail.finalScore}, textScore=${finalDetail.textScore}"
            )
            throw BatchTaskSkippedException("No fields to update")
        }

        onProgress(0.9f)

        if (config.previewOnly) {
            onProgress(1f)
            return BatchTaskProcessResult(
                resultJson = Json.encodeToString(
                    MatchPreviewPayload.serializer(),
                    MatchPreviewPayload(
                        songUri = song.uri,
                        songTitle = song.title.orEmpty(),
                        songArtist = song.artist.orEmpty(),
                        sourceId = sourceId,
                        sourceName = finalMatch.source?.name.orEmpty(),
                        candidateTitle = finalMatch.result.title.orEmpty(),
                        candidateArtist = finalMatch.result.artist.orEmpty(),
                        candidateAlbum = finalMatch.result.album.orEmpty(),
                        score = finalDetail.finalScore,
                        textScore = finalDetail.textScore,
                        entries = buildPreviewEntries(currentTag, tagDataToWrite)
                    )
                )
            )
        }

        val result = patchSongTagsUseCase(song.uri, tagDataToWrite)
        if (result !is SaveAudioTagsResult.Success) {
            throw Exception("Write failed")
        }

        onProgress(1f)

        return BatchTaskProcessResult()
    }

    /**
     * 取封面 URL。
     *
     * 优先用元数据候选自带的 `picUrl`（省一次请求）；没有才去封面源重搜。
     * 封面源是独立的一组（`PluginSourceType.COVER`），与元数据源不是同一批，
     * 所以这里单独取一次、单独 applyConfig。
     */
    private suspend fun resolveCoverUrl(
        config: MatchMetadataTaskConfig,
        song: SongEntity,
        preferredUrl: String?,
        onProgress: suspend (Float) -> Unit
    ): String? {
        if (!preferredUrl.isNullOrBlank()) return preferredUrl

        val sources = runCatching {
            searchSourceProvider.getSources(PluginSourceType.COVER)
        }.getOrDefault(emptyList())
        if (sources.isEmpty()) return null

        sources.forEach { source ->
            source.applyConfig(SourceRuntimeConfig(config.sourceSettings[source.id].orEmpty()))
        }

        val requestSong = SongSearchResult(
            id = song.uri,
            pluginId = "",
            pluginName = "",
            title = song.title.orEmpty(),
            artist = song.artist.orEmpty(),
            album = song.album.orEmpty(),
            duration = song.durationMilliseconds.toLong(),
            date = song.date.orEmpty()
        )

        var best: Pair<SongSearchResult, Double>? = null
        sources.forEachIndexed { index, source ->
            try {
                source.searchCovers(requestSong, pageSize = 5)
                    .asSequence()
                    .filter { it.picUrl.isNotBlank() }
                    .mapIndexed { rank, result ->
                        result to MusicMatchUtils.calculateMatchScore(
                            result = result,
                            song = song,
                            preferFileName = config.matchConfig.preferFileName,
                            rankIndex = rank
                        )
                    }
                    .maxByOrNull { it.second }
                    ?.let { candidate ->
                        if (candidate.second > (best?.second ?: Double.NEGATIVE_INFINITY)) {
                            best = candidate
                        }
                    }
            } catch (throwable: Exception) {
                if (throwable is CancellationException) throw throwable
                Log.w(TAG, "Cover source failed. songUri=${song.uri}, source=${source.id}", throwable)
            }
            onProgress(0.75f + 0.1f * (index + 1) / sources.size)
        }

        return best
            ?.takeIf { it.second >= MIN_COVER_SCORE }
            ?.first
            ?.picUrl
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun logPluginBatch(
        level: AppLogLevel,
        message: String,
        detail: String? = null,
        relatedId: String? = null
    ) {
        runCatching {
            appLogRepository.log(
                level = level,
                type = AppLogType.PLUGIN,
                tag = TAG,
                message = message,
                detail = detail,
                relatedId = relatedId
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to write batch plugin log", throwable)
        }
    }

    private suspend fun logPluginBatchException(
        message: String,
        throwable: Throwable,
        relatedId: String? = null
    ) {
        runCatching {
            appLogRepository.logException(
                type = AppLogType.PLUGIN,
                tag = TAG,
                message = message,
                throwable = throwable,
                relatedId = relatedId
            )
        }.onFailure { logThrowable ->
            Log.w(TAG, "Failed to write batch plugin exception log", logThrowable)
        }
    }

    private fun buildPlan(matchConfig: BatchMatchConfig): MatchMetadataPlan {
        val enabledTargetModes = matchConfig.targetModes
            .filterValues { mode ->
                mode != MetadataWriteMode.DISABLED
            }

        return MatchMetadataPlan(
            targetModes = enabledTargetModes
        )
    }
    private fun AudioTagData.isEmpty(): Boolean {
        return title == null &&
                artist == null &&
                album == null &&
                genre == null &&
                albumArtist == null &&
                date == null &&
                trackNumber == null &&
                discNumber == null &&
                composer == null &&
                lyricist == null &&
                lyrics == null &&
                picUrl == null &&
                comment == null &&
                replayGainTrackGain == null &&
                replayGainTrackPeak == null &&
                replayGainAlbumGain == null &&
                replayGainAlbumPeak == null &&
                replayGainReferenceLoudness == null
    }

    companion object {
        private const val TAG = "MatchMetadataProcessor"

        /** 与 MatchCoverProcessor 保持一致：封面单独搜时用同一个下限。 */
        private const val MIN_COVER_SCORE = 0.65
    }
}

private data class MatchMetadataPlan(
    val targetModes: Map<MetadataFieldTarget, MetadataWriteMode>
) {
    val requiresSearch: Boolean
        get() = targetModes.isNotEmpty()
}

@Serializable
data class MatchMetadataTaskConfig(
    val matchConfig: BatchMatchConfig,
    val separator: String,
    val enabledSourceOrderIds: List<String>,
    val sourceSettings: Map<String, Map<String, String>> = emptyMap(),
    val lyricRenderConfig: LyricRenderConfig? = null,
    val concurrency: Int = 3,
    /**
     * 只匹配、不写盘：结果序列化成 [com.lonx.lyrico.data.model.match.MatchPreviewPayload]
     * 存进 `item.resultJson`，等用户在预览页勾选后再走 APPLY_MATCH_PREVIEW 写入。
     */
    val previewOnly: Boolean = false
)
