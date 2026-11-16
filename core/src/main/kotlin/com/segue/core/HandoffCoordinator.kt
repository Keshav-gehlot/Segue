package com.segue.core

enum class Rollback { RESUME_AFTER_LEASE, SAFE_PAUSE }

data class CoordinatorConfig(
    val windowMs: Long = 4000,        // W: provisional playback window, anchored on READY
    val leaseGuardMs: Long = 500,     // g: source waits W + g after receiving READY before rollback
    val prepTimeoutMs: Long = 1500,
    val startBudgetMs: Long = 1000,   // target refuses STATE_TRANSFER later than W - startBudget
    val pauseBudgetMs: Long = 300,    // target issues its provisional stop at W - pauseBudget
    val retransmitMs: Long = 250,
    val rollback: Rollback = Rollback.RESUME_AFTER_LEASE,
    val deadTxMemory: Int = 256,
    val claimRepeats: Int = 6,
    // Ablation switch for the evaluation only. Production keeps this true.
    val freshnessBinding: Boolean = true   // READY-nonce echo + finished-transaction memory
)

enum class Phase {
    IDLE,
    PREPARING, SRC_PAUSING, TRANSFERRING, SRC_RECOVERY,          // source side
    READY_SENT, TGT_STARTING, PROVISIONAL, TGT_STOPPING, UNCERTAIN // target side
}

/**
 * Two-party playback handoff with a READY-anchored provisional lease.
 *
 * Safety argument (no synchronised clocks needed, only bounded timer drift):
 *   - The target may play provisionally only inside [t_ready_sent, t_ready_sent + W] on its own timer.
 *   - The source may roll back (resume) only after t_ready_received + W + g on its own timer.
 *   - t_ready_received >= t_ready_sent by causality, so the target's window has closed before the
 *     source resumes, unless the source has committed the target, in which case the source never resumes.
 *   - The source's decision (commit xor abort) is irrevocable; the target only follows it.
 *   - STATE_TRANSFER must echo the nonce of the READY it answers, and finished transactions are
 *     remembered, so delayed or duplicated messages from an earlier transaction cannot start playback.
 *
 * Known residual hazard: if the *coordinator process* on the target dies during the provisional
 * window while the music app keeps playing, nobody stops the target. RESUME_AFTER_LEASE then lets
 * the source resume too. SAFE_PAUSE removes this hazard at the cost of availability.
 */
