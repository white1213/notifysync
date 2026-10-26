'use strict';
// P2P 同步引擎。协议见 docs/PROTOCOL.md §4-§6。
// 纯 Node（不含 Electron 依赖），可被测试直接驱动。
const net = require('net');
const crypto = require('crypto');
const { EventEmitter } = require('events');
const C = require('./crypto');
const { Discovery } = require('./discovery');

const TCP_PORT = 37312;
const MAX_FRAME = 1024 * 1024;
const HEARTBEAT_MS = 15000;
const SILENCE_MS = 40000;
const DIAL_COOLDOWN_MS = 10000;
const BACKOFF_STEPS = [3000, 5000, 8000, 10000];
const DIALOG_COOLDOWN_MS = 30000;
const DIALOG_TIMEOUT_MS = 180000;   // 给足用户走到对方设备确认的时间
const INITIATOR_TIMEOUT_MS = 300000;
const KEEPALIVE_MS = 20000;
const UNPAIRED_DENY_MS = 2000;
// 哨兵：听到某已配对设备的广播、但 60 秒内一次都没连上 → 服务/网络异常（§7.1）
const SENTINEL_WINDOW_MS = 60000;

let connSeq = 0;

class Conn {
  constructor(engine, socket, dialer) {
    this.engine = engine;
    this.socket = socket;
    this.dialer = !!dialer;
    this.connId = ++connSeq;
    this.state = 'plain'; // plain | encrypted | closed
    this.peerId = null;
    this.hello = null;      // 对方 hello 对象
    this.myHello = null;    // 自己发出的 hello（保存 nonce 供派生会话密钥）
    this.key = null;
    this.sharedX = null;
    this.wasEstablished = false;
    this.recvBuf = Buffer.alloc(0);
    this.lastFrameAt = Date.now();
    this.hbTimer = null;
    this.denyTimer = null;
    engine.conns.set(this.connId, this);
    socket.setNoDelay(true);
    socket.setKeepAlive(true, KEEPALIVE_MS);
    socket.on('data', (d) => this._feed(d));
    socket.on('error', (e) => { this.engine._log(`sock-err ${e.message}`); this._teardown(); });
    socket.on('close', () => this._teardown());
  }

  _feed(chunk) {
    if (this.state === 'closed') return;
    this.recvBuf = this.recvBuf.length ? Buffer.concat([this.recvBuf, chunk]) : chunk;
    for (;;) {
      if (this.recvBuf.length < 4) return;
      const len = this.recvBuf.readUInt32BE(0);
      if (len > MAX_FRAME) return this.close('oversize');
      if (this.recvBuf.length < 4 + len) return;
      const payload = this.recvBuf.subarray(4, 4 + len);
      this.recvBuf = this.recvBuf.subarray(4 + len);
      this.lastFrameAt = Date.now();
      this._handleFrame(payload);
      if (this.state === 'closed') return;
    }
  }

  _handleFrame(payload) {
    if (this.state === 'encrypted') {
      let plain;
      try {
        plain = C.decrypt(this.key, payload);
      } catch (_) {
        // 单侧残留配对/重配对时，对端可能仍处于明文阶段的控制消息
        const guess = tryParseJson(payload);
        if (guess && guess.type === 'deny') return this.engine._onDeny(this, guess);
        if (guess && guess.type === 'pair_request') return this.engine._onPairRequest(this);
        if (guess && guess.type === 'pair_confirm') return this.engine._onPairConfirm(this);
        return this.close('bad-decrypt');
      }
      const obj = tryParseJson(plain);
      if (!obj) return this.close('bad-json');
      return this.engine._onMessage(this, obj);
    }
    // 明文阶段：等待用户确认期间可能收到对端已加密的心跳帧，无法解析则忽略
    const obj = tryParseJson(payload);
    if (!obj) return;
    switch (obj.type) {
      case 'hello': return this.engine._onHello(this, obj);
      case 'pair_request': return this.engine._onPairRequest(this);
      case 'pair_confirm': return this.engine._onPairConfirm(this);
      case 'pair_reject': return this.engine._onPairReject(this);
      case 'deny': return this.engine._onDeny(this, obj);
      default: return;
    }
  }

