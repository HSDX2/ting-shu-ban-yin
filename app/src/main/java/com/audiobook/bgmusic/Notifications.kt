package com.audiobook.bgmusic

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CHANNEL_ID = "bgm_channel"
    const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW
        )
            .setName("背景音乐")
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun openAppIntent(context: Context): Intent = Intent(context, MainActivity::class.java)

    fun openAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0, openAppIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun servicePendingIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context, action.hashCode(),
            Intent(context, MusicSyncService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun build(context: Context): Notification {
        val views = RemoteViews(context.packageName, R.layout.notification_player)
        views.setTextViewText(R.id.tv_status, statusText())
        views.setTextViewText(R.id.tv_volume, volumeText())
        val playRes = if (ServiceState.isMusicPlaying.value) R.drawable.ic_stat_pause else R.drawable.ic_stat_play
        views.setImageViewResource(R.id.iv_play, playRes)
        views.setOnClickPendingIntent(R.id.iv_play, servicePendingIntent(context, MusicSyncService.ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.iv_vol_down, servicePendingIntent(context, MusicSyncService.ACTION_VOLUME_DOWN))
        views.setOnClickPendingIntent(R.id.iv_vol_up, servicePendingIntent(context, MusicSyncService.ACTION_VOLUME_UP))
        views.setOnClickPendingIntent(R.id.container, openAppPendingIntent(context))

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_play)
            .setContentTitle("听书伴音")
            .setContentText(statusText())
            .setContentIntent(openAppPendingIntent(context))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setCustomContentView(views)
            .setCustomBigContentView(views)
            .build()
    }

    private fun statusText(): String {
        val sync = ServiceState.syncEnabled.value
        val external = ServiceState.externalPlaying.value
        val music = ServiceState.isMusicPlaying.value
        val app = ServiceState.externalAppName.value
        return when {
            !ServiceState.syncPermissionGranted.value -> "需开启「通知使用权」才能自动跟随"
            !sync -> "手动模式"
            external && music -> "跟随「${app ?: "其他应用"}」播放中"
            external && !music -> "跟随中（背景音乐已暂停）"
            else -> "等待其他应用播放…"
        }
    }

    private fun volumeText(): String = "${(ServiceState.volume.value * 100).toInt()}%"
}
