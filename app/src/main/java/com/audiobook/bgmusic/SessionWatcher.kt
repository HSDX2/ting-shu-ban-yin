package com.audiobook.bgmusic

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/**
 * 观察系统里所有正在播放的 MediaSession。
 * 当任一其他应用处于"播放中"（含缓冲/切歌/快进等瞬时状态）时回调 playing=true，
 * 全部暂停/结束时回调 false。自己的包名会被排除，避免自我触发。
 *
 * 注意：观察其他应用的会话需要「通知使用权」。未授权时 start() 返回 false 并安全降级
 * （不注册监听，不会崩溃），授权后重新调用 start() 即可生效。
 *
 * 切歌防抖：切上一首/下一首会短暂进入 SKIPPING/BUFFERING 等状态，
 * 为避免背景音乐跟着抖动，暂停会延迟 [PAUSE_DEBOUNCE_MS] 确认。
 */
class SessionWatcher(
    private val context: Context,
    private val onExternalPlayingChanged: (Boolean, String?) -> Unit
) {
    companion object {
        private const val TAG = "SessionWatcher"

        /** 暂停防抖时长：非播放状态持续超过该时长才真正暂停背景音乐 */
        private const val PAUSE_DEBOUNCE_MS = 500L

        /** 视为"仍在播放"的瞬时状态（切歌、快进快退、缓冲、连接中） */
        private val ACTIVE_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
        )

        /** 是否已获得「通知使用权」（观察其他应用会话的前提） */
        fun hasPermission(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
    }

    private val manager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(context, BgmNotificationListener::class.java)
    private val controllers = mutableMapOf<String, MediaController>()
    private var externalPlaying = false
    private var externalApp: String? = null
    private var started = false

    private val handler = Handler(Looper.getMainLooper())
    private var pauseScheduled = false
    private val pauseRunnable = Runnable {
        pauseScheduled = false
        setExternal(false, null)
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            recompute()
        }

        override fun onSessionDestroyed() {
            recompute()
        }
    }

    private val activeListener = MediaSessionManager.OnActiveSessionsChangedListener {
        refresh()
    }

    /** @return 是否成功开始监听（false 表示尚未授予通知使用权） */
    fun start(): Boolean {
        if (started) return true
        if (!hasPermission(context)) {
            Log.w(TAG, "未授予通知使用权，暂不监听媒体会话")
            return false
        }
        return try {
            manager.addOnActiveSessionsChangedListener(activeListener, component)
            started = true
            refresh()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "addOnActiveSessionsChangedListener 失败: ${e.message}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "监听媒体会话异常: ${e.message}")
            false
        }
    }

    fun stop() {
        runCatching { manager.removeOnActiveSessionsChangedListener(activeListener) }
        controllers.values.forEach { runCatching { it.unregisterCallback(controllerCallback) } }
        controllers.clear()
        handler.removeCallbacks(pauseRunnable)
        pauseScheduled = false
        started = false
        setExternal(false, null)
    }

    fun refreshNow() {
        if (started) refresh()
    }

    private fun refresh() {
        val active = try {
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSessions 失败: ${e.message}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "getActiveSessions 异常: ${e.message}")
            return
        }

        val seen = mutableSetOf<String>()
        val current = mutableMapOf<String, MediaController>()
        val whitelist = ServiceState.whitelist.value

        for (controller in active) {
            if (controller.packageName == context.packageName) continue
            if (whitelist.isNotEmpty() && whitelist.none { controller.packageName.startsWith(it) }) {
                continue
            }
            val token = controller.sessionToken.toString()
            seen.add(token)
            val existing = controllers[token]
            if (existing != null) {
                current[token] = existing
            } else {
                val c = MediaController(context, controller.sessionToken)
                c.registerCallback(controllerCallback)
                current[token] = c
            }
        }

        controllers.forEach { (token, c) ->
            if (token !in seen) {
                runCatching { c.unregisterCallback(controllerCallback) }
            }
        }
        controllers.clear()
        controllers.putAll(current)
        recompute()
    }

    private fun recompute() {
        var playing = false
        var appName: String? = null
        for (c in controllers.values) {
            val st = c.playbackState?.state ?: PlaybackState.STATE_NONE
            if (st in ACTIVE_STATES) {
                playing = true
                appName = labelOf(c.packageName) ?: c.packageName
                break
            }
        }

        if (playing) {
            // 一旦恢复播放，立即取消待定的暂停
            pauseScheduled = false
            handler.removeCallbacks(pauseRunnable)
            setExternal(true, appName)
        } else if (externalPlaying && !pauseScheduled) {
            // 瞬时非播放状态先不暂停，延迟确认，避免切歌/缓冲导致背景音乐抖动
            pauseScheduled = true
            handler.postDelayed(pauseRunnable, PAUSE_DEBOUNCE_MS)
        }
    }

    private fun setExternal(playing: Boolean, appName: String?) {
        if (playing != externalPlaying || appName != externalApp) {
            externalPlaying = playing
            externalApp = appName
            onExternalPlayingChanged(playing, appName)
        }
    }

    private fun labelOf(pkg: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}
