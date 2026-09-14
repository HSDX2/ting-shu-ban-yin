package com.audiobook.bgmusic

import android.net.Uri

data class Track(
    val uri: Uri,
    val name: String,
    val artist: String? = null,
    val durationMs: Long = 0L
)
