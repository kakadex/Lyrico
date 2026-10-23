package com.lonx.lyrico.data.model.match

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import kotlinx.serialization.Serializable

/**
 * 预览态的一条字段变更：`旧值 → 新值`。
 *
 * 存进 `batch_task_items.resultJson`，因此必须可序列化。
 * [newValue] 是完整值（歌词是整段 LRC、封面是 URL），写入阶段直接用它，不再重新联网；
 * [oldValue] 只用于界面展示，长文本会被截断（见 [snippet]），不参与写入。
 */
@Serializable
data class MatchPreviewEntry(
    val target: MetadataFieldTarget,
    val oldValue: String? = null,
    val newValue: String = ""
)

/**
 * 一首歌的匹配结果。
 *
 * 刻意把「匹配到了哪一条候选」也存下来：老实现里这些信息只进了日志，
 * 用户在写入后完全没法判断这首歌是从哪家、以多少分匹配来的。
 */
@Serializable
data class MatchPreviewPayload(
    val songUri: String,
    val songTitle: String = "",
    val songArtist: String = "",
    val sourceId: String = "",
    val sourceName: String = "",
    val candidateTitle: String = "",
    val candidateArtist: String = "",
    val candidateAlbum: String = "",
    val score: Double = 0.0,
    val textScore: Double = 0.0,
    val entries: List<MatchPreviewEntry> = emptyList()
) {
    /** 低于这个分数，界面上给个「低置信」标记，提醒用户这一首可能匹配错了。 */
    val isLowConfidence: Boolean
        get() = score < LOW_CONFIDENCE

    companion object {
        const val LOW_CONFIDENCE = 0.85
    }
}

/**
 * 用户确认后真正写盘的任务配置。
 *
 * 条目直接内联在配置里（配置存在 Room 的 `batch_tasks.configJson`，不受 WorkManager
 * inputData 10KB 的限制），所以 apply 阶段不需要回头去读预览任务的明细。
 */
@Serializable
data class ApplyMatchPreviewTaskConfig(
    val concurrency: Int = 3,
    /** songUri -> 用户勾选的字段（已按勾选过滤，未勾的不在这里） */
    val entries: Map<String, List<MatchPreviewEntry>> = emptyMap()
)

/**
 * 从「当前标签 + 待写入补丁」生成预览条目。
 *
 * 补丁（[patch]）本身只含真正发生变化的字段，所以遍历它即可得到 diff 列表。
 */
fun buildPreviewEntries(
    current: AudioTagData,
    patch: AudioTagData
): List<MatchPreviewEntry> = buildList {
    MetadataFieldTarget.entries.forEach { target ->
        val newValue = patch.valueOrNullOf(target) ?: return@forEach
        add(
            MatchPreviewEntry(
                target = target,
                oldValue = current.valueOrNullOf(target)?.let(::snippet),
                newValue = newValue
            )
        )
    }
}

/** 把用户勾选的条目还原成一次写入补丁。 */
fun List<MatchPreviewEntry>.toPatch(): AudioTagData {
    var data = AudioTagData()
    forEach { entry ->
        if (entry.target == MetadataFieldTarget.CUSTOM) return@forEach
        data = data.withValue(entry.target, entry.newValue)
    }
    return data
}

/** 界面上展示旧值时截断，避免把整段歌词塞进一行。 */
fun snippet(text: String, limit: Int = 120): String {
    val normalized = text.replace('\n', ' ').trim()
    return if (normalized.length <= limit) normalized else normalized.take(limit) + "…"
}

/**
 * 取某个字段的当前值。
 *
 * 注意 LYRICS/COVER 之外还有一批字段（如 CUSTOM）不在联网匹配范围内，
 * 这里对它们统一返回 null，调用方无需单独排除。
 */
fun AudioTagData.valueOrNullOf(target: MetadataFieldTarget): String? = when (target) {
    MetadataFieldTarget.TITLE -> title
    MetadataFieldTarget.ARTIST -> artist
    MetadataFieldTarget.ALBUM -> album
    MetadataFieldTarget.ALBUM_ARTIST -> albumArtist
    MetadataFieldTarget.GENRE -> genre
    MetadataFieldTarget.DATE -> date
    MetadataFieldTarget.TRACK_NUMBER -> trackNumber
    MetadataFieldTarget.DISC_NUMBER -> discNumber?.toString()
    MetadataFieldTarget.COMPOSER -> composer
    MetadataFieldTarget.LYRICIST -> lyricist
    MetadataFieldTarget.COMMENT -> comment
    MetadataFieldTarget.LYRICS -> lyrics
    MetadataFieldTarget.COVER -> picUrl?.takeIf { it.isNotBlank() }
        ?: if (pictures.isNotEmpty()) "embedded" else null
    MetadataFieldTarget.LANGUAGE -> language
    MetadataFieldTarget.COPYRIGHT -> copyright
    MetadataFieldTarget.RATING -> rating?.toString()
    MetadataFieldTarget.REPLAY_GAIN_TRACK_GAIN -> replayGainTrackGain
    MetadataFieldTarget.REPLAY_GAIN_TRACK_PEAK -> replayGainTrackPeak
    MetadataFieldTarget.REPLAY_GAIN_ALBUM_GAIN -> replayGainAlbumGain
    MetadataFieldTarget.REPLAY_GAIN_ALBUM_PEAK -> replayGainAlbumPeak
    MetadataFieldTarget.REPLAY_GAIN_REFERENCE_LOUDNESS -> replayGainReferenceLoudness
    MetadataFieldTarget.CUSTOM -> null
}

fun AudioTagData.withValue(target: MetadataFieldTarget, value: String): AudioTagData = when (target) {
    MetadataFieldTarget.TITLE -> copy(title = value)
    MetadataFieldTarget.ARTIST -> copy(artist = value)
    MetadataFieldTarget.ALBUM -> copy(album = value)
    MetadataFieldTarget.ALBUM_ARTIST -> copy(albumArtist = value)
    MetadataFieldTarget.GENRE -> copy(genre = value)
    MetadataFieldTarget.DATE -> copy(date = value)
    MetadataFieldTarget.TRACK_NUMBER -> copy(trackNumber = value)
    MetadataFieldTarget.DISC_NUMBER -> copy(discNumber = value.filter { it.isDigit() }.toIntOrNull())
    MetadataFieldTarget.COMPOSER -> copy(composer = value)
    MetadataFieldTarget.LYRICIST -> copy(lyricist = value)
    MetadataFieldTarget.COMMENT -> copy(comment = value)
    MetadataFieldTarget.LYRICS -> copy(lyrics = value)
    MetadataFieldTarget.COVER -> copy(picUrl = value)
    MetadataFieldTarget.LANGUAGE -> copy(language = value)
    MetadataFieldTarget.COPYRIGHT -> copy(copyright = value)
    MetadataFieldTarget.RATING -> copy(rating = value.filter { it.isDigit() }.toIntOrNull())
    MetadataFieldTarget.REPLAY_GAIN_TRACK_GAIN -> copy(replayGainTrackGain = value)
    MetadataFieldTarget.REPLAY_GAIN_TRACK_PEAK -> copy(replayGainTrackPeak = value)
    MetadataFieldTarget.REPLAY_GAIN_ALBUM_GAIN -> copy(replayGainAlbumGain = value)
    MetadataFieldTarget.REPLAY_GAIN_ALBUM_PEAK -> copy(replayGainAlbumPeak = value)
    MetadataFieldTarget.REPLAY_GAIN_REFERENCE_LOUDNESS -> copy(replayGainReferenceLoudness = value)
    MetadataFieldTarget.CUSTOM -> this
}
