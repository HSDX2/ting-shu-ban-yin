package com.audiobook.bgmusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Prefs.init(context)
            if (Prefs.autoStart && Prefs.folderUri != null) {
                MusicSyncService.start(context)
            }
        }
    }
}
