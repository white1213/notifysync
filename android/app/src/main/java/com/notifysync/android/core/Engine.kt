package com.notifysync.android.core

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2P 同步引擎（Kotlin 版），行为与 Windows 端 lib/engine.js 严格一致。
 * 协议见 docs/PROTOCOL.md。
 */
class SyncEngine(
    private val prefs: Prefs,
    private val db: Db,
    private val listener: Listener,
) {
    interface Listener {
        fun onPeerStatusChanged()
        fun onPeerUnpaired(name: String, remote: Boolean)
        fun onPairRequest(request: PairRequest)
        fun onPairWaiting(code: String)
        fun onPairResult(ok: Boolean, kind: String)
        fun onNotifReceived(record: NotifRecord)
        fun onNotifAcked(nid: String)
        fun onClipReceived(text: String, hash: String, originName: String)
    }

    class PairRequest(
        val peerId: String,
        val name: String,
        val role: String,
        val code: String,
        val confirm: () -> Unit,
        val reject: () -> Unit,
    )

    data class PeerStatus(val id: String, val name: String, val role: String, val online: Boolean)
    data class Addr(val address: String, val port: Int)

    // 不能叫 State：Conn 继承 Thread，Thread.State 会遮蔽同名枚举
    private enum class ConnState { PLAIN, ENCRYPTED, CLOSED }

    companion object {
        const val UDP_PORT = 37311
        const val TCP_PORT = 37312
        private const val TAG = "SyncEngine"
        private const val MAX_FRAME = 1024 * 1024
        private const val PRESENCE_INTERVAL_MS = 5000L
        private const val PRESENCE_TTL_MS = 30000L
        private const val HEARTBEAT_MS = 15000L
        private const val SILENCE_MS = 40000L
        private const val DIAL_COOLDOWN_MS = 10000L
        private val BACKOFF = longArrayOf(3000, 5000, 8000, 10000)
        private const val DIALOG_COOLDOWN_MS = 30000L
        private const val DIALOG_TIMEOUT_MS = 180000L   // 给足用户走到对方设备确认的时间
        private const val INITIATOR_TIMEOUT_MS = 300000L
        private const val UNPAIRED_DENY_MS = 2000L
        private const val LOCAL_SPAM_WINDOW_MS = 10000L
    }

    private class PairingEntry(val role: String, @Volatile var code: String?, @Volatile var connId: Int)

    private inner class Conn(val socket: Socket, val dialer: Boolean) : Thread() {
        val connId: Int = connSeq.incrementAndGet()
        @Volatile var state = ConnState.PLAIN
        @Volatile var peerId: String? = null
        var hello: JSONObject? = null
        val myHello: JSONObject = makeHello(dialer)
        @Volatile var key: ByteArray? = null
        @Volatile var sharedX: ByteArray? = null
        @Volatile var wasEstablished = false
        @Volatile var lastFrameAt = System.currentTimeMillis()
        @Volatile var lastPingSent = System.currentTimeMillis()
        @Volatile var dialedTo: Addr? = null
        @Volatile var denyTask: ScheduledFuture<*>? = null
        private val sendLock = Any()
        @Volatile var closeReason: String = ""

        init {
            name = "ns-conn-$connId"
            socket.tcpNoDelay = true
            socket.keepAlive = true
        }

        override fun run() {
            try {
                val din = DataInputStream(socket.getInputStream().buffered())
                while (state != ConnState.CLOSED) {
                    val len: Int = try {
                        din.readInt()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    if (len <= 0 || len > MAX_FRAME) {
                        close("oversize"); break
                    }
                    val payload = ByteArray(len)
                    din.readFully(payload)
                    lastFrameAt = System.currentTimeMillis()
                    handleFrame(payload)
                }
            } catch (_: Exception) {
                // 连接断开
            }
            if (state != ConnState.CLOSED) close("eof")
        }

        private fun handleFrame(payload: ByteArray) {
            if (state == ConnState.ENCRYPTED) {
                val k = key
                if (k != null) {
                    try {
                        val plain = Crypto.decrypt(k, payload)
                        val obj = parseJson(plain)
                        if (obj != null) {
                            onMessage(this, obj)
                            return
                        }
                        close("bad-json")
                        return
                    } catch (_: Exception) {
                        // 单侧残留配对/重配对时，对端可能仍在明文阶段
                        val guess = parseJson(payload)
                        when (guess?.optString("type")) {
                            "deny" -> { onDeny(this, guess); return }
                            "pair_request" -> { onPairRequest(this); return }
                            "pair_confirm" -> { onPairConfirm(this); return }
                        }
                        close("bad-decrypt")
                        return
                    }
                }
                return
            }
            val obj = parseJson(payload) ?: return // 等待确认期间对端的加密心跳，忽略
            when (obj.optString("type")) {
                "hello" -> onHello(this, obj)
                "pair_request" -> onPairRequest(this)
                "pair_confirm" -> onPairConfirm(this)
                "pair_reject" -> onPairReject(this)
                "deny" -> onDeny(this, obj)
            }
        }

        fun sendRaw(buf: ByteArray) {
            if (state == ConnState.CLOSED) return
            var failed: Exception? = null
            synchronized(sendLock) {
                try {
                    val out = DataOutputStream(socket.getOutputStream())
                    out.writeInt(buf.size)
                    out.write(buf)
                    out.flush()
                } catch (e: Exception) {
                    failed = e
                }
            }
            // 不能在持有 sendLock 时调 close（close 会拿 engine 锁，与发送路径的锁顺序相反）
            failed?.let {
                LogBus.log("net", "发送失败 ${it.javaClass.simpleName}: ${it.message}")
                close("send-failed")
            }
        }

        fun sendJsonPlain(obj: JSONObject) = sendRaw(obj.toString().toByteArray(Charsets.UTF_8))

        fun sendJsonEnc(obj: JSONObject) {
            val k = key ?: return
            sendRaw(Crypto.encrypt(k, obj.toString().toByteArray(Charsets.UTF_8)))
        }

        fun close(reason: String) {
            if (state == ConnState.CLOSED) return
            state = ConnState.CLOSED
            closeReason = reason
            connClosed(this, reason)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())
    private val connSeq = AtomicInteger(0)
    private val lock = Any()

    private val conns = HashMap<Int, Conn>()
    private val byPeer = HashMap<String, Conn>()
    private val peerAddr = HashMap<String, Addr>()
    private val lastDial = HashMap<String, Long>()
    private val backoffStep = HashMap<String, Int>()
    private val pairing = HashMap<String, PairingEntry>()
    private val dialogShownAt = HashMap<String, Long>()
    private val dialogTasks = HashMap<String, ScheduledFuture<*>>()
    private val discovered = ConcurrentHashMap<String, DiscoveredDevice>()
    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    /**
     * 通用后台执行器：系统通知监听回调和 Compose 按钮都在主线程，
     * 而主线程禁止网络操作（NetworkOnMainThreadException）——
     * 所有可能触碰套接字的对外入口必须调度到这里执行。
     */
    private val bgExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ns-bg") }
    @Volatile private var lastPruneDay = -1L

    private sealed class Purpose {
        class Reconnect(val peerId: String) : Purpose()
        class Pair(val peerId: String) : Purpose()
    }

    // ---------------------------------------------------------------- 生命周期

    fun start() {
        if (running) return
        running = true
        // 套接字绑定不能在主线程做（NetworkOnMainThreadException）
        Thread({
            try {
                serverSocket = ServerSocket(TCP_PORT)
                LogBus.log("net", "TCP 监听 :$TCP_PORT")
            } catch (e: Exception) {
                LogBus.log("net", "TCP 监听失败: ${e.message}")
            }
            try {
                // 必须显式绑 IPv4：部分设备（三星 One UI 实测）默认绑成 IPv6 套接字，
                // IPv4 广播发送会被静默拒绝，设备将永远无法被别人发现
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(java.net.InetAddress.getByName("0.0.0.0"), UDP_PORT))
                }
                LogBus.log("net", "UDP 绑定 :$UDP_PORT (IPv4)")
            } catch (e: Exception) {
                LogBus.log("net", "UDP 绑定失败: ${e.message}")
            }
            Thread({ serverLoop() }, "ns-server").start()
            Thread({ udpLoop() }, "ns-udp").start()
            scheduler.scheduleWithFixedDelay({ tick() }, 200, PRESENCE_INTERVAL_MS, TimeUnit.MILLISECONDS)
            db.prune(prefs.retentionDays)
            lastPruneDay = System.currentTimeMillis() / 86400000
        }, "ns-start").start()
    }

    fun stop() {
        running = false
        synchronized(lock) {
            for (c in conns.values.toList()) {
                if (c.state == ConnState.ENCRYPTED) {
                    try { c.sendJsonEnc(JSONObject().put("type", "bye").put("reason", "shutdown")) } catch (_: Exception) {}
                }
                c.close("shutdown")
            }
            try { serverSocket?.close() } catch (_: Exception) {}
            try { udpSocket?.close() } catch (_: Exception) {}
            serverSocket = null
            udpSocket = null
        }
        scheduler.shutdownNow()
        bgExecutor.shutdownNow()
    }

    private fun serverLoop() {
        while (running) {
            val ss = serverSocket ?: break
            try {
                val sock = ss.accept()
                if (!running) { sock.close(); break }
                val conn = Conn(sock, dialer = false)
                synchronized(lock) { conns[conn.connId] = conn }
                conn.sendJsonPlain(conn.myHello)
                conn.start()
            } catch (_: Exception) {
                if (running) try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun udpLoop() {
        val buf = ByteArray(2048)
        while (running) {
            val sock = udpSocket ?: break
            try {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val obj = parseJson(buf.copyOf(packet.length)) ?: continue
                if (obj.optInt("v") != 1 || obj.optString("type") != "presence") continue
                val id = obj.optString("id")
                if (id.isEmpty() || id == prefs.identity.id) continue
                val dev = DiscoveredDevice(
                    id = id,
                    name = obj.optString("name", "未知设备"),
                    role = obj.optString("role", "phone"),
                    address = packet.address.hostAddress ?: continue,
                    port = obj.optInt("port", TCP_PORT),
                    lastSeen = System.currentTimeMillis(),
                )
                onPresence(dev)
            } catch (_: Exception) {
                if (running) try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun tick() {
        if (!running) return
        broadcastPresence()
        val now = System.currentTimeMillis()
        synchronized(lock) {
            for (c in conns.values.toList()) {
                if (c.state == ConnState.ENCRYPTED) {
                    if (now - c.lastFrameAt > SILENCE_MS) {
                        c.close("silence")
                    } else if (now - c.lastPingSent > HEARTBEAT_MS) {
                        c.lastPingSent = now
                        c.sendJsonEnc(JSONObject().put("type", "ping").put("ts", now))
                    }
                }
            }
        }
        discovered.entries.removeIf { now - it.value.lastSeen > PRESENCE_TTL_MS }
        val today = now / 86400000
        if (today != lastPruneDay) {
            lastPruneDay = today
            db.prune(prefs.retentionDays)
        }
        if (now - lastPeersSent > 20000) {
            lastPeersSent = now
            sendPeers()
        }
    }

    private var lastPresenceFailLog = 0L

    private fun broadcastPresence() {
        val sock = udpSocket ?: return
        try {
            val payload = JSONObject()
                .put("v", 1).put("type", "presence")
                .put("id", prefs.identity.id)
                .put("name", prefs.identity.name)
                .put("role", prefs.identity.role)
                .put("port", TCP_PORT)
                .toString().toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(payload, payload.size, java.net.InetAddress.getByName("255.255.255.255"), UDP_PORT))
            lastPresenceFailLog = 0L
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            if (now - lastPresenceFailLog > 60000) { // 限频：最多每分钟记一次
                lastPresenceFailLog = now
                LogBus.log("net", "presence 广播失败 ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun makeHello(dialer: Boolean): JSONObject = JSONObject()
        .put("v", 1).put("type", "hello")
        .put("id", prefs.identity.id)
        .put("name", prefs.identity.name)
        .put("role", prefs.identity.role)
        .put("pub", prefs.identity.pub)
        .put("nonce", java.util.Base64.getEncoder().encodeToString(Crypto.randomBytes(16)))
        .put("dialer", dialer)

    private fun parseJson(buf: ByteArray): JSONObject? = try {
        val s = String(buf, Charsets.UTF_8).trim()
        if (s.isEmpty() || s[0] != '{') null else JSONObject(s)
    } catch (_: Exception) {
        null
    }

    // ---------------------------------------------------------------- 连接与重连

    private fun dial(address: String, port: Int, purpose: Purpose) {
        if (!running) return
        Thread({
            if (!running) return@Thread
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(address, port), 5000)
                val conn = Conn(sock, dialer = true)
                conn.dialedTo = Addr(address, port)
                synchronized(lock) { conns[conn.connId] = conn }
                conn.sendJsonPlain(conn.myHello)
                if (purpose is Purpose.Pair) {
                    conn.sendJsonPlain(JSONObject().put("type", "pair_request"))
                    beginInitiatorPairing(purpose.peerId, conn.connId)
                }
                conn.start()
            } catch (_: Exception) {
                when (purpose) {
                    is Purpose.Reconnect -> scheduleReconnect(purpose.peerId)
                    is Purpose.Pair -> {
                        synchronized(lock) { clearPairing(purpose.peerId) }
                        postPairResult(false, "failed")
                    }
                }
            }
        }, "ns-dial").start()
    }

    private fun tryDialPaired(peerId: String, force: Boolean) {
        if (!running) return
        synchronized(lock) {
            if (byPeer.containsKey(peerId)) return
            val now = System.currentTimeMillis()
            // 冷却只约束 presence 触发的拨号；退避重试自带节奏
            if (!force && now - (lastDial[peerId] ?: 0L) < DIAL_COOLDOWN_MS) return
            val pinned = prefs.getPeer(peerId) ?: return
            val addr = peerAddr[peerId]
                ?: pinned.lastAddress?.let { Addr(it, if (pinned.lastPort > 0) pinned.lastPort else TCP_PORT) }
                ?: return
            lastDial[peerId] = now
            dial(addr.address, addr.port, Purpose.Reconnect(peerId))
        }
    }

    private fun scheduleReconnect(peerId: String) {
        if (!running) return
        synchronized(lock) {
            if (prefs.getPeer(peerId) == null || byPeer.containsKey(peerId)) return
            val step = minOf(backoffStep[peerId] ?: 0, BACKOFF.size - 1)
            backoffStep[peerId] = step + 1
            val delay = BACKOFF[step]
            val task = scheduler.schedule({
                synchronized(lock) { reconnectTasks.remove(peerId) }
                tryDialPaired(peerId, true)
            }, delay, TimeUnit.MILLISECONDS)
            reconnectTasks[peerId] = task
        }
    }

    private val reconnectTasks = HashMap<String, ScheduledFuture<*>>()

    private var lastDiscoveryKey = ""

    private fun onPresence(dev: DiscoveredDevice) {
        val prev = discovered.put(dev.id, dev)
        if (prev == null) LogBus.log("disc", "发现 ${dev.name} @ ${dev.address}:${dev.port}")
        synchronized(lock) {
            peerAddr[dev.id] = Addr(dev.address, dev.port)
            if (prefs.getPeer(dev.id) != null) {
                prefs.updatePeerAddress(dev.id, dev.address, dev.port)
                if (!byPeer.containsKey(dev.id)) tryDialPaired(dev.id, false)
            }
        }
        // 发现列表有变化（新增/过期）才通知 UI，避免每 5 秒的 presence 造成无谓重组
        val key = discovered.keys.sorted().joinToString(",")
        if (key != lastDiscoveryKey) {
            lastDiscoveryKey = key
            main.post { listener.onPeerStatusChanged() }
        }
    }

    // ---------------------------------------------------------------- hello 与配对

    private fun sharedXOf(hello: JSONObject): ByteArray =
        Crypto.ecdhSharedX(prefs.identity.priv, hello.getString("pub"))

    private fun onHello(conn: Conn, obj: JSONObject) {
        val id = obj.optString("id")
        val pub = obj.optString("pub")
        val nonce = obj.optString("nonce")
        if (id.isEmpty() || pub.isEmpty() || nonce.isEmpty()) return conn.close("bad-hello")
        if (id == prefs.identity.id) return conn.close("self")

        synchronized(lock) {
            conn.peerId = id
            conn.hello = obj

            val existing = byPeer[id]
            if (existing != null && existing.connId != conn.connId && existing.state != ConnState.CLOSED) {
                // 重复连接仲裁：保留发起者设备 id 字典序较大的一条。
                // 两条连接的发起者只能是"我"或"对方"，统一用 id 表示对方，两端结论才一致
                // （peerId 必须先赋值，否则新连接为 null 会导致两端保留不同的连接、互相绞杀）
                val existingInitiator = if (existing.dialer) prefs.identity.id else id
                val newInitiator = if (conn.dialer) prefs.identity.id else id
                val keepNew = newInitiator > existingInitiator
                val loser = if (keepNew) existing else conn
                val winner = if (keepNew) conn else existing
                byPeer[id] = winner
                loser.close("dup-conn")
                // 配对握手若骑在被杀的连接上，迁移到幸存连接并重发（对端明文/加密态都能处理）
                val entry = pairing[id]
                if (entry != null) {
                    entry.connId = winner.connId
                    if (entry.role == "initiator") {
                        if (entry.code == null) {
                            val code = Crypto.verificationCode(sharedXOf(obj))
                            entry.code = code
                            main.post { listener.onPairWaiting(code) }
                        }
                        winner.sendJsonPlain(JSONObject().put("type", "pair_request"))
                        winner.sendJsonEnc(JSONObject().put("type", "pair_request"))
                    }
                }
            } else {
                byPeer[id] = conn
                val entry = pairing[id]
                if (entry != null) {
                    entry.connId = conn.connId
                    if (entry.role == "initiator" && entry.code == null) {
                        val code = Crypto.verificationCode(sharedXOf(obj))
                        entry.code = code
                        main.post { listener.onPairWaiting(code) }
                    }
                }
            }

            val pinned = prefs.getPeer(id)
            if (pinned != null) {
                if (pinned.pub != pub) return conn.close("pub-mismatch")
                return enterEncrypted(conn)
            }
            // 未配对：等 pair_request，2 秒没等到就 deny
            conn.denyTask = scheduler.schedule({
                conn.denyTask = null
                if (conn.state == ConnState.CLOSED || conn.state == ConnState.ENCRYPTED) return@schedule
                synchronized(lock) { if (pairing.containsKey(conn.peerId)) return@schedule }
                try { conn.sendJsonPlain(JSONObject().put("type", "deny").put("reason", "unpaired")) } catch (_: Exception) {}
                conn.close("unpaired-deny")
            }, UNPAIRED_DENY_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun onPairRequest(conn: Conn) {
        val pid = conn.peerId ?: return
        conn.denyTask?.cancel(false)
        conn.denyTask = null
        synchronized(lock) {
            val pinned = prefs.getPeer(pid)
            if (pinned != null) {
                val helloPub = conn.hello?.optString("pub")
                if (helloPub != null && pinned.pub == helloPub) {
                    // 对端丢了配对记录想重配：两份都发，对端处于明文/加密阶段各取所需
                    conn.sendJsonPlain(JSONObject().put("type", "pair_confirm"))
                    conn.sendJsonEnc(JSONObject().put("type", "pair_confirm"))
                    if (conn.state != ConnState.ENCRYPTED) enterEncrypted(conn)
                    return
                }
                return conn.close("pub-mismatch")
            }

            val entry = pairing[pid]
            if (entry != null && entry.role == "initiator") {
                // 双方都点了配对：对方的 pair_request 视为确认
                clearPairing(pid)
                conn.sendJsonPlain(JSONObject().put("type", "pair_confirm"))
                pin(conn)
                enterEncrypted(conn)
                conn.sendJsonEnc(JSONObject().put("type", "pair_done"))
                postPaired(pid)
                return
            }
            if (entry != null) {
                entry.connId = conn.connId
                return
            }

            val now = System.currentTimeMillis()
            if (now - (dialogShownAt[pid] ?: 0L) < DIALOG_COOLDOWN_MS) {
                conn.sendJsonPlain(JSONObject().put("type", "deny").put("reason", "cooldown"))
                return conn.close("cooldown")
            }
            dialogShownAt[pid] = now
            val hello = conn.hello ?: return conn.close("bad-hello")
            val code = Crypto.verificationCode(sharedXOf(hello))
            pairing[pid] = PairingEntry("receiver", code, conn.connId)
            dialogTasks[pid] = scheduler.schedule({
                synchronized(lock) { dialogTasks.remove(pid) }
                val c = pairingConn(pid)
                clearPairing(pid)
                postPairResult(false, "request-timeout")
                if (c != null) {
                    try { c.sendJsonPlain(JSONObject().put("type", "deny").put("reason", "timeout")) } catch (_: Exception) {}
                    c.close("pair-timeout")
                }
            }, DIALOG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val req = PairRequest(
                peerId = pid,
                name = hello.optString("name", "未知设备"),
                role = hello.optString("role", "phone"),
                code = code,
                confirm = { confirmPair(pid) },
                reject = { rejectPair(pid) },
            )
            LogBus.log("pair", "收到 ${req.name} 的配对请求，验证码 $code，等待用户确认")
            main.post { listener.onPairRequest(req) }
        }
    }

    fun confirmPair(peerId: String) {
        bgExecutor.execute { confirmPairBg(peerId) }
    }

    private fun confirmPairBg(peerId: String) {
        synchronized(lock) {
            val entry = pairing[peerId]
            if (entry == null) {
                // 配对状态已失效（对方断开或超时被清理）——必须给 UI 反馈，不能静默吞掉
                LogBus.log("pair", "确认时配对记录已不存在（连接可能已断开）")
                postPairResult(false, "stale")
                return
            }
            val conn = pairingConn(peerId)
            if (entry.role != "receiver" || conn == null || conn.state == ConnState.CLOSED) {
                LogBus.log("pair", "确认时连接已失效（role=${entry.role} conn=${conn != null}）")
                clearPairing(peerId)
                postPairResult(false, "stale")
                return
            }
            dialogTasks.remove(peerId)?.cancel(false)
            clearPairing(peerId)
            pin(conn)
            conn.sendJsonPlain(JSONObject().put("type", "pair_confirm"))
            enterEncrypted(conn)
            LogBus.log("pair", "已确认配对 ${conn.hello?.optString("name")}, 等待对方完成握手")
            postPairResult(true, "confirmed")
        }
    }

    fun rejectPair(peerId: String) {
        bgExecutor.execute { rejectPairBg(peerId) }
    }

    private fun rejectPairBg(peerId: String) {
        synchronized(lock) {
            val entry = pairing[peerId]
            if (entry == null) {
                LogBus.log("pair", "拒绝时配对记录已不存在")
                postPairResult(false, "stale")
                return
            }
            if (entry.role != "receiver") return
            dialogTasks.remove(peerId)?.cancel(false)
            val conn = pairingConn(peerId)
            clearPairing(peerId)
            if (conn != null) {
                conn.sendJsonPlain(JSONObject().put("type", "pair_reject"))
                conn.close("rejected")
            }
            LogBus.log("pair", "已拒绝配对请求")
            postPairResult(false, "rejected")
        }
    }

    fun initiatePair(target: DiscoveredDevice) {
        synchronized(lock) {
            if (pairing.containsKey(target.id) || prefs.getPeer(target.id) != null) return
            val live = byPeer[target.id]
            if (live != null && live.state == ConnState.ENCRYPTED) {
                // 已有活跃连接（本地记录丢失、对端仍信任）：直接在现有通道上发起重配对
                beginInitiatorPairing(target.id, live.connId)
                val hello = live.hello
                if (hello != null) {
                    val code = Crypto.verificationCode(sharedXOf(hello))
                    pairing[target.id]?.code = code
                    main.post { listener.onPairWaiting(code) }
                }
                live.sendJsonEnc(JSONObject().put("type", "pair_request"))
                return
            }
            lastDial[target.id] = System.currentTimeMillis()
        }
        dial(target.address, target.port, Purpose.Pair(target.id))
    }

    private fun beginInitiatorPairing(peerId: String, connId: Int) {
        pairing[peerId] = PairingEntry("initiator", null, connId)
        scheduler.schedule({
            synchronized(lock) {
                val c = pairingConn(peerId)
                clearPairing(peerId)
                c?.close("pair-timeout")
            }
            postPairResult(false, "timeout")
        }, INITIATOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun pairingConn(peerId: String): Conn? {
        val entry = pairing[peerId] ?: return null
        return conns[entry.connId] ?: byPeer[peerId]
    }

    private fun clearPairing(peerId: String) {
        pairing.remove(peerId)
    }

    private fun pin(conn: Conn) {
        val h = conn.hello ?: return
        prefs.addPeer(
            PeerInfo(
                id = h.getString("id"),
                name = h.optString("name", "未知设备"),
                role = h.optString("role", "phone"),
                pub = h.getString("pub"),
                pairedAt = System.currentTimeMillis(),
            ),
        )
    }

    fun unpair(peerId: String) {
        bgExecutor.execute { unpairBg(peerId) }
    }

    private fun unpairBg(peerId: String) {
        synchronized(lock) {
            val conn = byPeer[peerId]
            if (conn != null && conn.state == ConnState.ENCRYPTED) {
                try { conn.sendJsonEnc(JSONObject().put("type", "unpair")) } catch (_: Exception) {}
                conn.close("unpair")
            }
            prefs.removePeer(peerId)
        }
        main.post { listener.onPeerStatusChanged() }
    }

    private fun onPairConfirm(conn: Conn) {
        val pid = conn.peerId ?: return
        synchronized(lock) {
            val entry = pairing[pid] ?: return
            if (entry.role != "initiator") return
            clearPairing(pid)
            pin(conn)
            enterEncrypted(conn)
            conn.sendJsonEnc(JSONObject().put("type", "pair_done"))
            postPaired(pid)
        }
    }

    private fun onPairReject(conn: Conn) {
        val pid = conn.peerId ?: return
        synchronized(lock) { clearPairing(pid) }
        postPairResult(false, "rejected")
        conn.close("pair-rejected")
    }

    private fun onDeny(conn: Conn, obj: JSONObject) {
        val pid = conn.peerId ?: return
        val hadPeer: Boolean
        synchronized(lock) {
            hadPeer = prefs.getPeer(pid) != null
            if (hadPeer) {
                // 本地认为已配对但对端不认识我们（单侧残留）：按对端事实修正
                prefs.removePeer(pid)
            }
        }
        if (hadPeer) {
            val name = conn.hello?.optString("name") ?: pid.take(8)
            main.post { listener.onPeerUnpaired(name, remote = false) }
            main.post { listener.onPeerStatusChanged() }
        } else {
            postPairResult(false, "denied")
        }
        conn.close("denied")
    }

    private fun enterEncrypted(conn: Conn) {
        if (conn.state == ConnState.CLOSED || conn.key != null) return
        val hello = conn.hello ?: return conn.close("no-hello")
        conn.denyTask?.cancel(false)
        conn.denyTask = null
        val sharedX = sharedXOf(hello)
        val key = Crypto.sessionKey(
            sharedX,
            conn.myHello.getString("nonce"),
            hello.getString("nonce"),
        )
        conn.sharedX = sharedX
        conn.key = key
        conn.state = ConnState.ENCRYPTED
        conn.wasEstablished = true
        LogBus.log("net", "已建立加密连接 ${conn.hello?.optString("name")}")
        val pid = conn.peerId
        if (pid != null) {
            backoffStep[pid] = 0
            if (conn.dialer && conn.dialedTo != null) {
                prefs.updatePeerAddress(pid, conn.dialedTo!!.address, conn.dialedTo!!.port)
            } else {
                conn.socket.inetAddress?.hostAddress?.let { prefs.updatePeerAddress(pid, it, null) }
            }
        }
        main.post { listener.onPeerStatusChanged() }
    }

    private fun postPaired(peerId: String) {
        main.post { listener.onPairResult(true, "paired") }
        main.post { listener.onPeerStatusChanged() }
    }

    private fun postPairResult(ok: Boolean, kind: String) {
        main.post { listener.onPairResult(ok, kind) }
    }

    // ---------------------------------------------------------------- 消息

    private fun onMessage(conn: Conn, obj: JSONObject) {
        when (obj.optString("type")) {
            "notif" -> {
                val record = NotifRecord.fromWireJson(obj) ?: return
                LogBus.log("notif", "收到来自 ${record.originName}[${record.app}] ${record.title.take(16)}")
                val fresh = db.insertIfAbsent(record)
                if (fresh) {
                    main.post { listener.onNotifReceived(record) }
                    // 中转：路由器 AP 隔离等场景下，手机间无法直连；
                    // 把新通知转发给除来源外的其他在线设备（各自按 nid 去重，不会成环）
                    val source = conn
                    synchronized(lock) {
                        for (c in conns.values.toList()) {
                            if (c !== source && c.connId != source.connId && c.state == ConnState.ENCRYPTED) {
                                c.sendJsonEnc(obj)
                            }
                        }
                    }
                }
                // 回执：让发送方知道已送达（§6.2）
                conn.sendJsonEnc(JSONObject().put("type", "ack").put("nid", record.nid))
            }
            "ack" -> {
                LogBus.log("notif", "对方已收到通知 nid=${obj.optString("nid").take(8)}")
                main.post { listener.onNotifAcked(obj.optString("nid")) }
            }
            "peers" -> onPeersIntroduced(obj)
            "clip" -> {
                val text = obj.optString("text")
                val hash = obj.optString("hash")
                LogBus.log("clip", "收到来自 ${obj.optString("originName")} 的剪贴板 (${text.length} 字)")
                val source = conn
                var relay = false
                synchronized(lock) {
                    if (clipSeen.add("clip|$hash")) {
                        relay = true
                        while (clipSeen.size > 500) clipSeen.remove(clipSeen.first())
                    }
                }
                main.post { listener.onClipReceived(text, hash, obj.optString("originName")) }
                if (relay) {
                    synchronized(lock) {
                        for (c in conns.values.toList()) {
                            if (c.connId != source.connId && c.state == ConnState.ENCRYPTED) {
                                c.sendJsonEnc(obj)
                            }
                        }
                    }
                }
            }
            "ping" -> conn.sendJsonEnc(JSONObject().put("type", "pong").put("ts", System.currentTimeMillis()))
            "pong" -> {}
            "pair_request" -> onPairRequest(conn)
            "pair_confirm" -> onPairConfirm(conn)
            "pair_done" -> postPaired(conn.peerId ?: "")
            "unpair" -> {
                val pid = conn.peerId ?: return
                val name = prefs.getPeer(pid)?.name ?: "设备"
                prefs.removePeer(pid)
                conn.close("unpaired-remote")
                main.post { listener.onPeerUnpaired(name, remote = true) }
                main.post { listener.onPeerStatusChanged() }
            }
            "bye" -> conn.close("bye")
        }
    }

    private val clipSeen = LinkedHashSet<String>() // 剪贴板中转防环（上限 500 条）

    /** 剪贴板同步：广播本机剪贴板文本（调用方负责变化检测与去重） */
    fun sendClip(text: String) {
        val t = text.take(100000)
        if (t.isEmpty()) return
        val hash = Crypto.clipHash(t)
        synchronized(lock) {
            clipSeen.add("clip|$hash")
            while (clipSeen.size > 500) clipSeen.remove(clipSeen.first())
        }
        val msg = JSONObject()
            .put("type", "clip").put("cid", newUuid()).put("hash", hash).put("text", t)
            .put("originId", prefs.identity.id)
            .put("originName", prefs.identity.name)
            .put("ts", System.currentTimeMillis())
        for (c in conns.values.toList()) {
            if (c.state == ConnState.ENCRYPTED) c.sendJsonEnc(msg)
        }
    }

    /** 熟人引荐（§6.2 peers）：把对端发来的设备列表合并进本地地址表/发现列表，绕开不可靠的 UDP 发现 */
    private fun onPeersIntroduced(obj: JSONObject) {
        val arr = obj.optJSONArray("list") ?: return
        var changed = false
        synchronized(lock) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val pid = p.optString("id")
                val addr = p.optString("address")
                if (pid.isEmpty() || addr.isEmpty() || pid == prefs.identity.id) continue
                val port = p.optInt("port", TCP_PORT)
                peerAddr[pid] = Addr(addr, port)
                if (prefs.getPeer(pid) == null) {
                    val dev = DiscoveredDevice(pid, p.optString("name", "未知设备"), p.optString("role", "phone"), addr, port, System.currentTimeMillis())
                    if (discovered.put(pid, dev) == null) changed = true
                } else if (!byPeer.containsKey(pid)) {
                    tryDialPaired(pid, false)
                }
            }
        }
        if (changed) {
            LogBus.log("disc", "通过引荐发现新设备")
            main.post { listener.onPeerStatusChanged() }
        }
    }

    /** 把自己已知的设备（已配对+已发现）广播给在线对端，每 20 秒一轮 */
    private var lastPeersSent = 0L

    private fun sendPeers() {
        val arr = org.json.JSONArray()
        val seen = HashSet<String>()
        synchronized(lock) {
            for (p in prefs.peers) {
                val a = p.lastAddress ?: continue
                if (seen.add(p.id)) {
                    arr.put(JSONObject().put("id", p.id).put("name", p.name).put("role", p.role)
                        .put("address", a).put("port", if (p.lastPort > 0) p.lastPort else TCP_PORT))
                }
            }
            for (d in discovered.values) {
                if (seen.add(d.id)) {
                    arr.put(JSONObject().put("id", d.id).put("name", d.name).put("role", d.role)
                        .put("address", d.address).put("port", d.port))
                }
            }
        }
        if (arr.length() == 0) return
        val msg = JSONObject().put("type", "peers").put("list", arr)
        for (c in conns.values.toList()) {
            if (c.state == ConnState.ENCRYPTED) c.sendJsonEnc(msg)
        }
    }

    private fun connClosed(conn: Conn, reason: String) {
        synchronized(lock) {
            conns.remove(conn.connId)
            conn.denyTask?.cancel(false)
            conn.denyTask = null
            val pid = conn.peerId ?: return
            LogBus.log("conn", "连接断开 ${conn.hello?.optString("name") ?: pid.take(8)} ($reason)")
            if (byPeer[pid] === conn) {
                byPeer.remove(pid)
                if (conn.wasEstablished) {
                    main.post { listener.onPeerStatusChanged() }
                    scheduleReconnect(pid)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 对外接口

    /** 本机产生新通知（来自 NotificationListener）：入库并广播给在线设备 */
    fun onLocalNotification(record: NotifRecord) {
        // 监听回调在主线程，网络发送必须离开主线程
        bgExecutor.execute { onLocalNotificationBg(record) }
    }

    private fun onLocalNotificationBg(record: NotifRecord) {
        if (db.hasSimilarRecently(record.originId, record.pkg, record.title, record.text, LOCAL_SPAM_WINDOW_MS)) {
            LogBus.log("notif", "跳过重复通知[${record.app}] ${record.title.take(16)}")
            return
        }
        if (!db.insertIfAbsent(record)) return
        val wire = record.toWireJson()
        var sent = 0
        synchronized(lock) {
            for (c in conns.values.toList()) {
                if (c.state == ConnState.ENCRYPTED) {
                    c.sendJsonEnc(wire)
                    sent++
                }
            }
        }
        LogBus.log("notif", "已广播[${record.app}] ${record.title.take(16)} → $sent 台在线设备")
    }

    fun peerStatuses(): List<PeerStatus> = synchronized(lock) {
        prefs.peers.map { p ->
            val c = byPeer[p.id]
            PeerStatus(p.id, p.name, p.role, c != null && c.state == ConnState.ENCRYPTED)
        }
    }

    fun discoveredList(): List<DiscoveredDevice> = synchronized(lock) {
        // 已配对设备不再出现在"发现的新设备"里（否则与已配对列表重复）
        discovered.values
            .filter { prefs.getPeer(it.id) == null }
            .sortedByDescending { it.lastSeen }
    }

    fun onlineCount(): Int = synchronized(lock) {
        prefs.peers.count { p -> byPeer[p.id]?.state == ConnState.ENCRYPTED }
    }

    fun isRunning(): Boolean = running
}
