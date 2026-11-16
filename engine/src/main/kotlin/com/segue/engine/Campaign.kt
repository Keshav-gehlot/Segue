package com.segue.engine

import com.segue.core.Phase
import com.segue.core.Rollback

/**
 * Automated test campaign. One device (the conductor) runs every test case against one paired
 * peer: it puts both devices in the required starting state, injects faults on itself and, through
 * authenticated test commands, on the peer, triggers the handoff, waits, and records a verdict.
 *
 * The campaign lives outside the engine so that it survives the engine restarts it causes
 * (emulated coordinator crashes, rollback-policy switches). It runs on the engine's scheduler
 * thread and reaches the current engine through [engine].
 *
 * Verdicts here are the conductor's view (who plays, who owns, after the case settled). Overlap
 * between the two players is measured afterwards from both devices' logs by
 * scripts/analyze_handoffs.py, using the case_begin / case_end lines written here.
 */
class Campaign(
    private val sched: Scheduler,
    private val engine: () -> SegueEngine,
    private val onStatus: (CampaignStatus) -> Unit
) {
    enum class Preset(val label: String) {
        QUICK("Quick check"), TIMING("Timing"), FAULTS("Faults"), FULL("Everything")
    }

    private var gen = 0L
    private var cases: List<Case> = emptyList()
    private var index = 0
    private var peer = -1
    private var presetName = ""
    private var passed = 0
    private var failed = 0
    private var inconclusive = 0
    private var running = false
    private var lastLine = ""

    fun start(preset: Preset, peerId: Int) = sched.post {
        gen++
        cases = CaseList.build(preset)
        index = 0; peer = peerId; presetName = preset.label
        passed = 0; failed = 0; inconclusive = 0; running = true
        lastLine = "Starting"
        engine().testLog("campaign", "start preset=${preset.name} cases=${cases.size} peer=$peerId")
        publish()
        // The peer must accept test commands. A refused first command ends the campaign with a clear message.
        val g = gen
        remote("clear", g, onFail = { finish("Stopped: turn on Test mode on ${engine().labelFor(peer)} and keep it connected") }) { next(g) }
    }

    fun stop() = sched.post {
        if (!running) return@post
        gen++
        engine().testLocal("clear")
        engine().testRemote(peer, "clear") { }
        finish("Stopped by user")
    }

    // ------------------------------------------------------------------ sequencing

    private fun later(ms: Long, g: Long, block: () -> Unit) {
        sched.schedule(ms) { if (g == gen && running) block() }
    }

    /** Send a test command to the peer and continue on its ack; [onFail] on refusal or no answer in 4 s. */
    private fun remote(spec: String, g: Long, onFail: () -> Unit, onOk: () -> Unit) {
        var done = false
        engine().testRemote(peer, spec) { ok ->
            if (!done && g == gen && running) { done = true; if (ok) onOk() else onFail() }
        }
        sched.schedule(4000) { if (!done && g == gen && running) { done = true; onFail() } }
    }

    private fun finish(line: String) {
        running = false
        lastLine = line
        engine().testLog("campaign", "end passed=$passed failed=$failed inconclusive=$inconclusive ($line)")
        publish()
    }

    private fun publish() {
        val name = cases.getOrNull(index)?.let { it.name + if (it.kind == Kind.POLICY) "" else if (it.push) " (push)" else " (pull)" } ?: ""
        onStatus(CampaignStatus(running, presetName, minOf(index, cases.size), cases.size, name, passed, failed, inconclusive, lastLine))
    }

    private fun next(g: Long) {
        if (index >= cases.size) { finish("Finished"); return }
        publish()
        val c = cases[index]
        if (c.kind == Kind.POLICY) switchPolicy(c, g) else prepare(c, g, attempt = 0)
    }

    private fun skip(c: Case, why: String, g: Long) {
        inconclusive++
        lastLine = "${c.name}: skipped ($why)"
        engine().testLog("case_skip", "i=$index name=${c.name} why=$why")
        index++
        later(GAP_MS, g) { next(g) }
    }

    // ------------------------------------------------------------------ rollback policy switch

    private fun switchPolicy(c: Case, g: Long, attempt: Int = 0) {
        val want = c.policy!!
        val e = engine()
        if (attempt == 0) {
            e.testLog("campaign", "policy=$want")
            lastLine = "Switching both devices to $want"
            publish()
        }
        if (attempt > 12) return finish("Stopped: could not switch ${e.labelFor(peer)} to $want")
        if (!e.probe(peer).alive || !e.probe(peer).connected) return later(2500, g) { switchPolicy(c, g, attempt + 1) }
        // Peer first: it acks, then restarts its engine. The command is idempotent, so a lost ack is
        // answered by asking again once the link is back.
        remote("policy:$want", g, onFail = { later(2500, g) { switchPolicy(c, g, attempt + 1) } }) {
            if (engine().policy != want) engine().testLocal("policy:$want")
            index++
            later(2500, g) { next(g) }
        }
    }

    // ------------------------------------------------------------------ one case

    /** Bring both devices to: connected, idle, source playing and owning, target silent. */
    private fun prepare(c: Case, g: Long, attempt: Int) {
        val e = engine()
        val p = e.probe(peer)
        val st = p.peer
        if (attempt > 30) return skip(c, "devices did not reach the starting state", g)
        if (!p.alive || !p.connected || st == null) return later(2000, g) { prepare(c, g, attempt + 1) }
        if (attempt == 0) {
            // Clean slate on both sides, then look again with a fresh status from the peer.
            e.testLocal("clear"); e.testLocal("allow")
            return remote("clear", g, onFail = { later(2000, g) { prepare(c, g, attempt + 1) } }) {
                remote("allow", g, onFail = { later(2000, g) { prepare(c, g, attempt + 1) } }) {
                    remote("status", g, onFail = { later(2000, g) { prepare(c, g, attempt + 1) } }) { prepare(c, g, 1) }
                }
            }
        }
        if (p.phase != Phase.IDLE || st.phase != "IDLE") return later(1500, g) { prepare(c, g, attempt + 1) }
        val srcPlays = if (c.push) p.selfPlaying else st.playing
        val tgtPlays = if (c.push) st.playing else p.selfPlaying
        val srcOwns = if (c.push) p.ownerId == e.selfId else st.ownerId == peer
        if (srcPlays && !tgtPlays && srcOwns) return arm(c, g)
        // Put playback on the source with an explicit play there (the other device pauses on the claim).
        if (c.push) e.testLocal("play") else e.testRemote(peer, "play") { }
        later(3000, g) {
            remote("status", g, onFail = { later(1500, g) { prepare(c, g, attempt + 1) } }) { prepare(c, g, attempt + 1) }
        }
    }

    private fun arm(c: Case, g: Long) {
        val e = engine()
        val local = if (c.push) c.sourceFaults else c.targetFaults
        val remote = if (c.push) c.targetFaults else c.sourceFaults
        for (f in local) if (!e.testLocal(f)) return skip(c, "bad local fault $f", g)
        fun sendNext(i: Int) {
            if (i >= remote.size) { trigger(c, g); return }
            remote(remote[i], g, onFail = { skip(c, "peer did not arm ${remote[i]}", g) }) { sendNext(i + 1) }
        }
        sendNext(0)
    }

    private fun trigger(c: Case, g: Long) {
        val e = engine()
        val src = if (c.push) e.selfId else peer
        val tgt = if (c.push) peer else e.selfId
        e.testLog("case_begin", "i=$index name=${c.name} dir=${if (c.push) "push" else "pull"} src=$src tgt=$tgt " +
            "expect=${c.expect} expect_dual=${c.expectDual} policy=${e.policy}")
        lastLine = "Running ${c.name}"
        publish()
        // Commands for "the source" or "the target", whichever of them this device is.
        fun onSource(spec: String) { if (c.push) e.testLocal(spec) else e.testRemote(peer, spec) { } }
        fun onTarget(spec: String) { if (c.push) e.testRemote(peer, spec) { } else e.testLocal(spec) }
        when (c.kind) {
            Kind.HANDOFF -> if (c.push) e.testHandoff(peer) else e.testPull()
            Kind.REJECT_MANUAL -> {
                // The user paused on the target: it must refuse an automatic handoff.
                onTarget("pause")
                later(800, g) { engine().testHandoff(peer) }
            }
            Kind.CLAIM -> onTarget("play")
            Kind.CONCURRENT_CLAIM -> {
                onSource("pause")
                later(1500, g) { engine().testRemote(peer, "play") { }; engine().testLocal("play") }
            }
            Kind.PARTITION_CLAIM -> {
                // The target cuts the link, then its user presses play while the source still plays.
                if (c.push) {
                    e.testRemote(peer, "after:300:outage:6000") { }
                    e.testRemote(peer, "after:1800:play") { }
                } else {
                    e.testLocal("after:300:outage:6000")
                    e.testLocal("after:1800:play")
                }
            }
            Kind.POLICY -> {}
        }
        later(c.settleMs, g) { evaluate(c, g, attempt = 0) }
    }

    private fun evaluate(c: Case, g: Long, attempt: Int) {
        val e = engine()
        val p = e.probe(peer)
        if (attempt > 30) return conclude(c, g, "inconclusive", p, "devices did not settle")
        if (!p.alive || !p.connected) return later(2000, g) { evaluate(c, g, attempt + 1) }
        remote("status", g, onFail = { later(2000, g) { evaluate(c, g, attempt + 1) } }) {
            val q = engine().probe(peer)
            val st = q.peer
            if (!q.alive || st == null || q.phase != Phase.IDLE || st.phase != "IDLE") {
                later(2000, g) { evaluate(c, g, attempt + 1) }
            } else {
                val self = engine().selfId
                val srcPlays = if (c.push) q.selfPlaying else st.playing
                val tgtPlays = if (c.push) st.playing else q.selfPlaying
                val src = if (c.push) self else peer
                val tgt = if (c.push) peer else self
                val agree = q.ownerId == st.ownerId && q.ownerEpoch == st.ownerEpoch
                val ok = when (c.expect) {
                    Expect.HANDED -> tgtPlays && !srcPlays && agree && q.ownerId == tgt
                    Expect.KEPT -> srcPlays && !tgtPlays && agree && q.ownerId == src
                    Expect.ONE -> (srcPlays xor tgtPlays) && agree && q.ownerId == (if (srcPlays) src else tgt)
                }
                conclude(c, g, if (ok) "pass" else "fail", q, "")
            }
        }
    }

    private fun conclude(c: Case, g: Long, verdict: String, p: SegueEngine.Probe, note: String) {
        when (verdict) { "pass" -> passed++; "fail" -> failed++; else -> inconclusive++ }
        val e = engine()
        e.testLog("case_end", "i=$index name=${c.name} verdict=$verdict self_playing=${p.selfPlaying} " +
            "peer_playing=${p.peer?.playing} owner=${p.ownerId} peer_owner=${p.peer?.ownerId} " +
            "phase=${p.phase} peer_phase=${p.peer?.phase}" + if (note.isEmpty()) "" else " note=$note")
        lastLine = "${c.name}: $verdict"
        e.testLocal("clear"); e.testLocal("allow")
        e.testRemote(peer, "clear") { }
        e.testRemote(peer, "allow") { }
        index++
        publish()
        later(GAP_MS, g) { next(g) }
    }

    companion object {
        /** Pause between cases; long enough for status messages to arrive and audio to settle. */
        const val GAP_MS = 3000L
    }
}

