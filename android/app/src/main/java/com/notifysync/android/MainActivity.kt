package com.notifysync.android

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifysync.android.service.SyncService
import com.notifysync.android.ui.DevicesScreen
import com.notifysync.android.ui.HistoryScreen
import com.notifysync.android.ui.SettingsScreen
import com.notifysync.android.ui.UiState

class MainActivity : ComponentActivity() {

    companion object {
        /** 前台剪贴板轮询间隔（Android 只在窗口有焦点时可读） */
        private const val CLIP_POLL_MS = 1500L
    }

    private val app by lazy { application as App }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        handleCopyIntent(intent)
        handleClipShareIntent(intent)
        UiState.settingsMaster = app.prefs.masterEnabled
        if (app.prefs.masterEnabled) SyncService.start(this)
        if (intent?.getStringExtra("openTab") == "settings") UiState.pendingOpenTab = 2

        setContent {
            MaterialTheme(colorScheme = androidx.compose.material3.lightColorScheme()) {
                MainScreen()
            }
        }
    }

    private val clipPoller = android.os.Handler(android.os.Looper.getMainLooper())
    private val clipPollTask = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                maybeSyncLocalClipboard()
                clipPoller.postDelayed(this, CLIP_POLL_MS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 刷新历史列表（也可能来自其他线程的更新，这里兜底）
        app.dbExecutor.execute { UiState.items = app.db.queryRecent() }
        // 前台期间持续轮询：Android 10+ 只在有窗口焦点时可读剪贴板，
        // 单靠 onResume 这一次读取往往拿不到（焦点尚未就位），必须轮询 + 焦点回调双保险
        clipPoller.postDelayed(clipPollTask, CLIP_POLL_MS)
    }

    override fun onPause() {
        super.onPause()
        clipPoller.removeCallbacks(clipPollTask)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 窗口拿到焦点后立即读一次（最可靠的时机）
        if (hasFocus) maybeSyncLocalClipboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCopyIntent(intent)
        handleClipShareIntent(intent)
    }

    /** 系统分享菜单入口：任意 App 里复制/选中文本 → 分享 → 通知同步 */
    private fun handleClipShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type != "text/plain") return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        sendClipboardToPeers(text)
    }

    /** 手动同步当前剪贴板（设置页按钮；绕过一切焦点/时序问题） */
    fun syncClipboardNow() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = try { cm.primaryClip?.getItemAt(0)?.text?.toString() } catch (_: Exception) { null }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "剪贴板为空或系统未授权读取（先点一下 App 窗口再试）", Toast.LENGTH_LONG).show()
            return
        }
        sendClipboardToPeers(text)
    }

    /** App 在前台时读取本机剪贴板并同步（有新内容且不是自己刚写入的） */
    private fun maybeSyncLocalClipboard() {
        if (!app.prefs.clipSync) return
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            // Android 10+：无焦点时可能返回 null，属于正常情况，下次轮询再试
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
            if (text.isBlank() || text.length > 100000) return
            val hash = com.notifysync.android.core.Crypto.clipHash(text)
            if (hash == app.prefs.lastClipHash) return
            sendClipboardToPeers(text)
        } catch (_: Exception) {
        }
    }

    private fun sendClipboardToPeers(text: String) {
        val engine = app.engine ?: run {
            Toast.makeText(this, "同步服务未运行", Toast.LENGTH_SHORT).show()
            return
        }
        if (engine.onlineCount() == 0) {
            Toast.makeText(this, "没有在线设备，剪贴板未发送", Toast.LENGTH_SHORT).show()
            return
        }
        engine.sendClip(text)
        app.prefs.lastClipHash = com.notifysync.android.core.Crypto.clipHash(text)
        val n = engine.onlineCount()
        Toast.makeText(this, "剪贴板已同步到 $n 台在线设备", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun handleCopyIntent(intent: Intent?) {
        val text = intent?.getStringExtra("copyText") ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("otp", text))
        Toast.makeText(this, "验证码 $text 已复制", Toast.LENGTH_SHORT).show()
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val tabs = listOf(
        Tab("通知", Icons.Filled.Notifications),
        Tab("设备", Icons.Filled.Devices),
        Tab("设置", Icons.Filled.Settings),
    )
    var selected by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as App
    var crashLog by remember { mutableStateOf(app.readCrashLog()) }

    // 哨兵告警点击直达：切到设置（修复）页
    LaunchedEffect(UiState.pendingOpenTab) {
        UiState.pendingOpenTab?.let {
            selected = it
            UiState.pendingOpenTab = null
        }
    }

    // 全局提示
    LaunchedEffect(UiState.toast) {
        val msg = UiState.toast ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        UiState.toast = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, tab ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        val mod = Modifier.padding(padding)
        when (selected) {
            0 -> HistoryScreenWrapped(mod)
            1 -> DevicesScreenWrapped(mod)
            else -> SettingsScreenWrapped(mod)
        }
    }

    // 配对请求弹窗（验证码人工比对，防中间人）。点击任何按钮立即关闭，不依赖引擎回调。
    UiState.pendingPairRequest?.let { req ->
        AlertDialog(
            onDismissRequest = {
                UiState.pendingPairRequest = null
                req.reject()
            },
            title = { Text("配对请求") },
            text = {
                androidx.compose.foundation.layout.Column {
                    Text("${req.name}（${if (req.role == "pc") "电脑" else "手机"}）请求与本机配对")
                    Text(
                        req.code,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    Text("请核对：此验证码必须与对方设备屏幕显示的一致", fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    UiState.pendingPairRequest = null
                    UiState.toast = "已确认，正在完成配对…"
                    req.confirm()
                }) { Text("确认配对") }
            },
            dismissButton = {
                TextButton(onClick = {
                    UiState.pendingPairRequest = null
                    req.reject()
                }) { Text("拒绝") }
            },
        )
    }

    // 上次崩溃日志展示（远程排错用）
    crashLog?.let { log ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("上次异常退出") },
            text = {
                Text(
                    log.take(2600),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", log))
                    app.clearCrashLog()
                    crashLog = null
                }) { Text("复制并关闭") }
            },
            dismissButton = {
                TextButton(onClick = { app.clearCrashLog(); crashLog = null }) { Text("忽略") }
            },
        )
    }
}

// 包一层 modifier 的小包装，保持三个 Screen 签名统一
@Composable
private fun HistoryScreenWrapped(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier) { HistoryScreen() }
}

@Composable
private fun DevicesScreenWrapped(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier) { DevicesScreen() }
}

@Composable
private fun SettingsScreenWrapped(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier) { SettingsScreen() }
}
