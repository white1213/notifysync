package com.notifysync.android.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import com.notifysync.android.App
import com.notifysync.android.service.NotificationListener
import com.notifysync.android.service.SyncService

/** 设置页：开关 + 自检引导（后台保活是国产 ROM 下同步可靠性的关键） */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as App
    var nameField by remember(UiState.selfName) { mutableStateOf(UiState.selfName) }
    var checkTick by remember { mutableStateOf(0) }
    var showLog by remember { mutableStateOf(false) }
    // 自检结果在进入/回到本页/点击时重算（从系统设置页返回后自动刷新）
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) checkTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val listenerOk = remember(checkTick) { isListenerEnabled(context) }
    val batteryOk = remember(checkTick) { isIgnoringBattery(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("同步设置", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SettingSwitch(
            title = "启用通知同步",
            desc = "关闭后将停止后台同步服务",
            initial = UiState.settingsMaster,
        ) { checked ->
            app.prefs.masterEnabled = checked
            UiState.settingsMaster = checked
            if (checked) SyncService.start(context) else SyncService.stop(context)
        }
        SettingSwitch(
            title = "收到其他设备通知时弹本机通知",
            desc = null,
            initial = UiState.settingsMirrorPop,
        ) { app.prefs.mirrorPop = it; UiState.settingsMirrorPop = it }
        SettingSwitch(
            title = "忽略常驻通知",
            desc = "音乐、导航、下载进度等 ongoing 通知不同步",
            initial = UiState.settingsIgnoreOngoing,
        ) { app.prefs.ignoreOngoing = it; UiState.settingsIgnoreOngoing = it }
        SettingSwitch(
            title = "开机自启动",
            desc = "重启手机后自动恢复同步",
            initial = UiState.settingsBootStart,
        ) { app.prefs.bootStart = it; UiState.settingsBootStart = it }
        SettingSwitch(
            title = "剪贴板同步",
            desc = "接收：其他设备复制的内容自动进入本机剪贴板；发送：打开本 App 或用系统分享菜单（Android 10+ 禁止后台读剪贴板）",
            initial = UiState.settingsClipSync,
        ) { app.prefs.clipSync = it; UiState.settingsClipSync = it }
        SettingSwitch(
            title = "剪贴板同步提示",
            desc = "收到远端剪贴板时弹 Toast",
            initial = UiState.settingsClipToast,
        ) { app.prefs.clipToast = it; UiState.settingsClipToast = it }
        Row(Modifier.padding(top = 4.dp)) {
            OutlinedButton(onClick = {
                (context as? com.notifysync.android.MainActivity)?.syncClipboardNow()
                    ?: run { UiState.toast = "请从通知同步 App 内点击此按钮" }
            }) { Text("立即发送本机剪贴板") }
        }

        Text("本机名称", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = nameField,
                onValueChange = { nameField = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                app.prefs.setDeviceName(nameField)
                UiState.selfName = app.prefs.identity.name
                UiState.toast = "设备名已更新（5 秒内其他设备可见）"
            }) { Text("保存") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("保活自检（国产 ROM 必看）", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckRow(
                    ok = listenerOk && UiState.listenerConnected,
                    title = "通知使用权",
                    desc = when {
                        !listenerOk -> "未开启！无法读取通知，这是同步的前提"
                        UiState.listenerConnected -> "已开启，监听器已绑定"
                        else -> "已授权但监听器未绑定，点下方\"修复通知监听\""
                    },
                    actionText = if (listenerOk) null else "去开启",
                    onAction = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                )
                CheckRow(
                    ok = batteryOk,
                    title = "电池无限制",
                    desc = if (batteryOk) "已加入电池优化白名单" else "未白名单！后台可能被系统杀掉，导致\"同步经常断\"",
                    actionText = if (batteryOk) null else "去设置",
                    onAction = {
                        try {
                            context.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    },
                )
                CheckRow(
                    ok = true,
                    title = "厂商自启动/后台管理",
                    desc = "在手机管家里允许本应用\"自启动\"\"后台运行\"（各家路径不同）",
                    actionText = "尝试跳转",
                    onAction = { jumpVendorAutostart(context) },
                )
                CheckRow(
                    ok = UiState.serviceRunning,
                    title = "同步服务",
                    desc = if (UiState.serviceRunning) "前台服务运行中" else "未运行",
                    actionText = if (UiState.serviceRunning) null else "启动",
                    onAction = { SyncService.start(context) },
                )
                OutlinedButton(onClick = {
                    NotificationListener.rebind(context)
                    UiState.toast = "已请求重新绑定通知监听"
                    checkTick++
                }) { Text("修复通知监听（监听失效时点我）") }
                OutlinedButton(onClick = { checkTick++ }) { Text("重新检测") }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("排错", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row {
            OutlinedButton(onClick = {
                val engine = app.engine
                if (engine == null) {
                    UiState.toast = "同步服务未运行，先到上面开启\"启用通知同步\""
                    return@OutlinedButton
                }
                val record = com.notifysync.android.core.NotifRecord(
                    nid = com.notifysync.android.core.newUuid(),
                    originId = app.prefs.identity.id,
                    originName = app.prefs.identity.name,
                    pkg = "com.notifysync.test",
                    app = "NotifySync测试",
                    title = "测试通知",
                    text = "来自 ${app.prefs.identity.name}。看到这条说明本机→对方链路正常（不经过系统通知监听）",
                    subText = "", convTitle = "",
                    ts = System.currentTimeMillis(), recvTs = System.currentTimeMillis(),
                    ongoing = false, otp = null,
                )
                UiState.lastTestNid = record.nid
                engine.onLocalNotification(record)
                UiState.toast = "测试通知已发出，等待对方回执…"
            }) { Text("发送测试通知" ) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val report = buildDiagnosticReport(context, app)
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", report))
                UiState.toast = "诊断报告已复制，发给助手即可"
            }) { Text("复制诊断报告" ) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showLog = true }) { Text("查看运行日志") }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("数据", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("历史保留天数", Modifier.weight(1f), fontSize = 14.sp)
            for (d in listOf(7, 30, 90)) {
                OutlinedButton(onClick = {
                    app.prefs.retentionDays = d
                    UiState.settingsRetentionDays = d
                    app.dbExecutor.execute { app.db.prune(d) }
                }) {
                    Text(if (app.prefs.retentionDays == d) "●$d" else "$d", fontSize = 13.sp)
                }
                Spacer(Modifier.width(6.dp))
            }
            OutlinedButton(onClick = {
                app.db.clearAll()
                app.dbExecutor.execute { UiState.items = app.db.queryRecent() }
                UiState.toast = "历史已清空"
            }) { Text("清空历史") }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val versionName = remember {
            try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (_: Exception) { "?" }
        }
        Text(
            "通知同步 v$versionName · 局域网端到端加密同步",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 30.dp),
        )
    }

    if (showLog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLog = false },
            title = { Text("运行日志（最近 300 条）") },
            text = {
                Text(
                    com.notifysync.android.core.LogBus.dump().ifEmpty { "暂无日志" },
                    fontSize = 10.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(
                        android.content.ClipData.newPlainText("log", com.notifysync.android.core.LogBus.dump()),
                    )
                    UiState.toast = "日志已复制"
                    showLog = false
                }) { Text("复制全部") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showLog = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun SettingSwitch(title: String, desc: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember(initial) { mutableStateOf(initial) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp)
                if (desc != null) Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = {
                checked = it
                onChange(it)
            })
        }
    }
}

@Composable
private fun CheckRow(ok: Boolean, title: String, desc: String, actionText: String?, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⚠️", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp)
            Text(desc, fontSize = 11.sp, color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFE65100))
        }
        if (actionText != null) {
            OutlinedButton(onClick = onAction) { Text(actionText) }
        }
    }
}

