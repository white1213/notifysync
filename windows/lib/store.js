'use strict';
// 纯 JSON 持久化，无原生依赖。路径由调用方注入（Electron: userData；测试: 临时目录）
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { generateIdentity } = require('./crypto');

const MAX_NOTIFICATIONS = 20000;
const DEFAULT_RETENTION_DAYS = 30;

const DEFAULT_SETTINGS = {
  deviceName: os_hostname(),
  mirrorToast: true,     // 收到手机通知时弹 Windows 通知
  otpAutoCopy: true,     // 验证码自动复制到剪贴板
  retentionDays: DEFAULT_RETENTION_DAYS,
  offlineToast: true,    // 已配对设备掉线时弹提醒
};

function os_hostname() {
  try { return require('os').hostname(); } catch (_) { return 'Windows-PC'; }
}

class Store {
  constructor(dir) {
    this.dir = dir;
    fs.mkdirSync(dir, { recursive: true });
    this.identity = this._loadJson('identity.json') || generateIdentity('pc', os_hostname());
    this.settings = Object.assign({}, DEFAULT_SETTINGS, this._loadJson('settings.json') || {});
    this.peers = this._loadJson('peers.json') || []; // [{id,name,role,pub,pairedAt}]
    this.notifications = this._loadJson('notifications.json') || [];
    this._saveTimer = null;
    this._seenNids = new Set(this.notifications.map((n) => `${n.originId}|${n.nid}`));
    this._save('identity.json', this.identity);
  }

  _loadJson(name) {
    try {
      return JSON.parse(fs.readFileSync(path.join(this.dir, name), 'utf8'));
    } catch (_) { return null; }
  }

  _save(name, obj) {
    const file = path.join(this.dir, name);
    const tmp = `${file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(obj));
    fs.renameSync(tmp, file);
  }

  _saveSoon(name, obj) {
    if (this._saveTimer) return;
    this._saveTimer = setTimeout(() => {
      this._saveTimer = null;
      this._save(name, obj);
    }, 1000);
  }

  saveSettings() { this._save('settings.json', this.settings); }
  savePeers() { this._save('peers.json', this.peers); }

  setSetting(k, v) {
    this.settings[k] = v;
    this.saveSettings();
  }

  getPeer(id) { return this.peers.find((p) => p.id === id) || null; }

  addPeer(peer) {
    this.removePeer(peer.id);
    this.peers.push(Object.assign({ pairedAt: Date.now() }, peer));
    this.savePeers();
  }

  removePeer(id) {
    this.peers = this.peers.filter((p) => p.id !== id);
    this.savePeers();
  }

  updatePeerAddress(id, address, port) {
    const p = this.peers.find((x) => x.id === id);
    if (!p) return;
    if (address) p.lastAddress = address;
    if (port) p.lastPort = port;
    this.savePeers();
  }

  addNotification(n) {
    const key = `${n.originId}|${n.nid}`;
    if (this._seenNids.has(key)) return false;
    this._seenNids.add(key);
    this.notifications.push(n);
    this.prune(true);
    this._saveSoon('notifications.json', this.notifications);
    return true;
  }

  prune(silent) {
    const before = this.notifications.length;
    const cutoff = Date.now() - this.settings.retentionDays * 86400000;
    const byCount = this.notifications.length > MAX_NOTIFICATIONS;
    if (byCount) {
      this.notifications.sort((a, b) => b.ts - a.ts);
      this.notifications = this.notifications.slice(0, MAX_NOTIFICATIONS);
    }
    this.notifications = this.notifications.filter((n) => n.ts >= cutoff);
    if (!silent && this.notifications.length !== before) {
      this._saveSoon('notifications.json', this.notifications);
    }
    this._seenNids = new Set(this.notifications.map((n) => `${n.originId}|${n.nid}`));
    return before - this.notifications.length;
  }

  clearHistory() {
    this.notifications = [];
    this._seenNids = new Set();
    this._save('notifications.json', this.notifications);
  }

  newNid() { return crypto.randomUUID(); }
}

module.exports = { Store, DEFAULT_SETTINGS };
