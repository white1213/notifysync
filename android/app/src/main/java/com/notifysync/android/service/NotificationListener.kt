package com.notifysync.android.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.notifysync.android.App
import com.notifysync.android.core.LogBus
import com.notifysync.android.core.NotifRecord
import com.notifysync.android.core.Otp
import com.notifysync.android.core.newUuid
import com.notifysync.android.ui.UiState

/**
 * 系统通知监听：本机所有通知在这里入库并广播给已配对设备。
 * 过滤规则：跳过自己发的（防镜像循环）、可配置跳过常驻通知。
 */
class NotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        UiState.listenerConnected = true
        // 触发服务刷新常驻状态通知（服务若未运行则顺带拉起）
        try { SyncService.start(applicationContext) } catch (_: Exception) {}
    }

    override fun onListenerDisconnected() {
        UiState.listenerConnected = false
        try { SyncService.start(applicationContext) } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val app = application as? App ?: return
        val prefs = app.prefs
        if (sbn.packageName == packageName) return // 自己的镜像/状态通知，防止循环
        if (!prefs.masterEnabled) return
        if (sbn.isOngoing && prefs.ignoreOngoing) return

        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: ""
        val body = bigText.ifEmpty { text }
        if (title.isBlank() && body.isBlank()) return

        val engine = app.engine
        if (engine == null) {
            LogBus.log("notif", "收到通知但同步服务未运行（可能被系统杀掉），已丢弃")
            return
        }

        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val record = NotifRecord(
            nid = newUuid(),
            originId = prefs.identity.id,
            originName = prefs.identity.name,
            pkg = sbn.packageName,
            app = appLabel,
            title = title,
            text = body,
            subText = subText,
            convTitle = convTitle,
            ts = sbn.postTime,
            recvTs = System.currentTimeMillis(),
            ongoing = sbn.isOngoing,
            otp = Otp.extract(title, body),
        )
        LogBus.log("notif", "本机通知[$appLabel] ${title.take(16)}")
        engine.onLocalNotification(record)
    }

    companion object {
        /** 通知监听被系统静默关闭时的自愈：禁用再启用组件并请求重绑 */
        fun rebind(ctx: Context) {
            val cn = ComponentName(ctx, NotificationListener::class.java)
            val pm = ctx.packageManager
            try {
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            } catch (_: Exception) {
            }
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    NotificationListenerService.requestRebind(cn)
                } catch (_: Exception) {
                }
            }
        }
    }
}
