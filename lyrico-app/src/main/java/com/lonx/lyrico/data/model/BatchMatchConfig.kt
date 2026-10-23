package com.lonx.lyrico.data.model

import android.os.Parcelable
import com.lonx.lyrico.data.editfield.EditFieldRegistry
import com.lonx.lyrico.data.model.metadata.MetadataFieldTarget
import com.lonx.lyrico.data.model.metadata.MetadataWriteMode
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable

@Parcelize
@Serializable
data class BatchMatchConfig(
    val targetModes: Map<MetadataFieldTarget, MetadataWriteMode>,
    val concurrency: Int = 3,
    val preferFileName: Boolean = false,
    /**
     * 先匹配后确认：任务只把「匹配到什么」落库，不写盘，用户在预览页逐首逐字段勾完才真正写入。
     *
     * 默认开着 —— 联网结果直接覆盖标签一旦匹配错，是很难发现的（旧值已经被冲掉了）。
     * 老版本存下来的配置 JSON 里没有这个键，反序列化时走默认值，天然向后兼容。
     */
    val previewFirst: Boolean = true
) : Parcelable {
    fun restrictedTo(targets: Set<MetadataFieldTarget>): BatchMatchConfig = copy(
        targetModes = targetModes.mapValues { (target, mode) ->
            if (target in targets) mode else MetadataWriteMode.DISABLED
        }
    )
}

object BatchMatchConfigDefaults {
    val BATCH_MATCH_TARGETS: List<MetadataFieldTarget> =
        EditFieldRegistry.fields.mapNotNull { it.target }

    val DEFAULT_ENABLED_TARGETS = setOf(
        MetadataFieldTarget.TITLE,
        MetadataFieldTarget.ARTIST,
        MetadataFieldTarget.ALBUM,
        MetadataFieldTarget.GENRE,
        MetadataFieldTarget.DATE,
        MetadataFieldTarget.TRACK_NUMBER,
        MetadataFieldTarget.LYRICS,
        MetadataFieldTarget.COVER
    )

    val DEFAULT_CONFIG = BatchMatchConfig(
        targetModes = BATCH_MATCH_TARGETS.associateWith { target ->
            if (target in DEFAULT_ENABLED_TARGETS) {
                MetadataWriteMode.SUPPLEMENT
            } else {
                MetadataWriteMode.DISABLED
            }
        },
        concurrency = 3,
        preferFileName = false
    )

}
