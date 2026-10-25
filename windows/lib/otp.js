'use strict';
// 与 docs/PROTOCOL.md §8 严格对应，Android 端 core/Otp.kt 必须行为一致
const KEYWORDS = /验证码|校验码|动态码|识别码|安全码|确认码|口令|verification|verify|security code|one-time|otp|pin code|code/i;
// 允许数字间夹一个空格（银行短信常见防抓取写法，如 "730 122"），匹配后去空格
const DIGITS = /(?<!\d)(\d(?:[ ]?\d){3,7})(?!\d)/g;
const MAX_DIST = 30;

function extractOtp(title, text) {
  const s = `${title || ''} ${text || ''}`;
  const trimmed = (text || '').trim();

  if (!KEYWORDS.test(s)) {
    return /^\d{4,8}$/.test(trimmed) ? trimmed : null;
  }

  const kw = KEYWORDS.exec(s);
  if (!kw) return null;
  const kwEnd = kw.index + kw[0].length;

  let best = null;
  let m;
  DIGITS.lastIndex = 0;
  while ((m = DIGITS.exec(s)) !== null) {
    const v = m[1].replace(/ /g, '');
    if (v.length < 4 || v.length > 8) continue;
    const dist = m.index - kwEnd;
    if (dist < 0 || dist > MAX_DIST) continue;
    const better =
      best === null ||
      dist < best.dist ||
      (dist === best.dist && inPreferredLen(v) && !inPreferredLen(best.v));
    if (better) best = { v, dist };
  }
  return best ? best.v : null;
}

function inPreferredLen(v) {
  return v.length >= 4 && v.length <= 6;
}

module.exports = { extractOtp };
