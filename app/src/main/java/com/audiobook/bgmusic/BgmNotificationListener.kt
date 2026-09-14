package com.audiobook.bgmusic

import android.service.notification.NotificationListenerService

/**
 * 空实现的通知监听服务。
 *
 * 仅用于获取「通知使用权」（Notification Access）。
 * 普通应用直接调用 MediaSessionManager.getActiveSessions(null) 会抛
 * SecurityException（需要系统签名权限 MEDIA_CONTENT_CONTROL）；
 * 而授予通知使用权后，把本服务的 ComponentName 传给
 * getActiveSessions / addOnActiveSessionsChangedListener 即可合法观察
 * 其它应用（番茄小说等）的媒体会话。
 */
class BgmNotificationListener : NotificationListenerService()
