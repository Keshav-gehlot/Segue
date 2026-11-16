package com.segue.engine

import com.segue.core.CoordinatorConfig
import com.segue.core.CoordinatorStateCodec
import com.segue.core.Crypto
import com.segue.core.Identity
import com.segue.core.Msg
import com.segue.core.MsgCodec
import com.segue.core.MsgType
import com.segue.core.Phase
import com.segue.core.PlayerPort
import com.segue.core.Rollback
import com.segue.core.Snapshot
import java.io.File
import java.io.FileWriter
import java.nio.file.Files

/*
 * End-to-end tests of the engine: real handshake, real AES-GCM channel, real coordinator, real
 * write-ahead persistence. The radio is an in-memory model and the players are fakes that record
 * when they were audible, which gives ground truth for gaps and overlaps.
 *
 * Run: java -cp <core+engine+tests> com.segue.engine.EngineSelfTestKt [outDir]
 */

private var failures = 0
private var passes = 0
private fun expect(name: String, cond: Boolean, detail: String = "") {
    if (cond) { passes++; println("PASS  $name") } else { failures++; println("FAIL  $name  $detail") }
}

private const val WALL0 = 1_763_280_000_000L

/** Records audible intervals in shared (true) time. */
class FakePlayer(private val sched: VirtualScheduler, var pauseLatMs: Long = 80, var startLatMs: Long = 250) : PlayerPort {
    /** Player contract P2: a start not complete by this deadline fails and never produces audio. */
    var startTimeoutMs: Long = Long.MAX_VALUE
    var engine: SegueEngine? = null
    var playing = false; private set
    private var posBase = 0L
    private var startedAt = 0L
    val intervals = ArrayList<LongArray>()        // [start, end) true time; end = MAX while playing
    val startPositions = ArrayList<Long>()
    val pausePositions = ArrayList<Long>()

    fun position() = if (playing) posBase + (sched.now - startedAt) else posBase
    override fun isPlaying() = playing
    override fun snapshot() = Snapshot("segue.ref", "chirp-v1", position(), true)

    override fun requestPause() {
        sched.schedule(pauseLatMs) {
            if (playing) { posBase = position(); playing = false; intervals.last()[1] = sched.now; pausePositions.add(posBase) }
            engine?.onPlayerPaused()
        }
    }

    override fun requestPlay(snapshot: Snapshot?) {
        if (startLatMs > startTimeoutMs) {
            sched.schedule(startTimeoutMs) { engine?.onPlayerStarted(false) }
            return
        }
        sched.schedule(startLatMs) {
            if (!playing) {
                if (snapshot != null) posBase = snapshot.positionMs
                playing = true; startedAt = sched.now
                intervals.add(longArrayOf(sched.now, Long.MAX_VALUE)); startPositions.add(posBase)
            }
            engine?.onPlayerStarted(true)
        }
    }
}

class ListSink : EventSink {
    val kinds = ArrayList<String>()
    val lines = ArrayList<String>()
    /** True (shared) time of each line, for ground-truth windows. */
    val stamps = ArrayList<Long>()
    var clock: () -> Long = { 0L }
    override fun event(monoMs: Long, wallMs: Long, device: Int, kind: String, detail: String) {
        kinds.add(kind); lines.add("$kind $detail"); stamps.add(clock())
    }
    fun count(kind: String) = kinds.count { it == kind }
}

class TeeSink(private val a: EventSink, private val b: EventSink) : EventSink {
    override fun event(monoMs: Long, wallMs: Long, device: Int, kind: String, detail: String) {
        a.event(monoMs, wallMs, device, kind, detail); b.event(monoMs, wallMs, device, kind, detail)
    }
}

/** In-memory radio: discovery, connection, per-direction latency, partitions, relay MITM, replay. */
class Air(private val sched: VirtualScheduler, var latencyMs: Long = 20) {
    inner class Node(val idx: Int) : Transport {
        lateinit var engine: SegueEngine
        var normalName: String? = null
        var pairName: String? = null
        val sent = ArrayList<Pair<String, ByteArray>>()
        val ep get() = "ep$idx"
        override fun transmit(endpointId: String, bytes: ByteArray) { sent.add(endpointId to bytes); deliver(this, endpointId, bytes) }
        override fun disconnect(endpointId: String) { drop(this, endpointId) }
        override fun startNormal(localName: String) { normalName = localName; forget(this) }
        override fun rediscover() { forget(this); rediscovers++ }
        var rediscovers = 0
        override fun stopNormal() { normalName = null }
        override fun startPairing(localName: String) { pairName = localName; forget(this) }
        override fun stopPairing() { pairName = null }
    }

    private class Conn(val a: Node, val b: Node) { var up = true }

    val nodes = ArrayList<Node>()
    private val conns = ArrayList<Conn>()
    private val blocked = HashSet<Pair<Int, Int>>()
    /** Model a relay that runs two separate Nearby connections and forwards bytes verbatim. */
    var relayMitm = false
    /** Like Nearby: report each advertiser once per discovery session (until startNormal/rediscover). */
    var oneShot = false
    /** (x, y): x's discovery never sees y, as when one phone's scan misses the other's advertising. */
    val deaf = HashSet<Pair<Int, Int>>()
    private val reported = HashSet<Triple<Int, Int, Boolean>>()
    private fun forget(n: Node) { reported.removeAll { it.first == n.idx } }

    fun node() = Node(nodes.size).also { nodes.add(it) }
    private fun byEp(ep: String) = nodes.first { it.ep == ep }
    private fun conn(x: Node, y: Node) = conns.firstOrNull { it.up && ((it.a === x && it.b === y) || (it.a === y && it.b === x)) }
    private fun isBlocked(x: Node, y: Node) = (x.idx to y.idx) in blocked || (y.idx to x.idx) in blocked
    fun connected(x: Node, y: Node) = conn(x, y) != null

