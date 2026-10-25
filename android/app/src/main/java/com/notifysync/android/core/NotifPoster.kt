package com.notifysync.android.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.notifysync.android.MainActivity
import com.notifysync.android.R

object NotifPoster {

    const val CH_MIRROR = "sync_mirror"
    const val CH_STATUS = "sync_status"
    const val STATUS_NOTIF_ID = 1

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_MIRROR, ctx.getString(R.string.channel_mirror),
                NotificationManager.IMPORTANCE_HIGH).apply {
                description = ctx.getString(R.string.channel_mirror_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.channel_status),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = ctx.getString(R.string.channel_status_desc)
                setShowBadge(false)
            },
        )
    }

    /** 其他设备同步来的通知 → 本机系统通知（可选带"复制验证码"动作） */
    fun postMirror(ctx: Context, n: NotifRecord) {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(ctx, CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("${n.app} · ${n.originName}")
            .setContentText(n.text.ifEmpty { n.title })
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(n.ts)
        if (n.otp != null) {
            val copy = PendingIntent.getActivity(
                ctx, n.nid.hashCode(),
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("copyText", n.otp),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, ctx.getString(R.string.copy_code), copy)
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(n.nid.hashCode(), builder.build())
        } catch (e: SecurityException) {
            com.notifysync.android.core.LogBus.log("notif", "镜像通知弹出失败（通知权限未授予？）：${e.message}")
        }
    }

    /** 前台服务常驻状态通知 */
    fun buildStatus(ctx: Context, title: String, text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }
}
