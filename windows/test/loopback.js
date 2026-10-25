'use strict';
// 回环集成测试：node test/loopback.js
// 用真实 TCP 套接字驱动两个 SyncEngine，验证配对/加密/同步/自愈全链路。
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');
const C = require('../lib/crypto');
const { extractOtp } = require('../lib/otp');
const { SyncEngine } = require('../lib/engine');
const { Store } = require('../lib/store');

const V = JSON.parse(fs.readFileSync(path.join(__dirname, '..', '..', 'docs', 'test-vectors.json'), 'utf8'));

function tmpStore(name, role) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `notifysync-${name}-`));
  const s = new Store(dir);
  s.identity.role = role;
  return s;
}

function engineFor(store, port) {
  return new SyncEngine({ identity: store.identity, store, port });
}

function wait(ms) { return new Promise((r) => setTimeout(r, ms)); }

function once(emitter, event, timeoutMs = 8000) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`timeout waiting for ${event}`)), timeoutMs);
    emitter.once(event, (arg) => { clearTimeout(t); resolve(arg); });
  });
}

async function testCryptoVectors() {
  const sharedX = C.ecdhSharedX(crypto.createPrivateKey(V.keyA.privPem), V.keyB.pubB64);
  assert.strictEqual(sharedX.toString('hex'), V.sharedX, 'sharedX mismatch');
  assert.strictEqual(C.verificationCode(sharedX), V.verCode, 'verCode mismatch');
  const sKey = C.sessionKey(sharedX, V.nonceA, V.nonceB);
  assert.strictEqual(sKey.toString('hex'), V.sessionKey, 'sessionKey mismatch');

  const frame = Buffer.concat([Buffer.from(V.frameNonce, 'hex'), Buffer.from(V.frameCiphertext, 'hex')]);
  const plain = C.decrypt(Buffer.from(V.sessionKey, 'hex'), frame);
  assert.strictEqual(plain.toString('utf8'), V.framePlain, 'frame decrypt mismatch');

  // 加解密往返
  const key = crypto.randomBytes(32);
  const ct = C.encrypt(key, Buffer.from('hello 通知', 'utf8'));
  assert.strictEqual(C.decrypt(key, ct).toString('utf8'), 'hello 通知');
  console.log('  crypto vectors: OK');
}

async function testOtp() {
  for (const c of V.otpCases) {
    const got = extractOtp(c.title, c.text);
    assert.strictEqual(got, c.expect, `otp case failed: ${c.title}|${c.text} => ${got} (want ${c.expect})`);
  }
  console.log(`  otp cases (${V.otpCases.length}): OK`);
}

// 配对并等待双方 online
async function pairAndConnect(a, b, bPort) {
  const bPaired = once(b, 'pair-request');
  a.initiatePair({ id: b.identity.id, address: '127.0.0.1', port: bPort });
  const req = await bPaired;
  const aPaired = once(a, 'paired');
  req.confirm();
  await aPaired;
  await once(b, 'peer-online', 4000).catch(() => {}); // A 侧 pair_done 会触发
  // 等双方 status 都 online
  for (let i = 0; i < 50; i++) {
    const sa = a.status().peers.find((p) => p.id === b.identity.id);
    const sb = b.status().peers.find((p) => p.id === a.identity.id);
    if (sa && sa.online && sb && sb.online) return;
    await wait(100);
  }
  throw new Error('peers not online after pairing');
}

