package com.notifysync.android.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.notifysync.android.App
import com.notifysync.android.core.NotifPoster
import com.notifysync.android.core.SyncEngine
import com.notifysync.android.ui.UiState

/**
 * 前台常驻服务：持有 SyncEngine，维持局域网连接与状态通知。
 * "同步经常断"的三大防线：前台服务 + 状态通知可视化 + 见 UiState 自检引导。
 */
class SyncService : Service(), SyncEngine.Listener {

    companion object {
        const val ACTION_START = "com.notifysync.android.START"
        const val ACTION_STOP = "com.notifysync.android.STOP"

        fun start(ctx: android.content.Context) {
            ctx.startForegroundService(Intent(ctx, SyncService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: android.content.Context) {
            ctx.startService(Intent(ctx, SyncService::class.java).setAction(ACTION_STOP))
        }
    }

    private lateinit var app: App
    private lateinit var engine: SyncEngine
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as App
        // 不持锁时很多手机网卡会省电丢弃广播包，UDP 设备发现会时好时坏
        try {
            val wifi = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            multicastLock = wifi.createMulticastLock("notifysync").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        engine = SyncEngine(app.prefs, app.db, this)
        app.engine = engine
        engine.start()
        UiState.serviceRunning = true
        refreshStatusNotification()
        refreshUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 任何一次 start 都顺带刷新状态（监听器连接/断开时也会走到这里）
        refreshUi()
        refreshStatusNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        UiState.serviceRunning = false
        engine.stop()
        app.engine = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
        super.onDestroy()
    }

    // ---------------- SyncEngine.Listener ----------------

    override fun onPeerStatusChanged() {
        refreshUi()
        refreshStatusNotification()
    }

    override fun onPeerUnpaired(name: String, remote: Boolean) {
        UiState.toast = if (remote) "$name 已在对方设备上解除配对" else "$name 已解除配对"
        refreshUi()
    }

    override fun onPairRequest(request: SyncEngine.PairRequest) {
        UiState.pendingPairRequest = request
        refreshUi()
    }

    override fun onPairWaiting(code: String) {
        UiState.toast = "等待对方确认…核对两台设备验证码：$code"
    }

    override fun onPairResult(ok: Boolean, kind: String) {
        UiState.toast = when {
            ok && kind == "confirmed" -> "已确认配对，正在完成握手…"
            ok -> "配对成功 ✓"
            else -> when (kind) {
                "timeout" -> "配对超时：对方长时间未确认"
                "denied" -> "对方未开启配对或已拒绝"
                "rejected" -> "对方拒绝了配对"
                "request-timeout" -> "等待确认超时"
                "stale" -> "配对请求已过期（对方连接可能已断开），请对方重新发起"
                else -> "配对失败"
            }
        }
        UiState.pendingPairRequest = null
        refreshUi()
    }

    override fun onNotifReceived(record: com.notifysync.android.core.NotifRecord) {
        com.notifysync.android.core.LogBus.log("notif", "已入库 ${record.originName}[${record.app}]，刷新历史")
        app.dbExecutor.execute { UiState.items = app.db.queryRecent() }
        if (app.prefs.mirrorPop) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                UiState.toast = "本机通知权限未授予：同步来的通知只在历史里显示，不弹横幅"
            }
            NotifPoster.postMirror(this, record)
        }
    }

    private fun isListenerGranted(): Boolean = try {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    } catch (_: Exception) {
        false
    }

    override fun onNotifAcked(nid: String) {
        if (nid == UiState.lastTestNid) {
            UiState.toast = "✓ 对方设备已收到测试通知（引擎链路正常）"
            UiState.lastTestNid = null
        }
    }

    // ---------------- 状态维护 ----------------

    private fun refreshUi() {
        UiState.peers = engine.peerStatuses()
        UiState.discovered = engine.discoveredList()
        UiState.selfName = app.prefs.identity.name
        UiState.settingsMirrorPop = app.prefs.mirrorPop
        UiState.settingsIgnoreOngoing = app.prefs.ignoreOngoing
        UiState.settingsRetentionDays = app.prefs.retentionDays
        UiState.settingsBootStart = app.prefs.bootStart
    }

    private fun refreshStatusNotification() {
        // 以系统权限查询为准（回调信号可能在服务启动后才到，且此前没有刷新时机）
        val listening = isListenerGranted() || UiState.listenerConnected
        val online = engine.onlineCount()
        val total = app.prefs.peers.size
        val (title, text, _) = when {
            !listening -> Triple(
                "通知同步 · 异常",
                "通知使用权未开启，无法读取通知！点击进入 App 修复",
                true,
            )
            total == 0 -> Triple("通知同步 · 运行中", "尚未配对设备，进入 App 添加同一 WiFi 下的设备", false)
            online == 0 -> Triple("通知同步 · 运行中", "已配对 $total 台设备，当前无设备在线（检查其他设备）", true)
            else -> Triple("通知同步 · 运行中", "已连接 $online/$total 台设备，同步正常", false)
        }
        val notif: Notification = NotifPoster.buildStatus(this, title, text)
        try {
            ServiceCompat.startForeground(
                this, NotifPoster.STATUS_NOTIF_ID, notif,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // 前台服务被系统拒绝（如权限校验）：停止服务而不是让整个应用崩溃
            android.util.Log.e("SyncService", "startForeground failed", e)
            UiState.serviceRunning = false
            UiState.toast = "同步服务无法启动：${e.message?.take(80)}"
            stopSelf()
        }
    }
}
