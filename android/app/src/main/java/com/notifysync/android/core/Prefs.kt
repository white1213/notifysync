package com.notifysync.android.core

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 身份、已配对设备与用户设置的持久化 */
class Prefs(private val ctx: Context) {

    val identity: Identity by lazy { loadOrCreateIdentity() }
    var peers: MutableList<PeerInfo> = loadPeers()
        private set

    private val sp by lazy { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    var masterEnabled: Boolean
        get() = sp.getBoolean("masterEnabled", true)
        set(v) = sp.edit().putBoolean("masterEnabled", v).apply()

    /** 收到其他设备的通知时，在本机也弹系统通知 */
    var mirrorPop: Boolean
        get() = sp.getBoolean("mirrorPop", true)
        set(v) = sp.edit().putBoolean("mirrorPop", v).apply()

    /** 忽略常驻型通知（音乐/下载进度等 ongoing） */
    var ignoreOngoing: Boolean
        get() = sp.getBoolean("ignoreOngoing", true)
        set(v) = sp.edit().putBoolean("ignoreOngoing", v).apply()

    var retentionDays: Int
        get() = sp.getInt("retentionDays", 30)
        set(v) = sp.edit().putInt("retentionDays", v).apply()

    /** 开机自启动同步服务 */
    var bootStart: Boolean
        get() = sp.getBoolean("bootStart", true)
        set(v) = sp.edit().putBoolean("bootStart", v).apply()

    private fun identityFile(): File = File(ctx.filesDir, "identity.json")

    private fun loadOrCreateIdentity(): Identity {
        val f = identityFile()
        if (f.exists()) {
            try {
                val o = JSONObject(f.readText())
                return Identity(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    role = o.getString("role"),
                    pub = o.getString("pub"),
                    priv = o.getString("priv"),
                )
            } catch (_: Exception) { /* 损坏则重建 */ }
        }
        val (pub, priv) = Crypto.generateKeyPairB64()
        val id = Identity(
            id = newUuid(),
            name = "${Build.MANUFACTURER ?: "Android"} ${Build.MODEL ?: ""}".trim(),
            role = "phone",
            pub = pub,
            priv = priv,
        )
        saveIdentity(id)
        return id
    }

    fun saveIdentity(identity: Identity) {
        identityFile().writeText(
            JSONObject()
                .put("id", identity.id)
                .put("name", identity.name)
                .put("role", identity.role)
                .put("pub", identity.pub)
                .put("priv", identity.priv)
                .toString(),
        )
    }

    fun setDeviceName(name: String) {
        val trimmed = name.trim().ifEmpty { return }
        identity.name = trimmed
        saveIdentity(identity)
    }

    private fun peersFile(): File = File(ctx.filesDir, "peers.json")

    private fun loadPeers(): MutableList<PeerInfo> {
        val f = peersFile()
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                PeerInfo(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    role = o.optString("role", "phone"),
                    pub = o.getString("pub"),
                    pairedAt = o.optLong("pairedAt", 0),
                    lastAddress = if (o.isNull("lastAddress")) null else o.optString("lastAddress"),
                    lastPort = o.optInt("lastPort", 0),
                )
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    fun savePeers() {
        val arr = JSONArray()
        for (p in peers) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("role", p.role)
                    .put("pub", p.pub)
                    .put("pairedAt", p.pairedAt)
                    .put("lastAddress", p.lastAddress ?: JSONObject.NULL)
                    .put("lastPort", p.lastPort),
            )
        }
        peersFile().writeText(arr.toString())
    }

    @Synchronized
    fun getPeer(id: String): PeerInfo? = peers.find { it.id == id }

    @Synchronized
    fun addPeer(peer: PeerInfo) {
        peers.removeAll { it.id == peer.id }
        peers.add(peer)
        savePeers()
    }

    @Synchronized
    fun removePeer(id: String) {
        peers.removeAll { it.id == id }
        savePeers()
    }

    @Synchronized
    fun updatePeerAddress(id: String, address: String?, port: Int?) {
        val p = peers.find { it.id == id } ?: return
        // presence 每 5 秒一次，地址没变就不要写文件（避免无谓 IO）
        val addrChanged = (address != null && p.lastAddress != address)
        val portChanged = (port != null && port > 0 && p.lastPort != port)
        if (!addrChanged && !portChanged) return
        if (address != null) p.lastAddress = address
        if (port != null && port > 0) p.lastPort = port
        savePeers()
    }
}
