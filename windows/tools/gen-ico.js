'use strict';
// 把 assets 里的 PNG 打包成 Windows ICO（ICO 支持直接内嵌 PNG 数据）
const fs = require('fs');
const path = require('path');

const sizes = [16, 32, 48, 256];
const images = sizes.map((s) => ({
  size: s,
  data: fs.readFileSync(path.join(__dirname, '..', 'assets', `icon-${s}.png`)),
}));

const header = Buffer.alloc(6);
header.writeUInt16LE(0, 0);
header.writeUInt16LE(1, 2); // icon
header.writeUInt16LE(images.length, 4);

const entries = [];
let offset = 6 + images.length * 16;
for (const img of images) {
  const e = Buffer.alloc(16);
  e[0] = img.size === 256 ? 0 : img.size; // width
  e[1] = img.size === 256 ? 0 : img.size; // height
  e[2] = 0; // colors
  e[3] = 0;
  e.writeUInt16LE(1, 4);   // planes
  e.writeUInt16LE(32, 6);  // bitcount
  e.writeUInt32LE(img.data.length, 8);
  e.writeUInt32LE(offset, 12);
  offset += img.data.length;
  entries.push(e);
}

const outDir = path.join(__dirname, '..', 'build');
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'icon.ico'), Buffer.concat([header, ...entries, ...images.map((i) => i.data)]));
console.log('build/icon.ico written,', images.length, 'entries');