  sendRaw(buf) {
    if (this.state === 'closed') return;
    const head = Buffer.alloc(4);
    head.writeUInt32BE(buf.length, 0);
    this.socket.write(Buffer.concat([head, buf]));
  }

  sendJsonPlain(obj) { this.sendRaw(Buffer.from(JSON.stringify(obj), 'utf8')); }

  sendJsonEnc(obj) {
    if (!this.key) return;
    this.sendRaw(C.encrypt(this.key, Buffer.from(JSON.stringify(obj), 'utf8')));
  }

  close(reason) {
    if (this.state === 'closed') return;
    this.state = 'closed';
    this.engine._connClosed(this, reason);
    try { this.socket.destroy(); } catch (_) {}
  }

  _teardown() {
    if (this.state === 'closed') return;
    this.state = 'closed';
    this.engine._connClosed(this, 'teardown');
  }
}

function tryParseJson(buf) {
  try { return JSON.parse(buf.toString('utf8')); } catch (_) { return null; }
}

class SyncEngine extends EventEmitter {
  // opts: { identity, store }，store 需提供 getPeer/addPeer/removePeer/peers/newNid
  constructor(opts) {
    super();
    this.identity = opts.identity;
    this.store = opts.store;
    this._log = opts.logger || (() => {});
    this.port = opts.port || TCP_PORT;
    this.keys = C.loadKeyPair(this.identity);
    this.server = null;
    this.discovery = null;
    this.running = false;
    this.conns = new Map();      // connId -> Conn
    this.byPeer = new Map();     // peerId -> Conn（当前有效连接）
    this.peerAddr = new Map();   // peerId -> {address, port}
    this.lastDial = new Map();
    this.reconnectTimers = new Map();
    this.backoffStep = new Map();
    this.pairing = new Map();    // peerId -> {role:'initiator'|'receiver', code, connId}
    this.dialogShownAt = new Map();
    this.dialogTimers = new Map();
    this.initiatorTimers = new Map();
    this.introducedSeen = new Map(); // 无 UDP 发现模块时承接引荐数据
    // 哨兵状态：peerId -> { heardAt: 最近一次广播时间, firstHeardInEpisode: 本轮异常起始 }
    this.sentinel = new Map();
    this.sentinelAlerted = new Set(); // 本轮已告警的 peer（恢复后清除，保证"只报一次"）
  }

  start(opts = {}) {
    if (this.running) return;
    this.running = true;
    this._tryListen();
    this._peersTimer = setInterval(() => this._sendPeers(), 20000);
    if (opts.discovery === false) return; // 测试模式：跳过 UDP 发现，手动拨号
    this.discovery = new Discovery(this._selfInfo());
    this.discovery.on('presence', (info) => this._onPresence(info));
    this.discovery.on('error', (e) => this.emit('error', e));
    // 发现新设备/设备过期 → 通知 UI 刷新列表
    this.discovery.on('discovered', () => this.emit('peer-status-changed'));
    this.discovery.on('expired', () => this.emit('peer-status-changed'));
    this.discovery.start();
  }

  // TCP 监听：端口被占（旧实例残留 TIME_WAIT 套接字等）时自动重试，占用消失后自动恢复
  _tryListen() {
    if (!this.running) return;
    this.server = net.createServer((sock) => this._onAccept(sock));
    let errored = false;
    this.server.on('error', (e) => {
      errored = true;
      this.emit('listen-error', e);
      try { this.server.close(); } catch (_) {}
      if (this.running) setTimeout(() => this._tryListen(), 10000);
    });
    this.server.listen(this.port, () => {
      if (!errored) this.emit('listening', this.port);
    });
  }

  stop() {
    this.running = false;
    for (const conn of [...this.conns.values()]) {
      if (conn.state === 'encrypted') conn.sendJsonEnc({ type: 'bye', reason: 'shutdown' });
      conn.close('shutdown');
    }
    for (const t of [...this.reconnectTimers.values(), ...this.dialogTimers.values(),
      ...this.initiatorTimers.values()]) clearTimeout(t);
    this.reconnectTimers.clear(); this.dialogTimers.clear(); this.initiatorTimers.clear();
    if (this._peersTimer) { clearInterval(this._peersTimer); this._peersTimer = null; }
    if (this.server) { try { this.server.close(); } catch (_) {} this.server = null; }
    if (this.discovery) { this.discovery.stop(); this.discovery = null; }
  }

