'use strict';
// 与 docs/PROTOCOL.md §5.2 严格对应，Android 端 core/Crypto.kt 必须逐字节一致
const crypto = require('crypto');

const CURVE = 'prime256v1';

function generateIdentity(role, name) {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: CURVE });
  return {
    id: crypto.randomUUID(),
    name: name || 'Windows-PC',
    role: role || 'pc',
    pub: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
    priv: privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64'),
  };
}

function loadKeyPair(identity) {
  return {
    priv: crypto.createPrivateKey({
      key: Buffer.from(identity.priv, 'base64'), format: 'der', type: 'pkcs8',
    }),
    pub: crypto.createPublicKey({
      key: Buffer.from(identity.pub, 'base64'), format: 'der', type: 'spki',
    }),
  };
}

// ECDH 输出可能是 31/32 字节（前导零被截断），两端统一归一化为 32 字节
function normalizeX(buf) {
  const b = Buffer.from(buf);
  if (b.length === 32) return b;
  if (b.length > 32) return b.subarray(0, 32);
  return Buffer.concat([Buffer.alloc(32 - b.length), b]);
}

function ecdhSharedX(myPrivObj, peerPubB64) {
  const peerPub = crypto.createPublicKey({
    key: Buffer.from(peerPubB64, 'base64'), format: 'der', type: 'spki',
  });
  return normalizeX(crypto.diffieHellman({ privateKey: myPrivObj, publicKey: peerPub }));
}

function sha256Concat(...bufs) {
  const h = crypto.createHash('sha256');
  for (const b of bufs) h.update(b);
  return h.digest();
}

const PAIR_LABEL = Buffer.from('notifysync-pair-v1');
const SESSION_LABEL = Buffer.from('notifysync-session-v1');

function verificationCode(sharedX) {
  return sha256Concat(sharedX, PAIR_LABEL).subarray(0, 2).toString('hex').toUpperCase();
}

function sessionKey(sharedX, nonceA, nonceB) {
  const nA = Buffer.from(nonceA, 'base64');
  const nB = Buffer.from(nonceB, 'base64');
  const lo = Buffer.compare(nA, nB) <= 0 ? nA : nB;
  const hi = Buffer.compare(nA, nB) <= 0 ? nB : nA;
  return sha256Concat(sharedX, SESSION_LABEL, lo, hi);
}

function encrypt(key, plainBuf) {
  const nonce = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key, nonce);
  const ct = Buffer.concat([c.update(plainBuf), c.final(), c.getAuthTag()]);
  return Buffer.concat([nonce, ct]);
}

function decrypt(key, frameBuf) {
  if (frameBuf.length < 12 + 16) throw new Error('frame too short');
  const nonce = frameBuf.subarray(0, 12);
  const tag = frameBuf.subarray(frameBuf.length - 16);
  const ct = frameBuf.subarray(12, frameBuf.length - 16);
  const d = crypto.createDecipheriv('aes-256-gcm', key, nonce);
  d.setAuthTag(tag);
  return Buffer.concat([d.update(ct), d.final()]);
}

// 固定 nonce 的加密，仅用于生成测试向量
function encryptWithNonce(key, plainBuf, nonceBuf) {
  const c = crypto.createCipheriv('aes-256-gcm', key, nonceBuf);
  return Buffer.concat([c.update(plainBuf), c.final(), c.getAuthTag()]);
}

module.exports = {
  generateIdentity, loadKeyPair, ecdhSharedX, normalizeX,
  verificationCode, sessionKey, encrypt, decrypt, encryptWithNonce,
};