data class CampaignStatus(
    val running: Boolean = false,
    val preset: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val passed: Int = 0,
    val failed: Int = 0,
    val inconclusive: Int = 0,
    val lastLine: String = ""
)

internal enum class Expect { HANDED, KEPT, ONE }

internal enum class Kind { HANDOFF, REJECT_MANUAL, CLAIM, CONCURRENT_CLAIM, PARTITION_CLAIM, POLICY }

internal class Case(
    val name: String,
    /** true: the conductor is the source (push); false: the conductor is the target (pull). */
    val push: Boolean,
    val expect: Expect,
    val sourceFaults: List<String> = emptyList(),
    val targetFaults: List<String> = emptyList(),
    val settleMs: Long = 10_000,
    /** "no": any overlap is a failure; "hazard": the known orphaned-player hazard; "user": both users pressed play. */
    val expectDual: String = "no",
    val kind: Kind = Kind.HANDOFF,
    val policy: Rollback? = null
)

internal object CaseList {
    private fun both(name: String, expect: Expect, src: List<String> = emptyList(), tgt: List<String> = emptyList(),
                     settleMs: Long = 10_000, expectDual: String = "no", kind: Kind = Kind.HANDOFF) =
        listOf(true, false).map { Case(name, it, expect, src, tgt, settleMs, expectDual, kind) }