private fun isListenerEnabled(ctx: Context): Boolean = try {
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
} catch (_: Exception) {
    false
}

/** 汇总一切排错需要的状态 + 运行日志，一键复制发给维护者 */
private fun buildDiagnosticReport(context: Context, app: App): String {
    val pm = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
    val engine = app.engine
    val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) pm?.longVersionCode ?: -1
        else (pm?.versionCode ?: -1).toLong()
    return buildString {
        appendLine("NotifySync 诊断报告")
        appendLine("时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
        appendLine("版本: ${pm?.versionName} ($vCode)")
        appendLine("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine()
        appendLine("通知使用权已授予: ${isListenerEnabled(context)}")
        appendLine("监听器已绑定: ${UiState.listenerConnected}")
        appendLine("电池白名单: ${isIgnoringBattery(context)}")
        appendLine("同步服务运行: ${UiState.serviceRunning}")
        appendLine("同步开关: ${app.prefs.masterEnabled} · 弹镜像: ${app.prefs.mirrorPop} · 忽略常驻: ${app.prefs.ignoreOngoing}")
        appendLine()
        appendLine("已配对设备: ${UiState.peers.joinToString { "${it.name}(${if (it.online) "在线" else "离线"})" }.ifEmpty { "无" }}")
        appendLine("发现未配对: ${UiState.discovered.joinToString { "${it.name}@${it.address}" }.ifEmpty { "无" }}")
        appendLine()
        appendLine("==== 运行日志 ====")
        appendLine(com.notifysync.android.core.LogBus.dump().ifEmpty { "（空）" })
    }
}

private fun isIgnoringBattery(ctx: Context): Boolean = try {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    pm.isIgnoringBatteryOptimizations(ctx.packageName)
} catch (_: Exception) {
    false
}

/** 各厂商自启动设置页跳转（收集自社区通用方案，找不到时静默失败） */
private fun jumpVendorAutostart(context: Context) {
    val candidates = listOf(
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC"),
    )
    for (cn in candidates) {
        try {
            val intent = Intent().setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
    // 兜底：应用详情页
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
    }
}
