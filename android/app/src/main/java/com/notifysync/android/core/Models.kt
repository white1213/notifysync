package com.notifysync.android.core

import org.json.JSONObject
import java.util.UUID

/** 一条通知记录（本机产生或从其他设备同步而来） */
data class NotifRecord(
    val nid: String,
    val originId: String,
    val originName: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val subText: String,
    val convTitle: String,
    val ts: Long,
    val recvTs: Long,
    val ongoing: Boolean,
    val otp: String?,
) {
    /** 会话分组键，见 PROTOCOL.md §6.1 */
    val convKey: String
        get() = "$pkg|" + (convTitle.ifEmpty { subText.ifEmpty { title } })

    fun toWireJson(): JSONObject = JSONObject().apply {
        put("type", "notif")
        put("nid", nid)
        put("originId", originId)
        put("originName", originName)
        put("pkg", pkg)
        put("app", app)
        put("title", title)
        put("text", text)
        put("subText", subText)
        put("convTitle", convTitle)
        put("ts", ts)
        put("ongoing", ongoing)
        if (otp != null) put("otp", otp) else put("otp", JSONObject.NULL)
    }

    companion object {
        fun fromWireJson(o: JSONObject): NotifRecord? {
            return try {
                NotifRecord(
                    nid = o.getString("nid"),
                    originId = o.getString("originId"),
                    originName = o.optString("originName", "未知设备"),
                    pkg = o.getString("pkg"),
                    app = o.optString("app", o.getString("pkg")),
                    title = o.optString("title", ""),
                    text = o.optString("text", ""),
                    subText = o.optString("subText", ""),
                    convTitle = o.optString("convTitle", ""),
                    ts = o.optLong("ts", System.currentTimeMillis()),
                    recvTs = System.currentTimeMillis(),
                    ongoing = o.optBoolean("ongoing", false),
                    otp = if (o.isNull("otp")) null else o.optString("otp"),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** 已配对的设备 */
data class PeerInfo(
    val id: String,
    val name: String,
    val role: String,
    val pub: String,
    val pairedAt: Long,
    var lastAddress: String? = null,
    var lastPort: Int = 0,
)

/** 发现的未配对设备（来自 UDP presence） */
data class DiscoveredDevice(
    val id: String,
    val name: String,
    val role: String,
    val address: String,
    val port: Int,
    val lastSeen: Long,
)

class Identity(
    val id: String,
    var name: String,
    val role: String,
    val pub: String,
    val priv: String,
)

fun newUuid(): String = UUID.randomUUID().toString()
