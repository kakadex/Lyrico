package com.lonx.lyrico.data.model

import androidx.annotation.StringRes
import com.lonx.lyrico.R

enum class BatchTaskType(
    @field:StringRes val labelRes: Int
) {
    MATCH_METADATA(R.string.batch_task_match_tags),
    MATCH_LYRICS(R.string.batch_task_match_lyrics),
    MATCH_COVER(R.string.batch_task_match_cover),

    /** 一次性匹配标签 + 歌词 + 封面（走 MatchMetadataProcessor，靠 targetModes 决定取哪些）。 */
    MATCH_ALL(R.string.batch_task_match_all),

    /** 把预览阶段用户勾选的结果真正写盘（见 ApplyMatchPreviewProcessor）。 */
    APPLY_MATCH_PREVIEW(R.string.batch_task_apply_preview),
    EDIT_TAGS(R.string.batch_task_edit_tags),
    RENAME_FILES(R.string.batch_task_rename_files),
    CONVERT_LYRICS_FORMAT(R.string.lyrics_process_title),
    SCAN_REPLAY_GAIN(R.string.batch_task_scan_replay_gain),
    EXPORT_LYRICS(R.string.batch_task_export_lyrics),
    EXPORT_COVER(R.string.batch_task_export_cover)
}
