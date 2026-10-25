package com.notifysync.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.notifysync.android.core.DiscoveredDevice
import com.notifysync.android.core.NotifRecord
import com.notifysync.android.core.SyncEngine

/** 全局 UI 状态（Compose 快照状态，任意线程可写） */
object UiState {
    var items by mutableStateOf<List<NotifRecord>>(emptyList())
    var peers by mutableStateOf<List<SyncEngine.PeerStatus>>(emptyList())
    var discovered by mutableStateOf<List<DiscoveredDevice>>(emptyList())
    var listenerConnected by mutableStateOf(false)
    var serviceRunning by mutableStateOf(false)
    var selfName by mutableStateOf("")
    var pendingPairRequest by mutableStateOf<SyncEngine.PairRequest?>(null)
    var toast by mutableStateOf<String?>(null)
    /** 最近一次"发送测试通知"的 nid，用于回执提示 */
    var lastTestNid by mutableStateOf<String?>(null)

    var settingsMaster by mutableStateOf(true)
    var settingsMirrorPop by mutableStateOf(true)
    var settingsIgnoreOngoing by mutableStateOf(true)
    var settingsRetentionDays by mutableStateOf(30)
    var settingsBootStart by mutableStateOf(true)
    var settingsClipSync by mutableStateOf(true)
    var settingsClipToast by mutableStateOf(true)
}