    private fun normal(n: Int) = (0 until n).map { Case("normal", it % 2 == 0, Expect.HANDED, settleMs = 6_000) }

    /** Message loss, delay and duplication: the retransmission and abort paths. */
    private fun messageFaults(): List<Case> =
        both("drop_prepare_once", Expect.HANDED, src = listOf("drop:PREPARE:1")) +
        both("drop_ready_once", Expect.HANDED, tgt = listOf("drop:READY:1")) +
        both("drop_ready_all", Expect.KEPT, tgt = listOf("drop:READY:60")) +
        both("drop_state_twice", Expect.HANDED, src = listOf("drop:STATE_TRANSFER:2")) +
        both("drop_state_all", Expect.KEPT, src = listOf("drop:STATE_TRANSFER:80")) +
        // The target plays but its report never arrives: it stops at the window end and asks the source.
        both("drop_target_playing_all", Expect.ONE, tgt = listOf("drop:TARGET_PLAYING:80")) +
        both("drop_commit_thrice", Expect.HANDED, src = listOf("drop:OWNER_COMMITTED:3")) +
        both("delay_300ms", Expect.HANDED, src = listOf("delay:300"), tgt = listOf("delay:300")) +
        both("duplicate_all", Expect.HANDED, src = listOf("dup:1"), tgt = listOf("dup:1"))

