'use strict';
/* global document, window */
const NS = window.notifySync;

const state = {
  notifs: [],
  filterApp: 'all',
  search: '',
  settings: {},
  status: { self: { name: '' }, peers: [], discovered: [] },
  net: { tcp: false, udpError: null },
};
let pendingPair = null;

// ---- 工具 ----
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}
function convKeyOf(n) {
  return `${n.pkg}|${n.convTitle || n.subText || n.title || ''}`;
}
function fmtTime(ts) {
  const d = new Date(ts);
  const now = new Date();
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  if (d.toDateString() === now.toDateString()) return hm;
  return `${d.getMonth() + 1}-${d.getDate()} ${hm}`;
}
function colorOf(key) {
  let h = 0;
  for (const ch of String(key)) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return `hsl(${h % 360}, 55%, 45%)`;
}
function toast(msg, ms = 2600) {
  const t = document.getElementById('toast');
  t.textContent = msg;
  t.classList.remove('hidden');
  clearTimeout(toast._t);
  toast._t = setTimeout(() => t.classList.add('hidden'), ms);
}

// ---- 渲染 ----
function renderChips() {
  const box = document.getElementById('deviceChips');
  const peers = state.status.peers || [];
  if (!peers.length) {
    box.innerHTML = '<span class="chip"><span class="st"></span>未配对设备</span>';
    return;
  }
  box.innerHTML = peers.map((p) =>
    `<span class="chip ${p.online ? 'online' : ''}" title="${esc(p.name)}">
      <span class="st"></span>${esc(p.name)}${p.online ? '' : ' · 离线'}</span>`).join('');
}

function renderFilter() {
  const counts = new Map();
  for (const n of state.notifs) counts.set(n.app || n.pkg, (counts.get(n.app || n.pkg) || 0) + 1);
  const apps = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  let html = `<div class="app-item ${state.filterApp === 'all' ? 'active' : ''}" data-app="all">
    <span>全部</span><span class="n">${state.notifs.length}</span></div>`;
  for (const [app, c] of apps) {
    html += `<div class="app-item ${state.filterApp === app ? 'active' : ''}" data-app="${esc(app)}">
      <span>${esc(app)}</span><span class="n">${c}</span></div>`;
  }
  const aside = document.getElementById('appFilter');
  aside.innerHTML = html;
  aside.querySelectorAll('.app-item').forEach((el) => {
    el.onclick = () => { state.filterApp = el.dataset.app; renderAll(); };
  });
}

function renderList() {
  const kw = state.search.trim().toLowerCase();
  const items = state.notifs.filter((n) => {
    if (state.filterApp !== 'all' && (n.app || n.pkg) !== state.filterApp) return false;
    if (!kw) return true;
    return `${n.title} ${n.text} ${n.app} ${n.originName}`.toLowerCase().includes(kw);
  });

  const groups = new Map();
  for (const n of items) {
    const k = convKeyOf(n);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(n);
  }

  const box = document.getElementById('list');
  document.getElementById('countInfo').textContent = `${items.length} 条`;
  if (!items.length) {
    box.innerHTML = '<div class="empty">暂无通知<br><span class="hint">手机端装好 App 并配对后，通知会实时出现在这里</span></div>';
    return;
  }
  let html = '';
  for (const [key, list] of groups) {
    const first = list[0];
    const convName = first.convTitle || first.subText || first.title || first.app || first.pkg;
    html += `<div class="group-head">
      <div class="avatar" style="background:${colorOf(first.pkg)}">${esc((first.app || '?')[0] || '?')}</div>
      <span>${esc(first.app)}${convName && convName !== first.app ? ' · ' + esc(convName) : ''}</span>
      <span>·</span><span>${list.length} 条</span></div>`;
    for (const n of list) {
      html += `<div class="card">
        <div class="body">
          <div class="meta">
            <span class="title">${esc(n.title || convName)}</span>
            <span class="src">${esc(n.originName || '')}</span>
          </div>
          <div class="text">${esc(n.text || '')}</div>
          ${n.otp ? `<div class="otp">验证码 ${esc(n.otp)}
            <button data-copy="${esc(n.otp)}">复制</button></div>` : ''}
        </div>
        <span class="time">${fmtTime(n.ts)}</span>
      </div>`;
    }
  }
  box.innerHTML = html;
  box.querySelectorAll('[data-copy]').forEach((b) => {
    b.onclick = () => NS.copy(b.dataset.copy).then(() => toast('已复制'));
  });
}

function renderDevicesModal() {
  const paired = document.getElementById('pairedList');
  const discovered = document.getElementById('discoveredList');
  const netBox = document.getElementById('netStatus');
  const n = state.net || {};
  netBox.textContent =
    `本机网络：TCP 监听${n.tcp ? ' ✓' : ' ✗'} · UDP 发现${n.udpError ? ' ✗ ' + n.udpError : ' ✓'}` +
    (n.tcp && !n.udpError ? '' : '（异常时设备互相看不见）');
  const peers = state.status.peers || [];
  paired.innerHTML = peers.length
    ? peers.map((p) => `<div class="dev ${p.online ? 'online' : ''}">
        <span class="st"></span><span class="nm">${esc(p.name)}</span>
        <span class="role">${p.role === 'pc' ? '电脑' : '手机'}${p.online ? '' : ' · 离线'}</span>
        <button data-unpair="${p.id}">解除配对</button></div>`).join('')
    : '<p class="hint">还没有配对的设备</p>';
  const disc = state.status.discovered || [];
  discovered.innerHTML = disc.length
    ? disc.map((d) => `<div class="dev">
        <span class="st" style="background:var(--accent)"></span>
        <span class="nm">${esc(d.name)}</span>
        <span class="role">${d.role === 'pc' ? '电脑' : '手机'}</span>
        <button class="primary" data-pair="${d.id}">配对</button></div>`).join('')
    : '<p class="hint">暂未发现新设备，请确认手机端 App 正在运行且连着同一 WiFi</p>';
  paired.querySelectorAll('[data-unpair]').forEach((b) => {
    b.onclick = () => { if (window.confirm('确定解除配对？两端都会删除配对记录。')) NS.unpair(b.dataset.unpair); };
  });
  discovered.querySelectorAll('[data-pair]').forEach((b) => {
    b.onclick = () => {
      const d = (state.status.discovered || []).find((x) => x.id === b.dataset.pair);
      if (d) { NS.initiatePair(d); toast('已发起配对，请在手机上确认'); }
    };
  });
}

