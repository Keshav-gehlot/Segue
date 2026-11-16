package com.segue.sim

import com.segue.core.Enrolment
import com.segue.core.Hkdf
import com.segue.core.Identity
import com.segue.core.Msg
import com.segue.core.MsgCodec
import com.segue.core.MsgType
import com.segue.core.ReplayWindow
import com.segue.core.Roster
import com.segue.core.RotatingId
import com.segue.core.Snapshot
import java.util.SplittableRandom
import kotlin.system.exitProcess

private var failures = 0
private fun check(name: String, cond: Boolean) {
    println((if (cond) "PASS  " else "FAIL  ") + name)
    if (!cond) failures++
}

private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

fun main() {
    val rnd = SplittableRandom(7)

    // codec
    var okCodec = true
    repeat(10_000) {
        val m = Msg(MsgType.entries[rnd.nextInt(MsgType.entries.size)], rnd.nextInt(), rnd.nextInt(), rnd.nextLong(), rnd.nextLong(),
            rnd.nextLong(), rnd.nextLong(), if (rnd.nextBoolean()) Snapshot("p${rnd.nextInt(9)}", "c${rnd.nextLong()}", rnd.nextLong(), rnd.nextBoolean()) else null,
            rnd.nextBoolean(), rnd.nextLong(), "r${rnd.nextInt(99)}")
        if (MsgCodec.decode(MsgCodec.encode(m)) != m) okCodec = false
    }
    check("codec round-trips 10,000 random messages", okCodec)
    var threw = false
    repeat(10_000) { val g = ByteArray(rnd.nextInt(120)); rnd.nextBytes(g); try { MsgCodec.decode(g) } catch (e: Throwable) { threw = true } }
    check("codec never throws on 10,000 random byte strings", !threw)

    // replay window
    val w = ReplayWindow()
    var okW = true
    for (s in 0L until 200L) { if (!w.wouldAccept(s)) okW = false; w.commit(s) }
    for (s in 0L until 200L) if (w.wouldAccept(s)) okW = false
    check("replay window: in-order accepted once, replays rejected", okW)
    val w2 = ReplayWindow(); w2.commit(100)
    check("replay window: within-window older sequence accepted once", w2.wouldAccept(60) && run { w2.commit(60); !w2.wouldAccept(60) })
    check("replay window: older than 64 rejected", !w2.wouldAccept(36) && !w2.wouldAccept(0))
    check("replay window: negative rejected", !w2.wouldAccept(-1))

    // HKDF, RFC 5869 test case 1
    val prk = Hkdf.extract(hex("000102030405060708090a0b0c"), hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"))
    val okm = Hkdf.expand(prk, hex("f0f1f2f3f4f5f6f7f8f9"), 42)
    check("HKDF-SHA256 matches RFC 5869 test case 1",
        okm.contentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")))

    // handshake + channel
    val a = Party(1); val b = Party(2)
    val roster = Roster(mapOf(1 to a.identity.publicBytes, 2 to b.identity.publicBytes))
    val tok = ByteArray(32).also { rnd.nextBytes(it) }
    val chans = establish(a, b, roster, tok, tok)
    check("handshake between enrolled members succeeds", chans != null)
    val (ca, cb) = chans!!
    val msg = "hello".toByteArray()
    check("channel delivers both directions", cb.open(ca.seal(msg))!!.contentEquals(msg) && ca.open(cb.seal(msg))!!.contentEquals(msg))

    // rotating ids
    val gk = ByteArray(32).also { rnd.nextBytes(it) }
    val names = (0L until 1000L).map { RotatingId.endpointName(gk, it) }
    check("rotating endpoint names: 1000 epochs all distinct", names.toSet().size == 1000)
    val now = 1_794_800_000L
    val name = RotatingId.endpointName(gk, RotatingId.epochIndex(now))
    check("rotating endpoint names: member matches current/adjacent epoch only",
        RotatingId.matches(gk, name, now) && RotatingId.matches(gk, name, now + 900) && !RotatingId.matches(gk, name, now + 2700))
    val gk2 = ByteArray(32).also { rnd.nextBytes(it) }
    check("rotating endpoint names: different group key does not match", !RotatingId.matches(gk2, name, now))

    // SAS
    val nA = ByteArray(16).also { rnd.nextBytes(it) }; val nB = ByteArray(16).also { rnd.nextBytes(it) }
    val sas = Enrolment.sasCode(a.identity.publicBytes, b.identity.publicBytes, nA, nB)
    check("SAS: both sides compute the same 6-digit code", sas == Enrolment.sasCode(a.identity.publicBytes, b.identity.publicBytes, nA, nB) && sas.length == 6)
    var collisions = 0
    repeat(2000) { val m = Identity.generate(); if (Enrolment.sasCode(a.identity.publicBytes, m.publicBytes, nA, nB) == sas) collisions++ }
    check("SAS: 2000 substituted keys give a different code (collisions=$collisions)", collisions <= 1)

    // protocol properties through the simulator
    val clean = SimParams(loss = 0.0, skewMs = 0, pPartition = 0.0, pCrash = 0.0, pStartFail = 0.0, heavyDelayProb = 0.0, dupProb = 0.0, ancientDupProb = 0.0)
    for (v in listOf(Variant.LEASE_RESUME, Variant.LEASE_SAFE)) {
        val g = runCell(v, clean, 2000, 11)
        check("${v.name}: fault-free handoffs all complete (${g.handed}/${g.attempts}), no dual playback", g.handed == g.attempts && g.dualMsTotal == 0.0)
    }
    val harsh = SimParams(loss = 0.20, skewMs = 10_000, pPartition = 0.20, pCrash = 0.10, pStartFail = 0.10, heavyDelayProb = 0.20, dupProb = 0.20, ancientDupProb = 0.05)
    val safe = runCell(Variant.LEASE_SAFE, harsh, 5000, 99)
    check("LEASE_SAFE under harsh faults: zero dual playback over ${safe.attempts} attempts, zero owner conflicts",
        safe.dualMsTotal == 0.0 && safe.ownerConflicts == 0L)
    val noCrash = harsh.copy(pCrash = 0.0)
    val res = runCell(Variant.LEASE_RESUME, noCrash, 5000, 99)
    check("LEASE_RESUME under harsh faults without coordinator crashes: zero dual playback over ${res.attempts} attempts",
        res.dualMsTotal == 0.0 && res.ownerConflicts == 0L)
    val nofresh = runCell(Variant.LEASE_NOFRESH, noCrash, 5000, 99)
    check("ablation sanity: removing freshness binding does produce dual playback (${nofresh.attemptsWithDual} attempts)", nofresh.attemptsWithDual > 0)
    val naive = runCell(Variant.NAIVE, noCrash, 5000, 99)
    check("ablation sanity: naive variant does produce dual playback (${naive.attemptsWithDual} attempts)", naive.attemptsWithDual > 0)

    println(if (failures == 0) "\nALL TESTS PASSED" else "\n$failures TEST(S) FAILED")
    if (failures != 0) exitProcess(1)
}
