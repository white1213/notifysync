'use strict';
// 模拟手机向本机 PC 引擎发起配对（协议 §5.4 发起方），验证 PC 端弹窗与握手
const net = require('net');
const crypto = require('crypto');

const PORT = 37312;

const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const pubB64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64');
const myId = crypto.randomUUID();
const nonce = crypto.randomBytes(16).toString('base64');

const hello = Buffer.from(JSON.stringify({
  v: 1, type: 'hello', id: myId, name: 'VirtualPhone-测试', role: 'phone',
  pub: pubB64, nonce, dialer: true,
}));

const sock = net.connect({ host: '127.0.0.1', port: PORT });
let buf = Buffer.alloc(0);
let done = false;

const finish = (msg) => {
  if (done) return;
  done = true;
  console.log('[probe]', msg);
  setTimeout(() => process.exit(0), 500);
};

sock.setTimeout(200000, () => finish('等待确认超时（200 秒没人点确认）'));
sock.on('error', (e) => finish(`连接错误: ${e.message}`));
sock.on('close', () => finish('连接被 PC 关闭'));

sock.on('connect', () => {
  console.log('[probe] 已连上 PC，发送 hello + pair_request …');
  const frames = Buffer.concat([
    frame(hello),
    frame(Buffer.from(JSON.stringify({ type: 'pair_request' }))),
  ]);
  sock.write(frames);
});

sock.on('data', (d) => {
  buf = Buffer.concat([buf, d]);
  for (;;) {
    if (buf.length < 4) return;
    const len = buf.readUInt32BE(0);
    if (buf.length < 4 + len) return;
    const payload = buf.subarray(4, 4 + len);
    buf = buf.subarray(4 + len);
    const obj = tryJson(payload);
    if (!obj) continue; // 加密帧等，忽略
    if (obj.type === 'pair_confirm') {
      finish('PC 已确认配对！握手成功（如果屏幕上没弹窗，说明渲染层有问题）');
    } else if (obj.type === 'pair_reject') {
      finish('PC 拒绝了配对');
    } else if (obj.type === 'deny') {
      finish(`PC 拒绝接入: ${obj.reason}`);
    }
  }
});

function frame(payload) {
  const head = Buffer.alloc(4);
  head.writeUInt32BE(payload.length, 0);
  return Buffer.concat([head, payload]);
}

function tryJson(b) {
  try { return JSON.parse(b.toString()); } catch (_) { return null; }
}
