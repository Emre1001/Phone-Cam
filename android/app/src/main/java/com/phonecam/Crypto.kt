package com.phonecam

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Same scheme as pc/src/crypto.rs: ECDH P-256, ECDSA-signed transcript, HKDF-SHA256, AES-256-GCM with counter nonces. */
object Crypto {
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    /** Uncompressed SEC1 point: 0x04 || X || Y. */
    fun encode(pub: ECPublicKey): ByteArray = byteArrayOf(4) + fixed32(pub.w.affineX) + fixed32(pub.w.affineY)

    fun decode(b: ByteArray, params: ECParameterSpec): PublicKey {
        require(b.size == 65 && b[0] == 4.toByte()) { "bad point" }
        val p = ECPoint(BigInteger(1, b.copyOfRange(1, 33)), BigInteger(1, b.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(p, params))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

    /** HKDF-SHA256 for one 32-byte block. */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: String): ByteArray = hmac(hmac(salt, ikm), info.toByteArray() + byteArrayOf(1))

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** "AB12 CD34 EF56 7890", identical to the PC app. */
    fun fingerprint(pub: ByteArray): String =
        sha256(pub).copyOfRange(0, 8).toList().chunked(2).joinToString(" ") { "%02X%02X".format(it[0], it[1]) }

    private fun nonce(ctr: Long) = ByteArray(12).also { n -> for (i in 0 until 8) n[11 - i] = (ctr ushr (8 * i)).toByte() }

    class Sealer(key: ByteArray) {
        private val k = SecretKeySpec(key, "AES")
        private val c = Cipher.getInstance("AES/GCM/NoPadding")
        private var ctr = 0L
        fun seal(plain: ByteArray): ByteArray {
            c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, nonce(ctr++)))
            return c.doFinal(plain)
        }
    }

    class Opener(key: ByteArray) {
        private val k = SecretKeySpec(key, "AES")
        private val c = Cipher.getInstance("AES/GCM/NoPadding")
        private var ctr = 0L
        fun open(ct: ByteArray): ByteArray {
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(ctr++)))
            return c.doFinal(ct)
        }
    }
}
