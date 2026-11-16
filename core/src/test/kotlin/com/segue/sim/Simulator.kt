package com.segue.sim

import com.segue.core.CoordinatorConfig
import com.segue.core.Env
import com.segue.core.HandoffCoordinator
import com.segue.core.HandoffNode
import com.segue.core.Msg
import com.segue.core.PlayerPort
import com.segue.core.Rollback
import com.segue.core.Snapshot
import java.util.PriorityQueue
import java.util.SplittableRandom

enum class Variant(val label: String) {
    NAIVE("A0 naive ack/timeout"),
    EXPIRY("A1 epochs + wall-clock expiry"),
    LEASE_RESUME("A2 READY-anchored lease, rollback"),
    LEASE_SAFE("A3 READY-anchored lease, safe-pause"),
    LEASE_NOFRESH("A2 without freshness binding")
}

data class SimParams(
    val loss: Double = 0.05,
    val skewMs: Long = 1000,          // wall clocks are offset uniformly in [-skew, +skew]
    val pPartition: Double = 0.10,    // per attempt: link outage of 1.5-12 s starting 0-1.5 s after the trigger
    val pCrash: Double = 0.02,        // per attempt and per phone: coordinator process dies for 2-8 s; the player keeps its state
    val pStartFail: Double = 0.05,    // target player fails to start the content
    val heavyDelayProb: Double = 0.03,
    val dupProb: Double = 0.03,
    val ancientDupProb: Double = 0.01,
    val driftPpm: Double = 100.0,
    val attempts: Int = 3,
    val crashRole: Int = 0            // 0 = either phone, 1 = only the current source, 2 = only the current target
)

enum class Outcome { HANDED, SOURCE_KEPT, SILENT, DUAL }

class AttemptRecord {
    var triggered = false
    var refused = false
    var outcome = Outcome.SILENT
    var dualMs = 0.0
    var maxSilenceMs = 0.0
    var ownerConflict = false
    var noOwner = false
}

class Simulator(private val variant: Variant, private val p: SimParams, seed: Long) {
    private sealed class Ev(val time: Double, val seq: Long)
    private class Deliver(time: Double, seq: Long, val msg: Msg) : Ev(time, seq)
    private class TimerEv(time: Double, seq: Long, val node: Int, val name: String, val gen: Int) : Ev(time, seq)
    private class PlayerDone(time: Double, seq: Long, val node: Int, val play: Boolean, val snapshot: Snapshot?, val fresh: Boolean) : Ev(time, seq)
    private class Crash(time: Double, seq: Long, val node: Int) : Ev(time, seq)
    private class Restart(time: Double, seq: Long, val node: Int) : Ev(time, seq)
    private class Trigger(time: Double, seq: Long, val attempt: Int) : Ev(time, seq)
    private class Checkpoint(time: Double, seq: Long, val attempt: Int) : Ev(time, seq)

    private val scenarioRng = SplittableRandom(seed)
    private val netRng = SplittableRandom(seed xor 0x5DEECE66DL)
    private val playerRng = SplittableRandom(seed xor 0x2545F4914F6CDD1DL)
    private val idRng = SplittableRandom(seed xor 0x9E3779B97F4A7C15uL.toLong())

    private val queue = PriorityQueue<Ev>(compareBy<Ev> { it.time }.thenBy { it.seq })
    private var seq = 0L
    private var t = 0.0
    private val downIntervals = ArrayList<DoubleArray>()

    private val playing = BooleanArray(2)
    private var dualStart = -1.0
    private var silenceStart = -1.0
    private var cur: AttemptRecord? = null
    val records = ArrayList<AttemptRecord>()
    var events = 0L; private set

    private inner class SimPlayer(val id: Int) : PlayerPort {
        var position = 0.0
        var lastUpdate = 0.0
        var busyUntil = 0.0

        fun accrue() { if (playing[id]) position += t - lastUpdate; lastUpdate = t }
        override fun isPlaying(): Boolean = playing[id]
        override fun requestPause() {
            val done = maxOf(t, busyUntil) + 30 + playerRng.nextDouble() * 270
            busyUntil = done
            schedule(PlayerDone(done, seq++, id, play = false, snapshot = null, fresh = false))
        }
        override fun requestPlay(snapshot: Snapshot?) {
            val done = maxOf(t, busyUntil) + 30 + playerRng.nextDouble() * 270
            busyUntil = done
            schedule(PlayerDone(done, seq++, id, play = true, snapshot = snapshot, fresh = snapshot != null))
        }
        override fun snapshot(): Snapshot { accrue(); return Snapshot("ref", "track-1", position.toLong(), true) }
    }

