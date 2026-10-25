'use strict';
const { app, BrowserWindow, Tray, Menu, Notification, clipboard, ipcMain, dialog, nativeImage, shell } = require('electron');
const { spawn } = require('child_process');
const path = require('path');
const fs = require('fs');
const { Store } = require('./lib/store');
const { SyncEngine } = require('./lib/engine');
const { extractOtp } = require('./lib/otp');

app.setAppUserModelId('com.notifysync.app');

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
  main();
}

let win = null;
let tray = null;
let engine = null;
let store = null;
const offlineToastAt = new Map();
const netStatus = { tcp: false, udpError: null };

function main() {
  app.whenReady().then(bootstrap);
  app.on('window-all-closed', (e) => { /* 关窗即最小化到托盘，退出走托盘菜单 */ });
}

function bootstrap() {
  store = new Store(app.getPath('userData'));
  // 引擎事件落盘，排错时直接读 %APPDATA%/notifysync-windows/engine.log
  const logFile = path.join(app.getPath('userData'), 'engine.log');
  const engineLogger = (m) => {
    try {
      let size = 0;
      try { size = fs.statSync(logFile).size; } catch (_) {}
      if (size > 300000) fs.writeFileSync(logFile, '');
      fs.appendFileSync(logFile, `${new Date().toISOString()} ${m}\n`);
    } catch (_) {}
  };
  engine = new SyncEngine({ identity: store.identity, store, logger: engineLogger });
  wireEngine();
  engine.start();
  createWindow();
  createTray();
  applyLoginItem();
  store.prune();
  setInterval(() => store.prune(), 3600 * 1000);
  // 兜底：界面状态每 10 秒刷新一次，避免任何遗漏的事件导致界面停在旧状态
  setInterval(() => pushState(), 10000);
}

function createWindow() {
  const startHidden = process.argv.includes('--hidden');
  win = new BrowserWindow({
    width: 1080,
    height: 720,
    show: !startHidden,
    icon: path.join(__dirname, 'assets', 'icon-256.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.on('close', (e) => {
    if (!app.isQuitting) { e.preventDefault(); win.hide(); }
  });
}

function showWindow() {
  if (!win) return;
  win.show();
  win.focus();
}

function createTray() {
  const icon = nativeImage.createFromPath(path.join(__dirname, 'assets', 'icon-32.png'));
  tray = new Tray(icon);
  const rebuild = () => {
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: `通知同步 · ${onlineCount()}/${store.peers.length} 台设备在线`, enabled: false },
      { type: 'separator' },
      { label: '显示主窗口', click: showWindow },
      { label: '开机自启', type: 'checkbox', checked: !!store.settings.autoStart,
        click: (item) => setSetting('autoStart', item.checked) },
      { type: 'separator' },
      { label: '退出', click: () => { app.isQuitting = true; app.quit(); } },
    ]));
    tray.setToolTip(`通知同步 · ${onlineCount()}/${store.peers.length} 在线`);
  };
  tray.on('double-click', showWindow);
  rebuild();
  tray.rebuild = rebuild;
}

function onlineCount() {
  return engine.status().peers.filter((p) => p.online).length;
}

function applyLoginItem() {
  app.setLoginItemSettings({
    openAtLogin: !!store.settings.autoStart,
    path: process.execPath,
    args: ['--hidden'],
  });
}

function pushState() {
  if (win && !win.isDestroyed()) {
    win.webContents.send('state', {
      settings: store.settings,
      status: engine.status(),
      net: { ...netStatus },
    });
  }
}

function pushNotifs() {
  if (win && !win.isDestroyed()) {
    const list = store.notifications.slice(-3000).reverse();
    win.webContents.send('notifications', list);
  }
}

