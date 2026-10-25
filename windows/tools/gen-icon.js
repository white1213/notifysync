'use strict';
// 纯 Node 生成托盘/窗口图标（无 canvas 依赖）：圆角方形 + 双向同步箭头 + 红点
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

function crc32(buf) {
  let c;
  const table = crc32.table || (crc32.table = (() => {
    const t = [];
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c >>> 0;
    }
    return t;
  })());
  c = 0xffffffff;
  for (const b of buf) c = table[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(td), 0);
  return Buffer.concat([len, td, crc]);
}

function encodePng(size, pixels) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 6; // 8-bit RGBA
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    pixels.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---- 图形判定（坐标均为 0..1）----
function inRoundedRect(x, y, r) {
  if (x < r) {
    if (y < r) return (x - r) ** 2 + (y - r) ** 2 <= r * r;
    if (y > 1 - r) return (x - r) ** 2 + (y - (1 - r)) ** 2 <= r * r;
  }
  if (x > 1 - r) {
    if (y < r) return (x - (1 - r)) ** 2 + (y - r) ** 2 <= r * r;
    if (y > 1 - r) return (x - (1 - r)) ** 2 + (y - (1 - r)) ** 2 <= r * r;
  }
  return true;
}
function inArrow(x, y, { yTop, yBot, x0, x1, dir }) {
  if (y < yTop || y > yBot) return false;
  const head = 0.16, mid = (yTop + yBot) / 2, half = (yBot - yTop) / 2;
  if (dir > 0) { // →：头部在右端
    if (x > x1 - head) return x - (x1 - head) <= (1 - Math.abs(y - mid) / half) * head;
    return x >= x0 && x <= x1 - head;
  }
  if (x < x0 + head) return (x0 + head) - x <= (1 - Math.abs(y - mid) / half) * head;
  return x >= x0 + head && x <= x1;
}

function render(size) {
  const SS = 4; // 超采样抗锯齿
  const px = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const u = (x + (sx + 0.5) / SS) / size;
          const v = (y + (sy + 0.5) / SS) / size;
          if (!inRoundedRect(u, v, 0.22)) continue;
          a++;
          // 背景渐变 #4f8cff → #2f6ae0
          const t = (u + v) / 2;
          let cr = Math.round(0x4f + (0x2f - 0x4f) * t);
          let cg = Math.round(0x8c + (0x6a - 0x8c) * t);
          let cb = Math.round(0xff + (0xe0 - 0xff) * t);
          const white =
            inArrow(u, v, { yTop: 0.30, yBot: 0.42, x0: 0.24, x1: 0.66, dir: 1 }) ||
            inArrow(u, v, { yTop: 0.58, yBot: 0.70, x0: 0.34, x1: 0.76, dir: -1 });
          const dot = (u - 0.76) ** 2 + (v - 0.24) ** 2 <= 0.115 ** 2;
          if (white) { cr = 255; cg = 255; cb = 255; }
          if (dot) { cr = 0xff; cg = 0x5a; cb = 0x5a; }
          r += cr; g += cg; b += cb;
        }
      }
      const n = SS * SS;
      const i = (y * size + x) * 4;
      const k = a || 1;
      px[i] = Math.round(r / k);
      px[i + 1] = Math.round(g / k);
      px[i + 2] = Math.round(b / k);
      px[i + 3] = Math.round((a / n) * 255);
    }
  }
  return encodePng(size, px);
}

const outDir = path.join(__dirname, '..', 'assets');
fs.mkdirSync(outDir, { recursive: true });
for (const s of [16, 32, 48, 256]) {
  fs.writeFileSync(path.join(outDir, `icon-${s}.png`), render(s));
  console.log(`icon-${s}.png written`);
}