class HandoffCoordinator(
    private val env: Env,
    private val cfg: CoordinatorConfig,
    initialOwner: Int
) : HandoffNode {

    private enum class StopThen { NONE, GO_UNCERTAIN, REPLY_ABORTED, RESUME_COMMITTED }

    var ownerEpoch = 0L; private set
    var ownerId = initialOwner; private set
    var phase = Phase.IDLE; private set
    var staleRejected = 0L; private set

    private var epochFloor = 0L
    private var tx = 0L
    private var txEpoch = 0L
    private var peer = -1
    private var anchor = 0L
    private var win = 0L
    private var nonce = 0L
    private var snap: Snapshot? = null
    private var abortDecided = false
    private var abortWhilePausing = false
    private var stopThen = StopThen.NONE
    private var stopAfterStart = StopThen.NONE
    private var autoSuspended = false
    /** Target side: the player was confirmed playing the transferred state in this transaction. */
    private var tgtStarted = false
    private var claimsLeft = 0
    private var claimPeer = -1
    private val dead = LinkedHashSet<Long>()
    private val committed = LinkedHashMap<Long, Long>()

    override val believesOwner: Boolean get() = ownerId == env.self

    private val sourcePhases = setOf(Phase.PREPARING, Phase.SRC_PAUSING, Phase.TRANSFERRING, Phase.SRC_RECOVERY)
    private val targetPhases = setOf(Phase.READY_SENT, Phase.TGT_STARTING, Phase.PROVISIONAL, Phase.TGT_STOPPING, Phase.UNCERTAIN)

    // ---------------------------------------------------------------- helpers

    private fun send(type: MsgType, to: Int, txId: Long, epoch: Long, windowMs: Long = 0, nonce: Long = 0,
                     snapshot: Snapshot? = null, started: Boolean = false, reason: String = "") {
        env.send(Msg(type, env.self, to, txId, epoch, windowMs, nonce, snapshot, started, 0, reason))
    }

    private fun remember(set: LinkedHashSet<Long>, id: Long) {
        if (id == 0L || !cfg.freshnessBinding) return
        set.add(id)
        while (set.size > cfg.deadTxMemory) set.remove(set.iterator().next())
    }

    private fun finish(markDead: Boolean) {
        if (markDead) remember(dead, tx)
        env.cancelTimer(T_PREP); env.cancelTimer(T_LEASE); env.cancelTimer(T_WINDOW); env.cancelTimer(T_RTX)
        phase = Phase.IDLE
        tx = 0; snap = null; abortDecided = false; abortWhilePausing = false
        stopThen = StopThen.NONE; stopAfterStart = StopThen.NONE
        tgtStarted = false
    }

    /**
     * What a target that stopped uncommitted plays when the commit arrives after all: its own paused
     * position if it had started (that is where the content stopped), otherwise the transferred
     * snapshot. Without this, a target that crashed before starting would resume whatever its player
     * last held. Read it before finish() clears the transaction.
     */
    private fun resumePoint(): Snapshot? = if (tgtStarted) null else snap

    private fun commitAsSource() {
        ownerEpoch = txEpoch; ownerId = peer
        committed[tx] = txEpoch
        while (committed.size > cfg.deadTxMemory) committed.remove(committed.keys.iterator().next())
        val t = tx; val e = txEpoch; val p = peer
        finish(markDead = false)
        send(MsgType.OWNER_COMMITTED, p, t, e)
    }

    private fun commitAsTarget() {
        ownerEpoch = txEpoch; ownerId = env.self
        finish(markDead = false)
    }

    private fun resumeSourceAndFinish() {
        env.player().requestPlay(null)
        finish(markDead = true)
    }

    // ---------------------------------------------------------------- API

    override fun requestHandoff(target: Int): Boolean {
        if (!believesOwner || phase != Phase.IDLE || autoSuspended || !env.player().isPlaying()) return false
        tx = env.randomId()
        txEpoch = maxOf(ownerEpoch, epochFloor) + 1
        epochFloor = txEpoch
        peer = target
        phase = Phase.PREPARING
        send(MsgType.PREPARE, peer, tx, txEpoch, windowMs = cfg.windowMs)
        env.setTimer(T_PREP, cfg.prepTimeoutMs)
        env.setTimer(T_RTX, cfg.retransmitMs)
        return true
    }

    override fun manualClaim(peerId: Int) {
        // Explicit user intent outranks automation: abandon any transaction and claim a new epoch.
        if (tx != 0L) finish(markDead = true)
        ownerEpoch = maxOf(ownerEpoch, epochFloor) + 1
        epochFloor = ownerEpoch
        ownerId = env.self
        autoSuspended = false
        if (!env.player().isPlaying()) env.player().requestPlay(null)
        claimPeer = peerId
        claimsLeft = cfg.claimRepeats
        send(MsgType.OWNER_CLAIM, peerId, 0, ownerEpoch)
        env.setTimer(T_CLAIM, cfg.retransmitMs)
    }

    fun manualPause() {
        if (phase == Phase.PREPARING) {
            send(MsgType.ABORT_REQ, peer, tx, txEpoch)
            finish(markDead = true)
        }
        autoSuspended = true
        if (env.player().isPlaying()) env.player().requestPause()
    }

    /**
     * The user asked for playback on this phone ("play here"), so lift the suspension set by
     * manualPause() and accept the owner's next PREPARE. Added for the Android engine; never
     * called by the simulator, so the evaluated behaviour is unchanged.
     */
    fun allowHandoffHere() { autoSuspended = false }

    override fun onMessage(m: Msg) {
        if (m.recipient != env.self) return
        if (m.epoch > epochFloor) epochFloor = m.epoch
        when (m.type) {
            MsgType.PREPARE -> onPrepare(m)
            MsgType.READY -> onReady(m)
            MsgType.REJECTED -> if (phase == Phase.PREPARING && m.txId == tx) finish(markDead = true)
            MsgType.STATE_TRANSFER -> onStateTransfer(m)
            MsgType.TARGET_PLAYING -> onTargetPlaying(m)
            MsgType.OWNER_COMMITTED -> onOwnerCommitted(m)
            MsgType.ABORT_REQ -> onAbortReq(m)
            MsgType.ABORTED -> onAborted(m)
            MsgType.RECOVERY_QUERY -> onRecoveryQuery(m)
            MsgType.OWNER_CLAIM -> onOwnerClaim(m)
        }
    }

    // ---------------------------------------------------------------- source side

    private fun onReady(m: Msg) {
        if (phase == Phase.PREPARING && m.txId == tx) {
            env.cancelTimer(T_PREP)
            anchor = env.now()
            nonce = m.nonce
            phase = Phase.SRC_PAUSING
            env.setTimer(T_LEASE, cfg.windowMs + cfg.leaseGuardMs)
            env.player().requestPause()
        } else if (m.txId != tx && !committed.containsKey(m.txId)) {
            send(MsgType.ABORT_REQ, m.sender, m.txId, m.epoch)
        }
    }

    private fun onTargetPlaying(m: Msg) {
        when {
            phase == Phase.TRANSFERRING && m.txId == tx && !abortDecided -> commitAsSource()
            committed.containsKey(m.txId) -> send(MsgType.OWNER_COMMITTED, m.sender, m.txId, committed[m.txId]!!)
            m.txId == tx && phase == Phase.SRC_PAUSING -> { /* impossible before STATE_TRANSFER; ignore */ }
            else -> send(MsgType.ABORT_REQ, m.sender, m.txId, m.epoch)
        }
    }

    private fun onRecoveryQuery(m: Msg) {
        when {
            committed.containsKey(m.txId) -> send(MsgType.OWNER_COMMITTED, m.sender, m.txId, committed[m.txId]!!)
            phase == Phase.TRANSFERRING && m.txId == tx && !abortDecided && m.started -> commitAsSource()
            else -> {
                if (phase == Phase.TRANSFERRING && m.txId == tx) decideAbort()
                send(MsgType.ABORT_REQ, m.sender, m.txId, m.epoch)
            }
        }
    }

    private fun onAborted(m: Msg) {
        if (m.txId != tx) return
        when (phase) {
            Phase.PREPARING -> finish(markDead = true)               // never paused; keep playing
            Phase.SRC_PAUSING -> abortWhilePausing = true
            Phase.TRANSFERRING, Phase.SRC_RECOVERY -> resumeSourceAndFinish()
            else -> {}
        }
    }

    /** The source's abort decision is irrevocable: after this it never commits this transaction. */
    private fun decideAbort() {
        abortDecided = true
        remember(dead, tx)
        if (cfg.rollback == Rollback.SAFE_PAUSE) {
            phase = Phase.SRC_RECOVERY
            env.setTimer(T_RTX, cfg.retransmitMs)
        }
    }

    // ---------------------------------------------------------------- target side

    private fun onPrepare(m: Msg) {
        if (m.txId == tx && phase in targetPhases) {
            if (phase == Phase.READY_SENT) send(MsgType.READY, m.sender, tx, txEpoch, nonce = nonce) // never re-anchor
            return
        }
        if (m.txId in dead) { send(MsgType.ABORTED, m.sender, m.txId, m.epoch); return }
        val reason = when {
            m.sender != ownerId -> "not-owner"
            m.epoch <= ownerEpoch -> "stale-epoch"
            phase != Phase.IDLE -> "busy"
            autoSuspended -> "manual"
            !env.routeReady() -> "no-route"
            env.player().isPlaying() -> "target-playing"
            else -> null
        }
        if (reason != null) {
            if (reason == "stale-epoch" || reason == "not-owner") staleRejected++
            send(MsgType.REJECTED, m.sender, m.txId, m.epoch, reason = reason)
            return
        }
        tx = m.txId; txEpoch = m.epoch; peer = m.sender
        anchor = env.now()
        nonce = env.randomId()
        tgtStarted = false
        phase = Phase.READY_SENT
        win = if (m.windowMs in (cfg.startBudgetMs + 1)..cfg.windowMs) m.windowMs else cfg.windowMs
        send(MsgType.READY, peer, tx, txEpoch, nonce = nonce)
        env.setTimer(T_WINDOW, win - cfg.pauseBudgetMs)
    }

    private fun onStateTransfer(m: Msg) {
        if (m.txId == tx && phase in targetPhases) {
            if (phase == Phase.READY_SENT) {
                if ((cfg.freshnessBinding && m.nonce != nonce) || m.epoch != txEpoch) { staleRejected++; return }
                when {
                    env.now() > anchor + win - cfg.startBudgetMs -> targetAbort()
                    !env.routeReady() -> targetAbort()
                    m.snapshot == null -> targetAbort()
                    else -> {
                        snap = m.snapshot
                        phase = Phase.TGT_STARTING
                        env.player().requestPlay(m.snapshot)
                    }
                }
            } else if (phase == Phase.PROVISIONAL) {
                send(MsgType.TARGET_PLAYING, peer, tx, txEpoch)
            }
            return
        }
        // Unknown or finished transaction: fenced. Promise never to play it.
        staleRejected++
        remember(dead, m.txId)
        send(MsgType.ABORTED, m.sender, m.txId, m.epoch)
    }

    private fun targetAbort() {
        val p = peer; val t = tx; val e = txEpoch
        finish(markDead = true)
        send(MsgType.ABORTED, p, t, e)
    }

    private fun onOwnerCommitted(m: Msg) {
        if (m.txId != tx || m.epoch != txEpoch) return
        when (phase) {
            Phase.PROVISIONAL -> commitAsTarget()
            Phase.TGT_STOPPING -> if (stopThen == StopThen.GO_UNCERTAIN) {
                ownerEpoch = txEpoch; ownerId = env.self
                stopThen = StopThen.RESUME_COMMITTED
            }
            Phase.UNCERTAIN -> { val from = resumePoint(); commitAsTarget(); env.player().requestPlay(from) }
            else -> {}
        }
    }

    private fun onAbortReq(m: Msg) {
        if (m.txId == tx && phase in sourcePhases) return            // bogus direction
        if (m.txId == tx && phase in targetPhases) {
            when (phase) {
                Phase.READY_SENT, Phase.UNCERTAIN -> targetAbort()
                Phase.TGT_STARTING -> stopAfterStart = StopThen.REPLY_ABORTED
                Phase.PROVISIONAL -> { phase = Phase.TGT_STOPPING; stopThen = StopThen.REPLY_ABORTED; env.player().requestPause() }
                Phase.TGT_STOPPING -> if (stopThen != StopThen.RESUME_COMMITTED) stopThen = StopThen.REPLY_ABORTED
                else -> {}
            }
            return
        }
        if (committed.containsKey(m.txId)) return
        remember(dead, m.txId)
        send(MsgType.ABORTED, m.sender, m.txId, m.epoch)
    }

    private fun onOwnerClaim(m: Msg) {
        if (m.epoch <= ownerEpoch) return
        if (tx != 0L) finish(markDead = true)
        ownerEpoch = m.epoch; ownerId = m.sender
        env.cancelTimer(T_CLAIM); claimsLeft = 0
        if (env.player().isPlaying()) env.player().requestPause()
    }

    // ---------------------------------------------------------------- timers and player callbacks

    override fun onTimer(name: String) {
        when (name) {
            T_PREP -> if (phase == Phase.PREPARING) {
                send(MsgType.ABORT_REQ, peer, tx, txEpoch)
                finish(markDead = true)                                  // never paused; keep playing
            }
            T_LEASE -> when (phase) {
                Phase.SRC_PAUSING -> { abortDecided = true; abortWhilePausing = true; remember(dead, tx) }
                Phase.TRANSFERRING -> {
                    val p = peer; val t = tx; val e = txEpoch
                    if (cfg.rollback == Rollback.RESUME_AFTER_LEASE) {
                        abortDecided = true
                        resumeSourceAndFinish()
                    } else decideAbort()
                    send(MsgType.ABORT_REQ, p, t, e)
                }
                else -> {}
            }
            T_WINDOW -> when (phase) {
                Phase.READY_SENT -> targetAbort()
                Phase.TGT_STARTING -> if (stopAfterStart == StopThen.NONE) stopAfterStart = StopThen.GO_UNCERTAIN
                Phase.PROVISIONAL -> { phase = Phase.TGT_STOPPING; stopThen = StopThen.GO_UNCERTAIN; env.player().requestPause() }
                else -> {}
            }
            T_RTX -> {
                when (phase) {
                    Phase.PREPARING -> send(MsgType.PREPARE, peer, tx, txEpoch, windowMs = cfg.windowMs)
                    Phase.TRANSFERRING -> send(MsgType.STATE_TRANSFER, peer, tx, txEpoch, nonce = nonce, snapshot = snap)
                    Phase.SRC_RECOVERY -> send(MsgType.ABORT_REQ, peer, tx, txEpoch)
                    Phase.PROVISIONAL -> send(MsgType.TARGET_PLAYING, peer, tx, txEpoch)
                    Phase.UNCERTAIN -> send(MsgType.RECOVERY_QUERY, peer, tx, txEpoch, started = true)
                    else -> return
                }
                env.setTimer(T_RTX, cfg.retransmitMs)
            }
            T_CLAIM -> if (claimsLeft > 0 && believesOwner) {
                claimsLeft--
                send(MsgType.OWNER_CLAIM, claimPeer, 0, ownerEpoch)
                env.setTimer(T_CLAIM, cfg.retransmitMs)
            }
        }
    }

    override fun onPlayerPaused() {
        when (phase) {
            Phase.SRC_PAUSING -> {
                if (abortWhilePausing || abortDecided) {
                    // The target never received a STATE_TRANSFER for this transaction, so it cannot be playing.
                    val p = peer; val t = tx; val e = txEpoch
                    resumeSourceAndFinish()
                    send(MsgType.ABORT_REQ, p, t, e)
                } else {
                    snap = env.player().snapshot()   // final snapshot taken only after the pause is confirmed
                    phase = Phase.TRANSFERRING
                    send(MsgType.STATE_TRANSFER, peer, tx, txEpoch, nonce = nonce, snapshot = snap)
                    env.setTimer(T_RTX, cfg.retransmitMs)
                }
            }
            Phase.TGT_STOPPING -> when (stopThen) {
                StopThen.GO_UNCERTAIN -> {
                    phase = Phase.UNCERTAIN
                    send(MsgType.RECOVERY_QUERY, peer, tx, txEpoch, started = true)
                    env.setTimer(T_RTX, cfg.retransmitMs)
                }
                StopThen.REPLY_ABORTED -> targetAbort()
                StopThen.RESUME_COMMITTED -> { val from = resumePoint(); finish(markDead = false); env.player().requestPlay(from) }
                StopThen.NONE -> {}
            }
            else -> {}
        }
    }

    override fun onPlayerStarted(ok: Boolean) {
        when (phase) {
            Phase.TGT_STARTING -> when {
                !ok -> targetAbort()
                stopAfterStart != StopThen.NONE -> {
                    tgtStarted = true
                    phase = Phase.TGT_STOPPING; stopThen = stopAfterStart; stopAfterStart = StopThen.NONE
                    env.player().requestPause()
                }
                else -> {
                    tgtStarted = true
                    phase = Phase.PROVISIONAL
                    send(MsgType.TARGET_PLAYING, peer, tx, txEpoch)
                    env.setTimer(T_RTX, cfg.retransmitMs)
                }
            }
            Phase.IDLE -> if (ok && !believesOwner) env.player().requestPause()   // start raced with a claim
            else -> {}
        }
    }

    /**
     * Called after the coordinator process restarts. Fields are assumed persisted on every
     * transition (write-ahead); timers are not, so they are re-derived from the anchor.
     */
    override fun onRestart() {
        when (phase) {
            Phase.PREPARING -> { send(MsgType.ABORT_REQ, peer, tx, txEpoch); finish(markDead = true) }
            Phase.SRC_PAUSING, Phase.TRANSFERRING -> {
                val remaining = maxOf(1L, anchor + cfg.windowMs + cfg.leaseGuardMs - env.now())
                if (phase == Phase.SRC_PAUSING && !env.player().isPlaying()) {
                    // Pause completed while we were down, snapshot was never sent: target cannot have started.
                    abortDecided = true
                    val p = peer; val t = tx; val e = txEpoch
                    resumeSourceAndFinish()
                    send(MsgType.ABORT_REQ, p, t, e)
                } else {
                    env.setTimer(T_LEASE, remaining)
                    if (phase == Phase.TRANSFERRING) env.setTimer(T_RTX, cfg.retransmitMs)
                }
            }
            Phase.SRC_RECOVERY -> env.setTimer(T_RTX, cfg.retransmitMs)
            Phase.READY_SENT -> targetAbort()
            Phase.TGT_STARTING, Phase.PROVISIONAL, Phase.TGT_STOPPING -> {
                // Uncommitted provisional playback must not survive a restart.
                // A start issued before the crash may have completed while we were down.
                if (phase == Phase.TGT_STARTING && env.player().isPlaying()) tgtStarted = true
                phase = Phase.TGT_STOPPING; stopThen = StopThen.GO_UNCERTAIN; stopAfterStart = StopThen.NONE
                env.player().requestPause()
            }
            Phase.UNCERTAIN -> env.setTimer(T_RTX, cfg.retransmitMs)
            Phase.IDLE -> {}
        }
    }

    // ---------------------------------------------------------------- persistence
    // Added for the Android engine. Pure accessors: they do not change protocol behaviour.

    /** Every field onRestart() relies on. The engine stores it before any effect leaves the process. */
    fun exportState(): CoordinatorState = CoordinatorState(
        ownerEpoch = ownerEpoch, ownerId = ownerId, phase = phase, staleRejected = staleRejected,
        epochFloor = epochFloor, tx = tx, txEpoch = txEpoch, peer = peer, anchor = anchor, win = win,
        nonce = nonce, snap = snap, abortDecided = abortDecided, abortWhilePausing = abortWhilePausing,
        stopThen = stopThen.name, stopAfterStart = stopAfterStart.name, autoSuspended = autoSuspended, tgtStarted = tgtStarted,
        claimsLeft = claimsLeft, claimPeer = claimPeer, dead = dead.toList(),
        committed = committed.entries.map { it.key to it.value }
    )

    /** Restore a stored state; call onRestart() afterwards so timers are re-derived. */
    fun importState(s: CoordinatorState) {
        ownerEpoch = s.ownerEpoch; ownerId = s.ownerId; phase = s.phase; staleRejected = s.staleRejected
        epochFloor = s.epochFloor; tx = s.tx; txEpoch = s.txEpoch; peer = s.peer; anchor = s.anchor; win = s.win
        nonce = s.nonce; snap = s.snap; abortDecided = s.abortDecided; abortWhilePausing = s.abortWhilePausing
        stopThen = StopThen.valueOf(s.stopThen); stopAfterStart = StopThen.valueOf(s.stopAfterStart)
        autoSuspended = s.autoSuspended; tgtStarted = s.tgtStarted; claimsLeft = s.claimsLeft; claimPeer = s.claimPeer
        dead.clear(); dead.addAll(s.dead)
        committed.clear(); for ((k, v) in s.committed) committed[k] = v
    }

    companion object {
        const val T_PREP = "prep"
        const val T_LEASE = "lease"
        const val T_WINDOW = "window"
        const val T_RTX = "rtx"
        const val T_CLAIM = "claim"
    }
}
