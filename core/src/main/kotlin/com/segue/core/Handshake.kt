package com.segue.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Long-term device identity: ECDSA P-256. On Android the private key is generated inside
 * AndroidKeyStore (StrongBox where present) and never leaves it; here it is an ordinary JCA key
 * so the same code runs on the JVM for tests.
 */
class Identity(val keyPair: KeyPair) {
    val publicBytes: ByteArray get() = keyPair.public.encoded

    fun sign(data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(keyPair.private); s.update(data)
        return s.sign()
    }

    companion object {
        fun generate(): Identity = Identity(Crypto.newEcKeyPair())
    }
}

/** Pinned group membership: deviceId -> identity public key, established once at enrolment. */
class Roster(private val members: Map<Int, ByteArray>) {
    fun keyOf(deviceId: Int): PublicKey? = members[deviceId]?.let { Crypto.decodePublic(it) }
}

object Crypto {
    val rng = SecureRandom()

    fun newEcKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), rng)
        return g.generateKeyPair()
    }

    fun decodePublic(bytes: ByteArray): PublicKey? = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    } catch (e: Exception) { null }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        for (p in parts) d.update(p)
        return d.digest()
    }

    fun verify(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(pub); s.update(data)
        s.verify(sig)
    } catch (e: Exception) { false }

    fun ecdh(priv: java.security.PrivateKey, peerPub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv); ka.doPhase(peerPub, true)
        return ka.generateSecret()
    }
}

/**
 * Enrolment helpers. Enrolment happens once, with both phones in the user's hands:
 *  - preferred: each phone shows a QR code containing fingerprint(identityKey); the other scans it;
 *  - fallback: both phones display the 6-digit short authentication string and the user compares.
 * After enrolment no per-connection user comparison is ever needed.
 */
object Enrolment {
    fun fingerprint(publicBytes: ByteArray): String =
        Crypto.sha256("SG2-fp".toByteArray(), publicBytes).joinToString("") { "%02x".format(it) }

    /** Commit-then-reveal SAS: nonces must be exchanged under commitment before codes are compared. */
    fun commitment(publicBytes: ByteArray, nonce: ByteArray): ByteArray =
        Crypto.sha256("SG2-commit".toByteArray(), publicBytes, nonce)

    fun sasCode(pubA: ByteArray, pubB: ByteArray, nonceA: ByteArray, nonceB: ByteArray): String {
        val h = Crypto.sha256("SG2-sas".toByteArray(), pubA, pubB, nonceA, nonceB)
        val v = (ByteBuffer.wrap(h, 0, 8).long ushr 1) % 1_000_000L
        return "%06d".format(v)
    }
}

/**
 * Advertised endpoint name. Every identifier the app exposes is derived from one epoch index, so
 * all of them rotate at the same instant and none is a counter: an outsider cannot link two epochs,
 * while group members recompute the expected value from the group key.
 */
object RotatingId {
    const val EPOCH_SECONDS = 900L

    fun epochIndex(unixSeconds: Long): Long = unixSeconds / EPOCH_SECONDS

    fun endpointName(groupKey: ByteArray, epochIndex: Long): String {
        val mac = Hkdf.hmac(groupKey, "SG2-adv".toByteArray(), ByteBuffer.allocate(8).putLong(epochIndex).array())
        return mac.copyOf(8).joinToString("") { "%02x".format(it) }
    }

    fun matches(groupKey: ByteArray, name: String, unixSeconds: Long): Boolean {
        val e = epochIndex(unixSeconds)
        return (e - 1..e + 1).any { endpointName(groupKey, it) == name }
    }
}

/**
 * Three-message mutually authenticated key agreement (SIGMA-style: sign the ephemeral transcript
 * with pinned identity keys). The transcript also covers channelBinding, which on Android is the
 * raw authentication token Nearby Connections derives for this physical connection. A
 * man-in-the-middle who runs two separate Nearby connections sees two different tokens, so the
 * signatures fail even though no user compares anything.
 *
 *   M1  I -> R : idI | ephI | nonceI
 *   M2  R -> I : idR | ephR | nonceR | Sig_R("resp" | transcript)
 *   M3  I -> R : Sig_I("init" | transcript)
 */
object Handshake {
    class InitState(val selfId: Int, val peerId: Int, val eph: KeyPair, val nonce: ByteArray, val m1: ByteArray)
    class RespState(val channel: SecureChannel, val initiatorKey: PublicKey, val transcript: ByteArray)

    private fun field(out: ByteArrayOutputStream, b: ByteArray) {
        out.write(ByteBuffer.allocate(2).putShort(b.size.toShort()).array()); out.write(b)
    }

