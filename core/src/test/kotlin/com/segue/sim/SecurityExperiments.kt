package com.segue.sim

import com.segue.core.Handshake
import com.segue.core.Hkdf
import com.segue.core.Identity
import com.segue.core.Msg
import com.segue.core.MsgCodec
import com.segue.core.MsgType
import com.segue.core.Roster
import com.segue.core.SecureChannel
import com.segue.core.Snapshot
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.SplittableRandom

/**
 * Deliberately weak comparison channel reproducing one design class from the literature:
 * "accept only a counter higher than the last accepted one, authenticated by a short tag".
 * It is NOT a model of any vendor's implementation; it isolates the mechanism so the
 * desynchronisation behaviour can be measured as a function of tag length.
 */
class WeakCounterChannel(private val key: ByteArray, private val tagBits: Int) {
    private var last = -1L
    private val mask = if (tagBits == 32) -1 else (1 shl tagBits) - 1

    fun tag(seq: Long, payload: ByteArray): Int =
        ByteBuffer.wrap(Hkdf.hmac(key, ByteBuffer.allocate(8).putLong(seq).array(), payload), 0, 4).int and mask

    fun accept(seq: Long, payload: ByteArray, tag: Int): Boolean {
        if (seq <= last) return false
        if (tag != tag(seq, payload)) return false
        last = seq
        return true
    }
}

class Party(val id: Int) { val identity = Identity.generate() }

fun establish(a: Party, b: Party, roster: Roster, bindA: ByteArray, bindB: ByteArray,
              tamper: ((Int, ByteArray) -> ByteArray)? = null, rosterForB: Roster = roster): Pair<SecureChannel, SecureChannel>? {
    val st = Handshake.initiatorStart(a.id, b.id)
    val m1 = tamper?.invoke(1, st.m1) ?: st.m1
    val (rs, m2raw) = Handshake.responderRespond(b.identity, b.id, rosterForB, bindB, m1) ?: return null
    val m2 = tamper?.invoke(2, m2raw) ?: m2raw
    val (chA, m3raw) = Handshake.initiatorFinish(st, a.identity, roster, bindA, m2) ?: return null
    val m3 = tamper?.invoke(3, m3raw) ?: m3raw
    val chB = Handshake.responderFinish(rs, m3) ?: return null
    return Pair(chA, chB)
}

