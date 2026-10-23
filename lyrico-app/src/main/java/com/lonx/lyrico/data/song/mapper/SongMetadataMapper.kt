package com.lonx.lyrico.data.song.mapper

import com.lonx.audiotag.model.AudioTagData
import com.lonx.lyrico.data.model.SongFile
import com.lonx.lyrico.data.model.entity.SongEntity
import com.lonx.lyrico.utils.LyricsSearchTextExtractor

class SongMetadataMapper(
    private val sortKeyUpdater: SortKeyUpdater
) {
    /** Synchronize a complete, successfully read file snapshot, not a partial edit. */
    fun applyAudioTagData(
        old: SongEntity,
        tag: AudioTagData,
        fileLastModified: Long = System.currentTimeMillis()
    ): SongEntity {
        val lyrics = tag.lyrics
        return old.copy(
            title = tag.title,
            artist = tag.artist,
            albumArtist = tag.albumArtist,
            album = tag.album,
            genre = tag.genre,
            date = tag.date,
            trackerNumber = tag.trackNumber,
            discNumber = tag.discNumber,
            composer = tag.composer,
            lyricist = tag.lyricist,
            comment = tag.comment,
            lyrics = lyrics,
            lyricSearchText = LyricsSearchTextExtractor.toSearchText(lyrics),
            language = tag.language,
            copyright = tag.copyright,
            rating = tag.rating,
            // These are read back from the saved file; null means the tag was removed.
            replayGainTrackGain = tag.replayGainTrackGain,
            replayGainTrackPeak = tag.replayGainTrackPeak,
            replayGainAlbumGain = tag.replayGainAlbumGain,
            replayGainAlbumPeak = tag.replayGainAlbumPeak,
            replayGainReferenceLoudness = tag.replayGainReferenceLoudness,
            fileLastModified = fileLastModified
        ).let(sortKeyUpdater::update)
    }

    fun fromScannedFile(
        file: SongFile,
        tag: AudioTagData,
        folderId: Long,
        existingId: Long = 0L,
        source: String = "MEDIA_STORE",
        indexLyrics: Boolean = false
    ): SongEntity {
        return SongEntity(
            id = existingId,
            mediaId = file.mediaId,
            source = source,
            uri = file.uri.toString(),
            filePath = file.filePath,
            fileName = file.fileName,
            // 没有标题标签时，用文件名（去掉扩展名）兜底，与系统媒体库/播放器的行为一致，
            // 否则无标签文件入库后标题为空、全局搜索按标题搜不到。
            title = resolveTitle(tag.title, file.fileName),
            fileSize = file.fileSize,
            fileExtension = file.fileName.substringAfterLast(".").uppercase(),
            artist = tag.artist,
            albumArtist = tag.albumArtist,
            album = tag.album,
            genre = tag.genre,
            trackerNumber = tag.trackNumber,
            date = tag.date,
            language = tag.language,
            lyrics = tag.lyrics,
            lyricSearchText = if (indexLyrics) {
                LyricsSearchTextExtractor.toSearchText(tag.lyrics).orEmpty()
            } else {
                null
            },
            composer = tag.composer,
            lyricist = tag.lyricist,
            comment = tag.comment,
            discNumber = tag.discNumber,
            copyright = tag.copyright,
            rating = tag.rating,
            replayGainTrackGain = tag.replayGainTrackGain,
            replayGainTrackPeak = tag.replayGainTrackPeak,
            replayGainAlbumGain = tag.replayGainAlbumGain,
            replayGainAlbumPeak = tag.replayGainAlbumPeak,
            replayGainReferenceLoudness = tag.replayGainReferenceLoudness,
            durationMilliseconds = tag.durationMilliseconds,
            bitrate = tag.bitrate,
            sampleRate = tag.sampleRate,
            channels = tag.channels,
            fileLastModified = file.lastModified,
            fileAdded = file.dateAdded,
            folderId = folderId
        ).let(sortKeyUpdater::update)
    }

    /**
     * 解析曲目标题：优先用标签里的标题；标签为空时回退到文件名（去掉扩展名）。
     * 这样无标签文件（如仅靠文件名识别的 mp3）入库后也有可搜索、可显示的标题，
     * 行为与系统媒体库/播放器保持一致。
     */
    private fun resolveTitle(tagTitle: String?, fileName: String): String {
        val trimmed = tagTitle?.trim().orEmpty()
        if (trimmed.isNotBlank()) return trimmed
        val base = fileName.substringBeforeLast('.', fileName).trim()
        return base.ifBlank { fileName }
    }
}
