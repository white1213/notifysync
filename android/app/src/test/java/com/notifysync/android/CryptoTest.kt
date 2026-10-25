package com.notifysync.android

import com.notifysync.android.core.Crypto
import com.notifysync.android.core.Otp
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

/**
 * 跨端一致性测试：向量来自 docs/test-vectors.json（由 Windows 端 node:crypto 生成）。
 * 若重新生成向量（npm run gen:vectors），需同步更新本文件中的常量。
 */
class CryptoTest {

    private val privAB64 =
        "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgZfv+oAF34+DqAngJBwQN1Lye4M+fmyE7mNY0XVB/ybmhRANCAATvPkfpRNg4stjWsOxtQJFA01udhdF5dL3QU3VMW7bX+5a7YnUDjYAq4lMdrzUG/NiR01ewkGG99gHvEDqUM9Sj"
    private val pubBB64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEGzuat8fS2f4hzUR30a4ZOi++ZH/ld+mJTqocwA5FyFElk2fHMeHtYkRVmriFSavwCaXk+HL4eKAbvV4Sg3l+qw=="
    private val nonceA = "AAECAwQFBgcICQoLDA0ODw=="
    private val nonceB = "8ODQwLCgkIBwYFBAMCAQAA=="
    private val sharedXHex = "3bd5a647d958f58fa8299d6e0e1cea04345e953b37f78e8e1adb4376324c8b86"
    private val sessionKeyHex = "e7703888d3058dd8d0b7312cea07bb2891c9317f839222731fd2d7be5683588e"
    private val frameNonceHex = "000102030405060708090a0b"
    private val frameCtHex =
        "aec46a06991993db01141ba54bf17b3d8571ad87d078e56ede75601cf99d35607c5ee6e34d9541deec3f9527d149ba993064"
    private val framePlain = """{"type":"ping","ts":1730000000000}"""

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun hexStr(b: ByteArray): String =
        b.joinToString("") { String.format("%02x", it) }

    @Test
    fun sharedXMatchesWindowsImplementation() {
        val sharedX = Crypto.ecdhSharedX(privAB64, pubBB64)
        assertEquals(sharedXHex, hexStr(sharedX))
    }

    @Test
    fun verificationCodeMatches() {
        assertEquals("6D85", Crypto.verificationCode(hex(sharedXHex)))
    }

    @Test
    fun sessionKeyMatches() {
        val key = Crypto.sessionKey(hex(sharedXHex), nonceA, nonceB)
        assertEquals(sessionKeyHex, hexStr(key))
        // nonce 顺序无关
        val keyReversed = Crypto.sessionKey(hex(sharedXHex), nonceB, nonceA)
        assertEquals(sessionKeyHex, hexStr(keyReversed))
    }

    @Test
    fun frameDecryptMatches() {
        val frame = hex(frameNonceHex) + hex(frameCtHex)
        val plain = Crypto.decrypt(hex(sessionKeyHex), frame)
        assertEquals(framePlain, String(plain, Charsets.UTF_8))
    }

    @Test
    fun encryptRoundTrip() {
        val key = Crypto.randomBytes(32)
        val ct = Crypto.encrypt(key, "你好 notifysync 测试".toByteArray(Charsets.UTF_8))
        assertEquals("你好 notifysync 测试", String(Crypto.decrypt(key, ct), Charsets.UTF_8))
    }

    @Test
    fun otpExtractionMatchesWindowsImplementation() {
        val cases = listOf(
            Triple("微信支付", "您的验证码是883211，请勿泄露给他人。", "883211"),
            Triple("95588", "您在工商银行的动态码为 730 122，5分钟内有效", "730122"),
            Triple("", "【某商城】订单20241025已支付，校验码666888", "666888"),
            Triple("", "123456", "123456"),
            Triple("Gmail", "Your verification code is 458912", "458912"),
            Triple("GitHub", "Your one-time password is 729 441. It expires in 10 minutes.", "729441"),
            Triple("天气", "今天气温 23 度，适合出行", null),
            Triple("外卖", "订单 2025001 已送达，评分领红包 888", null),
            Triple("", "您的包裹已放入丰巢柜，取件码 A3F2", null),
            Triple("银行", "尾号8831的卡消费100.00元", null),
        )
        for ((title, text, expect) in cases) {
            assertEquals("case: $title | $text", expect, Otp.extract(title, text))
        }
    }

    @Test
    fun normalizeXPadsLeadingZeros() {
        val padded = Crypto.normalizeX(byteArrayOf(1, 2, 3))
        assertEquals(32, padded.size)
        assertEquals(0.toByte(), padded[0])
        assertEquals(1.toByte(), padded[29]) // 左侧补 29 个零后原数据从 29 号位开始
        assertEquals(3.toByte(), padded[31])
        val b64 = Base64.getEncoder().encodeToString(padded)
        assertEquals(44, b64.length) // 32 字节 → 44 字符 base64
    }
}
