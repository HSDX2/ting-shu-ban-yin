package com.audiobook.bgmusic

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "bgm_prefs"
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    }

    var folderUri: String?
        get() = sp.getString("folder_uri", null)
        set(v) = sp.edit().putString("folder_uri", v).apply()

    var syncEnabled: Boolean
        get() = sp.getBoolean("sync_enabled", true)
        set(v) = sp.edit().putBoolean("sync_enabled", v).apply()

    var volume: Float
        get() = sp.getFloat("volume", 0.5f)
        set(v) = sp.edit().putFloat("volume", v).apply()

    var autoStart: Boolean
        get() = sp.getBoolean("autostart", true)
        set(v) = sp.edit().putBoolean("autostart", v).apply()

    var whitelist: Set<String>
        get() = sp.getStringSet("whitelist", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("whitelist", v).apply()
}
