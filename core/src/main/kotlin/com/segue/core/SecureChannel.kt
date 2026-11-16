package com.segue.core

import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 64-entry sliding anti-replay window (same idea as IPsec/DTLS).
 * wouldAccept() is read-only; commit() is called only after authentication succeeds, so
 * unauthenticated input can never move the window. This is the property whose absence makes
 * "highest counter wins" designs vulnerable to counter-desynchronisation denial of service.
 */
class ReplayWindow {
    private var top = -1L
    private var bitmap = 0L

    fun wouldAccept(seq: Long): Boolean = when {
        seq < 0 -> false
        seq > top -> true
        top - seq >= 64 -> false
        else -> ((bitmap ushr (top - seq).toInt()) and 1L) == 0L
    }

    fun commit(seq: Long) {
        if (seq > top) {
            val shift = seq - top
            bitmap = if (shift >= 64) 0L else bitmap shl shift.toInt()
            bitmap = bitmap or 1L
            top = seq
        } else {
            bitmap = bitmap or (1L shl (top - seq).toInt())
        }
    }
}

object Hkdf {
    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; counter++
        }
        return out
    }
}

/**
 * Authenticated, replay-protected message channel layered on top of the (untrusted) peer transport.
 *
 * Frame: version(1) | seq(8) | AES-256-GCM(ciphertext | 128-bit tag)
 * AAD:   "SG2" | sessionId(16) | senderId(4) | recipientId(4) | seq(8)
 * Separate keys per direction, so a frame reflected to its sender never verifies.
 */
class SecureChannel(
    sendKey: ByteArray,
    recvKey: ByteArray,
    private val localId: Int,
    private val remoteId: Int,
    private val sessionId: ByteArray
) {
    private val sendKeySpec = SecretKeySpec(sendKey, "AES")
    private val recvKeySpec = SecretKeySpec(recvKey, "AES")
    private var sendSeq = 0L
    private val window = ReplayWindow()
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")

    var rejected = 0L; private set

    private fun nonce(seq: Long): ByteArray = ByteBuffer.allocate(12).putInt(0).putLong(seq).array()

    private fun aad(sender: Int, recipient: Int, seq: Long): ByteArray =
        ByteBuffer.allocate(3 + 16 + 4 + 4 + 8).put(TAG).put(sessionId).putInt(sender).putInt(recipient).putLong(seq).array()

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        val seq = sendSeq++
        cipher.init(Cipher.ENCRYPT_MODE, sendKeySpec, GCMParameterSpec(128, nonce(seq)))
        cipher.updateAAD(aad(localId, remoteId, seq))
        val ct = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(1 + 8 + ct.size).put(VERSION).putLong(seq).put(ct).array()
    }

    /** Returns the plaintext, or null. Receiver state changes only after the tag has verified. */
    @Synchronized
    fun open(frame: ByteArray): ByteArray? {
        if (frame.size < 1 + 8 + 16 || frame[0] != VERSION) { rejected++; return null }
        val seq = ByteBuffer.wrap(frame, 1, 8).long
        if (!window.wouldAccept(seq)) { rejected++; return null }      // read-only pre-check
        return try {
            cipher.init(Cipher.DECRYPT_MODE, recvKeySpec, GCMParameterSpec(128, nonce(seq)))
            cipher.updateAAD(aad(remoteId, localId, seq))
            val pt = cipher.doFinal(frame, 9, frame.size - 9)
            window.commit(seq)                                           // only now
            pt
        } catch (e: Exception) {
            rejected++; null
        }
    }

    companion object {
        private const val VERSION: Byte = 2
        private val TAG = "SG2".toByteArray()
    }
}