  _selfInfo() {
    return { id: this.identity.id, name: this.identity.name, role: this.identity.role, port: this.port };
  }

  setName(name) {
    this.identity.name = name;
    if (this.discovery) {
      this.discovery.self = this._selfInfo();
      this.discovery.broadcast();
    }
  }

  // ---- 连接管理 ----

  _onAccept(sock) {
    this._log('accept in');
    const conn = new Conn(this, sock, false);
    conn.myHello = this._helloObj(false);
    conn.sendJsonPlain(conn.myHello);
  }

  _helloObj(dialer) {
    return {
      v: 1, type: 'hello',
      id: this.identity.id, name: this.identity.name, role: this.identity.role,
      pub: this.identity.pub,
      nonce: crypto.randomBytes(16).toString('base64'),
      dialer,
    };
  }

  dial(address, port, purpose) {
    const sock = net.connect({ host: address, port: port || TCP_PORT });
    sock.on('error', () => {
      if (!purpose || !purpose.peerId) return;
      if (purpose.kind === 'pair') {
        this._clearPairing(purpose.peerId);
        this.emit('pair-failed', { peerId: purpose.peerId });
      } else {
        this._scheduleReconnect(purpose.peerId);
      }
    });
    sock.on('connect', () => {
      this._log(`dial out ok -> ${address}:${port}`);
      const conn = new Conn(this, sock, true);
      conn.dialedTo = { address, port: port || TCP_PORT };
      conn.myHello = this._helloObj(true);
      conn.sendJsonPlain(conn.myHello);
      if (purpose && purpose.kind === 'pair') {
        conn.sendJsonPlain({ type: 'pair_request' });
        this._beginInitiatorPairing(purpose.peerId, conn.connId);
      }
    });
  }

  _tryDialPaired(peerId, force) {
    if (!this.running || !this.store.getPeer(peerId)) return;
    if (this.byPeer.has(peerId)) return;
    const now = Date.now();
    // 冷却只约束 presence 触发的拨号；退避重试自带节奏，不受冷却限制
    if (!force && now - (this.lastDial.get(peerId) || 0) < DIAL_COOLDOWN_MS) return;
    const pinned = this.store.getPeer(peerId);
    const addr = this.peerAddr.get(peerId)
      || (pinned && pinned.lastAddress ? { address: pinned.lastAddress, port: pinned.lastPort || TCP_PORT } : null);
    if (!addr) return;
    this.lastDial.set(peerId, now);
    this.dial(addr.address, addr.port, { kind: 'reconnect', peerId });
  }

  _scheduleReconnect(peerId) {
    if (!this.running || !this.store.getPeer(peerId)) return;
    if (this.reconnectTimers.has(peerId)) return;
    if (this.byPeer.has(peerId)) return;
    const step = Math.min(this.backoffStep.get(peerId) || 0, BACKOFF_STEPS.length - 1);
    this.backoffStep.set(peerId, step + 1);
    this.reconnectTimers.set(peerId, setTimeout(() => {
      this.reconnectTimers.delete(peerId);
      this._tryDialPaired(peerId, true);
    }, BACKOFF_STEPS[step]));
  }

  _onPresence(info) {
    this.peerAddr.set(info.id, { address: info.address, port: info.port });
    if (this.store.getPeer(info.id)) {
      // 哨兵信号源：听到了已配对设备的广播
      const s = this.sentinel.get(info.id) || {};
      s.heardAt = Date.now();
      if (!s.firstHeardInEpisode) s.firstHeardInEpisode = Date.now();
      this.sentinel.set(info.id, s);
      if (!this.byPeer.has(info.id)) this._tryDialPaired(info.id, false);
    }
  }