async function testPairAndSync() {
  const storeA = tmpStore('a', 'phone');
  const storeB = tmpStore('b', 'pc');
  const a = engineFor(storeA, 41101);
  const b = engineFor(storeB, 41102);
  a.start({ discovery: false });
  b.start({ discovery: false });

  await pairAndConnect(a, b, 41102);

  // A -> B 通知
  const gotNotif = once(b, 'notif');
  const sent = a.sendNotif({
    pkg: 'com.tencent.mm', app: '微信', title: '妈妈',
    text: '您的验证码是883211，请勿泄露。', convTitle: '家人群',
    ts: Date.now(), otp: extractOtp('微信支付', '您的验证码是883211，请勿泄露。'),
  });
  const n1 = await gotNotif;
  assert.strictEqual(n1.originId, a.identity.id);
  assert.strictEqual(n1.otp, '883211');
  assert.strictEqual(n1.title, '妈妈');
  assert(storeB.addNotification(n1), 'store should insert');
  assert(!storeB.addNotification(n1), 'store should dedup');

  // B -> A 通知（PC 也可能发，协议对称）
  const got2 = once(a, 'notif');
  b.sendNotif({ pkg: 'test.app', app: 'Test', title: 't', text: 'hello', ts: Date.now() });
  const n2 = await got2;
  assert.strictEqual(n2.originId, b.identity.id);
  assert.strictEqual(n2.text, 'hello');

  // 双向解配：A 主动 unpair，B 收到 remote 事件
  const bUnpaired = once(b, 'peer-unpaired');
  a.unpair(b.identity.id);
  const ev = await bUnpaired;
  assert.strictEqual(ev.peerId, a.identity.id);
  assert.strictEqual(ev.remote, true);
  assert.strictEqual(storeA.peers.length, 0);
  assert.strictEqual(storeB.peers.length, 0);

  a.stop(); b.stop();
  console.log('  pair + bidirectional sync + unpair: OK');
}

async function testOneSidedHeal() {
  // 模拟 B 单方面丢失配对记录：A 仍认为已配对并重连，B 应 deny，A 应自动解除
  const storeA = tmpStore('heal-a', 'phone');
  const storeB = tmpStore('heal-b', 'phone');
  const a = engineFor(storeA, 41103);
  const b = engineFor(storeB, 41104);
  a.start({ discovery: false });
  b.start({ discovery: false });
  await pairAndConnect(a, b, 41104);

  // 模拟 B 侧记录损坏（绕过 unpair 报文直接删），随后连接断开
  storeB.removePeer(a.identity.id);
  assert.strictEqual(storeA.peers.length, 1);
  const liveConn = a.byPeer.get(b.identity.id);
  assert(liveConn, 'A should have a live conn to B');
  liveConn.close('test-restart');

  const aHealed = once(a, 'peer-unpaired', 15000);
  const healed = await aHealed;
  assert.strictEqual(healed.peerId, b.identity.id);
  assert.strictEqual(storeA.peers.length, 0, 'A should have healed its stale pairing');
  a.stop(); b.stop();
  console.log('  one-sided stale pairing self-heal: OK');
}

async function testRePairHeal() {
  // A 已配对 B、B 记录丢失：A 重连后 B 仍在……反向场景：
  // B 有 A 的记录，A 发起 pair_request（A 记录丢失），B 应直接确认
  const storeA = tmpStore('re-a', 'phone');
  const storeB = tmpStore('re-b', 'pc');
  const a = engineFor(storeA, 41105);
  const b = engineFor(storeB, 41106);
  a.start({ discovery: false });
  b.start({ discovery: false });
  await pairAndConnect(a, b, 41106);

  // A 丢失记录后重新发起配对
  storeA.removePeer(b.identity.id);
  const aPaired = once(a, 'paired');
  a.initiatePair({ id: b.identity.id, address: '127.0.0.1', port: 41106 });
  await aPaired;
  assert(storeA.getPeer(b.identity.id), 'A should have re-pinned B');
  for (let i = 0; i < 50; i++) {
    const sb = b.status().peers.find((p) => p.id === a.identity.id);
    if (sb && sb.online) break;
    await wait(100);
  }
  assert(b.status().peers.find((p) => p.id === a.identity.id).online, 'B should be online with A');
  a.stop(); b.stop();
  console.log('  re-pair heal (pinned side auto-confirms): OK');
}

