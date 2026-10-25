'use strict';
// 生成 docs/test-vectors.json（一次即可，结果提交进仓库，供两端单元测试使用）
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const C = require('../lib/crypto');

function genKey() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
  return {
    privPem: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString().trim(),
    pubPem: publicKey.export({ type: 'spki', format: 'pem' }).toString().trim(),
    privB64: privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64'),
    pubB64: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
  };
}

const keyA = genKey();
const keyB = genKey();

const nonceA = Buffer.from('000102030405060708090a0b0c0d0e0f', 'hex');
const nonceB = Buffer.from('f0e0d0c0b0a090807060504030201000', 'hex');

const privA = crypto.createPrivateKey(keyA.privPem);
const pubB = crypto.createPublicKey(keyB.pubPem);
const sharedX = C.normalizeX(crypto.diffieHellman({ privateKey: privA, publicKey: pubB }));

const verCode = C.verificationCode(sharedX);
const sKey = C.sessionKey(sharedX, nonceA.toString('base64'), nonceB.toString('base64'));

const framePlain = '{"type":"ping","ts":1730000000000}';
const frameNonce = Buffer.from('000102030405060708090a0b', 'hex');
const ct = C.encryptWithNonce(sKey, Buffer.from(framePlain, 'utf8'), frameNonce);

const vectors = {
  note: 'NotifySync 跨端一致性测试向量。Android 端 CryptoTest.kt 与 Windows 端 test/loopback.js 必须全部通过。',
  keyA: { privPem: keyA.privPem, pubPem: keyA.pubPem, privB64: keyA.privB64, pubB64: keyA.pubB64 },
  keyB: { privPem: keyB.privPem, pubPem: keyB.pubPem, privB64: keyB.privB64, pubB64: keyB.pubB64 },
  nonceA: nonceA.toString('base64'),
  nonceB: nonceB.toString('base64'),
  sharedX: sharedX.toString('hex'),
  verCode,
  sessionKey: sKey.toString('hex'),
  framePlain,
  frameNonce: frameNonce.toString('hex'),
  frameCiphertext: ct.toString('hex'),
  otpCases: [
    { title: '微信支付', text: '您的验证码是883211，请勿泄露给他人。', expect: '883211' },
    { title: '95588', text: '您在工商银行的动态码为 730 122，5分钟内有效', expect: '730122' },
    { title: '', text: '【某商城】订单20241025已支付，校验码666888', expect: '666888' },
    { title: '', text: '123456', expect: '123456' },
    { title: 'Gmail', text: 'Your verification code is 458912', expect: '458912' },
    { title: 'GitHub', text: 'Your one-time password is 729 441. It expires in 10 minutes.', expect: '729441' },
    { title: '天气', text: '今天气温 23 度，适合出行', expect: null },
    { title: '外卖', text: '订单 2025001 已送达，评分领红包 888', expect: null },
    { title: '', text: '您的包裹已放入丰巢柜，取件码 A3F2', expect: null },
    { title: '银行', text: '尾号8831的卡消费100.00元', expect: null },
  ],
};

const out = path.join(__dirname, '..', '..', 'docs', 'test-vectors.json');
fs.writeFileSync(out, JSON.stringify(vectors, null, 2) + '\n');
console.log('written:', out);
console.log('verCode:', verCode, 'sessionKey:', sKey.toString('hex'));