    private inner class Ctx(override val self: Int) : Env {
        var crashed = false
        lateinit var node: HandoffNode
        val player = SimPlayer(self)
        var drift = 0.0
        var monoOffset = 0L
        var wallSkew = 0L
        val timerGen = HashMap<String, Int>()

        override fun now(): Long = (t * (1 + drift)).toLong() + monoOffset
        override fun wallNow(): Long = t.toLong() + wallSkew
        override fun send(msg: Msg) = network(msg)
        override fun setTimer(name: String, delayMs: Long) {
            val g = (timerGen[name] ?: 0) + 1
            timerGen[name] = g
            schedule(TimerEv(t + maxOf(0L, delayMs) / (1 + drift), seq++, self, name, g))
        }
        override fun cancelTimer(name: String) { timerGen[name] = (timerGen[name] ?: 0) + 1 }
        override fun player(): PlayerPort = player
        override fun routeReady(): Boolean = true
        override fun randomId(): Long { var v = idRng.nextLong(); if (v == 0L) v = 1L; return v }
    }

    private val ctx = arrayOf(Ctx(0), Ctx(1))

    private fun schedule(e: Ev) { queue.add(e) }

    private fun linkDown(at: Double): Boolean = downIntervals.any { at >= it[0] && at < it[1] }

    private fun network(msg: Msg) {
        if (linkDown(t)) return
        if (netRng.nextDouble() >= p.loss) schedule(Deliver(t + delay(), seq++, msg))
        if (netRng.nextDouble() < p.dupProb) schedule(Deliver(t + delay(), seq++, msg))
        if (netRng.nextDouble() < p.ancientDupProb) schedule(Deliver(t + 5000 + netRng.nextDouble() * 35000, seq++, msg))
    }

    private fun delay(): Double {
        var d = Math.exp(Math.log(30.0) + 0.6 * netRng.nextGaussian())
        if (netRng.nextDouble() < p.heavyDelayProb) d += 500 + netRng.nextDouble() * 5500
        return d
    }

    private fun setPlaying(id: Int, flag: Boolean) {
        ctx[id].player.accrue()
        if (playing[id] == flag) return
        val bothBefore = playing[0] && playing[1]
        val noneBefore = !playing[0] && !playing[1]
        playing[id] = flag
        val bothAfter = playing[0] && playing[1]
        val noneAfter = !playing[0] && !playing[1]
        if (!bothBefore && bothAfter) dualStart = t
        if (bothBefore && !bothAfter) closeDual()
        if (!noneBefore && noneAfter) silenceStart = t
        if (noneBefore && !noneAfter) closeSilence()
    }

    private fun closeDual() { if (dualStart >= 0) { cur?.let { it.dualMs += t - dualStart }; dualStart = -1.0 } }
    private fun closeSilence() {
        if (silenceStart >= 0) { cur?.let { it.maxSilenceMs = maxOf(it.maxSilenceMs, t - silenceStart) }; silenceStart = -1.0 }
    }

