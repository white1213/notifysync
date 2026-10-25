package com.notifysync.android.core

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 协议加密原语，与 docs/PROTOCOL.md §5.2 和 Windows 端 lib/crypto.js 逐字节一致。
 * 一致性由 docs/test-vectors.json + CryptoTest.kt 保证。
 */
object Crypto {

    fun generateKeyPairB64(): Pair<String, String> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val pub = Base64.getEncoder().encodeToString(kp.public.encoded)
        val priv = Base64.getEncoder().encodeToString(kp.private.encoded)
        return pub to priv
    }

    /** ECDH 共享秘密，归一化到 32 字节（对端实现可能截断前导零） */
    fun ecdhSharedX(privB64: String, peerPubB64: String): ByteArray {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)))
        val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(peerPubB64)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return normalizeX(ka.generateSecret())
    }

    fun normalizeX(b: ByteArray): ByteArray = when {
        b.size == 32 -> b
        b.size > 32 -> b.copyOf(32)
        else -> ByteArray(32) { i -> if (i >= 32 - b.size) b[i - (32 - b.size)] else 0 }
    }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    private val PAIR_LABEL = "notifysync-pair-v1".toByteArray()
    private val SESSION_LABEL = "notifysync-session-v1".toByteArray()

    /** 4 位大写十六进制配对验证码 */
    fun verificationCode(sharedX: ByteArray): String {
        val h = sha256(sharedX, PAIR_LABEL)
        return String.format("%02X%02X", h[0], h[1])
    }

    fun sessionKey(sharedX: ByteArray, nonceAB64: String, nonceBB64: String): ByteArray {
        val nA = Base64.getDecoder().decode(nonceAB64)
        val nB = Base64.getDecoder().decode(nonceBB64)
        val (lo, hi) = if (compareBytes(nA, nB) <= 0) nA to nB else nB to nA
        return sha256(sharedX, SESSION_LABEL, lo, hi)
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            // 必须按无符号比较，与 Node Buffer.compare 一致；Kotlin Byte 是有符号的
            val c = (a[i].toInt() and 0xff).compareTo(b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return a.size - b.size
    }

    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** 剪贴板同步用的内容哈希：sha256 前 16 字节 hex（与 Windows 端一致） */
    fun clipHash(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.take(16).joinToString("") { String.format("%02x", it) }
    }

    /** [12B nonce][AES-256-GCM 密文+tag]，无 AAD */
    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val nonce = randomBytes(12)
        val ct = encryptWithNonce(key, plain, nonce)
        return nonce + ct
    }

    fun encryptWithNonce(key: ByteArray, plain: ByteArray, nonce: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return c.doFinal(plain)
    }

    fun decrypt(key: ByteArray, frame: ByteArray): ByteArray {
        require(frame.size >= 12 + 16) { "frame too short" }
        val nonce = frame.copyOfRange(0, 12)
        val ct = frame.copyOfRange(12, frame.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return c.doFinal(ct)
    }
}
