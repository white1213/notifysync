'use strict';
// 临时诊断：单侧残留自愈场景事件追踪
const fs = require('fs');
const path = require('path');
const os = require('os');
const { SyncEngine } = require('../lib/engine');
const { Store } = require('../lib/store');

function tmpStore(name) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `nsdbg-${name}-`));
  return new Store(dir);
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const once = (em, ev, ms = 20000) => new Promise((res, rej) => {
  const t = setTimeout(() => rej(new Error('timeout ' + ev)), ms);
  em.once(ev, (a) => { clearTimeout(t); res(a); });
});

const EVENTS = ['listening', 'pair-request', 'paired', 'peer-online', 'peer-offline',
  'peer-unpaired', 'pair-denied', 'pair-rejected', 'pair-failed', 'pair-timeout',
  'pair-request-timeout', 'pair-cancelled', 'pair-rejected-locally', 'notif', 'listen-error', 'error'];

function trace(name, engine) {
  for (const ev of EVENTS) engine.on(ev, (a) => console.log(`[${name}] ${ev}`, a ? JSON.stringify(a).slice(0, 120) : ''));
}

async function main() {
  const sA = tmpStore('a'), sB = tmpStore('b');
  const a = new SyncEngine({ identity: sA.identity, store: sA, port: 41113 });
  const b = new SyncEngine({ identity: sB.identity, store: sB, port: 41114 });
  trace('A', a); trace('B', b);
  a.start({ discovery: false }); b.start({ discovery: false });

  const reqP = once(b, 'pair-request');
  a.initiatePair({ id: sB.identity.id, address: '127.0.0.1', port: 41114 });
  const req = await reqP;
  console.log('--- pairing');
  req.confirm && req.confirm();
  await once(a, 'paired');
  await wait(500);
  console.log('A peers:', JSON.stringify(a.status().peers));
  console.log('B peers:', JSON.stringify(b.status().peers));

  console.log('--- corrupt B + disconnect');
  sB.removePeer(sA.identity.id);
  const conn = a.byPeer.get(sB.identity.id);
  if (!conn) { console.log('NO CONN'); return; }
  conn.close('test-restart');
  await wait(12000);
  console.log('A peers:', JSON.stringify(a.status().peers));
  console.log('B peers:', JSON.stringify(b.status().peers));
  a.stop(); b.stop();
}

main().catch((e) => { console.error(e); process.exit(1); });