    private fun readFields(bytes: ByteArray, count: Int): List<ByteArray>? {
        val res = ArrayList<ByteArray>(count)
        var pos = 0
        repeat(count) {
            if (pos + 2 > bytes.size) return null
            val len = ByteBuffer.wrap(bytes, pos, 2).short.toInt() and 0xffff
            pos += 2
            if (pos + len > bytes.size) return null
            res.add(bytes.copyOfRange(pos, pos + len)); pos += len
        }
        return if (pos == bytes.size) res else null
    }

    private fun intBytes(i: Int) = ByteBuffer.allocate(4).putInt(i).array()

    private fun transcript(idI: Int, idR: Int, ephI: ByteArray, ephR: ByteArray, nI: ByteArray, nR: ByteArray, binding: ByteArray) =
        Crypto.sha256("SG2-hs".toByteArray(), intBytes(idI), intBytes(idR), ephI, ephR, nI, nR, Crypto.sha256(binding))

    private fun deriveChannel(shared: ByteArray, nI: ByteArray, nR: ByteArray, th: ByteArray, localId: Int, remoteId: Int, initiator: Boolean): SecureChannel {
        val prk = Hkdf.extract(nI + nR, shared)
        val kI2R = Hkdf.expand(prk, "i2r".toByteArray() + th, 32)
        val kR2I = Hkdf.expand(prk, "r2i".toByteArray() + th, 32)
        val sid = Hkdf.expand(prk, "sid".toByteArray() + th, 16)
        return if (initiator) SecureChannel(kI2R, kR2I, localId, remoteId, sid)
        else SecureChannel(kR2I, kI2R, localId, remoteId, sid)
    }

    fun initiatorStart(selfId: Int, peerId: Int): InitState {
        val eph = Crypto.newEcKeyPair()
        val nonce = ByteArray(16).also { Crypto.rng.nextBytes(it) }
        val out = ByteArrayOutputStream()
        field(out, intBytes(selfId)); field(out, eph.public.encoded); field(out, nonce)
        return InitState(selfId, peerId, eph, nonce, out.toByteArray())
    }

    fun responderRespond(self: Identity, selfId: Int, roster: Roster, channelBinding: ByteArray, m1: ByteArray): Pair<RespState, ByteArray>? {
        val f = readFields(m1, 3) ?: return null
        if (f[0].size != 4 || f[2].size != 16) return null
        val idI = ByteBuffer.wrap(f[0]).int
        val initiatorKey = roster.keyOf(idI) ?: return null            // not an enrolled member
        val ephIPub = Crypto.decodePublic(f[1]) ?: return null
        val eph = Crypto.newEcKeyPair()
        val nR = ByteArray(16).also { Crypto.rng.nextBytes(it) }
        val th = transcript(idI, selfId, f[1], eph.public.encoded, f[2], nR, channelBinding)
        val shared = try { Crypto.ecdh(eph.private, ephIPub) } catch (e: Exception) { return null }
        val channel = deriveChannel(shared, f[2], nR, th, selfId, idI, initiator = false)
        val sig = self.sign("resp".toByteArray() + th)
        val out = ByteArrayOutputStream()
        field(out, intBytes(selfId)); field(out, eph.public.encoded); field(out, nR); field(out, sig)
        return Pair(RespState(channel, initiatorKey, th), out.toByteArray())
    }

    fun initiatorFinish(st: InitState, self: Identity, roster: Roster, channelBinding: ByteArray, m2: ByteArray): Pair<SecureChannel, ByteArray>? {
        val f = readFields(m2, 4) ?: return null
        if (f[0].size != 4 || f[2].size != 16) return null
        val idR = ByteBuffer.wrap(f[0]).int
        if (idR != st.peerId) return null
        val responderKey = roster.keyOf(idR) ?: return null
        val ephRPub = Crypto.decodePublic(f[1]) ?: return null
        val th = transcript(st.selfId, idR, st.eph.public.encoded, f[1], st.nonce, f[2], channelBinding)
        if (!Crypto.verify(responderKey, "resp".toByteArray() + th, f[3])) return null
        val shared = try { Crypto.ecdh(st.eph.private, ephRPub) } catch (e: Exception) { return null }
        val channel = deriveChannel(shared, st.nonce, f[2], th, st.selfId, idR, initiator = true)
        return Pair(channel, self.sign("init".toByteArray() + th))
    }

    /** The responder must not use the channel until this returns non-null. */
    fun responderFinish(st: RespState, m3: ByteArray): SecureChannel? =
        if (Crypto.verify(st.initiatorKey, "init".toByteArray() + st.transcript, m3)) st.channel else null
}