    fun run() {
        for (c in ctx) {
            c.drift = (scenarioRng.nextDouble() * 2 - 1) * p.driftPpm * 1e-6
            c.monoOffset = scenarioRng.nextLong(1_000_000)
            c.wallSkew = if (p.skewMs == 0L) 0 else scenarioRng.nextLong(-p.skewMs, p.skewMs + 1)
            c.node = when (variant) {
                Variant.NAIVE -> NaiveNode(c, 0)
                Variant.EXPIRY -> ExpiryNode(c, 0)
                Variant.LEASE_RESUME -> HandoffCoordinator(c, CoordinatorConfig(rollback = Rollback.RESUME_AFTER_LEASE), 0)
                Variant.LEASE_SAFE -> HandoffCoordinator(c, CoordinatorConfig(rollback = Rollback.SAFE_PAUSE), 0)
                Variant.LEASE_NOFRESH -> HandoffCoordinator(c, CoordinatorConfig(rollback = Rollback.RESUME_AFTER_LEASE, freshnessBinding = false), 0)
            }
        }
        playing[0] = true   // phone 0 starts as the playing owner

        // The fault schedule is drawn up front from its own stream so every variant sees the same scenario.
        for (k in 0 until p.attempts) {
            val trig = 3000.0 + k * ATTEMPT_SPACING + scenarioRng.nextDouble() * 2000
            schedule(Trigger(trig, seq++, k))
            schedule(Checkpoint(trig + CHECKPOINT_AFTER, seq++, k))
            val part = scenarioRng.nextDouble() < p.pPartition
            val partStart = trig + scenarioRng.nextDouble() * 1500
            val partLen = 1500 + scenarioRng.nextDouble() * 10500
            if (part) downIntervals.add(doubleArrayOf(partStart, partStart + partLen))
            for (n in 0..1) {
                val crash = scenarioRng.nextDouble() < p.pCrash
                val at = trig + scenarioRng.nextDouble() * 3000
                val len = 2000 + scenarioRng.nextDouble() * 6000
                if (crash) { schedule(Crash(at, seq++, n)); schedule(Restart(at + len, seq++, n)) }
            }
        }
        val end = 3000.0 + (p.attempts - 1) * ATTEMPT_SPACING + 2000 + CHECKPOINT_AFTER + 1000

        while (queue.isNotEmpty()) {
            val e = queue.poll()
            if (e.time > end) break
            t = e.time
            events++
            when (e) {
                is Deliver -> { val c = ctx[e.msg.recipient]; if (!c.crashed && !linkDown(t)) c.node.onMessage(e.msg) }
                is TimerEv -> { val c = ctx[e.node]; if (!c.crashed && c.timerGen[e.name] == e.gen) c.node.onTimer(e.name) }
                is PlayerDone -> {
                    val c = ctx[e.node]
                    if (e.play) {
                        val ok = !(e.fresh && playerRng.nextDouble() < p.pStartFail)
                        if (ok) {
                            if (e.snapshot != null) { c.player.accrue(); c.player.position = e.snapshot.positionMs.toDouble() }
                            setPlaying(e.node, true)
                        }
                        if (!c.crashed) c.node.onPlayerStarted(ok)
                    } else {
                        setPlaying(e.node, false)
                        if (!c.crashed) c.node.onPlayerPaused()
                    }
                }
                is Crash -> {
                    val isSource = e.node == attemptSrc
                    val allowed = p.crashRole == 0 || (p.crashRole == 1 && isSource) || (p.crashRole == 2 && !isSource)
                    if (allowed && cur != null) {
                        val c = ctx[e.node]; c.crashed = true
                        for (k in c.timerGen.keys.toList()) c.timerGen[k] = c.timerGen[k]!! + 1
                    }
                }
                is Restart -> { val c = ctx[e.node]; if (c.crashed) { c.crashed = false; c.node.onRestart() } }
                is Trigger -> {
                    val rec = AttemptRecord()
                    records.add(rec)
                    val src = if (playing[0] && !playing[1]) 0 else if (playing[1] && !playing[0]) 1 else -1
                    if (src >= 0 && !ctx[src].crashed) {
                        if (dualStart >= 0) dualStart = t
                        if (silenceStart >= 0) silenceStart = t
                        cur = rec
                        rec.triggered = true
                        attemptSrc = src
                        rec.refused = !ctx[src].node.requestHandoff(1 - src)
                    }
                }
                is Checkpoint -> {
                    val rec = records[e.attempt]
                    if (rec.triggered) {
                        if (dualStart >= 0) { rec.dualMs += t - dualStart; dualStart = t }
                        if (silenceStart >= 0) { rec.maxSilenceMs = maxOf(rec.maxSilenceMs, t - silenceStart); silenceStart = t }
                        val src = attemptSrc; val tgt = 1 - src
                        rec.outcome = when {
                            playing[src] && playing[tgt] -> Outcome.DUAL
                            playing[tgt] -> Outcome.HANDED
                            playing[src] -> Outcome.SOURCE_KEPT
                            else -> Outcome.SILENT
                        }
                        rec.ownerConflict = ctx[0].node.believesOwner && ctx[1].node.believesOwner
                        rec.noOwner = !ctx[0].node.believesOwner && !ctx[1].node.believesOwner
                        cur = null
                        // The user repairs anything that is not "exactly one phone playing" by pressing play on the target.
                        if (rec.outcome == Outcome.DUAL || rec.outcome == Outcome.SILENT) ctx[tgt].node.manualClaim(src)
                    }
                }
            }
        }
    }

    private var attemptSrc = 0

    companion object {
        const val ATTEMPT_SPACING = 22000.0
        const val CHECKPOINT_AFTER = 16000.0
    }
}