  /**
   * 哨兵判定（每 10 秒由外部驱动）：已配对设备"持续听到广播但始终连不上"。
   * 广播消失（设备离开/被彻底杀死）→ 清除本轮状态，不告警；恢复在线 → 重置告警标记（只报一次）。
   */
  sentinelTick() {
    if (!this.running) return;
    const now = Date.now();
    for (const [pid, s] of this.sentinel) {
      const peer = this.store.getPeer(pid);
      if (!peer) { this.sentinel.delete(pid); this.sentinelAlerted.delete(pid); continue; }
      const online = !!(this.byPeer.get(pid) && this.byPeer.get(pid).state === 'encrypted');
      if (online) {
        // 恢复：清空本轮观察与告警标记，下次故障重新告警
        this.sentinel.delete(pid);
        this.sentinelAlerted.delete(pid);
        continue;
      }
      // 广播已消失超过 40s（正常离线：出门/关机/彻底被杀）→ 静默清除，不告警
      if (!s.heardAt || now - s.heardAt > 40000) {
        this.sentinel.delete(pid);
        this.sentinelAlerted.delete(pid);
        continue;
      }
      // 持续听到广播但 60 秒仍未连上 → 异常（只报一次）
      if (now - s.firstHeardInEpisode >= SENTINEL_WINDOW_MS && !this.sentinelAlerted.has(pid)) {
        this.sentinelAlerted.add(pid);
        this.emit('sentinel-alert', {
          peerId: pid, name: peer.name,
          address: s.lastAddr || (this.peerAddr.get(pid) || {}).address,
        });
      }
    }
  }

  // ---- hello / 配对 ----

  _onHello(conn, obj) {
    if (!obj || typeof obj.id !== 'string' || typeof obj.pub !== 'string' || typeof obj.nonce !== 'string') {
      return conn.close('bad-hello');
    }
    if (obj.id === this.identity.id) return conn.close('self');

    conn.peerId = obj.id;
    conn.hello = obj;

    const existing = this.byPeer.get(obj.id);
    if (existing && existing.connId !== conn.connId && existing.state !== 'closed') {
      // 重复连接仲裁（§5.5）：保留发起者设备 id 字典序较大的一条。
      // 两条连接的发起者只能是"我"或"对方"，统一用 obj.id 表示对方，两端结论才一致。
      const existingInitiator = existing.dialer ? this.identity.id : obj.id;
      const newInitiator = conn.dialer ? this.identity.id : obj.id;
      const keepNew = newInitiator > existingInitiator;
      const loser = keepNew ? existing : conn;
      const winner = keepNew ? conn : existing;
      this.byPeer.set(obj.id, winner);
      loser.close('dup-conn');
      // 配对握手若骑在被杀的连接上，迁移到幸存连接并重发（对端明文/加密态都能处理）
      const entry = this.pairing.get(obj.id);
      if (entry) {
        entry.connId = winner.connId;
        if (entry.role === 'initiator') {
          if (!entry.code) {
            entry.code = C.verificationCode(this._sharedX(conn, conn.hello));
            this.emit('pair-waiting', { peerId: obj.id, name: obj.name, role: obj.role, code: entry.code });
          }
          winner.sendJsonPlain({ type: 'pair_request' });
          winner.sendJsonEnc({ type: 'pair_request' });
        }
      }
    } else {
      this.byPeer.set(obj.id, conn);
      const entry = this.pairing.get(obj.id);
      if (entry) {
        entry.connId = conn.connId;
        if (entry.role === 'initiator' && !entry.code) {
          const code = C.verificationCode(this._sharedX(conn, conn.hello));
          entry.code = code;
          this.emit('pair-waiting', { peerId: obj.id, name: obj.name, role: obj.role, code });
        }
      }
    }

    const pinned = this.store.getPeer(obj.id);
    if (pinned) {
      if (pinned.pub !== obj.pub) return conn.close('pub-mismatch');
      return this._enterEncrypted(conn);
    }
    // 未配对：等对方 pair_request（2 秒内没等到就 deny，§5.4）
    conn.denyTimer = setTimeout(() => {
      conn.denyTimer = null;
      if (conn.state === 'closed' || conn.state === 'encrypted') return;
      if (this.pairing.has(conn.peerId)) return;
      conn.sendJsonPlain({ type: 'deny', reason: 'unpaired' });
      conn.close('unpaired-deny');
    }, UNPAIRED_DENY_MS);
  }

