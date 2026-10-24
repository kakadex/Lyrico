package com.lonx.lyrico.utils

import com.lonx.lyrico.data.model.entity.SongEntity

/**
 * 目录整理（V0.09）规划器：由标签（缺失时从文件名反解）算出「艺人/专辑」目标相对路径。
 * 只处理文件夹授权（SAF）来源的歌曲；目标根 = 歌曲所在授权目录的根。
 */
object OrganizePlanner {

    /** 整理计划里的一首歌。 */
    data class PlanItem(
        val song: SongEntity,
        val artist: String,
        val album: String,
        val oldRelativePath: String,
        val newRelativePath: String,
        val rootPath: String
    ) {
        /** 目标目录相对路径（艺人/专辑，专辑空则只有艺人）。 */
        val targetDirRelative: String
            get() = if (album.isBlank()) artist else "$artist/$album"

        val targetDirAbsolute: String
            get() = joinPath(rootPath, targetDirRelative)

        val newFilePath: String
            get() = joinPath(targetDirAbsolute, song.fileName)
    }

    /** 扫描结果：需移动的条目 + 各类跳过计数。 */
    data class Plan(
        val items: List<PlanItem>,
        val skippedMediaStore: Int,
        val skippedNoArtist: Int,
        val skippedAlreadyInPlace: Int
    )

    /** 目录名里的非法字符。 */
    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    /** 清洗目录名：去非法字符、压缩空白、去首尾点和空白、截断 60 字。 */
    fun sanitizeDirName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .replace(ILLEGAL, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.', ' ')
            .take(60)
    }

    /**
     * 为整库生成整理计划。
     * - 不在任何 SAF 授权目录下的歌（rootPath 缺失）按媒体库来源跳过并计数；
     * - 艺人为空时用 [FileNameParser.candidatePairs] 从文件名反解；仍无艺人则跳过；
     * - 已在「艺人」或「艺人/专辑」目录里的歌视为已归位，跳过。
     */
    fun plan(
        songs: List<SongEntity>,
        rootPathByFolderId: Map<Long, String>
    ): Plan {
        val items = ArrayList<PlanItem>()
        var skipMediaStore = 0
        var skipNoArtist = 0
        var skipInPlace = 0

        for (song in songs) {
            val root = rootPathByFolderId[song.folderId]
            if (root.isNullOrBlank()) {
                skipMediaStore++
                continue
            }
            val normalizedRoot = root.trimEnd('/')

            val parentAbs = song.filePath.substringBeforeLast('/', "")
            if (parentAbs.isBlank() || !parentAbs.startsWith(normalizedRoot)) {
                skipMediaStore++
                continue
            }
            val parentRel = parentAbs.removePrefix(normalizedRoot).trim('/')

            val (rawArtist, rawAlbum) = resolveArtistAlbum(song)
            val artist = sanitizeDirName(rawArtist)
            val album = sanitizeDirName(rawAlbum)
            if (artist.isBlank()) {
                skipNoArtist++
                continue
            }
            val targetRel = if (album.isBlank()) artist else "$artist/$album"

            if (parentRel == artist || parentRel == targetRel) {
                skipInPlace++
                continue
            }

            items.add(
                PlanItem(
                    song = song,
                    artist = artist,
                    album = album,
                    oldRelativePath = parentRel,
                    newRelativePath = targetRel,
                    rootPath = normalizedRoot
                )
            )
        }
        items.sortWith(compareBy({ it.targetDirAbsolute }, { it.song.fileName }))
        return Plan(items, skipMediaStore, skipNoArtist, skipInPlace)
    }

    /** 艺人/专辑：标签优先，文件名反解兜底。 */
    fun resolveArtistAlbum(song: SongEntity): Pair<String, String> {
        val tagArtist = song.artist?.takeIf { it.isNotBlank() && !it.contains("未知", true) }
        val tagAlbum = song.album?.takeIf { it.isNotBlank() && !it.contains("未知", true) } ?: ""
        if (!tagArtist.isNullOrBlank()) return tagArtist to tagAlbum
        val stem = song.fileName.substringBeforeLast('.', song.fileName)
        for ((a, _) in FileNameParser.candidatePairs(stem)) {
            if (a.isNotBlank()) return a to tagAlbum
        }
        return "" to tagAlbum
    }

    fun joinPath(a: String, b: String): String = when {
        a.isBlank() -> b
        b.isBlank() -> a
        else -> "$a/$b"
    }
}
