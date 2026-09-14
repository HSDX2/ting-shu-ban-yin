package com.audiobook.bgmusic

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MusicLibrary {

    private const val TAG = "MusicLibrary"

    // ExoPlayer(media3) 实际支持的音频容器/编码扩展名（用于 MIME 缺失时兜底）
    private val AUDIO_EXTS = setOf(
        "mp3", "mp2", "mpga",            // MPEG 音频
        "flac",                          // FLAC（无损）
        "wav",                           // WAV / PCM（无损）
        "m4a", "m4b", "mp4", "aac",      // MP4/M4A/ADTS（AAC 或 ALAC）
        "ogg", "oga", "opus",            // Ogg（Vorbis / Opus / FLAC）
        "mka", "webm",                   // Matroska / WebM
        "amr",                           // AMR
        "ac3", "eac3", "ec3"             // AC-3 / E-AC-3
    )

    private fun isAudio(mime: String, name: String): Boolean {
        if (mime.startsWith("audio/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTS
    }

    suspend fun scan(context: Context, treeUri: Uri): List<Track> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Track>()
        val visited = mutableSetOf<String>()
        var dirCount = 0
        var docCount = 0

        fun walk(docId: String, depth: Int) {
            if (depth > 12) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            try {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    ),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val childId = c.getString(0) ?: continue
                        val mime = c.getString(1) ?: ""
                        val name = c.getString(2) ?: ""
                        if (childId in visited) continue
                        visited.add(childId)
                        docCount++
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            dirCount++
                            walk(childId, depth + 1)
                        } else if (isAudio(mime, name)) {
                            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            result.add(metadata(context, uri, name))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "遍历目录失败 docId=$docId: ${e.message}")
            }
        }

        try {
            walk(DocumentsContract.getTreeDocumentId(treeUri), 0)
        } catch (e: Exception) {
            Log.w(TAG, "扫描失败: ${e.message}")
        }
        result.sortBy { it.name.lowercase() }
        Log.i(TAG, "扫描完成: 目录 $dirCount 个, 文档 $docCount 个, 音频 ${result.size} 首")
        result
    }

    fun folderDisplayName(context: Context, treeUri: Uri): String {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        runCatching {
            context.contentResolver.query(docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) return c.getString(n)
                }
            }
        }
        return "音乐文件夹"
    }

    private fun metadata(context: Context, uri: Uri, fallbackName: String): Track {
        var name = fallbackName
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n)
                }
            }
        }

        var artist: String? = null
        var duration = 0L
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
        } finally {
            runCatching { mmr.release() }
        }
        return Track(uri, name, artist, duration)
    }
}
