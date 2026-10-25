'use strict';
// UDP 广播发现，见 docs/PROTOCOL.md §3
const dgram = require('dgram');
const os = require('os');
const { EventEmitter } = require('events');

const DISCOVERY_PORT = 37311;
const PRESENCE_INTERVAL = 5000;
const PRESENCE_TTL = 30000;

function subnetBroadcast(iface) {
  const ip = iface.address.split('.').map(Number);
  const mask = iface.netmask.split('.').map(Number);
  const out = ip.map((o, i) => (o | (~mask[i] & 255)) & 255);
  return out.join('.');
}

class Discovery extends EventEmitter {
  constructor(selfInfo) {
    super();
    this.self = selfInfo; // {id, name, role, port}
    this.seen = new Map(); // id -> {name, role, address, port, lastSeen}
    this.sock = null;
    this.timers = [];
  }

  start() {
    this.sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.sock.on('error', (e) => this.emit('error', e));
    this.sock.on('message', (buf, rinfo) => this._onPacket(buf, rinfo));
    this.sock.bind(DISCOVERY_PORT, () => {
      try { this.sock.setBroadcast(true); } catch (_) { /* 无广播权限时仅监听 */ }
      this.broadcast();
      this.timers.push(setInterval(() => this.broadcast(), PRESENCE_INTERVAL));
      this.timers.push(setInterval(() => this._sweep(), PRESENCE_TTL / 2));
    });
  }

  stop() {
    this.timers.forEach(clearInterval);
    this.timers = [];
    if (this.sock) { try { this.sock.close(); } catch (_) {} this.sock = null; }
  }

  broadcast() {
    const payload = Buffer.from(JSON.stringify({
      v: 1, type: 'presence',
      id: this.self.id, name: this.self.name, role: this.self.role, port: this.self.port,
    }));
    const targets = new Set(['255.255.255.255']);
    for (const list of Object.values(os.networkInterfaces())) {
      for (const it of list || []) {
        if (it.family === 'IPv4' && !it.internal) {
          try { targets.add(subnetBroadcast(it)); } catch (_) {}
        }
      }
    }
    for (const addr of targets) {
      try { this.sock.send(payload, DISCOVERY_PORT, addr); } catch (_) {}
    }
  }

  _onPacket(buf, rinfo) {
    let p;
    try { p = JSON.parse(buf.toString('utf8')); } catch (_) { return; }
    if (!p || p.v !== 1 || p.type !== 'presence' || typeof p.id !== 'string') return;
    if (p.id === this.self.id) return;
    const prev = this.seen.get(p.id);
    const info = {
      id: p.id, name: p.name || '未知设备', role: p.role || 'phone',
      address: rinfo.address, port: p.port || 37312, lastSeen: Date.now(),
    };
    this.seen.set(p.id, info);
    this.emit('presence', info);
    if (!prev) this.emit('discovered', info);
  }

  _sweep() {
    const now = Date.now();
    for (const [id, info] of this.seen) {
      if (now - info.lastSeen > PRESENCE_TTL) {
        this.seen.delete(id);
        this.emit('expired', info);
      }
    }
  }

  list() {
    return [...this.seen.values()].sort((a, b) => b.lastSeen - a.lastSeen);
  }
}

module.exports = { Discovery, DISCOVERY_PORT, PRESENCE_INTERVAL, subnetBroadcast };
