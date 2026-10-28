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

    /**
     * 其他设备同步来的通知 → 本机系统通知。
     *
     * 按「来源设备 + 应用 + 会话」聚合到同一条通知里（InboxStyle 追加最近几条，
     * 标题带累计条数），而不是每条通知都新开一个。
     * 原因：部分 ROM（三星 One UI 实测）对单应用通知上限 24 条，
     * 逐条堆叠会堆满后被系统静默丢弃，表现为"同步突然停了"。
     */
    fun postMirror(ctx: Context, n: NotifRecord) {
        val key = "${n.originId}|${n.pkg}|${n.convTitle.ifEmpty { n.subText.ifEmpty { n.title } }}"
        val state = convNotifs.getOrPut(key) { ConvNotif(nextMirrorId()) }
        state.lastAt = System.currentTimeMillis()
        state.count += 1
        val line = (if (n.title.isNotEmpty()) "${n.title}: " else "") + n.text.ifEmpty { n.app }
        state.lines.addLast(line.take(120))
        while (state.lines.size > MAX_LINES_PER_CONV) state.lines.removeFirst()

        // 会话总数超限：取消最久未更新的，避免触发 ROM 的单应用通知上限
        while (convNotifs.size > MAX_CONV_NOTIFS) {
            val oldest = convNotifs.entries.filter { it.key != key }.minByOrNull { it.value.lastAt } ?: break
            cancelMirror(ctx, oldest.value.id)
            convNotifs.remove(oldest.key)
        }

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.InboxStyle()
        for (l in state.lines) style.addLine(l)
        style.setSummaryText(if (state.count > MAX_LINES_PER_CONV) "共 ${state.count} 条 · ${n.originName}" else n.originName)

        val builder = NotificationCompat.Builder(ctx, CH_MIRROR)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("${n.app} · ${n.originName}")
            .setContentText(n.text.ifEmpty { n.title })
            .setStyle(style)
            .setNumber(state.count)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(n.ts)
            .setShowWhen(true)
        if (n.otp != null) {
            val copy = PendingIntent.getActivity(
                ctx, n.otp.hashCode(),
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("copyText", n.otp),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, ctx.getString(R.string.copy_code), copy)
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(state.id, builder.build())
        } catch (e: Exception) {
            LogBus.log("notif", "镜像通知弹出失败：${e.javaClass.simpleName} ${e.message}")
        }
    }

    private class ConvNotif(val id: Int) {
        val lines = ArrayDeque<String>()
        var count = 0
        var lastAt = 0L
    }

    private val convNotifs = LinkedHashMap<String, ConvNotif>()
    private var mirrorSeq = 5000

    // 每个会话保留最近 6 行；最多同时存在 6 个会话通知（远低于 ROM 的 24 条上限）
    private const val MAX_LINES_PER_CONV = 6
    private const val MAX_CONV_NOTIFS = 6

    private fun nextMirrorId(): Int = mirrorSeq++

    /** 服务启动时清掉上次运行遗留的镜像通知（避免多次重启后累积到 ROM 上限） */
    fun clearAllMirrors(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (id in 5000..5030) {
            try { nm.cancel(id) } catch (_: Exception) {}
        }
    }

    private fun cancelMirror(ctx: Context, id: Int) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        } catch (_: Exception) {
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
