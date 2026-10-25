'use strict';
// 网络探针：验证本机 LAN 上的 NotifySync 广播流 + TCP 引擎握手
// 只监听不发送，不影响正在运行的电脑端（UDP 广播会同时投递给两个套接字）
const dgram = require('dgram');
const net = require('net');

const seen = new Set();
const s = dgram.createSocket({ type: 'udp4', reuseAddr: true });
s.on('message', (buf, rinfo) => {
  try {
    const p = JSON.parse(buf.toString());
    if (p.type === 'presence') {
      const key = `${p.id}|${p.name}|${p.role}`;
      if (!seen.has(key)) {
        seen.add(key);
        console.log(`[presence] ${p.name} (${p.role}) @ ${rinfo.address}:${p.port} id=${p.id.slice(0, 8)}`);
      }
    }
  } catch (_) {}
});
s.bind(37311, () => { try { s.setBroadcast(true); } catch (_) {} });
console.log('listening UDP 37311 for presence broadcasts (30s)...');

// TCP 握手测试：连本机引擎，发 hello，应能收到对方的 hello
function tcpTest() {
  return new Promise((resolve) => {
    const c = net.connect({ host: '127.0.0.1', port: 37312 });
    let buf = Buffer.alloc(0);
    const hello = Buffer.from(JSON.stringify({
      v: 1, type: 'hello', id: 'probe-0000', name: 'probe', role: 'pc',
      pub: 'MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
      nonce: Buffer.alloc(16).toString('base64'), dialer: true,
    }));
    const fail = (m) => { console.log('[tcp] FAIL:', m); resolve(); };
    c.setTimeout(4000, () => fail('timeout'));
    c.on('error', (e) => fail(e.message));
    c.on('connect', () => {
      const head = Buffer.alloc(4);
      head.writeUInt32BE(hello.length);
      c.write(Buffer.concat([head, hello]));
    });
    c.on('data', (d) => {
      buf = Buffer.concat([buf, d]);
      if (buf.length >= 4) {
        const len = buf.readUInt32BE(0);
        if (buf.length >= 4 + len) {
          const obj = JSON.parse(buf.subarray(4, 4 + len).toString());
          console.log(`[tcp] OK 收到 hello: ${obj.name} (${obj.role}) id=${obj.id.slice(0, 8)}`);
          c.destroy();
          resolve();
        }
      }
    });
  });
}

setTimeout(async () => {
  console.log('--- UDP 监听结束 ---');
  await tcpTest();
  console.log('--- 探针完成 ---');
  process.exit(0);
}, 30000);