    private fun scan() {
        for (x in nodes) for (y in nodes) {
            if (x === y || isBlocked(x, y)) continue
            if (conn(x, y) != null) {
                // Nearby still reports an advertiser that is already connected; the session's one report is then used up.
                if (oneShot && (x.idx to y.idx) !in deaf) {
                    if (x.normalName != null && y.normalName != null) reported.add(Triple(x.idx, y.idx, false))
                    if (x.pairName != null && y.pairName != null) reported.add(Triple(x.idx, y.idx, true))
                }
                continue
            }
            for (pairingSvc in listOf(false, true)) {
                val yName = (if (pairingSvc) y.pairName else y.normalName) ?: continue
                if ((if (pairingSvc) x.pairName else x.normalName) == null) continue   // x is not discovering
                if ((x.idx to y.idx) in deaf) continue
                if (oneShot && !reported.add(Triple(x.idx, y.idx, pairingSvc))) continue
                val req = x.engine.requestNameFor(y.ep, yName, pairingSvc) ?: continue
                if (!y.engine.shouldAccept(req, pairingSvc)) { x.engine.onLinkFailed(y.ep); continue }
                val c = Conn(x, y); conns.add(c)
                val tokX = ByteArray(32).also { Crypto.rng.nextBytes(it) }
                val tokY = if (relayMitm) ByteArray(32).also { Crypto.rng.nextBytes(it) } else tokX
                val ex = x.engine; val ey = y.engine
                sched.schedule(latencyMs) { if (c.up) { ex.onLinkUp(y.ep, false, tokX, pairingSvc); ey.onLinkUp(x.ep, true, tokY, pairingSvc) } }
                break
            }
        }
    }

    fun startScanning(intervalMs: Long = 300) { sched.schedule(intervalMs) { scan(); startScanning(intervalMs) } }

    fun deliver(from: Node, ep: String, bytes: ByteArray) {
        val to = byEp(ep)
        val c = conn(from, to) ?: return
        val target = to.engine
        sched.schedule(latencyMs) { if (c.up) target.onBytes(from.ep, bytes) }
    }

    private fun drop(from: Node, ep: String) {
        val to = byEp(ep)
        val c = conn(from, to) ?: return
        c.up = false
        val ef = from.engine; val et = to.engine
        sched.schedule(latencyMs) { ef.onLinkDown(ep); et.onLinkDown(from.ep) }
    }

    /** Process death: connections drop, only the survivors are told. */
    fun kill(n: Node) {
        for (c in conns.filter { it.up && (it.a === n || it.b === n) }) {
            c.up = false
            val other = if (c.a === n) c.b else c.a
            val eo = other.engine
            sched.schedule(latencyMs) { eo.onLinkDown(n.ep) }
        }
        n.normalName = null; n.pairName = null
    }

    fun partition(x: Node, y: Node) {
        blocked.add(x.idx to y.idx)
        conn(x, y)?.let { c ->
            c.up = false
            val ea = c.a.engine; val eb = c.b.engine
            sched.schedule(1) { ea.onLinkDown(c.b.ep); eb.onLinkDown(c.a.ep) }
        }
    }

    fun heal(x: Node, y: Node) { blocked.remove(x.idx to y.idx); blocked.remove(y.idx to x.idx) }
}

class Phone(val air: Air, val sched: VirtualScheduler, val name: String, var clockOffset: Long,
            val store: EngineStore = MemoryStore(), val identity: Identity = Identity.generate()) {
    val node = air.node()
    val player = FakePlayer(sched)
    val sink = ListSink().also { it.clock = { sched.now } }
    var route = true
    var bootId = "boot-1"
    var last: EngineState? = null
    var killRequested = false
    var testMode = false
    var cfg = CoordinatorConfig()
    var extraSink: EventSink? = null
    var campaignStatus = CampaignStatus()
    /** Replaces the built-in fake player (Spotify tests). */
    var customPlayer: com.segue.core.PlayerPort? = null
    var routeExtra: () -> Boolean = { true }
    lateinit var engine: SegueEngine
    val id get() = engine.selfId
    /** Lives outside the engine, like in the app's service, so it survives engine restarts. */
    val campaign = Campaign(sched, { engine }) { campaignStatus = it }

    fun boot(cfg: CoordinatorConfig = this.cfg, extraSink: EventSink? = this.extraSink) {
        this.cfg = cfg; this.extraSink = extraSink
        killRequested = false
        val e = SegueEngine(identity, store, OffsetScheduler(sched, clockOffset), customPlayer ?: player, { route && routeExtra() }, node,
            EngineConfig(coordinator = cfg, label = name), wallClock = { WALL0 + sched.now }, bootId = bootId,
            sink = if (extraSink != null) TeeSink(sink, extraSink) else sink)
        e.listener = object : EngineListener {
            override fun onState(state: EngineState) { last = state }
            override fun onKillRequested() { killRequested = true }
            // Coordinator crash with the player surviving: links drop, a new engine starts from the store.
            override fun onCrashRequested(restartAfterMs: Long) { die(); sched.schedule(restartAfterMs) { boot() } }
            override fun onPolicyRequested(rollback: com.segue.core.Rollback) {
                if (rollback == this@Phone.cfg.rollback) return
                engine.halt(); die()
                sched.post { boot(this@Phone.cfg.copy(rollback = rollback)) }
            }
        }
        e.testMode = testMode
        engine = e; node.engine = e; player.engine = e
        e.start()
    }

    fun die() { air.kill(node) }
}

/** A phone whose player is the Spotify controller, wired as in the app's service. */
private fun spotifyPhone(air: Air, sched: VirtualScheduler, name: String, offset: Long, account: SpotifyAccount): Pair<Phone, FakeSpotify> {
    val p = Phone(air, sched, name, offset)
    val app = FakeSpotify(account, sched, name)
    val cfg = SpotifyController.coordinatorConfig(com.segue.core.Rollback.RESUME_AFTER_LEASE)
    val ctl = SpotifyController(app, OffsetScheduler(sched, offset), SpotifyController.listenerFor { p.engine },
        cfg.startBudgetMs - 100, cfg.pauseBudgetMs - 200) { p.sink.event(0, 0, 0, "spotify", it) }
    app.controller = ctl
    p.customPlayer = ctl
    p.cfg = cfg
    p.routeExtra = { app.connected }
    return p to app
}

private fun overlapOf(x: FakeSpotify, y: FakeSpotify, end: Long): Long {
    var total = 0L
    for (i in x.intervals) for (j in y.intervals) {
        val s = maxOf(i[0], j[0]); val e = minOf(minOf(i[1], end), minOf(j[1], end))
        if (e > s) total += e - s
    }
    return total
}

private fun pair(sched: VirtualScheduler, a: Phone, b: Phone): Boolean {
    a.engine.beginPairing(); b.engine.beginPairing()
    sched.runFor(3000)
    val pa = a.last?.pairing; val pb = b.last?.pairing
    if (pa !is PairingView.Compare || pb !is PairingView.Compare || pa.sas != pb.sas) return false
    a.engine.confirmPairing(true); b.engine.confirmPairing(true)
    sched.runFor(3000)
    return a.last?.pairing is PairingView.Done && b.last?.pairing is PairingView.Done
}

