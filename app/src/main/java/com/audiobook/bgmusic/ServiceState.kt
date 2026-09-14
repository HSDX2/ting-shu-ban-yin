package com.audiobook.bgmusic

import kotlinx.coroutines.flow.MutableStateFlow

object ServiceState {
    val serviceRunning = MutableStateFlow(false)

    val syncEnabled = MutableStateFlow(true)
    val userPaused = MutableStateFlow(false)

    val volume = MutableStateFlow(0.5f)

    val isMusicPlaying = MutableStateFlow(false)
    val externalPlaying = MutableStateFlow(false)
    val externalAppName = MutableStateFlow<String?>(null)
    val syncPermissionGranted = MutableStateFlow(false)

    val playlist = MutableStateFlow<List<Track>>(emptyList())
    val scanning = MutableStateFlow(false)
    val folderName = MutableStateFlow("")

    val whitelist = MutableStateFlow<List<String>>(emptyList())
}
