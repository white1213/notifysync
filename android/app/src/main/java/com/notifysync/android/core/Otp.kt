package com.notifysync.android.core

/**
 * 验证码提取，与 docs/PROTOCOL.md §8 和 Windows 端 lib/otp.js 行为一致。
 * 一致性由 docs/test-vectors.json 的 otpCases + OtpTest 保证。
 */
object Otp {

    private val KEYWORDS = Regex(
        """验证码|校验码|动态码|识别码|安全码|确认码|口令|verification|verify|security code|one-time|otp|pin code|code""",
        RegexOption.IGNORE_CASE,
    )
    private val DIGITS = Regex("""(?<!\d)(\d(?:[ ]?\d){3,7})(?!\d)""")
    private val PURE_CODE = Regex("""^\d{4,8}$""")
    private const val MAX_DIST = 30

    fun extract(title: String?, text: String?): String? {
        val s = "${title ?: ""} ${text ?: ""}"
        val trimmed = (text ?: "").trim()

        if (!KEYWORDS.containsMatchIn(s)) {
            return if (PURE_CODE.matches(trimmed)) trimmed else null
        }

        val kw = KEYWORDS.find(s) ?: return null
        val kwEnd = kw.range.first + kw.value.length

        var best: Pair<String, Int>? = null
        for (m in DIGITS.findAll(s)) {
            val v = m.groupValues[1].replace(" ", "")
            if (v.length !in 4..8) continue
            val dist = m.range.first - kwEnd
            if (dist < 0 || dist > MAX_DIST) continue
            val cur = best
            if (cur == null || dist < cur.second ||
                (dist == cur.second && v.length in 4..6 && cur.first.length !in 4..6)
            ) {
                best = v to dist
            }
        }
        return best?.first
    }
}