  _onPairRequest(conn) {
    const pid = conn.peerId;
    if (!pid) return;
    if (conn.denyTimer) { clearTimeout(conn.denyTimer); conn.denyTimer = null; }

    const pinned = this.store.getPeer(pid);
    if (pinned) {
      // 本地已 pin 且公钥一致 → 对端丢了配对记录想重配，直接确认。
      // 对端此刻可能在明文阶段也可能已加密（复用现有通道），两份都发，各取所需。
      if (conn.hello && pinned.pub === conn.hello.pub) {
        conn.sendJsonPlain({ type: 'pair_confirm' });
        conn.sendJsonEnc({ type: 'pair_confirm' });
        if (conn.state !== 'encrypted') this._enterEncrypted(conn);
        return;
      }
      return conn.close('pub-mismatch');
    }

    const entry = this.pairing.get(pid);
    if (entry && entry.role === 'initiator') {
      // 双方都点了配对：对方的 pair_request 视为确认
      this._clearPairing(pid);
      conn.sendJsonPlain({ type: 'pair_confirm' });
      this._pin(conn);
      this._enterEncrypted(conn);
      conn.sendJsonEnc({ type: 'pair_done' });
      this.emit('paired', this.store.getPeer(pid));
      return;
    }
    if (entry) { entry.connId = conn.connId; return; } // 弹窗已开，换绑新连接

    const now = Date.now();
    if (now - (this.dialogShownAt.get(pid) || 0) < DIALOG_COOLDOWN_MS) {
      conn.sendJsonPlain({ type: 'deny', reason: 'cooldown' });
      return conn.close('cooldown');
    }
    this.dialogShownAt.set(pid, now);
    const code = C.verificationCode(this._sharedX(conn, conn.hello));
    this.pairing.set(pid, { role: 'receiver', code, connId: conn.connId });
    this.dialogTimers.set(pid, setTimeout(() => {
      this.dialogTimers.delete(pid);
      const c = this._pairingConn(pid);
      this._clearPairing(pid);
      this.emit('pair-request-timeout', { peerId: pid });
      if (c) { c.sendJsonPlain({ type: 'deny', reason: 'timeout' }); c.close('pair-timeout'); }
    }, DIALOG_TIMEOUT_MS));
    this.emit('pair-request', {
      peerId: pid, name: conn.hello.name, role: conn.hello.role, code,
      confirm: () => this.confirmPair(pid),
      reject: () => this.rejectPair(pid),
    });
  }

  confirmPair(peerId) {
    const entry = this.pairing.get(peerId);
    const conn = this._pairingConn(peerId);
    if (!entry || !conn || entry.role !== 'receiver' || conn.state === 'closed') {
      // 状态已失效（对方超时离开等）——必须给 UI 反馈，不能静默吞掉
      this._clearPairing(peerId);
      this.emit('pair-denied', { peerId, reason: 'stale' });
      return;
    }
    clearTimeout(this.dialogTimers.get(peerId));
    this.dialogTimers.delete(peerId);
    this._clearPairing(peerId);
    this._pin(conn);
    conn.sendJsonPlain({ type: 'pair_confirm' });
    this._enterEncrypted(conn);
  }

  rejectPair(peerId) {
    const conn = this._pairingConn(peerId);
    const entry = this.pairing.get(peerId);
    if (!entry || entry.role !== 'receiver') return;
    clearTimeout(this.dialogTimers.get(peerId));
    this.dialogTimers.delete(peerId);
    this._clearPairing(peerId);
    if (conn) { conn.sendJsonPlain({ type: 'pair_reject' }); conn.close('rejected'); }
    this.emit('pair-rejected-locally', { peerId });
  }

  initiatePair(target) {
    // target: discovery 条目 {id, address, port}
    if (this.pairing.has(target.id) || this.store.getPeer(target.id)) return;
    const live = this.byPeer.get(target.id);
    if (live && live.state === 'encrypted') {
      // 已有活跃连接（本地记录丢失、对端仍信任）：直接在现有通道上发起重配对
      this._beginInitiatorPairing(target.id, live.connId);
      const code = C.verificationCode(this._sharedX(live, live.hello));
      const entry = this.pairing.get(target.id);
      if (entry) entry.code = code;
      this.emit('pair-waiting', { peerId: target.id, name: live.hello && live.hello.name, code });
      live.sendJsonEnc({ type: 'pair_request' });
      return;
    }
    this.lastDial.set(target.id, Date.now());
    this.dial(target.address, target.port, { kind: 'pair', peerId: target.id });
  }

