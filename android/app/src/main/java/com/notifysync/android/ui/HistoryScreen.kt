package com.notifysync.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notifysync.android.core.NotifRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 通知历史页：按会话分组 + 搜索 + 应用筛选 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    var filterApp by remember { mutableStateOf<String?>(null) }
    var keyword by remember { mutableStateOf("") }

    val filtered = UiState.items.filter { n ->
        (filterApp == null || n.app == filterApp) &&
            (keyword.isBlank() || "${n.title} ${n.text} ${n.app} ${n.originName}".contains(keyword.trim(), ignoreCase = true))
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("搜索通知…") },
            singleLine = true,
        )
        AppFilterRow(selected = filterApp, onSelect = { filterApp = if (filterApp == it) null else it })
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (UiState.items.isEmpty()) "暂无通知\n同步其他设备后通知会出现在这里"
                    else "没有匹配的通知",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                val grouped = filtered.groupBy { it.convKey }
                for ((_, list) in grouped) {
                    item(key = "head-${list.first().convKey}") { GroupHeader(list.first(), list.size) }
                    items(list, key = { it.originId + ":" + it.nid }) { NotifCard(it) }
                }
            }
        }
    }
}

@Composable
private fun AppFilterRow(selected: String?, onSelect: (String) -> Unit) {
    val apps = UiState.items.groupBy { it.app }.toList().sortedByDescending { it.second.size }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect("") }, label = { Text("全部") })
        for ((app, list) in apps) {
            FilterChip(selected = selected == app, onClick = { onSelect(app) }, label = { Text("$app ${list.size}") })
        }
    }
}

@Composable
private fun GroupHeader(first: NotifRecord, count: Int) {
    val convName = first.convTitle.ifEmpty { first.subText.ifEmpty { first.title } }
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(24.dp).background(colorOf(first.pkg), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(first.app.take(1), color = Color.White, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (convName.isNotEmpty() && convName != first.app) "${first.app} · $convName" else first.app,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("  $count 条", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

@Composable
private fun NotifCard(n: NotifRecord) {
    val clipboard = LocalClipboardManager.current
    Card(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    n.title.ifEmpty { n.app },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (n.originName.contains("PC", true) || n.originId.endsWith("pc")) Icons.Filled.Computer else Icons.Filled.PhoneAndroid,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(fmtTime(n.ts), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (n.text.isNotEmpty()) {
                Text(n.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (n.otp != null) {
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .background(Color(0xFFFFB02E).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(n.otp))
                            UiState.toast = "验证码 ${n.otp} 已复制"
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("验证码 ${n.otp}", fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = Color(0xFFFFB02E))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制", modifier = Modifier.size(13.dp), tint = Color(0xFFFFB02E))
                }
            }
        }
    }
}

private fun colorOf(key: String): Color {
    var h = 0
    for (c in key) h = h * 31 + c.code
    val hsv = floatArrayOf((h % 360).toFloat(), 0.55f, 0.55f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun fmtTime(ts: Long): String {
    val d = Date(ts)
    val now = Date()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(d) == SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(now)
    return if (sameDay) SimpleDateFormat("HH:mm", Locale.CHINA).format(d)
    else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(d)
}