fun main(args: Array<String>) {
    Locale.setDefault(Locale.ROOT)
    val outDir = File(if (args.isNotEmpty()) args[0] else "results"); outDir.mkdirs()
    val out = File(outDir, "security.csv").printWriter()
    out.println("test,case,attempts,accepted,detail")
    fun row(test: String, case: String, attempts: Long, accepted: Long, detail: String = "") {
        out.println("$test,$case,$attempts,$accepted,$detail"); out.flush()
        println("%-22s %-34s attempts=%-9d accepted=%-7d %s".format(test, case, attempts, accepted, detail))
    }
    val rnd = SplittableRandom(42)
    val a = Party(1); val b = Party(2); val outsider = Party(1)       // outsider claims id 1 with its own key
    val roster = Roster(mapOf(1 to a.identity.publicBytes, 2 to b.identity.publicBytes))
    val token = ByteArray(32).also { rnd.nextBytes(it) }

    // ---- 1. handshake
    val n = 500L
    var ok = 0L
    repeat(n.toInt()) { if (establish(a, b, roster, token, token) != null) ok++ }
    row("handshake", "honest members", n, ok, "expected=all")
    ok = 0
    repeat(n.toInt()) { if (establish(outsider, b, roster, token, token) != null) ok++ }
    row("handshake", "outsider impersonates initiator", n, ok, "expected=0")
    ok = 0
    val fakeB = Party(2)
    repeat(n.toInt()) {
        val st = Handshake.initiatorStart(a.id, 2)
        val r = Handshake.responderRespond(fakeB.identity, 2, roster, token, st.m1)
        if (r != null && Handshake.initiatorFinish(st, a.identity, roster, token, r.second) != null) ok++
    }
    row("handshake", "outsider impersonates responder", n, ok, "expected=0")
    ok = 0
    repeat(n.toInt()) {
        // Man-in-the-middle relaying between two separate transport connections: each victim holds a different token.
        val t2 = ByteArray(32).also { rnd.nextBytes(it) }
        if (establish(a, b, roster, token, t2) != null) ok++
    }
    row("handshake", "relay across two transport sessions", n, ok, "expected=0 (channel binding)")
    ok = 0
    repeat(n.toInt()) {
        val which = 1 + rnd.nextInt(3)
        val res = establish(a, b, roster, token, token, tamper = { i, m ->
            if (i == which) m.copyOf().also { val pos = rnd.nextInt(it.size); it[pos] = (it[pos].toInt() xor (1 shl rnd.nextInt(8))).toByte() } else m })
        // Count a run as accepted only if both sides finish and the resulting channel actually works.
        if (res != null) {
            val (ca, cb) = res
            if (cb.open(ca.seal(byteArrayOf(1, 2, 3))) != null) ok++
        }
    }
    row("handshake", "single-bit flip in M1/M2/M3", n, ok, "expected=0")

    // ---- 2. channel: forgery, tampering, replay, reflection
    val (chA, chB) = establish(a, b, roster, token, token)!!
    val sample = Msg(MsgType.STATE_TRANSFER, 1, 2, 123456789L, 12, nonce = 77, snapshot = Snapshot("spotify", "spotify:track:0000000000000000000000", 91234, true))
    val plain = MsgCodec.encode(sample)
    val frames = ArrayList<ByteArray>()
    var delivered = 0L
    repeat(2000) { val f = chA.seal(plain); frames.add(f); if (chB.open(f) != null) delivered++ }
    row("channel", "legitimate frames in order", 2000, delivered, "expected=all; frame=${frames[0].size}B payload=${plain.size}B")

    var acc = 0L
    for (f in frames) if (chB.open(f) != null) acc++
    row("channel", "replay of every earlier frame", 2000, acc, "expected=0")

    acc = 0
    val nRand = 200_000L
    repeat(nRand.toInt()) {
        val f = ByteArray(frames[0].size); rnd.nextBytes(f); f[0] = 2
        if (rnd.nextBoolean()) ByteBuffer.wrap(f, 1, 8).putLong(2000 + rnd.nextLong(1_000_000))
        if (chB.open(f) != null) acc++
    }
    row("channel", "random forged frames", nRand, acc, "expected=0")

    acc = 0
    val fresh = ArrayList<ByteArray>(); repeat(2000) { fresh.add(chA.seal(plain)) }
    var flips = 0L
    for (f in fresh) repeat(50) {
        val g = f.copyOf(); val pos = 1 + rnd.nextInt(g.size - 1)
        g[pos] = (g[pos].toInt() xor (1 shl rnd.nextInt(8))).toByte()
        flips++
        if (chB.open(g) != null) acc++
    }
    row("channel", "single-bit tamper (header or body)", flips, acc, "expected=0")
    delivered = 0
    for (f in fresh) if (chB.open(f) != null) delivered++
    row("channel", "untampered originals afterwards", 2000, delivered, "expected=all (tamper attempts did not disturb state)")

    acc = 0
    val fromB = ArrayList<ByteArray>(); repeat(2000) { fromB.add(chB.seal(plain)) }
    for (f in fromB) if (chB.open(f) != null) acc++
    row("channel", "reflection to sender", 2000, acc, "expected=0")

    val (chA2, chB2) = establish(a, b, roster, token, token)!!
    acc = 0
    for (f in frames) if (chB2.open(f) != null) acc++
    row("channel", "cross-session replay", 2000, acc, "expected=0")
    chA2.seal(plain)

    // out-of-order tolerance
    val batch = ArrayList<ByteArray>(); repeat(640) { batch.add(chA.seal(plain)) }
    delivered = 0
    for (blk in 0 until 10) {
        val idx = (blk * 64 until blk * 64 + 64).shuffled(java.util.Random(blk.toLong()))
        for (i in idx) if (chB.open(batch[i]) != null) delivered++
    }
    row("channel", "reordered within 64-frame window", 640, delivered, "expected=all")

    // ---- 3. counter desynchronisation
    val (dA, dB) = establish(a, b, roster, token, token)!!
    acc = 0
    val nDesync = 2_000_000L
    val forged = ByteArray(frames[0].size); forged[0] = 2
    repeat(nDesync.toInt()) { i ->
        rnd.nextBytes(forged); forged[0] = 2
        ByteBuffer.wrap(forged, 1, 8).putLong(Long.MAX_VALUE - (i % 1000))
        if (dB.open(forged) != null) acc++
    }
    row("desync", "SG2 channel: max-counter forgeries", nDesync, acc, "expected=0")
    delivered = 0
    repeat(1000) { if (dB.open(dA.seal(plain)) != null) delivered++ }
    row("desync", "SG2 channel: legit delivery after flood", 1000, delivered, "expected=all")

    for ((bits, trials) in listOf(8 to 200, 12 to 200, 16 to 200, 20 to 40)) {
        val tries = ArrayList<Long>()
        var legitAfter = 0L
        repeat(trials) {
            val key = ByteArray(32).also { rnd.nextBytes(it) }
            val rx = WeakCounterChannel(key, bits); val tx = WeakCounterChannel(key, bits)
            val payload = ByteArray(16).also { rnd.nextBytes(it) }
            repeat(10) { s -> rx.accept(s.toLong(), payload, tx.tag(s.toLong(), payload)) }
            var count = 0L
            val start = rnd.nextInt(1 shl bits)                // attacker enumerates tag values from a random offset
            for (g in 0 until (1 shl bits)) {
                count++
                if (rx.accept(Long.MAX_VALUE - 1, payload, (start + g) and ((1 shl bits) - 1))) break
            }
            tries.add(count)
            for (s in 10L until 110L) if (rx.accept(s, payload, tx.tag(s, payload))) legitAfter++
        }
        val sorted = tries.sorted()
        row("desync", "higher-only counter + $bits-bit tag", trials.toLong(), trials.toLong(),
            "forgeries_to_desync_mean=%.0f median=%d max=%d; legit_delivered_after=%d/%d".format(tries.average(), sorted[sorted.size / 2], sorted.last(), legitAfter, trials * 100))
    }

    // ---- 4. cost on this JVM (desktop-class CPU, NOT a phone)
    val (pA, pB) = establish(a, b, roster, token, token)!!
    repeat(100_000) { pB.open(pA.seal(plain)) }
    val iters = 300_000
    var t0 = System.nanoTime()
    repeat(iters) { pB.open(pA.seal(plain)) }
    val nsPer = (System.nanoTime() - t0).toDouble() / iters
    row("cost", "seal+open one handoff message", iters.toLong(), iters.toLong(), "%.2f us per round trip on this JVM; overhead=%d bytes".format(nsPer / 1000, frames[0].size - plain.size))
    repeat(100) { establish(a, b, roster, token, token) }
    t0 = System.nanoTime()
    repeat(300) { establish(a, b, roster, token, token) }
    row("cost", "full 3-message handshake (both sides)", 300, 300, "%.2f ms per handshake on this JVM".format((System.nanoTime() - t0) / 300.0 / 1e6))
    out.close()
}