async function testDualDialTieBreak() {
  // 回归测试：双方同时互拨时，重复连接仲裁必须让两端保留同一条连接。
  // 旧 bug：仲裁时新连接 peerId 为空，两端保留不同连接并互相绞杀（4ms 死亡、3s 循环）。
  const storeA = tmpStore('dd-a', 'phone');
  const storeB = tmpStore('dd-b', 'pc');
  const a = engineFor(storeA, 41109);
  const b = engineFor(storeB, 41110);
  a.start({ discovery: false });
  b.start({ discovery: false });
  await pairAndConnect(a, b, 41110);

  const connA = a.byPeer.get(storeB.identity.id);
  const connB = b.byPeer.get(storeA.identity.id);
  connA.close('test');
  connB.close('test');
  await wait(50);
  // 双向同时拨号
  a.dial('127.0.0.1', 41110, { kind: 'reconnect', peerId: storeB.identity.id });
  b.dial('127.0.0.1', 41109, { kind: 'reconnect', peerId: storeA.identity.id });

  const encryptedOf = (e) => [...e.byPeer.values()].filter((c) => c.state === 'encrypted');
  let stable = false;
  for (let i = 0; i < 100; i++) {
    if (encryptedOf(a).length === 1 && encryptedOf(b).length === 1) { stable = true; break; }
    await wait(100);
  }
  assert.ok(stable, 'dual-dial: 10 秒内未收敛到单连接');

  await wait(8000); // 超过旧 bug 的 3 秒死亡循环多个周期
  assert.strictEqual(encryptedOf(a).length, 1, '8 秒后 A 应仍恰好保持 1 条加密连接');
  assert.strictEqual(encryptedOf(b).length, 1, '8 秒后 B 应仍恰好保持 1 条加密连接');

  const got = once(b, 'notif');
  a.sendNotif({ pkg: 'dd.test', app: 'DD', title: 't', text: 'dual-dial ok', ts: Date.now() });
  const n = await got;
  assert.strictEqual(n.text, 'dual-dial ok');

  a.stop(); b.stop();
  console.log('  dual-dial tie-break (regression): OK');
}

async function testPairSurvivesDupConn() {
  // 回归测试：单侧残留配对 + 双方同时互拨时，配对握手必须迁移到幸存连接完成。
  // 旧 bug：仲裁杀掉的恰是承载握手的那条连接，配对永远超时。
  const storeC = tmpStore('pd-c', 'phone');
  const storeD = tmpStore('pd-d', 'phone');
  const c = engineFor(storeC, 41113);
  const d = engineFor(storeD, 41114);
  c.start({ discovery: false });
  d.start({ discovery: false });
  await pairAndConnect(c, d, 41114);

  // 模拟单侧残留：C 侧删除记录（D 仍 pin C）
  storeC.removePeer(storeD.identity.id);
  const liveC = c.byPeer.get(storeD.identity.id);
  const liveD = d.byPeer.get(storeC.identity.id);
  liveC.close('test');
  liveD.close('test');
  await wait(50);

  // C 发起重配对，同时 D 因 presence 触发回拨（双拨并发）
  const cPaired = once(c, 'paired', 15000);
  c.initiatePair({ id: storeD.identity.id, address: '127.0.0.1', port: 41114 });
  d.dial('127.0.0.1', 41113, { kind: 'reconnect', peerId: storeC.identity.id });
  await cPaired;

  const got = once(d, 'notif');
  c.sendNotif({ pkg: 'pd.test', app: 'PD', title: 't', text: 'pair-dup ok', ts: Date.now() });
  const n = await got;
  assert.strictEqual(n.text, 'pair-dup ok');

  c.stop(); d.stop();
  console.log('  pairing survives dup-conn (regression): OK');
}

async function testPeerIntroduction() {
  // 熟人引荐：A 与 B、C 分别配对（枢纽），A 广播 peers 后，
  // B 应能发现 C 并直接 TCP 配对成功——完全绕开 UDP 发现
  const storeA = tmpStore('pi-a', 'pc');
  const storeB = tmpStore('pi-b', 'phone');
  const storeC = tmpStore('pi-c', 'phone');
  const a = engineFor(storeA, 41115);
  const b = engineFor(storeB, 41116);
  const c = engineFor(storeC, 41117);
  a.start({ discovery: false });
  b.start({ discovery: false }); // B/C 关闭 UDP 发现，确保走的是引荐路径
  c.start({ discovery: false });
  await pairAndConnect(a, b, 41116);
  await pairAndConnect(a, c, 41117);

  a._sendPeers(); // 手动触发一次引荐（等价于 20s 定时器）

  let found = null;
  for (let i = 0; i < 50; i++) {
    found = b.status().discovered.find((d) => d.id === storeC.identity.id);
    if (found) break;
    await wait(100);
  }
  assert.ok(found, 'B 应通过引荐发现 C');
  assert.strictEqual(found.address, '127.0.0.1');

  const reqP = once(c, 'pair-request');
  b.initiatePair(found);
  const req = await reqP;
  const bPaired = once(b, 'paired', 15000);
  req.confirm();
  await bPaired;

  const got = once(c, 'notif');
  b.sendNotif({ pkg: 'pi.test', app: 'PI', title: 't', text: 'intro ok', ts: Date.now() });
  const n = await got;
  assert.strictEqual(n.text, 'intro ok');

  a.stop(); b.stop(); c.stop();
  console.log('  peer introduction (regression): OK');
}