private fun overlapSince(x: FakePlayer, y: FakePlayer, from: Long, end: Long): Long {
    var total = 0L
    for (i in x.intervals) for (j in y.intervals) {
        val s = maxOf(from, maxOf(i[0], j[0])); val e = minOf(minOf(i[1], end), minOf(j[1], end))
        if (e > s) total += e - s
    }
    return total
}

private fun overlapMs(x: FakePlayer, y: FakePlayer, end: Long): Long {
    var total = 0L
    for (i in x.intervals) for (j in y.intervals) {
        val s = maxOf(i[0], j[0]); val e = minOf(minOf(i[1], end), minOf(j[1], end))
        if (e > s) total += e - s
    }
    return total
}

fun main(args: Array<String>) {
    val outDir = File(args.getOrElse(0) { "build/engine-test" }).apply { mkdirs() }

    // ---------------------------------------------------------------- 1. pairing + handoff + pull + claim
    run {
        val sched = VirtualScheduler(); val air = Air(sched); air.startScanning()
        val csvA = FileWriter(File(outDir, "phoneA.csv")); val csvB = FileWriter(File(outDir, "phoneB.csv"))
        val A = Phone(air, sched, "Phone A", 0)
        val B = Phone(air, sched, "Phone B", 7_654_321)          // unrelated monotonic origin
        A.boot(extraSink = CsvEventSink(csvA)); B.boot(extraSink = CsvEventSink(csvB))
        sched.runFor(100)

        A.engine.beginPairing(); B.engine.beginPairing()
        sched.runFor(3000)
        val pa = A.last?.pairing; val pb = B.last?.pairing
        expect("pairing: both phones show the same 6-digit code",
            pa is PairingView.Compare && pb is PairingView.Compare && pa.sas == pb.sas && pa.sas.length == 6, "$pa / $pb")
        expect("pairing: nothing is saved before the user confirms", A.engine.debugMembers().isEmpty() && B.engine.debugMembers().isEmpty())
        A.engine.confirmPairing(true)
        sched.runFor(2000)
        expect("pairing: one side confirming is not enough", A.engine.debugMembers().isEmpty() && A.last?.pairing is PairingView.Waiting)
        B.engine.confirmPairing(true)
        sched.runFor(2000)
        expect("pairing: both finish", A.last?.pairing is PairingView.Done && B.last?.pairing is PairingView.Done, "${A.last?.pairing} ${B.last?.pairing}")
        expect("pairing: each roster pins the other's identity key",
            A.engine.debugMembers().singleOrNull()?.publicKey?.contentEquals(B.identity.publicBytes) == true &&
                B.engine.debugMembers().singleOrNull()?.publicKey?.contentEquals(A.identity.publicBytes) == true)
        expect("pairing: both hold the same group key", A.engine.debugGroupKey()?.contentEquals(B.engine.debugGroupKey()) == true)
        expect("pairing: the pairing link becomes the authenticated peer link",
            A.engine.debugConnected() == setOf(B.id) && B.engine.debugConnected() == setOf(A.id))
        expect("advertising: rotating names differ per phone and carry no stable id",
            A.node.normalName != null && B.node.normalName != null && A.node.normalName != B.node.normalName && A.node.normalName!!.length == 16)

        A.engine.play(); sched.runFor(2000)
        expect("play: A owns and plays", A.player.playing && A.engine.debugOwner()?.first == A.id)
        expect("ownership sync: B learned A owns via the authenticated status", B.engine.debugOwner() == A.engine.debugOwner(),
            "${B.engine.debugOwner()} vs ${A.engine.debugOwner()}")

        sched.runFor(10_000)
        val tReq = sched.now
        A.engine.handoffTo(B.id)
        sched.runFor(8000)
        expect("handoff: B plays, A is paused", B.player.playing && !A.player.playing)
        expect("handoff: both agree B owns", A.engine.debugOwner() == B.engine.debugOwner() && B.engine.debugOwner()?.first == B.id)
        val aEnd = A.player.intervals.last()[1]; val bStart = B.player.intervals.last()[0]
        val gap = bStart - aEnd
        expect("handoff: break-before-make (gap > 0, no overlap)", gap > 0 && overlapMs(A.player, B.player, sched.now) == 0L, "gap=$gap")
        expect("handoff: B resumes exactly where A paused", B.player.startPositions.last() == A.player.pausePositions.last(),
            "${B.player.startPositions.last()} vs ${A.player.pausePositions.last()}")
        val trigToAudible = bStart - tReq
        println("      handoff timing (model latencies 20 ms link, 80 ms pause, 250 ms start): gap=$gap ms, request-to-audible=$trigToAudible ms")
        expect("handoff: both phones back to IDLE", A.engine.debugCoordinatorPhase() == Phase.IDLE && B.engine.debugCoordinatorPhase() == Phase.IDLE)
        File(outDir, "truth.txt").writeText("gap_ms=$gap\nrequest_to_audible_ms=$trigToAudible\nsource_pause_true=$aEnd\ntarget_start_true=$bStart\n")
        csvA.flush(); csvB.flush()

        sched.runFor(3000)
        A.engine.pullHere(); sched.runFor(8000)
        expect("pull: 'play here' on A moves playback back with position", A.player.playing && !B.player.playing &&
            A.player.startPositions.last() == B.player.pausePositions.last())
        expect("pull: no overlap", overlapMs(A.player, B.player, sched.now) == 0L)

        sched.runFor(3000)
        B.engine.play(); sched.runFor(3000)
        expect("manual claim: B plays, A pauses, single owner", B.player.playing && !A.player.playing &&
            A.engine.debugOwner() == B.engine.debugOwner())

        // pause on B, then pull to B must still work (lifts the manual suspension)
        B.engine.pause(); sched.runFor(1000)
        A.engine.play(); sched.runFor(2000)
        B.engine.pullHere(); sched.runFor(8000)
        expect("pull after a manual pause on the target works", B.player.playing && !A.player.playing,
            "A=${A.player.playing} B=${B.player.playing}\nA:\n" + A.sink.lines.takeLast(25).joinToString("\n") + "\nB:\n" + B.sink.lines.takeLast(25).joinToString("\n"))

        // route not ready on the target: the handoff is refused and the source keeps playing
        A.route = false; A.engine.onRouteChanged()
        B.engine.handoffTo(A.id); sched.runFor(5000)
        expect("no headset route on target: refused, source keeps playing", B.player.playing && !A.player.playing &&
            A.sink.lines.any { it.contains("REJECTED") && it.contains("no-route") })
        A.route = true; A.engine.onRouteChanged()

        // replay and tampering on the live link
        val rejectsBefore = A.sink.count("frame_reject")
        val lastData = B.node.sent.last { it.second[0].toInt() == 0x10 }
        air.deliver(B.node, lastData.first, lastData.second)
        val flipped = B.node.sent.last { it.second[0].toInt() == 0x10 }.second.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        air.deliver(B.node, A.node.ep, flipped)
        sched.runFor(500)
        expect("channel: replayed and bit-flipped frames are rejected", A.sink.count("frame_reject") == rejectsBefore + 2)

        // rotating names across epochs: partition for 20 minutes, then heal
        air.partition(A.node, B.node); sched.runFor(20 * 60_000L)
        expect("partition: link is down", A.engine.debugConnected().isEmpty())
        air.heal(A.node, B.node); sched.runFor(5000)
        expect("reconnect after 20 min (names rotated twice): authenticated link back", A.engine.debugConnected() == setOf(B.id))
        expect("clock offset estimate is within 1 ms of the true origin difference",
            A.last?.peers?.firstOrNull()?.clockOffsetMs?.let { kotlin.math.abs(it - 7_654_321) <= 1 } == true,
            "${A.last?.peers?.firstOrNull()?.clockOffsetMs}")
        csvA.close(); csvB.close()
    }

    // ---------------------------------------------------------------- 2. pairing negatives and relay MITM
    run {
        val sched = VirtualScheduler(); val air = Air(sched); air.startScanning()
        val C = Phone(air, sched, "C", 0); val D = Phone(air, sched, "D", 99)
        C.boot(); D.boot(); sched.runFor(100)
        C.engine.beginPairing(); D.engine.beginPairing(); sched.runFor(3000)
        C.engine.confirmPairing(false); sched.runFor(2000)
        expect("pairing: 'codes don't match' cancels on both phones, nothing saved",
            C.last?.pairing is PairingView.Failed && D.last?.pairing is PairingView.Failed &&
                C.engine.debugMembers().isEmpty() && D.engine.debugMembers().isEmpty(), "${C.last?.pairing} ${D.last?.pairing}")

        air.relayMitm = true
        C.engine.beginPairing(); D.engine.beginPairing(); sched.runFor(3000)
        val same = (C.last?.pairing as? PairingView.Compare)?.sas == (D.last?.pairing as? PairingView.Compare)?.sas
        C.engine.confirmPairing(true); D.engine.confirmPairing(true); sched.runFor(3000)
        expect("relay MITM during pairing: codes look identical but channel binding stops it, nothing saved",
            same && C.engine.debugMembers().isEmpty() && D.engine.debugMembers().isEmpty() &&
                (C.sink.lines.any { it.contains("handshake rejected") } || D.sink.lines.any { it.contains("handshake rejected") }))
        air.relayMitm = false

        expect("pair for real", pair(sched, C, D))
        air.relayMitm = true
        C.die(); D.die(); C.bootId = "x"; D.bootId = "x"; C.boot(); D.boot(); sched.runFor(15_000)
        expect("relay MITM on a normal reconnect: no link is established", C.engine.debugConnected().isEmpty() && D.engine.debugConnected().isEmpty() &&
            (C.sink.count("link_fail") + D.sink.count("link_fail")) > 0)
        air.relayMitm = false
        sched.runFor(15_000)
        expect("without the relay the same phones connect", C.engine.debugConnected() == setOf(D.id))
    }

    // ---------------------------------------------------------------- 3. three phones, group rules, sender spoofing
    run {
        val sched = VirtualScheduler(); val air = Air(sched); air.startScanning()
        val A = Phone(air, sched, "A", 0); val B = Phone(air, sched, "B", 1000); val C = Phone(air, sched, "C", 2000)
        val X = Phone(air, sched, "X", 3000); val Y = Phone(air, sched, "Y", 4000)
        listOf(A, B, C, X, Y).forEach { it.boot() }; sched.runFor(100)
        expect("3 phones: A+B pair", pair(sched, A, B))
        expect("3 phones: C joins via A and takes the group key", pair(sched, C, A) && C.engine.debugGroupKey()?.contentEquals(A.engine.debugGroupKey()) == true)
        expect("3 phones: C also pairs with B (same key already)", pair(sched, C, B))
        sched.runFor(5000)
        expect("3 phones: full mesh of authenticated links", A.engine.debugConnected() == setOf(B.id, C.id) &&
            B.engine.debugConnected() == setOf(A.id, C.id) && C.engine.debugConnected() == setOf(A.id, B.id))
        expect("separate group: X+Y pair", pair(sched, X, Y))
        expect("two established groups cannot be merged by pairing", !pair(sched, X, A) && X.engine.debugMembers().size == 1 && A.engine.debugMembers().size == 2)

        A.engine.play(); sched.runFor(3000)
        val bOwner = B.engine.debugOwner()
        C.engine.debugSendRaw(B.id, 1, MsgCodec.encode(Msg(MsgType.OWNER_CLAIM, A.id, B.id, 0, 999)))
        sched.runFor(1000)
        expect("a member cannot speak for another member (sender bound to the link)", B.engine.debugOwner() == bOwner &&
            B.sink.lines.any { it.startsWith("msg_reject") })
        A.engine.handoffTo(C.id); sched.runFor(8000)
        expect("3 phones: handoff A->C; B also learns C owns", C.player.playing && !A.player.playing && !B.player.playing &&
            B.engine.debugOwner()?.first == C.id)
    }

    // ---------------------------------------------------------------- 4. crash during the provisional window, restart from disk
    fun crashScenario(rollback: Rollback, restartTarget: Boolean, restartDelayMs: Long = 1000): Triple<Phone, Phone, VirtualScheduler> {
        val sched = VirtualScheduler(); val air = Air(sched); air.startScanning()
        val dirA = Files.createTempDirectory("apA").toFile(); val dirB = Files.createTempDirectory("apB").toFile()
        val cfg = CoordinatorConfig(rollback = rollback)
        val A = Phone(air, sched, "A", 0, FileStore(dirA)); val B = Phone(air, sched, "B", 31_337, FileStore(dirB))
        A.boot(cfg); B.boot(cfg); sched.runFor(100)
        check(pair(sched, A, B))
        A.engine.play(); sched.runFor(3000)
        B.engine.faults.killInPhase = Phase.PROVISIONAL
        A.engine.handoffTo(B.id)
        var t = 0
        while (!B.killRequested && t < 10_000) { sched.runFor(10); t += 10 }
        B.die()                                   // coordinator gone; the fake player keeps playing (orphaned)
        if (restartTarget) {
            sched.runFor(restartDelayMs)
            B.boot(cfg)
        }
        sched.runFor(15_000)
        return Triple(A, B, sched)
    }

    run {
        val (A, B, sched) = crashScenario(Rollback.RESUME_AFTER_LEASE, restartTarget = true)
        val stored = (B.store.loadCoordinator())?.let { Codec.decodeStored(it) }
        expect("crash: target was killed right after persisting PROVISIONAL", B.sink.lines.any { it.contains("kill after persisting PROVISIONAL") })
        val oneOwner = A.engine.debugOwner() == B.engine.debugOwner()
        val owner = A.engine.debugOwner()?.first
        val playingIds = listOfNotNull(A.id.takeIf { A.player.playing }, B.id.takeIf { B.player.playing })
        expect("crash+restart: target stops its uncommitted playback and recovers; exactly the agreed owner plays",
            oneOwner && playingIds == listOf(owner) && B.sink.lines.any { it.contains("PROVISIONAL->TGT_STOPPING via restart") },
            "owners=${A.engine.debugOwner()}/${B.engine.debugOwner()} playing=$playingIds")
        println("      crash outcome: owner=" + (if (owner == A.id) "source (lease rollback)" else "target (committed via RECOVERY_QUERY)"))
        expect("crash+restart: no dual playback at any time", overlapMs(A.player, B.player, sched.now) == 0L,
            "overlap=${overlapMs(A.player, B.player, sched.now)}")
        expect("crash+restart: stored state decodes and re-encodes identically",
            stored != null && CoordinatorStateCodec.decode(CoordinatorStateCodec.encode(stored.second)) == stored.second)
    }
    run {
        val (A, B, sched) = crashScenario(Rollback.RESUME_AFTER_LEASE, restartTarget = false)
        val ov = overlapMs(A.player, B.player, sched.now)
        expect("known hazard reproduced: target coordinator never returns + RESUME_AFTER_LEASE -> dual playback", ov > 0, "overlap=$ov")
    }
    run {
        val (A, B, sched) = crashScenario(Rollback.SAFE_PAUSE, restartTarget = true, restartDelayMs = 7000)
        val bStopped = B.player.intervals.last()[1]
        val aResumed = A.player.intervals.last()[0]
        expect("SAFE_PAUSE + late restart: source stays silent until the restarted target confirms it stopped, then resumes",
            A.player.playing && !B.player.playing && aResumed > bStopped && overlapMs(A.player, B.player, sched.now) == 0L &&
                A.engine.debugCoordinatorPhase() == Phase.IDLE && B.engine.debugCoordinatorPhase() == Phase.IDLE,
            "A=${A.player.playing}/${A.engine.debugCoordinatorPhase()} B=${B.player.playing}/${B.engine.debugCoordinatorPhase()}")
    }
    run {
        val (A, B, sched) = crashScenario(Rollback.SAFE_PAUSE, restartTarget = false)
        expect("SAFE_PAUSE removes it: source stays paused, no dual playback", !A.player.playing && overlapMs(A.player, B.player, sched.now) == 0L)
    }

    // ---------------------------------------------------------------- 5. source reboot in TRANSFERRING (clock origin resets)
    run {
        val sched = VirtualScheduler(); val air = Air(sched); air.startScanning()
        val A = Phone(air, sched, "A", 500_000_000, FileStore(Files.createTempDirectory("apR").toFile()))
        val B = Phone(air, sched, "B", 0)
        A.boot(); B.boot(); sched.runFor(100)
        check(pair(sched, A, B))
        A.engine.play(); sched.runFor(3000)
        A.engine.faults.killInPhase = Phase.TRANSFERRING
        A.engine.handoffTo(B.id)
        var t = 0
        while (!A.killRequested && t < 10_000) { sched.runFor(10); t += 10 }
        A.die()
        sched.runFor(30_000)                                  // "reboot"
        A.clockOffset = 0; A.bootId = "boot-2"                // elapsedRealtime restarted near zero
        val tBoot = sched.now
        A.boot()
        sched.runFor(2000)
        val resumedAt = A.player.intervals.lastOrNull()?.get(0) ?: Long.MAX_VALUE
        expect("reboot: lease treated as expired, source resumes within 2 s instead of waiting ~500,000 s",
            A.player.playing && resumedAt - tBoot < 2000 && A.engine.debugCoordinatorPhase() == Phase.IDLE, "resumedAt-tBoot=${resumedAt - tBoot}")
        expect("reboot: target never played the aborted transaction", B.player.intervals.isEmpty())
    }

    // ---------------------------------------------------------------- 6. concurrent claims and slow players (review items)
    run {
        val sched = VirtualScheduler(); val air = Air(sched); air.startScanning()
        val A = Phone(air, sched, "A", 0); val B = Phone(air, sched, "B", 42)
        A.boot(); B.boot(); sched.runFor(100)
        check(pair(sched, A, B))
        A.engine.play(); sched.runFor(3000)
        air.partition(A.node, B.node); sched.runFor(1000)
        A.engine.play(); B.engine.play(); sched.runFor(3000)            // both users press play while apart
        expect("claims while partitioned: both play (each follows its own user's command)", A.player.playing && B.player.playing)
        val tHeal = sched.now
        air.heal(A.node, B.node)
        var t = 0L
        while (t < 20_000 && (A.player.playing == B.player.playing || A.engine.debugOwner() != B.engine.debugOwner())) { sched.runFor(10); t += 10 }
        sched.runFor(2000)
        val owner = A.engine.debugOwner()?.first
        val playing = listOfNotNull(A.id.takeIf { A.player.playing }, B.id.takeIf { B.player.playing })
        expect("claims while partitioned: after reconnection they converge to one owner, the one that plays",
            A.engine.debugOwner() == B.engine.debugOwner() && playing == listOf(owner) && owner == maxOf(A.id, B.id),
            "owners=${A.engine.debugOwner()}/${B.engine.debugOwner()} playing=$playing")
        println("      convergence after reconnection: ${t} ms (includes discovery and handshake)")

        // same instant while connected
        A.engine.pause(); B.engine.pause(); sched.runFor(2000)
        A.engine.play(); B.engine.play()
        val t0 = sched.now
        t = 0
        while (t < 10_000 && (A.player.playing == B.player.playing || A.engine.debugOwner() != B.engine.debugOwner())) { sched.runFor(10); t += 10 }
        sched.runFor(2000)
        expect("simultaneous claims while connected: converge to one owner",
            A.engine.debugOwner() == B.engine.debugOwner() && (A.player.playing xor B.player.playing))
        println("      convergence of simultaneous claims: ${sched.now - 2000 - t0} ms")

        // target player slower than b_start but honouring P2: the handoff fails safely
        val owner2 = if (A.player.playing) A else B
        val other = if (owner2 === A) B else A
        other.player.startLatMs = 1400; other.player.startTimeoutMs = 950
        val before = other.player.intervals.size
        val tStart = sched.now
        owner2.engine.handoffTo(other.id); sched.runFor(10_000)
        expect("target slower than b_start (P2 honoured): start fails, source keeps playing, no overlap",
            owner2.player.playing && !other.player.playing && other.player.intervals.size == before &&
                overlapSince(A.player, B.player, tStart, sched.now) == 0L,
            "src=${owner2.player.playing} tgt=${other.player.playing} overlap=${overlapSince(A.player, B.player, tStart, sched.now)}")
    }

    // ---------------------------------------------------------------- automated campaign (all test cases)
    run {
        val sched = VirtualScheduler(); val air = Air(sched); air.startScanning()
        val csvA = FileWriter(File(outDir, "campaignA.csv")); val csvB = FileWriter(File(outDir, "campaignB.csv"))
        val A = Phone(air, sched, "A", 0); val B = Phone(air, sched, "B", 3_333_333); val C = Phone(air, sched, "C", 99)
        A.testMode = true; B.testMode = true
        A.boot(extraSink = CsvEventSink(csvA)); B.boot(extraSink = CsvEventSink(csvB)); C.boot(); sched.runFor(100)
        check(pair(sched, A, B)); check(pair(sched, C, A)); check(pair(sched, C, B))
        sched.runFor(3000)

        // Test mode off on the peer: commands are refused and the campaign stops with a clear message.
        B.engine.testMode = false
        A.campaign.start(Campaign.Preset.QUICK, B.id); sched.runFor(6000)
        expect("campaign: refuses to run while the peer's test mode is off",
            !A.campaignStatus.running && A.campaignStatus.lastLine.contains("Test mode") && B.sink.lines.any { it.startsWith("test_refused") })
        B.engine.testMode = true

        val cBefore = C.player.intervals.size
        A.campaign.start(Campaign.Preset.QUICK, B.id)
        var guard = 0
        do { sched.runFor(5000); guard++ } while (A.campaignStatus.running && guard < 2000)
        val st = A.campaignStatus
        csvA.flush(); csvB.flush()

        // Ground truth per case: overlap of the two fake players inside the case's window.
        class Row(val name: String, val dir: String, val policy: String, val expectDual: String, val begin: Long) { var end = Long.MAX_VALUE; var verdict = "?" }
        val rows = ArrayList<Row>()
        fun field(line: String, key: String) = Regex("$key=(\\S+)").find(line)?.groupValues?.get(1) ?: ""
        for ((i, line) in A.sink.lines.withIndex()) {
            if (line.startsWith("case_begin")) {
                rows.lastOrNull()?.let { if (it.end == Long.MAX_VALUE) it.end = A.sink.stamps[i] }
                rows.add(Row(field(line, "name"), field(line, "dir"), field(line, "policy"), field(line, "expect_dual"), A.sink.stamps[i]))
            } else if (line.startsWith("case_end")) rows.lastOrNull()?.verdict = field(line, "verdict")
        }
        rows.lastOrNull()?.let { if (it.end == Long.MAX_VALUE) it.end = sched.now }
        fun overlapIn(r: Row): Long {
            var total = 0L
            for (i in A.player.intervals) for (j in B.player.intervals) {
                val s0 = maxOf(r.begin, maxOf(i[0], j[0])); val e0 = minOf(r.end, minOf(minOf(i[1], sched.now), minOf(j[1], sched.now)))
                if (e0 > s0) total += e0 - s0
            }
            return total
        }
        val truth = StringBuilder("name,dir,policy,expect_dual,verdict,overlap_ms\n")
        println("      case                              dir   policy              verdict  overlap_ms")
        for (r in rows) {
            val ov = overlapIn(r)
            truth.append("${r.name},${r.dir},${r.policy},${r.expectDual},${r.verdict},$ov\n")
            println("      %-33s %-5s %-19s %-8s %d%s".format(r.name, r.dir, r.policy, r.verdict, ov, if (r.expectDual != "no") "  (expected: ${r.expectDual})" else ""))
        }
        File(outDir, "campaign_truth.csv").writeText(truth.toString())

        expect("campaign: quick preset ran every case (${st.total} steps) and finished", !st.running && st.lastLine == "Finished" && rows.size >= 55,
            "running=${st.running} last=${st.lastLine} rows=${rows.size}")
        expect("campaign: every case got the expected outcome", st.failed == 0 && st.inconclusive == 0 && rows.all { it.verdict == "pass" },
            "passed=${st.passed} failed=${st.failed} inconclusive=${st.inconclusive}: " + rows.filter { it.verdict != "pass" }.joinToString { "${it.name}/${it.dir}/${it.policy}=${it.verdict}" })
        val unexpected = rows.filter { it.expectDual == "no" && overlapIn(it) > 0 }
        expect("campaign: no overlap of the two players in any case that forbids it", unexpected.isEmpty(),
            unexpected.joinToString { "${it.name}/${it.dir}/${it.policy}=${overlapIn(it)}ms" })
        val hazard = rows.filter { it.expectDual == "hazard" }
        expect("campaign: orphaned-player hazard appears under rollback (both directions) and never under safe-pause",
            hazard.size == 2 && hazard.all { overlapIn(it) > 1000 } &&
                rows.filter { it.name == "crash_target_provisional_long" && it.policy == "SAFE_PAUSE" }.let { sp -> sp.size == 2 && sp.all { overlapIn(it) == 0L } },
            hazard.joinToString { "${it.dir}=${overlapIn(it)}" })
        expect("campaign: both devices end on the default rollback policy, faults cleared",
            A.last?.policy == "RESUME_AFTER_LEASE" && B.last?.policy == "RESUME_AFTER_LEASE" && !A.engine.faults.duplicate && !B.engine.faults.forceNoRoute)
        expect("campaign: the third device never played", C.player.intervals.size == cBefore)
        println("      campaign: ${rows.size} cases in ${(rows.last().end - rows.first().begin) / 60000} min of simulated time")

        // Stop mid-run
        A.campaign.start(Campaign.Preset.TIMING, B.id); sched.runFor(40_000)
        val mid = A.campaignStatus
        A.campaign.stop(); sched.runFor(2000)
        expect("campaign: can be stopped mid-run", mid.running && mid.index >= 2 && !A.campaignStatus.running)
        csvA.close(); csvB.close()
    }

    // ---------------------------------------------------------------- log export
    run {
        val dir = Files.createTempDirectory("sglogs").toFile()
        File(dir, "segue-1.csv").writeText("t_mono_ms,t_wall_ms,device,kind,detail\n1,2,3,start,\"x\"\n")
        File(dir, "segue-2.csv").writeText("t_mono_ms,t_wall_ms,device,kind,detail\n")
        val bo = java.io.ByteArrayOutputStream()
        val n = LogExport.zip(bo, dir.listFiles()!!.toList(), mapOf("device.txt" to "model=test"))
        val names = ArrayList<String>(); var body = ""
        java.util.zip.ZipInputStream(bo.toByteArray().inputStream()).use { z ->
            while (true) { val e = z.nextEntry ?: break; names.add(e.name); if (e.name == "logs/segue-1.csv") body = z.readBytes().decodeToString() }
        }
        expect("log export: ZIP holds device info and every log file intact",
            n == 2 && names == listOf("device.txt", "logs/segue-1.csv", "logs/segue-2.csv") && body.contains("3,start"))
    }

    // ---------------------------------------------------------------- 6b. reconnection with Nearby-like discovery
    // Nearby reports each advertiser once per discovery session, and discovery is often one-sided.
    // The earlier sections scan continuously and symmetrically, which hid both problems.
    for (deafLower in listOf(false, true)) run {
        val sched = VirtualScheduler(); val air = Air(sched); air.oneShot = true; air.startScanning()
        val A = Phone(air, sched, "Phone A", 0); val B = Phone(air, sched, "Phone B", 1_000)
        A.boot(); B.boot(); sched.runFor(100)
        val tag = if (deafLower) "one-sided discovery" else "one report per session"
        expect("reconnect ($tag): pairing works", pair(sched, A, B))
        sched.runFor(20_000)
        val (lo, hi) = if (A.id < B.id) A to B else B to A
        if (deafLower) air.deaf.add(lo.node.idx to hi.node.idx)      // the phone that normally initiates never sees the other
        air.partition(A.node, B.node); sched.runFor(5_000); air.heal(A.node, B.node)
        val t0 = sched.now
        var t = -1L
        while (sched.now - t0 < 60_000) { sched.runFor(500); if (A.engine.debugConnected() == setOf(B.id) && B.engine.debugConnected() == setOf(A.id)) { t = sched.now - t0; break } }
        expect("reconnect ($tag): the link comes back on its own", t >= 0,
            "A:\n" + A.sink.lines.takeLast(15).joinToString("\n") + "\nB:\n" + B.sink.lines.takeLast(15).joinToString("\n"))
        println("      reconnect ($tag): ${t} ms after the partition healed")
        sched.runFor(30_000)
        expect("reconnect ($tag): exactly one link, still up 30 s later",
            A.engine.debugConnected() == setOf(B.id) && B.engine.debugConnected() == setOf(A.id) && air.connected(A.node, B.node))
        if (deafLower) expect("reconnect ($tag): the higher id initiated", hi.sink.lines.any { it.startsWith("radio") && it.contains("initiate=true") })
        A.engine.play(); sched.runFor(2_000); A.engine.handoffTo(B.id); sched.runFor(8_000)
        expect("reconnect ($tag): a handoff works over the new link", B.player.playing && !A.player.playing)
    }

    // ---------------------------------------------------------------- 6c. Spotify as the player, one account on both phones
    run {
        val sched = VirtualScheduler(); val air = Air(sched); air.startScanning()
        val acct = SpotifyAccount(sched)
        val (A, sa) = spotifyPhone(air, sched, "Phone A", 0, acct)
        val (B, sb) = spotifyPhone(air, sched, "Phone B", 5_000, acct)
        A.boot(); B.boot(); sched.runFor(100)
        expect("spotify: pairing works", pair(sched, A, B))
        fun tail() = "\nA:\n" + A.sink.lines.takeLast(30).joinToString("\n") + "\nB:\n" + B.sink.lines.takeLast(30).joinToString("\n")
        fun owners() = A.engine.debugOwner() to B.engine.debugOwner()

        sa.userPlay("spotify:track:one"); sched.runFor(4_000)
        expect("spotify: a song started in the Spotify app is detected and this phone becomes the owner",
            sa.audible && A.last?.playing == true && A.engine.debugOwner()?.first == A.id && B.engine.debugOwner() == A.engine.debugOwner(), tail())
        expect("spotify: the other phone sees the same session but does not count it as playing here",
            B.last?.playing == false && !sb.audible && sb.commands.isEmpty(), "B cmds=${sb.commands}")

        sched.runFor(20_000)
        var t0 = sched.now
        A.engine.handoffTo(B.id); sched.runFor(14_000)
        expect("spotify handoff: audio is on B only, both agree B owns, both idle",
            sb.audible && !sa.audible && owners().first == owners().second && B.engine.debugOwner()?.first == B.id &&
                A.engine.debugCoordinatorPhase() == Phase.IDLE && B.engine.debugCoordinatorPhase() == Phase.IDLE, tail())
        val gap = sb.intervals.last()[0] - sa.intervals.last()[1]
        val pausedAt = A.sink.lines.last { it.startsWith("player_cb paused") }.substringAfter("pos=").toLong()
        expect("spotify handoff: break-before-make, and B continues from A's position",
            gap > 0 && overlapOf(sa, sb, sched.now) == 0L && kotlin.math.abs(acct.beginPositions.last() - pausedAt) < 100, "gap=$gap paused=$pausedAt start=${acct.beginPositions.last()}")
        println("      spotify handoff (model: 60 ms commands, 300 ms audio start): gap=$gap ms")
        expect("spotify handoff: the source sends one pause and nothing after; the target uses Spotify's own transfer",
            sa.commandsSince(t0) == listOf("pause") && sb.commandsSince(t0).first() == "switch" && "play" !in sb.commandsSince(t0), "A=${sa.commandsSince(t0)} B=${sb.commandsSince(t0)}")
        expect("spotify handoff: the source does not mistake the session playing on B for playback here",
            A.last?.playing == false && A.sink.lines.none { it.contains("Spotify started playing here") && A.sink.lines.indexOf(it) > A.sink.lines.indexOfLast { l -> l.startsWith("cmd handoff") } }, tail())

        sched.runFor(10_000)
        t0 = sched.now
        A.engine.pullHere(); sched.runFor(14_000)
        expect("spotify pull: playback moves back to A", sa.audible && !sb.audible && A.engine.debugOwner()?.first == A.id && owners().first == owners().second, tail())

        sa.userPause(); sched.runFor(3_000)
        expect("spotify: pausing in the Spotify app is seen as the user's pause", !sa.audible && A.last?.playing == false &&
            A.sink.lines.any { it.contains("Spotify was paused here") })
        sa.userResume(); sched.runFor(3_000)
        expect("spotify: resuming in the Spotify app is seen", sa.audible && A.last?.playing == true)

        t0 = sched.now
        sb.userMoveHere(); sched.runFor(6_000)
        expect("spotify: moving playback in Spotify's own device list is followed (B owns, A sends nothing)",
            sb.audible && !sa.audible && B.engine.debugOwner()?.first == B.id && owners().first == owners().second && sa.commandsSince(t0).isEmpty(),
            "A cmds=${sa.commandsSince(t0)} owners=${owners()}" + tail())

        // "Play here now" on A while B plays: A's transfer and B's pause race inside Spotify.
        for (lat in listOf(60L, 250L, 900L)) {
            if (!sb.audible) { sb.userMoveHere(); sb.userResume(); sched.runFor(6_000) }
            sb.pauseLatMs = lat
            A.engine.play(); sched.runFor(9_000)
            expect("spotify claim (B's pause takes $lat ms): A ends up playing and owning", sa.audible && !sb.audible &&
                A.engine.debugOwner()?.first == A.id && owners().first == owners().second && A.last?.playing == true, "owners=${owners()}" + tail())
            sched.runFor(3_000)
        }
        sb.pauseLatMs = 60

        // the target's Spotify app is not reachable: it reports "not ready", the handoff is refused
        sb.connected = false; B.engine.onRouteChanged(); sched.runFor(6_000)
        A.engine.handoffTo(B.id); sched.runFor(6_000)
        expect("spotify: a target whose Spotify app is not connected refuses; the source keeps playing", sa.audible && !sb.audible && A.engine.debugOwner()?.first == A.id, tail())
        sb.connected = true; B.engine.onRouteChanged(); sched.runFor(6_000)

        // the target's Spotify accepts commands but never starts: the start times out and the source resumes
        sb.dead = true
        A.engine.handoffTo(B.id); sched.runFor(20_000)
        expect("spotify: a start that never becomes audible fails, and the source resumes", sa.audible && !sb.audible &&
            A.engine.debugOwner()?.first == A.id && A.engine.debugCoordinatorPhase() == Phase.IDLE && B.engine.debugCoordinatorPhase() == Phase.IDLE, "owners=${owners()}" + tail())
        sb.dead = false
    }

    // ---------------------------------------------------------------- 6d. Spotify, a different account on each phone
    run {
        val sched = VirtualScheduler(); val air = Air(sched); air.startScanning()
        val (A, sa) = spotifyPhone(air, sched, "Phone A", 0, SpotifyAccount(sched))
        val (B, sb) = spotifyPhone(air, sched, "Phone B", 5_000, SpotifyAccount(sched))
        A.boot(); B.boot(); sched.runFor(100)
        expect("spotify (two accounts): pairing works", pair(sched, A, B))
        sb.account.mutate { uri = "spotify:track:old"; setPosition(42_000) }      // B's own account has another track loaded, paused
        sa.userPlay("spotify:track:two"); sched.runFor(30_000)
        A.engine.handoffTo(B.id); sched.runFor(14_000)
        val pausedAt = A.sink.lines.last { it.startsWith("player_cb paused") }.substringAfter("pos=").toLong()
        val expected = pausedAt + (sched.now - sb.intervals.last()[0])
        expect("spotify (two accounts): the target plays the same track by URI, from the source's position",
            sb.audible && !sa.audible && sb.account.uri == "spotify:track:two" && kotlin.math.abs(sb.account.position() - expected) < 600 &&
                overlapOf(sa, sb, sched.now) == 0L && B.engine.debugOwner()?.first == B.id,
            "uri=${sb.account.uri} pos=${sb.account.position()} expected=$expected\nB:\n" + B.sink.lines.takeLast(20).joinToString("\n"))
        // B's user pauses in the Spotify app; A plays again; a later send to B must still work
        sb.userPause(); sched.runFor(3_000)
        sa.userResume(); sched.runFor(8_000)
        A.engine.handoffTo(B.id); sched.runFor(14_000)
        expect("spotify (two accounts): a phone where Spotify was paused earlier still accepts a handoff", sb.audible && !sa.audible && B.engine.debugOwner()?.first == B.id,
            "A:\n" + A.sink.lines.takeLast(20).joinToString("\n") + "\nB:\n" + B.sink.lines.takeLast(20).joinToString("\n"))
        A.engine.play(); sched.runFor(9_000)
        expect("spotify (two accounts): 'play here' on A pauses B's Spotify", sa.audible && !sb.audible && overlapOf(sa, sb, sched.now) < 1_000, "overlap=${overlapOf(sa, sb, sched.now)}")
    }

    // ---------------------------------------------------------------- 7. codecs reject garbage
    run {
        val rnd = java.util.Random(7)
        var nullOk = true
        repeat(2000) {
            val b = ByteArray(rnd.nextInt(80)).also { rnd.nextBytes(it) }
            if (Codec.decodeStatus(b) != null && b.size < 23) nullOk = false
            Codec.decodeGroup(b); Codec.decodeMembers(b); Codec.decodeStored(b); CoordinatorStateCodec.decode(b)
        }
        expect("codecs never throw on random input", nullOk)
        val ms = listOf(Member(5, byteArrayOf(1, 2, 3), "Pixel"), Member(9, byteArrayOf(4), "Galaxy"))
        expect("members codec round-trips", Codec.decodeMembers(Codec.encodeMembers(ms)) == ms)
    }

    println()
    println("$passes passed, $failures failed")
    if (failures > 0) System.exit(1)
}
