package com.notifysync.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifysync.android.App
import com.notifysync.android.core.SyncEngine

/** 设备管理页：已配对设备在线状态 + 发现新设备 + 配对 */
@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val engine = app.engine

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text("已配对设备", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (UiState.peers.isEmpty()) {
            item {
                Text(
                    "还没有配对的设备。确保其他设备装好 App 并连接同一 WiFi，\n它们会出现在下方\"发现的新设备\"里。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        // 双保险：即便数据源异常也不要让两个列表出现重复 key 而崩溃
        items(UiState.peers, key = { "p-${it.id}" }) { p ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp).background(
                            if (p.online) Color(0xFF3ECF8E) else Color(0xFFB00020),
                            CircleShape,
                        ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name)
                        Text(
                            (if (p.role == "pc") "电脑" else "手机") + if (p.online) " · 在线" else " · 离线",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = {
                        engine?.unpair(p.id)
                        UiState.toast = "已解除与 ${p.name} 的配对"
                    }) { Text("解除配对") }
                }
            }
        }
        item {
            Spacer(Modifier.size(8.dp))
            Text("发现的新设备", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "同一 WiFi 下运行 NotifySync 的设备，约 5 秒内出现",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        val discovered = UiState.discovered.filter { d -> UiState.peers.none { it.id == d.id } }
        if (discovered.isEmpty()) {
            item {
                Text(
                    "暂未发现新设备",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(discovered, key = { "d-${it.id}" }) { d ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name)
                        Text(
                            (if (d.role == "pc") "电脑" else "手机") + " · " + d.address,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { engine?.initiatePair(d) }) { Text("配对") }
                }
            }
        }
    }
}