  _beginInitiatorPairing(peerId, connId) {
    this.pairing.set(peerId, { role: 'initiator', code: null, connId });
    this.initiatorTimers.set(peerId, setTimeout(() => {
      const c = this._pairingConn(peerId);
      this._clearPairing(peerId);
      if (c) c.close('pair-timeout');
      this.emit('pair-timeout', { peerId });
    }, INITIATOR_TIMEOUT_MS));
  }

  cancelPair(peerId) {
    const conn = this._pairingConn(peerId);
    this._clearPairing(peerId);
    if (conn) conn.close('cancelled');
    this.emit('pair-cancelled', { peerId });
  }

  _pairingConn(peerId) {
    const entry = this.pairing.get(peerId);
    if (!entry) return null;
    return this.conns.get(entry.connId) || this.byPeer.get(peerId) || null;
  }

  _clearPairing(peerId) {
    this.pairing.delete(peerId);
    const t = this.initiatorTimers.get(peerId);
    if (t) { clearTimeout(t); this.initiatorTimers.delete(peerId); }
  }

  _pin(conn) {
    const h = conn.hello;
    this.store.addPeer({ id: h.id, name: h.name, role: h.role, pub: h.pub, pairedAt: Date.now() });
  }

  unpair(peerId) {
    const conn = this.byPeer.get(peerId);
    if (conn && conn.state === 'encrypted') {
      conn.sendJsonEnc({ type: 'unpair' });
      conn.close('unpair');
    }
    this.store.removePeer(peerId);
    this.emit('peer-unpaired', { peerId });
  }

  _sharedX(conn, hello) {
    return C.ecdhSharedX(this.keys.priv, hello.pub);
  }

  _enterEncrypted(conn) {
    if (conn.state === 'closed' || conn.key) return;
    if (!conn.myHello || !conn.hello) return conn.close('no-hello');
    if (conn.denyTimer) { clearTimeout(conn.denyTimer); conn.denyTimer = null; }
    conn.sharedX = this._sharedX(conn, conn.hello);
    conn.key = C.sessionKey(conn.sharedX, conn.myHello.nonce, conn.hello.nonce);
    conn.state = 'encrypted';
    conn.wasEstablished = true;
    conn.hbTimer = setInterval(() => {
      if (conn.state !== 'encrypted') return;
      if (Date.now() - conn.lastFrameAt > SILENCE_MS) return conn.close('silence');
      conn.sendJsonEnc({ type: 'ping', ts: Date.now() });
    }, HEARTBEAT_MS);
    this.backoffStep.set(conn.peerId, 0);
    // 记住最后已知地址，重启/无 presence 时也能重连
    if (conn.dialer && conn.dialedTo) {
      this.store.updatePeerAddress(conn.peerId, conn.dialedTo.address, conn.dialedTo.port);
    } else if (conn.socket.remoteAddress) {
      this.store.updatePeerAddress(conn.peerId, conn.socket.remoteAddress, undefined);
    }
    this._log(`encrypted with ${conn.hello.name} (${String(conn.peerId).slice(0, 8)}) dialer=${conn.dialer}`);
    this.emit('peer-online', this._peerInfo(conn));
  }

  _peerInfo(conn) {
    const pinned = this.store.getPeer(conn.peerId);
    return {
      id: conn.peerId,
      name: (pinned && pinned.name) || (conn.hello && conn.hello.name) || '未知设备',
      role: (pinned && pinned.role) || (conn.hello && conn.hello.role) || 'phone',
    };
  }

  // ---- 明文控制消息 ----