function wireEngine() {
  engine.on('listening', (port) => {
    console.log(`[notifysync] TCP listening on ${port}`);
    netStatus.tcp = true;
    pushState();
  });

  engine.on('error', (e) => {
    // UDP 绑定失败等：设备发现会完全失效，要在界面可见
    netStatus.udpError = e && e.message ? e.message : String(e);
    pushState();
  });

  let listenErrorShown = false;
  engine.on('listen-error', (e) => {
    netStatus.tcp = false;
    pushState();
    if (!listenErrorShown) {
      listenErrorShown = true;
      dialog.showErrorBox('端口暂不可用（将自动重试）',
        `TCP ${e.port || 37312} 监听失败：${e.message}\n\n` +
        '通常是旧实例残留占用，几分钟内自动恢复；界面"设备"页会实时显示恢复状态。');
    }
  });

  engine.on('notif', (n) => {
    const fresh = store.addNotification({
      nid: n.nid, originId: n.originId, originName: n.originName,
      pkg: n.pkg, app: n.app || n.pkg, title: n.title || '',
      text: n.text || '', subText: n.subText || '', convTitle: n.convTitle || '',
      ts: n.ts || Date.now(), recvTs: Date.now(), ongoing: !!n.ongoing, otp: n.otp || null,
    });
    pushNotifs();
    if (!fresh) return;

    const originName = n.originName || '手机';
    let body = n.text || n.title || '';
    let toastTitle = `${n.app || '通知'} · ${originName}`;
    if (n.otp && store.settings.otpAutoCopy) {
      clipboard.writeText(n.otp);
      toastTitle = `验证码 ${n.otp} 已复制`;
      body = `${body}\n（已复制到剪贴板）`;
    }
    if (store.settings.mirrorToast) {
      const toast = new Notification({ title: toastTitle, body: body.slice(0, 180), silent: false });
      toast.on('click', () => showWindow());
      toast.show();
    }
  });

  engine.on('peer-online', (p) => {
    tray && tray.rebuild && tray.rebuild();
    pushState();
    if (store.settings.offlineToast) {
      new Notification({ title: '设备已连接', body: `${p.name} 已上线，通知同步恢复` }).show();
    }
  });

  engine.on('peer-offline', (p) => {
    tray && tray.rebuild && tray.rebuild();
    pushState();
    if (!p.graceful && store.settings.offlineToast) {
      const now = Date.now();
      if (now - (offlineToastAt.get(p.id) || 0) > 60000) {
        offlineToastAt.set(p.id, now);
        new Notification({ title: '设备离线', body: `${p.name} 已断开，正在重连…` }).show();
      }
    }
  });

  engine.on('paired', () => { tray && tray.rebuild && tray.rebuild(); pushState(); });
  engine.on('peer-unpaired', () => { tray && tray.rebuild && tray.rebuild(); pushState(); });
  engine.on('peer-status-changed', () => { tray && tray.rebuild && tray.rebuild(); pushState(); });
  engine.on('pair-waiting', (info) => { if (win) win.webContents.send('pair-waiting', info); });
  engine.on('pair-request', (info) => {
    showWindow();
    // IPC 只能传纯数据（engine 的 info 带 confirm/reject 函数，直接发会序列化失败导致弹窗永远不出现）
    if (win) {
      win.webContents.send('pair-request', {
        peerId: info.peerId, name: info.name, role: info.role, code: info.code,
      });
    }
    // 双保险：系统通知 + 默认提示音，确保用户注意到要在电脑上确认
    try {
      const toast = new Notification({
        title: `${info.name} 请求配对`,
        body: `验证码 ${info.code} —— 点击本条，在弹出的窗口中点"确认配对"`,
        timeoutType: 'never',
      });
      toast.on('click', () => showWindow());
      toast.show();
    } catch (_) {}
  });
  engine.on('paired', () => { if (win) win.webContents.send('pair-result', { ok: true }); });
  const pairFail = (name) => (info) => { if (win) win.webContents.send('pair-result', { ok: false, kind: name, info }); };
  engine.on('pair-timeout', pairFail('timeout'));
  engine.on('pair-denied', pairFail('denied'));
  engine.on('pair-rejected', pairFail('rejected'));
  engine.on('pair-failed', pairFail('failed'));
  engine.on('pair-request-timeout', pairFail('request-timeout'));
}

function setSetting(key, value) {
  store.setSetting(key, value);
  if (key === 'deviceName') {
    engine.setName(value);
  }
  if (key === 'autoStart') applyLoginItem();
  if (key === 'retentionDays') store.prune();
  if (tray && tray.rebuild) tray.rebuild();
  pushState();
}

ipcMain.handle('ns:get-state', () => ({ settings: store.settings, status: engine.status(), net: { ...netStatus } }));
ipcMain.handle('ns:get-notifications', () => store.notifications.slice(-3000).reverse());
ipcMain.handle('ns:set-setting', (_e, key, value) => { setSetting(key, value); return true; });
ipcMain.handle('ns:confirm-pair', (_e, peerId) => { engine.confirmPair(peerId); return true; });
ipcMain.handle('ns:reject-pair', (_e, peerId) => { engine.rejectPair(peerId); return true; });
ipcMain.handle('ns:initiate-pair', (_e, target) => { engine.initiatePair(target); return true; });
ipcMain.handle('ns:unpair', (_e, peerId) => { engine.unpair(peerId); return true; });
ipcMain.handle('ns:clear-history', () => { store.clearHistory(); pushNotifs(); return true; });
ipcMain.handle('ns:copy', (_e, text) => { clipboard.writeText(String(text)); return true; });
ipcMain.handle('ns:fix-firewall', async () => {
  // 以管理员权限添加防火墙入站规则（会弹 UAC 授权窗，用户点"是"即可）
  const bat = path.join(app.getPath('userData'), 'fix-firewall.bat');
  fs.writeFileSync(bat, [
    '@echo off',
    'netsh advfirewall firewall delete rule name="NotifySync TCP" >nul 2>&1',
    'netsh advfirewall firewall delete rule name="NotifySync UDP" >nul 2>&1',
    'netsh advfirewall firewall add rule name="NotifySync TCP" dir=in action=allow protocol=TCP localport=37312 >nul',
    'netsh advfirewall firewall add rule name="NotifySync UDP" dir=in action=allow protocol=UDP localport=37311 >nul',
    'exit',
  ].join('\r\n'));
  return new Promise((resolve) => {
    const quoted = bat.replace(/'/g, "''");
    const p = spawn('powershell', [
      '-NoProfile', '-Command',
      `Start-Process -FilePath '${quoted}' -Verb RunAs -Wait`,
    ]);
    let err = null;
    p.on('error', (e) => { err = e.message; });
    p.on('exit', (code) => {
      if (err) return resolve({ ok: false, message: err });
      if (code !== 0) return resolve({ ok: false, message: '未执行（可能拒绝了授权）' });
      resolve({ ok: true, message: '防火墙规则已添加' });
    });
  });
});
ipcMain.handle('ns:quit', () => { app.isQuitting = true; app.quit(); });