function renderSettingsModal() {
  const s = state.settings;
  document.getElementById('setDeviceName').value = s.deviceName || '';
  document.getElementById('setMirrorToast').checked = !!s.mirrorToast;
  document.getElementById('setOtpAutoCopy').checked = !!s.otpAutoCopy;
  document.getElementById('setOfflineToast').checked = !!s.offlineToast;
  document.getElementById('setClipSync').checked = !!s.clipboardSync;
  document.getElementById('setClipToast').checked = !!s.clipboardToast;
  document.getElementById('setAutoStart').checked = !!s.autoStart;
  document.getElementById('setRetention').value = s.retentionDays || 30;
}

function renderAll() { renderChips(); renderFilter(); renderList(); renderDevicesModal(); }

// ---- 事件 ----
document.getElementById('search').addEventListener('input', (e) => {
  state.search = e.target.value;
  renderList();
});
document.getElementById('btnDevices').onclick = () => showModal('modalDevices');
document.getElementById('btnSettings').onclick = () => { renderSettingsModal(); showModal('modalSettings'); };
document.getElementById('btnFirewall').onclick = async () => {
  toast('正在请求管理员权限…请在弹出的窗口点"是"', 6000);
  const r = await NS.fixFirewall();
  toast(r.ok ? '防火墙规则已添加，等几秒看设备是否出现' : `失败：${r.message}`, 5000);
};
document.querySelectorAll('[data-close]').forEach((b) => {
  b.onclick = () => document.getElementById(b.dataset.close).classList.add('hidden');
});
document.querySelectorAll('.modal').forEach((m) => {
  m.addEventListener('click', (e) => { if (e.target === m) m.classList.add('hidden'); });
});

document.getElementById('setDeviceName').addEventListener('change', (e) => {
  NS.setSetting('deviceName', e.target.value.trim() || 'Windows-PC');
});
for (const [id, key] of [
  ['setMirrorToast', 'mirrorToast'], ['setOtpAutoCopy', 'otpAutoCopy'],
  ['setOfflineToast', 'offlineToast'], ['setAutoStart', 'autoStart'],
  ['setClipSync', 'clipboardSync'], ['setClipToast', 'clipboardToast'],
]) {
  document.getElementById(id).addEventListener('change', (e) => NS.setSetting(key, e.target.checked));
}
document.getElementById('setRetention').addEventListener('change', (e) => {
  const v = Math.max(1, Math.min(365, Number(e.target.value) || 30));
  NS.setSetting('retentionDays', v);
});
document.getElementById('btnClear').onclick = () => {
  if (window.confirm('确定清空全部通知历史？此操作不可恢复。')) {
    NS.clearHistory().then(() => toast('历史已清空'));
  }
};

document.getElementById('btnPairConfirm').onclick = () => {  if (pendingPair) { NS.confirmPair(pendingPair.peerId); }
  document.getElementById('modalPair').classList.add('hidden');
  pendingPair = null;
};
document.getElementById('btnPairReject').onclick = () => {
  if (pendingPair) { NS.rejectPair(pendingPair.peerId); }
  document.getElementById('modalPair').classList.add('hidden');
  pendingPair = null;
};

function showModal(id) { document.getElementById(id).classList.remove('hidden'); }

// ---- 主进程事件 ----
NS.onState((s) => { state.settings = s.settings; state.status = s.status; state.net = s.net || state.net; renderAll(); });
NS.onNotifications((list) => { state.notifs = list; renderAll(); });
NS.onPairRequest((info) => {
  pendingPair = info;
  document.getElementById('pairTitle').textContent = '配对请求';
  document.getElementById('pairDesc').textContent =
    `${info.name}（${info.role === 'pc' ? '电脑' : '手机'}）请求与本机配对`;
  document.getElementById('pairCode').textContent = info.code;
  showModal('modalPair');
});
NS.onPairWaiting((info) => {
  toast(`等待对方确认… 两台设备的验证码应均为 ${info.code}`, 5000);
});
NS.onPairResult((r) => {
  if (r.ok) {
    toast('配对成功 ✓');
    document.getElementById('modalPair').classList.add('hidden');
    pendingPair = null;
  } else {
    const why = {
      timeout: '对方长时间未确认（5 分钟），请重新发起',
      denied: '对方超时未响应或配对已过期，请重试',
      rejected: '对方拒绝了配对',
      failed: '连接失败',
      'request-timeout': '等待确认超时（3 分钟）',
    }[r.kind] || '配对失败';
    toast(`配对失败：${why}`);
  }
});
NS.onSentinelAlert((info) => {
  toast(`⚠ ${info.name} 同步异常：能收到广播但连不上（恢复前不再提醒）`, 8000);
  renderDevicesModal();
});

// ---- 初始化 ----
(async function init() {
  const s = await NS.getState();
  state.settings = s.settings;
  state.status = s.status;
  const list = await NS.getNotifications();
  state.notifs = list;
  renderAll();
})();