    private fun refusals(): List<Case> =
        both("reject_no_route", Expect.KEPT, tgt = listOf("noroute:1")) +
        listOf(Case("reject_manual_pause", true, Expect.KEPT, kind = Kind.REJECT_MANUAL))

    /** Link outages and coordinator crashes; these behave differently under the two rollback policies. */
    private fun outagesAndCrashes(policy: Rollback): List<Case> =
        both("outage_in_transfer", Expect.KEPT, src = listOf("on:TRANSFERRING:outage:8000"), settleMs = 14_000) +
        both("outage_in_provisional", Expect.KEPT, tgt = listOf("on:PROVISIONAL:outage:8000"), settleMs = 14_000) +
        both("crash_source_preparing", Expect.KEPT, src = listOf("on:PREPARING:crash:3000")) +
        both("crash_source_transferring", Expect.KEPT, src = listOf("on:TRANSFERRING:crash:3000")) +
        // Whether the restarted target's query reaches the source before its lease ends depends on reconnection time.
        both("crash_target_starting", Expect.ONE, tgt = listOf("on:TGT_STARTING:crash:3000")) +
        both("crash_target_provisional_short", Expect.ONE, tgt = listOf("on:PROVISIONAL:crash:1500")) +
        // The orphaned player: the target's coordinator is down past the source's lease while its player plays on.
        both("crash_target_provisional_long", Expect.KEPT, tgt = listOf("on:PROVISIONAL:crash:9000"), settleMs = 16_000,
            expectDual = if (policy == Rollback.RESUME_AFTER_LEASE) "hazard" else "no")

    private fun claims(): List<Case> =
        both("manual_claim", Expect.HANDED, kind = Kind.CLAIM, settleMs = 6_000) +
        // Both users press play at the same moment: each starts before the other's claim can arrive.
        listOf(Case("concurrent_claim", true, Expect.ONE, kind = Kind.CONCURRENT_CLAIM, settleMs = 8_000, expectDual = "user")) +
        both("claim_while_partitioned", Expect.ONE, kind = Kind.PARTITION_CLAIM, settleMs = 14_000, expectDual = "user")

    private fun policyStep(r: Rollback) = Case("policy_$r", true, Expect.ONE, kind = Kind.POLICY, policy = r)

    private fun faultRound(): List<Case> =
        listOf(policyStep(Rollback.RESUME_AFTER_LEASE)) +
        messageFaults() + refusals() + outagesAndCrashes(Rollback.RESUME_AFTER_LEASE) + claims() +
        listOf(policyStep(Rollback.SAFE_PAUSE)) +
        normal(2) + both("drop_state_all", Expect.KEPT, src = listOf("drop:STATE_TRANSFER:80")) +
        outagesAndCrashes(Rollback.SAFE_PAUSE) +
        listOf(policyStep(Rollback.RESUME_AFTER_LEASE))

    fun build(preset: Campaign.Preset): List<Case> = when (preset) {
        Campaign.Preset.QUICK -> normal(4) + faultRound()
        Campaign.Preset.TIMING -> normal(400)
        Campaign.Preset.FAULTS -> (0 until 3).flatMap { faultRound() }
        Campaign.Preset.FULL -> normal(400) + (0 until 3).flatMap { faultRound() }
    }
}