async function testNotifRelay() {
  // 中转：B—A—C（B 与 C 互不直连，模拟路由器 AP 隔离），
  // B 的通知应经 A 转发到达 C；转发不会成环
  const storeA = tmpStore('rl-a', 'pc');
  const storeB = tmpStore('rl-b', 'phone');
  const storeC = tmpStore('rl-c', 'phone');
  const a = engineFor(storeA, 41119);
  const b = engineFor(storeB, 41120);
  const c = engineFor(storeC, 41121);
  a.start({ discovery: false });
  b.start({ discovery: false });
  c.start({ discovery: false });
  await pairAndConnect(a, b, 41120);
  await pairAndConnect(a, c, 41121);

  const gotC = once(c, 'notif', 10000);
  b.sendNotif({ pkg: 'relay.test', app: 'RELAY', title: 't', text: 'relayed ok', ts: Date.now() });
  const n = await gotC;
  assert.strictEqual(n.text, 'relayed ok');
  assert.strictEqual(n.originId, storeB.identity.id);

  // 转发不应产生风暴：稍等后连接应全部健在
  await wait(2000);
  assert.strictEqual(a.status().peers.filter((p) => p.online).length, 2, '中转者应保持 2 条在线');

  a.stop(); b.stop(); c.stop();
  console.log('  notif relay via hub (regression): OK');
}

async function testClipboardSync() {
  // B—A—C：B 复制的文本应到达 A 并经 A 中转到达 C；不会回环
  const storeA = tmpStore('cb-a', 'pc');
  const storeB = tmpStore('cb-b', 'phone');
  const storeC = tmpStore('cb-c', 'phone');
  const a = engineFor(storeA, 41123);
  const b = engineFor(storeB, 41124);
  const c = engineFor(storeC, 41125);
  a.start({ discovery: false });
  b.start({ discovery: false });
  c.start({ discovery: false });
  await pairAndConnect(a, b, 41124);
  await pairAndConnect(a, c, 41125);

  const gotA = once(a, 'clip', 10000);
  const gotC = once(c, 'clip', 10000);
  b.sendClip('剪贴板测试 clipboard-ok 123');
  const na = await gotA;
  const nc = await gotC;
  assert.strictEqual(na.text, '剪贴板测试 clipboard-ok 123');
  assert.strictEqual(nc.text, '剪贴板测试 clipboard-ok 123');
  assert.ok(na.hash && na.hash.length === 32, 'hash 应为 32 字符 hex');

  // B 不应收到自己的回声；连接保持健在
  await wait(2000);
  assert.strictEqual(a.status().peers.filter((p) => p.online).length, 2);

  a.stop(); b.stop(); c.stop();
  console.log('  clipboard sync + relay (regression): OK');
}

async function main() {
  console.log('crypto vectors:');
  await testCryptoVectors();
  console.log('otp extraction:');
  await testOtp();
  console.log('engine loopback:');
  await testPairAndSync();
  await testOneSidedHeal();
  await testRePairHeal();
  await testDualDialTieBreak();
  await testPairSurvivesDupConn();
  await testPeerIntroduction();
  await testNotifRelay();
  await testClipboardSync();
  console.log('\nALL TESTS PASSED');
}

main().catch((e) => { console.error('TEST FAILED:', e); process.exit(1); });
