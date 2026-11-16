package com.segue.engine

import com.segue.core.Crypto
import com.segue.core.Enrolment
import com.segue.core.Env
import com.segue.core.HandoffCoordinator
import com.segue.core.Handshake
import com.segue.core.Identity
import com.segue.core.Msg
import com.segue.core.MsgCodec
import com.segue.core.MsgType
import com.segue.core.Phase
import com.segue.core.PlayerPort
import com.segue.core.Rollback
import com.segue.core.Roster
import com.segue.core.RotatingId
import com.segue.core.SecureChannel
import com.segue.core.Snapshot
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the Segue protocol on one phone: authenticated links to enrolled phones, SAS pairing,
 * and the HandoffCoordinator from :core.
 *
 * Threading: every public method may be called from any thread; work is posted to [sched], and all
 * mutable state below is touched only from that single thread. The two synchronous queries used by
 * the radio layer (shouldAccept, requestNameFor) read an immutable snapshot.
 *
 * Write-ahead: while the coordinator handles an event, the messages and player commands it emits
 * are buffered. After the handler returns, the coordinator state is persisted, and only then are
 * the buffered effects released. A crash at any point therefore never leaves an effect in the
 * world that the stored state does not account for, which is what onRestart() assumes.
 */
class SegueEngine(
    private val identity: Identity,
    private val store: EngineStore,
    private val sched: Scheduler,
    private val platformPlayer: PlayerPort,
    private val routeReady: () -> Boolean,
    private val transport: Transport,
    private val cfg: EngineConfig = EngineConfig(),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val bootId: String = "",
    private val sink: EventSink = EventSink.NONE
) {
    val selfId: Int = DeviceIds.idFor(identity.publicBytes)
    val fingerprint: String = Enrolment.fingerprint(identity.publicBytes)
    val faults = FaultPlan()
    @Volatile var listener: EngineListener? = null
    /** While on, enrolled peers may send test commands (fault injection) to this device. Off by default. */
    @Volatile var testMode = false
    /** Monotonic time until which a test outage refuses every normal link. */
    @Volatile private var outageUntil = 0L

    // ------------------------------------------------------------------ loop-thread state
    private var started = false
    @Volatile private var killed = false
    private var coordinator: HandoffCoordinator? = null
    private var members: List<Member> = emptyList()
    private var groupKey: ByteArray? = null
    private val links = HashMap<String, PeerLink>()          // by endpoint id
    private val peerLinks = HashMap<Int, PeerLink>()         // established links, by peer device id
    private val peerStatus = HashMap<Int, PeerStatus>()
    private val clocks = HashMap<Int, ClockSample>()
    private var pairing: PairingSession? = null
    private var pairingView: PairingView = PairingView.Idle
    private var lastResult: String? = null
    private val recent = ArrayDeque<String>()
    private val timers = HashMap<String, Pair<Long, Cancellable>>()
    private var timerGen = 0L
    private val outbox = ArrayList<Msg>()
    private val playerEffects = ArrayList<() -> Unit>()
    private var lastSaved: ByteArray? = null
    private var epochTask: Cancellable? = null
    private var pingTask: Cancellable? = null
    private var normalRunning = false
    private val testAcks = HashMap<Long, (Boolean) -> Unit>()
    private var testSeq = 0L

    /** Written by the radio thread in requestNameFor, read on the loop thread. */
    private val pendingRequest = ConcurrentHashMap<String, Int>()

    private class Shared(val members: List<Member>, val groupKey: ByteArray?, val connected: Set<Int>, val pairName: String?)
    @Volatile private var shared = Shared(emptyList(), null, emptySet(), null)

    private class ClockSample(val offsetMs: Long, val rttMs: Long, val atMs: Long)
    private class PairingSession(val name: String) { var endpointId: String? = null; var timeout: Cancellable? = null }

    private enum class LinkState {
        WAIT_HS1, HS1_SENT, HS2_SENT,
        PAIR_WAIT_COMMIT, PAIR_COMMIT_SENT, PAIR_WAIT_REVEAL_I, PAIR_SAS,
        ESTABLISHED, CLOSED
    }

    private inner class PeerLink(val endpointId: String, val initiator: Boolean, val pairing: Boolean, val binding: ByteArray) {
        var state = LinkState.WAIT_HS1
        var peerId = -1
        var channel: SecureChannel? = null
        var hsInit: Handshake.InitState? = null
        var hsResp: Handshake.RespState? = null
        var timeout: Cancellable? = null
        // pairing only
        var myNonce: ByteArray? = null
        var peerCommit: ByteArray? = null
        var peerPub: ByteArray? = null
        var sas: String? = null
        var localConfirmed = false
        var pendingHs1: ByteArray? = null
        var myGroupKey: ByteArray? = null
        var groupSent = false
        var peerGroup: GroupMsg? = null
        var finalized = false
        val established get() = state == LinkState.ESTABLISHED
    }

    // ================================================================== public API

    fun start() = onLoop {
        if (started) return@onLoop
        started = true
        members = store.loadMembers()
        groupKey = store.loadGroupKey()
        val c = HandoffCoordinator(env, cfg.coordinator, NO_OWNER)
        var restored = false
        store.loadCoordinator()?.let { Codec.decodeStored(it) }?.let { (boot, st) ->
            val rebooted = boot != bootId
            // elapsedRealtime restarts at boot; a reboot takes far longer than W + g, so treat the lease as expired.
            val s = if (rebooted) st.copy(anchor = sched.nowMs() - cfg.coordinator.windowMs - cfg.coordinator.leaseGuardMs - 1) else st
            c.importState(s)
            restored = true
            log("restore", "phase=${s.phase} owner=${s.ownerId} epoch=${s.ownerEpoch} rebooted=$rebooted")
        }
        coordinator = c
        lastSaved = Codec.encodeStored(bootId, c.exportState())
        log("start", "self=$selfId members=${members.size} fp=${fingerprint.take(12)} playing=${platformPlayer.isPlaying()} policy=${cfg.coordinator.rollback}")
        if (restored) coord("restart") { it.onRestart() }
        refreshShared()
        startNormal()
        schedulePing()
        publish()
    }

    fun stop() = onLoop {
        for (l in links.values.toList()) transport.disconnect(l.endpointId)
        links.clear(); peerLinks.clear()
        stopNormal(); transport.stopPairing()
        epochTask?.cancel(); pingTask?.cancel()
        for (t in timers.values) t.second.cancel()
        timers.clear()
        started = false
        refreshShared(); publish()
    }

    /** Stop this engine for good (the host is about to replace it). Leaves the radio to the host. */
    fun halt() {
        sched.post {
            killed = true
            cancelAllTasks()
        }
    }

    private fun cancelAllTasks() {
        epochTask?.cancel(); pingTask?.cancel()
        for (t in timers.values) t.second.cancel()
        timers.clear()
    }

    // --- user commands
    /** Push playback from this phone to [peerId]. Only works while this phone owns and is playing. */
    fun handoffTo(peerId: Int) = onLoop { doHandoff(peerId) }

    private fun doHandoff(peerId: Int) {
        var ok = false
        coord("cmd handoff") { ok = it.requestHandoff(peerId) }
        val label = labelOf(peerId)
        lastResult = if (ok) "Handing off to $label…" else "Can't hand off now (this phone must be playing and idle)"
        log("cmd", "handoff to=$peerId accepted=$ok")
        publish()
    }

    /** Ask the phone that is playing to hand playback to this phone. */
    fun pullHere() = onLoop { doPull() }

    private fun doPull() {
        val c = coordinator
        val owner = peerStatus.entries.firstOrNull { (id, s) -> s.ownerId == id && s.playing && peerLinks.containsKey(id) }?.key
            ?: c?.ownerId?.takeIf { it != selfId && peerLinks.containsKey(it) }   // the owner decides whether it can hand off
        if (owner == null) {
            lastResult = "No connected phone is playing"
        } else {
            coord("cmd pull") { it.allowHandoffHere() }
            sendApp(peerLinks.getValue(owner), AppKind.PULL, ByteArray(0))
            lastResult = "Asked ${labelOf(owner)} to hand off…"
            log("cmd", "pull from=$owner")
        }
        publish()
    }

    /** Explicit play on this phone: claims ownership (OWNER_CLAIM); the previous owner pauses. */
    fun play() = onLoop { doPlay() }

    private fun doPlay() {
        val c = coordinator ?: return
        val target = when {
            c.ownerId != selfId && peerLinks.containsKey(c.ownerId) -> c.ownerId
            else -> peerLinks.keys.firstOrNull() ?: -1
        }
        coord("cmd play") { it.manualClaim(target) }
        log("cmd", "play claim-to=$target")
        lastResult = null
        publish()
    }

    fun pause() = onLoop { doPause() }

    private fun doPause() {
        coord("cmd pause") { it.manualPause() }
        log("cmd", "pause")
        publish()
    }

    fun beginPairing() = onLoop {
        if (pairing != null) return@onLoop
        val tok = ByteArray(4).also { Crypto.rng.nextBytes(it) }.hex()
        val s = PairingSession(Names.PAIR_PREFIX + tok)
        s.timeout = sched.schedule(cfg.pairingTimeoutMs) {
            onLoop {
                if (pairing === s) {
                    s.endpointId?.let { ep -> links.remove(ep); transport.disconnect(ep) }
                    endPairing(PairingView.Failed("No phone found. Open pairing on both phones and keep them close."))
                }
            }
        }
        pairing = s
        pairingView = PairingView.Searching
        refreshShared()
        transport.startPairing(s.name)
        log("pair", "start name=${s.name}")
        publish()
    }

    fun cancelPairing() = onLoop {
        val s = pairing ?: run { pairingView = PairingView.Idle; publish(); return@onLoop }
        s.endpointId?.let { ep -> links[ep]?.let { sendFrame(it, Frame.ABORT, ByteArray(0)) }; links.remove(ep); transport.disconnect(ep) }
        endPairing(PairingView.Idle)
    }

    /** The user compared the 6-digit codes on both phones. */
    fun confirmPairing(codesMatch: Boolean) = onLoop {
        val s = pairing ?: return@onLoop
        val link = s.endpointId?.let { links[it] } ?: return@onLoop
        if (link.state != LinkState.PAIR_SAS || link.localConfirmed) return@onLoop
        if (!codesMatch) {
            sendFrame(link, Frame.ABORT, ByteArray(0))
            closeLink(link, "codes did not match")
            endPairing(PairingView.Failed("Codes did not match. Nothing was saved."))
            return@onLoop
        }
        link.localConfirmed = true
        pairingView = PairingView.Waiting(link.sas!!)
        log("pair", "local confirm peer=${link.peerId}")
        if (link.initiator) startHandshake(link, link.peerId)
        else link.pendingHs1?.let { link.pendingHs1 = null; respond(link, it) }
        publish()
    }

    fun dismissPairingResult() = onLoop { if (pairing == null) { pairingView = PairingView.Idle; publish() } }

    fun unpair(peerId: Int) = onLoop {
        peerLinks.remove(peerId)?.let { closeLink(it, "unpaired") }
        members = members.filter { it.id != peerId }
        store.saveMembers(members)
        if (members.none { it.id != selfId }) { groupKey = null; store.saveGroupKey(null) }
        peerStatus.remove(peerId); clocks.remove(peerId)
        log("unpair", "peer=$peerId")
        refreshShared(); restartNormal(); publish()
    }

    // --- platform callbacks
    fun onPlayerPaused() = onLoop {
        log("player_cb", "paused pos=${platformPlayer.snapshot().positionMs}")
        coord("player paused") { it.onPlayerPaused() }
        broadcastStatus(); publish()
    }

    fun onPlayerStarted(ok: Boolean) = onLoop {
        log("player_cb", "started ok=$ok pos=${platformPlayer.snapshot().positionMs}")
        coord("player started") { it.onPlayerStarted(ok) }
        broadcastStatus(); publish()
    }
    fun onRouteChanged() = onLoop { log("route", "ready=${routeReady()}"); broadcastStatus(); publish() }
    /** Free-form line in the event log (device build, test notes). */
    fun note(kind: String, detail: String) = onLoop { log(kind, detail) }
    fun refresh() = onLoop { publish() }

    // --- radio layer (synchronous decisions use the immutable snapshot)
    /** Accept an incoming connection request from [remoteName]? */
    fun shouldAccept(remoteName: String, pairingService: Boolean): Boolean {
        val s = shared
        if (pairingService) return s.pairName != null && remoteName.startsWith(Names.PAIR_PREFIX) && remoteName != s.pairName
        if (sched.nowMs() < outageUntil) return false
        return matchMember(s, remoteName)?.let { it.id !in s.connected } ?: false
    }

    /** A discovered endpoint. Returns the local name to request a connection with, or null to ignore it. */
    fun requestNameFor(endpointId: String, foundName: String, pairingService: Boolean): String? {
        val s = shared
        if (pairingService) {
            val mine = s.pairName ?: return null
            if (!foundName.startsWith(Names.PAIR_PREFIX) || foundName == mine) return null
            return if (mine < foundName) mine else null   // exactly one side initiates
        }
        if (sched.nowMs() < outageUntil) return null
        val gk = s.groupKey ?: return null
        val m = matchMember(s, foundName) ?: return null
        if (selfId >= m.id || m.id in s.connected) return null   // lower id initiates
        pendingRequest[endpointId] = m.id
        return advertisedName(gk)
    }

    /** Connection established at the radio level; [rawToken] is Nearby's raw authentication token. */
    fun onLinkUp(endpointId: String, incoming: Boolean, rawToken: ByteArray?, pairingService: Boolean) = onLoop {
        if (rawToken == null || rawToken.size < 16) {
            log("link_refused", "ep=$endpointId no channel binding token")
            transport.disconnect(endpointId)
            return@onLoop
        }
        links.remove(endpointId)?.let { it.timeout?.cancel() }
        val link = PeerLink(endpointId, initiator = !incoming, pairing = pairingService, binding = rawToken.copyOf())
        links[endpointId] = link
        val limit = if (pairingService) cfg.pairingTimeoutMs else cfg.handshakeTimeoutMs
        link.timeout = sched.schedule(limit) { onLoop { if (!link.established && links[endpointId] === link) failLink(link, "timeout in ${link.state}") } }
        log("link_up", "ep=$endpointId incoming=$incoming pairing=$pairingService")
        if (pairingService) {
            val s = pairing
            if (s == null || s.endpointId != null) { failLink(link, "not expecting a pairing connection"); return@onLoop }
            s.endpointId = endpointId
            if (link.initiator) {
                link.myNonce = ByteArray(16).also { Crypto.rng.nextBytes(it) }
                link.state = LinkState.PAIR_COMMIT_SENT
                sendFrame(link, Frame.ENR_COMMIT, Enrolment.commitment(identity.publicBytes, link.myNonce!!))
            } else link.state = LinkState.PAIR_WAIT_COMMIT
        } else if (link.initiator) {
            val pid = pendingRequest.remove(endpointId)
            if (pid == null) failLink(link, "outgoing link to unknown member") else startHandshake(link, pid)
        } else link.state = LinkState.WAIT_HS1
    }

    fun onLinkDown(endpointId: String) = onLoop {
        pendingRequest.remove(endpointId)
        val link = links.remove(endpointId) ?: return@onLoop
        link.state = LinkState.CLOSED
        link.timeout?.cancel()
        if (peerLinks[link.peerId] === link) { peerLinks.remove(link.peerId); peerStatus.remove(link.peerId) }
        if (pairing?.endpointId == endpointId && !link.finalized) endPairing(PairingView.Failed("Connection lost during pairing"))
        log("link_down", "ep=$endpointId peer=${link.peerId}")
        refreshShared(); publish()
    }

    /** A connection request failed before it was established. */
    fun onLinkFailed(endpointId: String) = onLoop { pendingRequest.remove(endpointId); log("link_attempt_failed", "ep=$endpointId") }

    fun onBytes(endpointId: String, bytes: ByteArray) = onLoop {
        val link = links[endpointId] ?: return@onLoop
        if (bytes.isEmpty()) return@onLoop
        val body = bytes.copyOfRange(1, bytes.size)
        when (bytes[0].toInt() and 0xff) {
            Frame.DATA -> onData(link, body)
            Frame.HS1 -> onHs1(link, body)
            Frame.HS2 -> onHs2(link, body)
            Frame.HS3 -> onHs3(link, body)
            Frame.ENR_COMMIT -> onEnrCommit(link, body)
            Frame.ENR_REVEAL_R -> onEnrRevealR(link, body)
            Frame.ENR_REVEAL_I -> onEnrRevealI(link, body)
            Frame.ABORT -> { failLink(link, "peer cancelled") }
            else -> log("frame_drop", "unknown type from ep=$endpointId")
        }
    }

    // ================================================================== coordinator glue

    private val bufferedPlayer = object : PlayerPort {
        override fun isPlaying() = platformPlayer.isPlaying()
        override fun snapshot() = platformPlayer.snapshot()
        override fun requestPause() { playerEffects.add { log("player_cmd", "pause"); platformPlayer.requestPause() } }
        override fun requestPlay(snapshot: Snapshot?) {
            playerEffects.add { log("player_cmd", "play from=${snapshot?.positionMs ?: "resume"}"); platformPlayer.requestPlay(snapshot) }
        }
    }

    private val env = object : Env {
        override val self: Int get() = selfId
        override fun now(): Long = sched.nowMs()
        override fun wallNow(): Long = wallClock()
        override fun send(msg: Msg) { outbox.add(msg) }
        override fun setTimer(name: String, delayMs: Long) {
            timers.remove(name)?.second?.cancel()
            val gen = ++timerGen
            timers[name] = gen to sched.schedule(delayMs) {
                onLoop {
                    if (timers[name]?.first == gen) {
                        timers.remove(name)
                        coord("timer $name") { it.onTimer(name) }
                    }
                }
            }
        }
        override fun cancelTimer(name: String) { timers.remove(name)?.second?.cancel() }
        override fun player(): PlayerPort = bufferedPlayer
        override fun routeReady(): Boolean = routeOk()
        override fun randomId(): Long {
            var v: Long
            do { v = Crypto.rng.nextLong() } while (v == 0L)
            return v
        }
    }

    private fun coord(what: String, block: (HandoffCoordinator) -> Unit) {
        val c = coordinator ?: return
        val phase0 = c.phase; val owner0 = c.ownerId; val epoch0 = c.ownerEpoch
        block(c)
        if (c.phase != phase0) log("phase", "$phase0->${c.phase} via $what")
        val ownerChanged = c.ownerId != owner0 || c.ownerEpoch != epoch0
        if (ownerChanged) log("owner", "owner=${c.ownerId} epoch=${c.ownerEpoch}")
        persist(c)
        val kill = faults.killInPhase
        if (kill != null && c.phase == kill) {
            faults.killInPhase = null
            log("fault", "kill after persisting $kill")
            killed = true
            outbox.clear(); playerEffects.clear()
            listener?.onKillRequested()
            return
        }
        val crash = faults.crashInPhase
        if (crash != null && c.phase == crash) {
            val restartMs = faults.crashRestartMs
            faults.crashInPhase = null
            log("fault", "crash after persisting $crash restart_ms=$restartMs player_playing=${platformPlayer.isPlaying()}")
            killed = true
            outbox.clear(); playerEffects.clear()
            cancelAllTasks()
            listener?.onCrashRequested(restartMs)
            return
        }
        val outage = faults.outageInPhase
        if (outage != null && c.phase == outage) {
            faults.outageInPhase = null
            startOutage(faults.outageMs)          // before the flush: this phase's messages are lost in flight
        }
        flushEffects()
        if (ownerChanged || c.phase != phase0) broadcastStatus()
    }

    private fun persist(c: HandoffCoordinator) {
        val bytes = Codec.encodeStored(bootId, c.exportState())
        if (lastSaved?.contentEquals(bytes) == true) return
        store.saveCoordinator(bytes)
        lastSaved = bytes
    }

    private fun flushEffects() {
        val msgs = ArrayList(outbox); outbox.clear()
        val fx = ArrayList(playerEffects); playerEffects.clear()
        for (m in msgs) sendCoord(m)
        for (f in fx) f()
    }

    private fun sendCoord(m: Msg) {
        if (faults.consumeDrop(m.type)) { log("fault", "dropped ${m.type} to=${m.recipient}"); return }
        val link = peerLinks[m.recipient]
        if (link == null) { log("msg_unsent", "${m.type} to=${m.recipient} (not connected)"); return }
        log("msg_out", "${m.type} to=${m.recipient} tx=${m.txId.toULong().toString(16)} ep=${m.epoch}" + if (m.reason.isNotEmpty()) " reason=${m.reason}" else "")
        if (m.type == MsgType.REJECTED) lastResult = "Refused handoff: " + when (m.reason) {
            "no-route" -> "the earbuds aren't connected to this phone"
            "target-playing" -> "this phone is already playing"
            "manual" -> "you paused on this phone; press Play here or Pull to allow it"
            else -> m.reason
        }
        val delay = faults.extraDelayMs
        repeat(if (faults.duplicate) 2 else 1) {
            val sealed = seal(link, AppKind.COORD, MsgCodec.encode(m)) ?: return
            if (delay > 0) sched.schedule(delay) { transport.transmit(link.endpointId, sealed) } else transport.transmit(link.endpointId, sealed)
        }
    }

    private fun onCoordMsg(link: PeerLink, body: ByteArray) {
        val m = MsgCodec.decode(body) ?: run { log("msg_reject", "undecodable from peer=${link.peerId}"); return }
        // The coordinator trusts Msg.sender, so bind it to the authenticated identity of the link.
        if (m.sender != link.peerId || m.recipient != selfId) {
            log("msg_reject", "${m.type} claims sender=${m.sender} on link of peer=${link.peerId}")
            return
        }
        log("msg_in", "${m.type} from=${m.sender} tx=${m.txId.toULong().toString(16)} ep=${m.epoch}" + if (m.reason.isNotEmpty()) " reason=${m.reason}" else "")
        if (m.type == MsgType.REJECTED) lastResult = "${labelOf(m.sender)} refused: ${m.reason}"
        if (m.type == MsgType.OWNER_COMMITTED || m.type == MsgType.TARGET_PLAYING) lastResult = null
        coord("msg ${m.type}") { it.onMessage(m) }
        publish()
    }

    // ================================================================== ownership sync over STATUS

    private fun statusBytes(): ByteArray {
        val c = coordinator
        return Codec.encodeStatus(PeerStatus(
            ownerId = c?.ownerId ?: NO_OWNER, ownerEpoch = c?.ownerEpoch ?: 0, playing = platformPlayer.isPlaying(),
            routeReady = routeOk(), positionMs = platformPlayer.snapshot().positionMs, phase = c?.phase?.name ?: "IDLE"))
    }

    private fun routeOk(): Boolean = !faults.forceNoRoute && routeReady()

    private fun broadcastStatus() {
        if (peerLinks.isEmpty()) return
        val b = statusBytes()
        for (l in peerLinks.values) sendApp(l, AppKind.STATUS, b)
    }

    private fun onStatus(peerId: Int, body: ByteArray) {
        val st = Codec.decodeStatus(body) ?: return
        peerStatus[peerId] = st
        val c = coordinator ?: return
        if (st.ownerId == peerId) {
            if (st.ownerEpoch > c.ownerEpoch) {
                // The peer, authenticated by this link, says it owns at a newer epoch. This is exactly an
                // OWNER_CLAIM; re-announcing it on every link makes claims survive disconnections.
                coord("status claim") { it.onMessage(Msg(MsgType.OWNER_CLAIM, peerId, selfId, 0, st.ownerEpoch)) }
            } else if (st.ownerEpoch == c.ownerEpoch && c.believesOwner && c.phase == Phase.IDLE &&
                platformPlayer.isPlaying() && (!st.playing || selfId > peerId)) {
                // Both claimed the same epoch while partitioned: the playing phone (or higher id) re-claims.
                log("conflict", "both own epoch=${c.ownerEpoch}; re-claiming")
                coord("resolve conflict") { it.manualClaim(peerId) }
            }
        }
        publish()
    }

    private fun onPull(peerId: Int) {
        val c = coordinator ?: return
        var ok = false
        if (c.believesOwner) coord("pull from $peerId") { ok = it.requestHandoff(peerId) }
        log("pull", "from=$peerId started=$ok")
        if (ok) lastResult = "${labelOf(peerId)} asked for playback"
        publish()
    }

    // ================================================================== links: handshake

    private fun rosterFor(link: PeerLink): Roster =
        if (link.pairing) Roster(mapOf(link.peerId to link.peerPub!!))     // only the SAS-verified key
        else Roster(members.filter { it.id != selfId }.associate { it.id to it.publicKey })

    private fun startHandshake(link: PeerLink, peerId: Int) {
        link.peerId = peerId
        val st = Handshake.initiatorStart(selfId, peerId)
        link.hsInit = st
        link.state = LinkState.HS1_SENT
        sendFrame(link, Frame.HS1, st.m1)
    }

    private fun onHs1(link: PeerLink, m1: ByteArray) {
        if (link.initiator) return failLink(link, "unexpected HS1")
        when (link.state) {
            LinkState.WAIT_HS1 -> respond(link, m1)
            LinkState.PAIR_SAS -> if (link.localConfirmed) respond(link, m1) else link.pendingHs1 = m1
            else -> failLink(link, "HS1 in ${link.state}")
        }
    }

    private fun respond(link: PeerLink, m1: ByteArray) {
        val claimed = Codec.handshakeSenderId(m1) ?: return failLink(link, "bad HS1")
        if (link.pairing && claimed != link.peerId) return failLink(link, "HS1 from a key other than the one compared")
        val r = Handshake.responderRespond(identity, selfId, rosterFor(link), link.binding, m1)
            ?: return failLink(link, "handshake rejected (not a member)")
        link.peerId = claimed
        link.hsResp = r.first
        link.state = LinkState.HS2_SENT
        sendFrame(link, Frame.HS2, r.second)
    }

    private fun onHs2(link: PeerLink, m2: ByteArray) {
        if (link.state != LinkState.HS1_SENT) return failLink(link, "HS2 in ${link.state}")
        val r = Handshake.initiatorFinish(link.hsInit!!, identity, rosterFor(link), link.binding, m2)
            ?: return failLink(link, "handshake rejected (bad signature or channel binding)")
        link.channel = r.first
        sendFrame(link, Frame.HS3, r.second)
        established(link)
    }

    private fun onHs3(link: PeerLink, m3: ByteArray) {
        if (link.state != LinkState.HS2_SENT) return failLink(link, "HS3 in ${link.state}")
        link.channel = Handshake.responderFinish(link.hsResp!!, m3)
            ?: return failLink(link, "handshake rejected (bad signature or channel binding)")
        established(link)
    }

    private fun established(link: PeerLink) {
        link.state = LinkState.ESTABLISHED
        link.hsInit = null; link.hsResp = null
        log("secure", "peer=${link.peerId} ep=${link.endpointId} pairing=${link.pairing}")
        if (link.pairing) {
            link.timeout?.cancel()
            sendGroup(link)
            maybeFinishPairing(link)
        } else {
            link.timeout?.cancel()
            registerPeer(link)
        }
    }

    private fun registerPeer(link: PeerLink) {
        val old = peerLinks.put(link.peerId, link)
        if (old != null && old !== link) closeLink(old, "replaced by newer link")
        refreshShared()
        sendApp(link, AppKind.STATUS, statusBytes())
        for (i in 0 until 3) sched.schedule(150L * i) { onLoop { if (link.established) ping(link) } }
        publish()
    }

    private fun onData(link: PeerLink, frame: ByteArray) {
        if (!link.established) return failLink(link, "data before handshake")
        val pt = link.channel!!.open(frame) ?: run { log("frame_reject", "auth/replay failure from peer=${link.peerId}"); return }
        if (pt.isEmpty()) return
        val kind = pt[0].toInt()
        val body = pt.copyOfRange(1, pt.size)
        if (link.pairing && !link.finalized) {
            if (kind == AppKind.GROUP) onGroup(link, body)
            return
        }
        when (kind) {
            AppKind.COORD -> onCoordMsg(link, body)
            AppKind.PULL -> onPull(link.peerId)
            AppKind.STATUS -> onStatus(link.peerId, body)
            AppKind.PING -> Codec.decodeLongs(body, 1)?.let { sendApp(link, AppKind.PONG, Codec.encodeLongs(it[0], sched.nowMs())) }
            AppKind.PONG -> Codec.decodeLongs(body, 2)?.let { onPong(link.peerId, it[0], it[1]) }
            AppKind.TEST -> Codec.decodeTest(body)?.let { (id, spec) -> onTest(link, id, spec) }
            AppKind.TEST_ACK -> Codec.decodeLongs(body, 2)?.let { testAcks.remove(it[0])?.invoke(it[1] == 1L) }
            else -> log("frame_drop", "unknown app kind $kind")
        }
    }

    private fun seal(link: PeerLink, kind: Int, body: ByteArray): ByteArray? {
        val ch = link.channel ?: return null
        val pt = ByteArray(body.size + 1)
        pt[0] = kind.toByte()
        System.arraycopy(body, 0, pt, 1, body.size)
        return Frame.wrap(Frame.DATA, ch.seal(pt))
    }

    private fun sendApp(link: PeerLink, kind: Int, body: ByteArray) {
        seal(link, kind, body)?.let { transport.transmit(link.endpointId, it) }
    }

    private fun sendFrame(link: PeerLink, type: Int, body: ByteArray) = transport.transmit(link.endpointId, Frame.wrap(type, body))

    private fun failLink(link: PeerLink, reason: String) {
        log("link_fail", "ep=${link.endpointId} peer=${link.peerId} state=${link.state}: $reason")
        val wasPairing = pairing?.endpointId == link.endpointId && !link.finalized
        closeLink(link, reason)
        if (wasPairing) endPairing(PairingView.Failed(reasonForUser(reason)))
        refreshShared(); publish()
    }

    private fun closeLink(link: PeerLink, reason: String) {
        link.state = LinkState.CLOSED
        link.timeout?.cancel()
        links.remove(link.endpointId)
        if (peerLinks[link.peerId] === link) { peerLinks.remove(link.peerId); peerStatus.remove(link.peerId) }
        log("link_close", "ep=${link.endpointId} peer=${link.peerId}: $reason")
        transport.disconnect(link.endpointId)
    }

    private fun reasonForUser(r: String) = when {
        r.startsWith("handshake rejected") -> "The secure check failed, so nothing was saved. Try again."
        r == "peer cancelled" -> "The other phone cancelled pairing."
        r.startsWith("timeout") -> "Pairing timed out."
        else -> "Pairing failed ($r)."
    }

    // ================================================================== links: SAS pairing (commit, reveal, compare)

    private fun onEnrCommit(link: PeerLink, body: ByteArray) {
        if (!link.pairing || link.initiator || link.state != LinkState.PAIR_WAIT_COMMIT || body.size != 32) return failLink(link, "bad commit")
        link.peerCommit = body
        link.myNonce = ByteArray(16).also { Crypto.rng.nextBytes(it) }
        link.state = LinkState.PAIR_WAIT_REVEAL_I
        sendFrame(link, Frame.ENR_REVEAL_R, Codec.fields(identity.publicBytes, link.myNonce!!))
    }

    private fun onEnrRevealR(link: PeerLink, body: ByteArray) {
        if (!link.pairing || !link.initiator || link.state != LinkState.PAIR_COMMIT_SENT) return failLink(link, "unexpected reveal")
        val f = Codec.readFields(body, 2) ?: return failLink(link, "bad reveal")
        if (!validPeerKey(f[0]) || f[1].size != 16) return failLink(link, "bad reveal")
        link.peerPub = f[0]
        sendFrame(link, Frame.ENR_REVEAL_I, Codec.fields(identity.publicBytes, link.myNonce!!))
        link.sas = Enrolment.sasCode(identity.publicBytes, f[0], link.myNonce!!, f[1])
        showSas(link)
    }

    private fun onEnrRevealI(link: PeerLink, body: ByteArray) {
        if (!link.pairing || link.initiator || link.state != LinkState.PAIR_WAIT_REVEAL_I) return failLink(link, "unexpected reveal")
        val f = Codec.readFields(body, 2) ?: return failLink(link, "bad reveal")
        if (!validPeerKey(f[0]) || f[1].size != 16) return failLink(link, "bad reveal")
        if (!MessageDigest.isEqual(Enrolment.commitment(f[0], f[1]), link.peerCommit)) return failLink(link, "commitment mismatch")
        link.peerPub = f[0]
        link.sas = Enrolment.sasCode(f[0], identity.publicBytes, f[1], link.myNonce!!)
        showSas(link)
    }

    private fun validPeerKey(pub: ByteArray) = Crypto.decodePublic(pub) != null && !pub.contentEquals(identity.publicBytes)

    private fun showSas(link: PeerLink) {
        link.state = LinkState.PAIR_SAS
        link.peerId = DeviceIds.idFor(link.peerPub!!)
        pairingView = PairingView.Compare(link.sas!!, Enrolment.fingerprint(link.peerPub!!).take(12))
        log("pair", "compare peer=${link.peerId}")
        publish()
    }

    private fun sendGroup(link: PeerLink) {
        val key = groupKey ?: ByteArray(32).also { Crypto.rng.nextBytes(it) }
        link.myGroupKey = key
        val count = members.count { it.id != selfId && it.id != link.peerId }
        sendApp(link, AppKind.GROUP, Codec.encodeGroup(GroupMsg(key, count, cfg.label.take(40))))
        link.groupSent = true
    }

    private fun onGroup(link: PeerLink, body: ByteArray) {
        if (link.peerGroup != null) return
        link.peerGroup = Codec.decodeGroup(body) ?: return failLink(link, "bad group message")
        maybeFinishPairing(link)
    }

    private fun maybeFinishPairing(link: PeerLink) {
        val g = link.peerGroup ?: return
        if (!link.groupSent || link.finalized) return
        val mine = link.myGroupKey!!
        val myCount = members.count { it.id != selfId && it.id != link.peerId }
        // Both sides evaluate the same rule on the same inputs, so they agree on one key.
        val key = when {
            mine.contentEquals(g.key) -> mine
            myCount > 0 && g.memberCount > 0 -> null
            g.memberCount > myCount -> g.key
            myCount > g.memberCount -> mine
            link.initiator -> mine
            else -> g.key
        }
        if (key == null) {
            sendFrame(link, Frame.ABORT, ByteArray(0))
            closeLink(link, "two different groups")
            endPairing(PairingView.Failed("Both phones are already paired with other phones. Unpair one of them first."))
            return
        }
        groupKey = key
        store.saveGroupKey(key)
        members = members.filter { it.id != link.peerId } + Member(link.peerId, link.peerPub!!, g.label.take(40))
        store.saveMembers(members)
        link.finalized = true
        log("pair", "done peer=${link.peerId} label=${g.label}")
        endPairing(PairingView.Done(g.label))
        registerPeer(link)
        restartNormal()
    }

    private fun endPairing(view: PairingView) {
        pairing?.timeout?.cancel()
        if (pairing != null) transport.stopPairing()
        pairing = null
        pairingView = view
        refreshShared(); publish()
    }

    // ================================================================== advertising, clocks, logging

    private fun advertisedName(gk: ByteArray): String =
        RotatingId.endpointName(Names.deviceKey(gk, selfId), RotatingId.epochIndex(wallClock() / 1000))

    private fun matchMember(s: Shared, name: String): Member? {
        val gk = s.groupKey ?: return null
        val now = wallClock() / 1000
        return s.members.firstOrNull { it.id != selfId && RotatingId.matches(Names.deviceKey(gk, it.id), name, now) }
    }

    private fun startNormal() {
        val gk = groupKey
        if (!started || gk == null || members.none { it.id != selfId }) return
        transport.startNormal(advertisedName(gk))
        normalRunning = true
        val nowMs = wallClock()
        val next = (RotatingId.epochIndex(nowMs / 1000) + 1) * RotatingId.EPOCH_SECONDS * 1000
        epochTask?.cancel()
        epochTask = sched.schedule(next - nowMs + 500) { onLoop { restartNormal() } }
    }

    private fun stopNormal() {
        if (normalRunning) transport.stopNormal()
        normalRunning = false
        epochTask?.cancel()
    }

    private fun restartNormal() { stopNormal(); startNormal() }

    private fun schedulePing() {
        pingTask?.cancel()
        pingTask = sched.schedule(cfg.pingIntervalMs) {
            onLoop {
                for (l in peerLinks.values) ping(l)
                broadcastStatus()
                publish()
                schedulePing()
            }
        }
    }

    private fun ping(link: PeerLink) = sendApp(link, AppKind.PING, Codec.encodeLongs(sched.nowMs()))

    private fun onPong(peerId: Int, t1: Long, t2: Long) {
        val t3 = sched.nowMs()
        val rtt = t3 - t1
        if (rtt < 0) return
        val offset = t2 - (t1 + t3) / 2          // peer clock minus this clock
        val prev = clocks[peerId]
        if (prev == null || rtt <= prev.rttMs || t3 - prev.atMs > 60_000) {
            clocks[peerId] = ClockSample(offset, rtt, t3)
            log("clock", "peer=$peerId offset=$offset rtt=$rtt")
        }
    }

    private fun refreshShared() {
        shared = Shared(members.toList(), groupKey?.copyOf(), peerLinks.keys.toSet(), pairing?.name)
    }

    private fun labelOf(id: Int) = members.firstOrNull { it.id == id }?.label ?: "phone $id"

    private fun log(kind: String, detail: String) {
        sink.event(sched.nowMs(), wallClock(), selfId, kind, detail)
        recent.addFirst("${sched.nowMs() % 100_000} $kind $detail")
        while (recent.size > cfg.recentEvents) recent.removeLast()
    }

    private fun publish() {
        val l = listener ?: return
        val c = coordinator
        val snap = platformPlayer.snapshot()
        l.onState(EngineState(
            selfId = selfId, fingerprint = fingerprint.take(12), label = cfg.label,
            ownerId = c?.ownerId ?: NO_OWNER, ownerEpoch = c?.ownerEpoch ?: 0, phase = c?.phase?.name ?: "IDLE",
            playing = platformPlayer.isPlaying(), positionMs = snap.positionMs, routeReady = routeReady(),
            peers = members.filter { it.id != selfId }.map { m ->
                val ck = clocks[m.id]
                PeerView(m.id, m.label, Enrolment.fingerprint(m.publicKey).take(12), peerLinks.containsKey(m.id),
                    peerStatus[m.id], ck?.offsetMs, ck?.rttMs)
            },
            pairing = pairingView, lastResult = lastResult, recent = recent.toList(), policy = cfg.coordinator.rollback.name
        ))
    }

    private inline fun onLoop(crossinline block: () -> Unit) = sched.post { if (!killed) block() }

    // ================================================================== test commands (fault injection)
    //
    // A command is a short text spec, applied locally or sent to a peer over the authenticated link:
    //   clear | drop:<MsgType>:<n> | delay:<ms> | dup:<0|1> | noroute:<0|1>
    //   on:<Phase>:kill | on:<Phase>:crash:<restartMs> | on:<Phase>:outage:<ms> | outage:<ms>
    //   play | pause | allow | status | policy:<Rollback> | after:<ms>:<spec>
    // A peer obeys only while its own test mode is on.

    private fun startOutage(ms: Long) {
        log("fault", "outage ms=$ms")
        outageUntil = sched.nowMs() + ms
        for (l in links.values.toList()) if (!l.pairing || l.finalized) closeLink(l, "test outage")
        refreshShared()
        sched.schedule(ms + 50) { onLoop { log("fault", "outage over"); restartNormal() } }
        publish()
    }

    private fun applyTest(spec: String, from: String): Boolean {
        val p = spec.split(":")
        val ok = try {
            when (p[0]) {
                "clear" -> faults.clear()
                "drop" -> faults.dropNext(MsgType.valueOf(p[1]), p[2].toInt())
                "delay" -> faults.extraDelayMs = p[1].toLong()
                "dup" -> faults.duplicate = p[1] == "1"
                "noroute" -> { faults.forceNoRoute = p[1] == "1"; broadcastStatus() }
                "on" -> {
                    val phase = Phase.valueOf(p[1])
                    when (p[2]) {
                        "kill" -> faults.killInPhase = phase
                        "crash" -> { faults.crashRestartMs = p[3].toLong(); faults.crashInPhase = phase }
                        "outage" -> { faults.outageMs = p[3].toLong(); faults.outageInPhase = phase }
                        else -> throw IllegalArgumentException(spec)
                    }
                }
                "outage" -> startOutage(p[1].toLong())
                "play" -> doPlay()
                "pause" -> doPause()
                "allow" -> coord("test allow") { it.allowHandoffHere() }
                "status" -> broadcastStatus()
                // Delayed so that the ack for this command leaves before the host restarts the engine.
                "policy" -> { val r = Rollback.valueOf(p[1]); sched.schedule(400) { listener?.onPolicyRequested(r) } }
                "after" -> {
                    val rest = p.drop(2).joinToString(":")
                    require(rest.isNotEmpty())
                    sched.schedule(p[1].toLong()) { onLoop { applyTest(rest, from) } }
                }
                else -> throw IllegalArgumentException(spec)
            }
            true
        } catch (e: RuntimeException) { false }
        log("test_cmd", "$spec from=$from ok=$ok")
        return ok
    }

    private fun onTest(link: PeerLink, id: Long, spec: String) {
        val ok = if (testMode) applyTest(spec, "peer${link.peerId}") else { log("test_refused", "$spec from=${link.peerId} (test mode is off)"); false }
        if (link.established) sendApp(link, AppKind.TEST_ACK, Codec.encodeLongs(id, if (ok) 1L else 0L))
    }

    /** What the campaign runner sees. Call on the engine's scheduler thread only. */
    internal class Probe(
        val alive: Boolean, val selfPlaying: Boolean, val phase: Phase, val ownerId: Int, val ownerEpoch: Long,
        val connected: Boolean, val peer: PeerStatus?
    )

    internal fun probe(peerId: Int): Probe {
        val c = coordinator
        return Probe(alive = !killed && started && c != null, selfPlaying = platformPlayer.isPlaying(),
            phase = c?.phase ?: Phase.IDLE, ownerId = c?.ownerId ?: NO_OWNER, ownerEpoch = c?.ownerEpoch ?: 0,
            connected = peerLinks.containsKey(peerId), peer = peerStatus[peerId])
    }

    internal fun testLocal(spec: String): Boolean = if (killed) false else applyTest(spec, "local")

    /** Send a test command to [peerId]; [onAck] gets the peer's answer (never called if the link drops first). */
    internal fun testRemote(peerId: Int, spec: String, onAck: (Boolean) -> Unit) {
        val link = peerLinks[peerId]
        if (killed || link == null) { sched.post { onAck(false) }; return }
        val id = ++testSeq
        testAcks[id] = onAck
        sendApp(link, AppKind.TEST, Codec.encodeTest(id, spec))
    }

    internal fun testHandoff(peerId: Int) { if (!killed) doHandoff(peerId) }
    internal fun testPull() { if (!killed) doPull() }
    internal fun testLog(kind: String, detail: String) = log(kind, detail)
    internal fun labelFor(peerId: Int): String = labelOf(peerId)
    internal val policy: Rollback get() = cfg.coordinator.rollback

    // ------------------------------------------------------------------ test hooks (same module only)
    internal fun debugCoordinatorPhase(): Phase? = coordinator?.phase
    internal fun debugOwner(): Pair<Int, Long>? = coordinator?.let { it.ownerId to it.ownerEpoch }
    internal fun debugMembers(): List<Member> = members
    internal fun debugGroupKey(): ByteArray? = groupKey
    internal fun debugConnected(): Set<Int> = peerLinks.keys.toSet()
    internal fun debugSendRaw(peerId: Int, kind: Int, body: ByteArray) = onLoop { peerLinks[peerId]?.let { sendApp(it, kind, body) } }

    companion object {
        /** No phone owns playback yet. Device ids are never 0. */
        const val NO_OWNER = 0
    }
}