  _onPairConfirm(conn) {
    const entry = this.pairing.get(conn.peerId);
    if (!entry || entry.role !== 'initiator') return;
    this._clearPairing(conn.peerId);
    this._pin(conn);
    this._enterEncrypted(conn);
    conn.sendJsonEnc({ type: 'pair_done' });
    this.emit('paired', this.store.getPeer(conn.peerId));
  }

  _onPairReject(conn) {
    this._clearPairing(conn.peerId);
    this.emit('pair-rejected', { peerId: conn.peerId });
    conn.close('pair-rejected');
  }

  _onDeny(conn, obj) {
    const pid = conn.peerId;
    if (this.store.getPeer(pid)) {
      // 本地认为已配对但对端不认识我们（单侧残留）：按对端事实修正
      this.store.removePeer(pid);
      this.emit('peer-unpaired', { peerId: pid, reason: obj && obj.reason });
    } else {
      this.emit('pair-denied', { peerId: pid, reason: obj && obj.reason });
    }
    conn.close('denied');
  }

  // ---- 加密消息 ----

  _onMessage(conn, obj) {
    switch (obj.type) {
      case 'notif': {
        this._log(`notif in from ${String(conn.peerId).slice(0, 8)} pkg=${obj.pkg} nid=${String(obj.nid).slice(0, 8)}`);
        this.emit('notif', obj);
        // 回执：让发送方知道已送达（§6.2）
        conn.sendJsonEnc({ type: 'ack', nid: obj.nid });
        // 中转：AP 隔离等场景下设备间无法直连，把新消息转发给除来源外的其他在线设备。
        // 引擎级去重防环（发送方也会把自己发出的 nid 记入）
        const key = `${obj.originId}|${obj.nid}`;
        if (!this._relayedSeen) this._relayedSeen = new Set();
        if (this._relayedSeen.has(key)) return;
        this._relayedSeen.add(key);
        if (this._relayedSeen.size > 5000) {
          this._relayedSeen = new Set([...this._relayedSeen].slice(-2500));
        }
        for (const c of this.byPeer.values()) {
          if (c !== conn && c.state === 'encrypted') c.sendJsonEnc(obj);
        }
        return;
      }
      case 'ack': return this.emit('ack', { ...this._peerInfo(conn), nid: obj.nid });
      case 'clip': {
        this._log(`clip in from ${String(conn.peerId).slice(0, 8)} hash=${obj.hash} len=${(obj.text || '').length}`);
        this.emit('clip', obj);
        // 中转：与 notif 相同的防环去重（§6.2）
        const ckey = `clip|${obj.hash}`;
        if (!this._relayedSeen) this._relayedSeen = new Set();
        if (this._relayedSeen.has(ckey)) return;
        this._relayedSeen.add(ckey);
        if (this._relayedSeen.size > 5000) {
          this._relayedSeen = new Set([...this._relayedSeen].slice(-2500));
        }
        for (const c of this.byPeer.values()) {
          if (c !== conn && c.state === 'encrypted') c.sendJsonEnc(obj);
        }
        return;
      }
      case 'peers': return this._onPeersIntroduced(obj);
      case 'ping': return conn.sendJsonEnc({ type: 'pong', ts: Date.now() });
      case 'pong': return;
      case 'pair_request': return this._onPairRequest(conn);
      case 'pair_confirm': return this._onPairConfirm(conn);
      case 'pair_done': return this.emit('paired', this._peerInfo(conn));
      case 'unpair': {
        const pid = conn.peerId;
        this.store.removePeer(pid);
        conn.close('unpaired-remote');
        this.emit('peer-unpaired', { peerId: pid, remote: true });
        return;
      }
      case 'bye': return conn.close('bye');
      default: return; // 未知类型忽略（向前兼容）
    }
  }

  // ---- 熟人引荐（§6.2 peers）：UDP 发现不可靠时，通过已加密连接互传设备地址 ----

  _onPeersIntroduced(obj) {
    const list = Array.isArray(obj.list) ? obj.list : [];
    let changed = false;
    const target = this.discovery ? this.discovery.seen : this.introducedSeen;
    for (const p of list) {
      if (!p || typeof p.id !== 'string' || !p.address) continue;
      if (p.id === this.identity.id) continue;
      const port = p.port || TCP_PORT;
      this.peerAddr.set(p.id, { address: p.address, port });
      if (!this.store.getPeer(p.id)) {
        if (!target.has(p.id)) changed = true;
        target.set(p.id, {
          id: p.id, name: p.name || '未知设备', role: p.role || 'phone',
          address: p.address, port, lastSeen: Date.now(),
        });
      } else if (!this.byPeer.has(p.id)) {
        this._tryDialPaired(p.id, false);
      }
    }
    if (changed) this.emit('peer-status-changed');
  }

