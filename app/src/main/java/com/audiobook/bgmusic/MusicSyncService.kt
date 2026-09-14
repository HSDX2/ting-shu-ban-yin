package com.audiobook.bgmusic

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.AudioAttributes as ExoAudioAttributes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：负责播放背景音乐，并镜像其他应用的播放/暂停状态。
 * 关键点：ExoPlayer 以 handleAudioFocus=false 初始化，从不申请音频焦点，
 * 因此系统在番茄小说等应用抢焦点时不会暂停我们，两路音频共存。
 */
class MusicSyncService : Service() {

    companion object {
        const val ACTION_START = "com.audiobook.bgmusic.action.START"
        const val ACTION_STOP = "com.audiobook.bgmusic.action.STOP"
        const val ACTION_SET_SYNC = "com.audiobook.bgmusic.action.SET_SYNC"
        const val ACTION_TOGGLE_PLAY = "com.audiobook.bgmusic.action.TOGGLE_PLAY"
        const val ACTION_PLAY_PAUSE = "com.audiobook.bgmusic.action.PLAY_PAUSE"
        const val ACTION_SET_VOLUME = "com.audiobook.bgmusic.action.SET_VOLUME"
        const val ACTION_VOLUME_UP = "com.audiobook.bgmusic.action.VOL_UP"
        const val ACTION_VOLUME_DOWN = "com.audiobook.bgmusic.action.VOL_DOWN"
        const val ACTION_RELOAD_FOLDER = "com.audiobook.bgmusic.action.RELOAD_FOLDER"
        const val ACTION_SELECT_TRACK = "com.audiobook.bgmusic.action.SELECT_TRACK"
        const val ACTION_REFRESH_WATCHER = "com.audiobook.bgmusic.action.REFRESH_WATCHER"

        const val EXTRA_SYNC = "sync"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_INDEX = "index"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MusicSyncService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MusicSyncService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var player: ExoPlayer
    private var watcher: SessionWatcher? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var reloadJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifications.createChannel(this)

        ServiceState.syncEnabled.value = Prefs.syncEnabled
        ServiceState.volume.value = Prefs.volume
        ServiceState.userPaused.value = false
        ServiceState.whitelist.value = Prefs.whitelist.toList()
        ServiceState.serviceRunning.value = true

        buildPlayer()
        startWatcher()
        startForeground(
            Notifications.NOTIFICATION_ID,
            Notifications.build(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        reloadPlaylist()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()

            ACTION_SET_SYNC -> {
                ServiceState.syncEnabled.value = intent.getBooleanExtra(EXTRA_SYNC, true)
                Prefs.syncEnabled = ServiceState.syncEnabled.value
                applyDesired()
            }

            ACTION_TOGGLE_PLAY, ACTION_PLAY_PAUSE -> {
                ServiceState.userPaused.value = !ServiceState.userPaused.value
                applyDesired()
            }

            ACTION_SET_VOLUME -> {
                val v = intent.getFloatExtra(EXTRA_VOLUME, ServiceState.volume.value).coerceIn(0f, 1f)
                ServiceState.volume.value = v
                Prefs.volume = v
                player.setVolume(v)
                updateNotification()
            }

            ACTION_VOLUME_UP -> changeVolume(+0.05f)
            ACTION_VOLUME_DOWN -> changeVolume(-0.05f)
            ACTION_RELOAD_FOLDER -> reloadPlaylist()

            ACTION_SELECT_TRACK -> {
                selectTrack(intent.getIntExtra(EXTRA_INDEX, 0))
            }

            ACTION_REFRESH_WATCHER -> {
                startWatcher()
                watcher?.refreshNow()
                applyDesired()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉 App 时，前台服务会被系统移除。
        // 音乐 App 的标准做法：立即重启服务并恢复常驻通知，避免"划掉即被杀"。
        if (Prefs.folderUri != null) {
            runCatching { start(this) }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        watcher?.stop()
        runCatching { player.release() }
        scope.cancel()
        ServiceState.serviceRunning.value = false
        ServiceState.isMusicPlaying.value = false
        ServiceState.externalPlaying.value = false
        super.onDestroy()
    }

    private fun buildPlayer() {
        val attrs = ExoAudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, false) // 不处理音频焦点 → 与听书共存
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.setHandleAudioBecomingNoisy(false)
        player.setVolume(ServiceState.volume.value)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ServiceState.isMusicPlaying.value = isPlaying
                updateNotification()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateNotification()
            }
        })
        // 不创建 MediaSession：避免成为系统"媒体按键会话"抢走耳机按键。
        // 这样耳机按键会路由到正在播放且持有音频焦点的听书 App，由它控制暂停/播放，
        // 我们再通过 SessionWatcher 观察它的状态来同步背景音乐。
    }

    private fun startWatcher() {
        if (watcher == null) {
            watcher = SessionWatcher(this) { playing, appName ->
                if (ServiceState.syncEnabled.value) {
                    ServiceState.userPaused.value = false
                }
                ServiceState.externalPlaying.value = playing
                ServiceState.externalAppName.value = appName
                applyDesired()
                updateNotification()
            }
        }
        val ok = watcher?.start() ?: false
        ServiceState.syncPermissionGranted.value = ok
        if (!ok) {
            android.util.Log.w("MusicSyncService", "未授予通知使用权，自动同步暂不可用")
        }
    }

    private fun applyDesired() {
        val sync = ServiceState.syncEnabled.value
        val external = ServiceState.externalPlaying.value
        val userPaused = ServiceState.userPaused.value
        val shouldPlay = (if (sync) external else true) && !userPaused

        if (shouldPlay && player.mediaItemCount == 0) {
            return
        }
        if (shouldPlay && !player.isPlaying) {
            if (player.playbackState == Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        } else if (!shouldPlay && player.isPlaying) {
            player.pause()
        }
        updateNotification()
    }

    private fun reloadPlaylist() {
        val folderUri = Prefs.folderUri
        if (folderUri == null) {
            ServiceState.playlist.value = emptyList()
            ServiceState.folderName.value = ""
            ServiceState.scanning.value = false
            updateNotification()
            return
        }
        val uri = Uri.parse(folderUri)
        ServiceState.scanning.value = true
        reloadJob?.cancel()
        reloadJob = scope.launch {
            val tracks = MusicLibrary.scan(this@MusicSyncService, uri)
            ServiceState.playlist.value = tracks
            ServiceState.folderName.value = MusicLibrary.folderDisplayName(this@MusicSyncService, uri)
            ServiceState.scanning.value = false
            if (tracks.isNotEmpty()) {
                player.setMediaItems(tracks.map { MediaItem.fromUri(it.uri) }, 0, 0L)
                player.prepare()
            } else {
                player.stop()
                player.clearMediaItems()
            }
            applyDesired()
            updateNotification()
        }
    }

    private fun selectTrack(index: Int) {
        if (player.mediaItemCount == 0) return
        val i = index.coerceIn(0, player.mediaItemCount - 1)
        player.seekTo(i, 0L)
        player.prepare()
        applyDesired()
    }

    private fun changeVolume(delta: Float) {
        val v = (ServiceState.volume.value + delta).coerceIn(0f, 1f)
        ServiceState.volume.value = v
        Prefs.volume = v
        player.setVolume(v)
        updateNotification()
    }

    private fun updateNotification() {
        runCatching {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm.notify(Notifications.NOTIFICATION_ID, Notifications.build(this))
        }
    }
}