  _sendPeers() {
    const list = [];
    const seen = new Set();
    for (const p of this.store.peers) {
      if (!p.lastAddress || seen.has(p.id)) continue;
      seen.add(p.id);
      list.push({ id: p.id, name: p.name, role: p.role, address: p.lastAddress, port: p.lastPort || TCP_PORT });
    }
    for (const d of (this.discovery ? this.discovery.list() : [])) {
      if (seen.has(d.id)) continue;
      seen.add(d.id);
      list.push({ id: d.id, name: d.name, role: d.role, address: d.address, port: d.port });
    }
    if (!list.length) return;
    const msg = { type: 'peers', list };
    for (const c of this.byPeer.values()) {
      if (c.state === 'encrypted') c.sendJsonEnc(msg);
    }
  }

  /** 剪贴板同步：广播本机剪贴板文本（调用方负责变化检测与去重） */
  sendClip(text) {
    const msg = {
      type: 'clip',
      cid: this.store.newNid(),
      hash: require('crypto').createHash('sha256').update(text, 'utf8').digest('hex').slice(0, 32),
      text: String(text).slice(0, 100000),
      originId: this.identity.id,
      originName: this.identity.name,
      ts: Date.now(),
    };
    if (!this._relayedSeen) this._relayedSeen = new Set();
    this._relayedSeen.add(`clip|${msg.hash}`);
    for (const c of this.byPeer.values()) {
      if (c.state === 'encrypted') c.sendJsonEnc(msg);
    }
    return msg;
  }

  // ---- 连接关闭 ----

  _connClosed(conn, reason) {
    this.conns.delete(conn.connId);
    if (conn.denyTimer) { clearTimeout(conn.denyTimer); conn.denyTimer = null; }
    if (conn.hbTimer) { clearInterval(conn.hbTimer); conn.hbTimer = null; }
    if (conn.wasEstablished) this._log(`closed ${String(conn.peerId).slice(0, 8)} reason=${reason}`);
    if (this.byPeer.get(conn.peerId) === conn) {
      this.byPeer.delete(conn.peerId);
      if (conn.wasEstablished) {
        this.emit('peer-offline', { ...this._peerInfo(conn), graceful: reason === 'bye' });
        this._scheduleReconnect(conn.peerId);
      }
    }
  }

  // ---- 通知发送 ----

  sendNotif(fields) {
    const msg = Object.assign({
      type: 'notif', nid: this.store.newNid(), originId: this.identity.id,
      originName: this.identity.name, ts: Date.now(),
      subText: '', convTitle: '', otp: null, ongoing: false,
    }, fields);
    // 自己发出的消息记入中转去重集，防止经对端转发回来后再被中转
    if (!this._relayedSeen) this._relayedSeen = new Set();
    this._relayedSeen.add(`${msg.originId}|${msg.nid}`);
    for (const conn of this.byPeer.values()) {
      if (conn.state === 'encrypted') conn.sendJsonEnc(msg);
    }
    return msg;
  }

  status() {
    const peers = this.store.peers.map((p) => ({
      id: p.id, name: p.name, role: p.role,
      online: !!(this.byPeer.get(p.id) && this.byPeer.get(p.id).state === 'encrypted'),
    }));
    const pairedIds = new Set(this.store.peers.map((p) => p.id));
    const base = this.discovery
      ? this.discovery.list()
      : [...this.introducedSeen.values()];
    const discovered = base
      .filter((d) => !pairedIds.has(d.id))
      .map((d) => ({ id: d.id, name: d.name, role: d.role, address: d.address, port: d.port }));
    return { self: this._selfInfo(), peers, discovered, tcpPort: TCP_PORT };
  }
}

module.exports = { SyncEngine, TCP_PORT };
