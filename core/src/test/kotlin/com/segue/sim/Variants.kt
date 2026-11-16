package com.segue.sim

import com.segue.core.Env
import com.segue.core.HandoffNode
import com.segue.core.Msg
import com.segue.core.MsgType
import com.segue.core.Snapshot

/**
 * Ablation variant A0: pause, transfer, wait for an acknowledgement, roll back on timeout.
 * Idempotent per transaction id, but no ownership epochs, no expiry and no provisional lease.
 */
class NaiveNode(private val env: Env, initialOwner: Int) : HandoffNode {
    private enum class P { IDLE, PAUSING, WAIT_ACK, STARTING }

    private var phase = P.IDLE
    private var tx = 0L
    private var peer = -1
    private var snap: Snapshot? = null
    private var anchor = 0L
    private var owner = initialOwner == env.self
    private val seen = HashSet<Long>()
    private val started = HashSet<Long>()
    private var ackRepeats = 0
    private var ackTx = 0L
    private var ackPeer = -1
    private var claims = 0
    private var claimPeer = -1

    override val believesOwner: Boolean get() = owner

    private fun send(type: MsgType, to: Int, txId: Long, snapshot: Snapshot? = null) =
        env.send(Msg(type, env.self, to, txId, 0, snapshot = snapshot))

    override fun requestHandoff(target: Int): Boolean {
        if (phase != P.IDLE || !env.player().isPlaying()) return false
        tx = env.randomId(); peer = target; phase = P.PAUSING
        env.player().requestPause()
        return true
    }

    override fun onPlayerPaused() {
        if (phase != P.PAUSING) return
        snap = env.player().snapshot()
        phase = P.WAIT_ACK
        anchor = env.now()
        send(MsgType.STATE_TRANSFER, peer, tx, snap)
        env.setTimer("ack", ACK_TIMEOUT)
        env.setTimer("rtx", RTX)
    }

    override fun onMessage(m: Msg) {
        when (m.type) {
            MsgType.STATE_TRANSFER -> {
                if (m.txId in seen) { if (m.txId in started) send(MsgType.TARGET_PLAYING, m.sender, m.txId); return }
                seen.add(m.txId)
                if (phase != P.IDLE || !env.routeReady() || m.snapshot == null) { send(MsgType.ABORTED, m.sender, m.txId); return }
                tx = m.txId; peer = m.sender; phase = P.STARTING
                env.player().requestPlay(m.snapshot)
            }
            MsgType.TARGET_PLAYING -> if (phase == P.WAIT_ACK && m.txId == tx) {
                owner = false; phase = P.IDLE
                env.cancelTimer("ack"); env.cancelTimer("rtx")
            }
            MsgType.ABORTED -> if (phase == P.WAIT_ACK && m.txId == tx) {
                phase = P.IDLE; env.cancelTimer("ack"); env.cancelTimer("rtx")
                env.player().requestPlay(null)
            }
            MsgType.OWNER_CLAIM -> {
                phase = P.IDLE; owner = false; claims = 0
                env.cancelTimer("ack"); env.cancelTimer("rtx"); env.cancelTimer("claim")
                if (env.player().isPlaying()) env.player().requestPause()
            }
            else -> {}
        }
    }

    override fun onPlayerStarted(ok: Boolean) {
        if (phase != P.STARTING) return
        phase = P.IDLE
        if (ok) {
            owner = true; started.add(tx)
            ackTx = tx; ackPeer = peer; ackRepeats = 3
            send(MsgType.TARGET_PLAYING, peer, tx)
            env.setTimer("ackrtx", RTX)
        } else send(MsgType.ABORTED, peer, tx)
    }

    override fun onTimer(name: String) {
        when (name) {
            "ack" -> if (phase == P.WAIT_ACK) { phase = P.IDLE; env.cancelTimer("rtx"); env.player().requestPlay(null) }
            "rtx" -> if (phase == P.WAIT_ACK) { send(MsgType.STATE_TRANSFER, peer, tx, snap); env.setTimer("rtx", RTX) }
            "ackrtx" -> if (ackRepeats > 0) { ackRepeats--; send(MsgType.TARGET_PLAYING, ackPeer, ackTx); env.setTimer("ackrtx", RTX) }
            "claim" -> if (claims > 0 && owner) { claims--; env.send(Msg(MsgType.OWNER_CLAIM, env.self, claimPeer, 0, 0)); env.setTimer("claim", RTX) }
        }
    }

    override fun onRestart() {
        when (phase) {
            P.WAIT_ACK -> { env.setTimer("ack", maxOf(1L, anchor + ACK_TIMEOUT - env.now())); env.setTimer("rtx", RTX) }
            P.PAUSING -> if (!env.player().isPlaying()) { phase = P.IDLE; env.player().requestPlay(null) }
            P.STARTING -> { phase = P.IDLE; if (env.player().isPlaying()) { owner = true; started.add(tx) } }
            P.IDLE -> {}
        }
    }

    override fun manualClaim(peerId: Int) {
        phase = P.IDLE; owner = true
        env.cancelTimer("ack"); env.cancelTimer("rtx")
        if (!env.player().isPlaying()) env.player().requestPlay(null)
        claimPeer = peerId; claims = 6
        env.send(Msg(MsgType.OWNER_CLAIM, env.self, peerId, 0, 0))
        env.setTimer("claim", RTX)
    }

    companion object { const val ACK_TIMEOUT = 3000L; const val RTX = 250L }
}

/**
 * Ablation variant A1: PREPARE/READY, ownership epochs, finished-transaction memory and a
 * wall-clock expiry carried in STATE_TRANSFER (the literal issuedAt/expiresAt reading of the
 * v1.0 envelope). The target commits as soon as it starts; the source rolls back when the expiry
 * passes on its own clock. Conflicts are reconciled by epoch when the two phones can talk again.
 */
class ExpiryNode(private val env: Env, initialOwner: Int) : HandoffNode {
    private enum class P { IDLE, PREPARING, SRC_PAUSING, TRANSFERRING, READY_SENT, TGT_STARTING }

    private var phase = P.IDLE
    private var ownerEpoch = 0L
    private var ownerId = initialOwner
    private var epochFloor = 0L
    private var tx = 0L
    private var txEpoch = 0L
    private var peer = -1
    private var snap: Snapshot? = null
    private var expiresAtWall = 0L
    private val dead = HashSet<Long>()
    private val committed = HashSet<Long>()
    private var announceTx = 0L
    private var announceEpoch = 0L
    private var announcePeer = -1
    private var claims = 0
    private var claimPeer = -1

    override val believesOwner: Boolean get() = ownerId == env.self

    private fun send(type: MsgType, to: Int, txId: Long, epoch: Long, snapshot: Snapshot? = null, expires: Long = 0, reason: String = "") =
        env.send(Msg(type, env.self, to, txId, epoch, snapshot = snapshot, expiresAtWallMs = expires, reason = reason))

    private fun idle(markDead: Boolean) {
        if (markDead && tx != 0L) dead.add(tx)
        phase = P.IDLE; tx = 0; snap = null
        env.cancelTimer("prep"); env.cancelTimer("expiry"); env.cancelTimer("rtx"); env.cancelTimer("ready")
    }

    override fun requestHandoff(target: Int): Boolean {
        if (!believesOwner || phase != P.IDLE || !env.player().isPlaying()) return false
        tx = env.randomId(); txEpoch = maxOf(ownerEpoch, epochFloor) + 1; epochFloor = txEpoch; peer = target
        phase = P.PREPARING
        send(MsgType.PREPARE, peer, tx, txEpoch)
        env.setTimer("prep", 1500); env.setTimer("rtx", RTX)
        return true
    }

    override fun onMessage(m: Msg) {
        if (m.epoch > epochFloor) epochFloor = m.epoch
        when (m.type) {
            MsgType.PREPARE -> {
                if (m.txId == tx && phase == P.READY_SENT) { send(MsgType.READY, m.sender, tx, txEpoch); return }
                if (m.txId == tx) return
                if (m.txId in dead) { send(MsgType.ABORTED, m.sender, m.txId, m.epoch); return }
                val ok = m.sender == ownerId && m.epoch > ownerEpoch && phase == P.IDLE && env.routeReady() && !env.player().isPlaying()
                if (!ok) { send(MsgType.REJECTED, m.sender, m.txId, m.epoch); return }
                tx = m.txId; txEpoch = m.epoch; peer = m.sender; phase = P.READY_SENT
                send(MsgType.READY, peer, tx, txEpoch)
                env.setTimer("ready", 2 * WINDOW)
            }
            MsgType.READY -> if (phase == P.PREPARING && m.txId == tx) {
                env.cancelTimer("prep"); phase = P.SRC_PAUSING; env.player().requestPause()
            }
            MsgType.REJECTED -> if (phase == P.PREPARING && m.txId == tx) idle(true)
            MsgType.STATE_TRANSFER -> {
                if (phase == P.READY_SENT && m.txId == tx && m.epoch == txEpoch) {
                    if (env.wallNow() > m.expiresAtWallMs - START_BUDGET || !env.routeReady() || m.snapshot == null) {
                        val p = peer; val t = tx; val e = txEpoch
                        idle(true); send(MsgType.ABORTED, p, t, e)
                    } else { phase = P.TGT_STARTING; env.cancelTimer("ready"); env.player().requestPlay(m.snapshot) }
                } else if (m.txId != tx && m.txId != announceTx) { dead.add(m.txId); send(MsgType.ABORTED, m.sender, m.txId, m.epoch) }
            }
            MsgType.TARGET_PLAYING -> {
                if (m.txId in committed) { send(MsgType.OWNER_COMMITTED, m.sender, m.txId, m.epoch); return }
                // Either the normal commit, or reconciliation after this phone already rolled back:
                // the higher epoch wins, so yield to the target.
                if (m.epoch > ownerEpoch) {
                    val wasMine = m.txId == tx
                    if (wasMine) idle(false) else if (tx != 0L) idle(true)
                    ownerEpoch = m.epoch; ownerId = m.sender; committed.add(m.txId)
                    if (env.player().isPlaying()) env.player().requestPause()
                    send(MsgType.OWNER_COMMITTED, m.sender, m.txId, m.epoch)
                }
            }
            MsgType.OWNER_COMMITTED -> if (m.txId == announceTx) { announceTx = 0; env.cancelTimer("announce") }
            MsgType.ABORTED -> if (m.txId == tx) when (phase) {
                P.PREPARING -> idle(true)
                P.TRANSFERRING -> { idle(true); env.player().requestPlay(null) }
                else -> {}
            }
            MsgType.OWNER_CLAIM -> if (m.epoch > ownerEpoch) {
                if (tx != 0L) idle(true)
                announceTx = 0; env.cancelTimer("announce"); claims = 0; env.cancelTimer("claim")
                ownerEpoch = m.epoch; ownerId = m.sender
                if (env.player().isPlaying()) env.player().requestPause()
            }
            else -> {}
        }
    }

    override fun onPlayerPaused() {
        if (phase != P.SRC_PAUSING) return
        snap = env.player().snapshot()
        expiresAtWall = env.wallNow() + WINDOW
        phase = P.TRANSFERRING
        send(MsgType.STATE_TRANSFER, peer, tx, txEpoch, snap, expiresAtWall)
        env.setTimer("expiry", WINDOW + GUARD); env.setTimer("rtx", RTX)
    }

    override fun onPlayerStarted(ok: Boolean) {
        when (phase) {
            P.TGT_STARTING -> {
                val p = peer; val t = tx; val e = txEpoch
                if (ok) {
                    ownerEpoch = e; ownerId = env.self                     // target commits on start
                    idle(false)
                    announceTx = t; announceEpoch = e; announcePeer = p
                    send(MsgType.TARGET_PLAYING, p, t, e)
                    env.setTimer("announce", RTX)
                } else { idle(true); send(MsgType.ABORTED, p, t, e) }
            }
            P.IDLE -> if (ok && !believesOwner) env.player().requestPause()
            else -> {}
        }
    }

    override fun onTimer(name: String) {
        when (name) {
            "prep" -> if (phase == P.PREPARING) idle(true)
            "ready" -> if (phase == P.READY_SENT) idle(true)
            "expiry" -> if (phase == P.TRANSFERRING) { idle(true); env.player().requestPlay(null) }   // roll back
            "rtx" -> when (phase) {
                P.PREPARING -> { send(MsgType.PREPARE, peer, tx, txEpoch); env.setTimer("rtx", RTX) }
                P.TRANSFERRING -> { send(MsgType.STATE_TRANSFER, peer, tx, txEpoch, snap, expiresAtWall); env.setTimer("rtx", RTX) }
                else -> {}
            }
            "announce" -> if (announceTx != 0L && believesOwner) {
                send(MsgType.TARGET_PLAYING, announcePeer, announceTx, announceEpoch); env.setTimer("announce", RTX)
            }
            "claim" -> if (claims > 0 && believesOwner) { claims--; send(MsgType.OWNER_CLAIM, claimPeer, 0, ownerEpoch); env.setTimer("claim", RTX) }
        }
    }

    override fun onRestart() {
        when (phase) {
            P.PREPARING, P.READY_SENT -> idle(true)
            P.SRC_PAUSING -> if (!env.player().isPlaying()) { idle(true); env.player().requestPlay(null) }
            P.TRANSFERRING -> { env.setTimer("expiry", maxOf(1L, expiresAtWall + GUARD - env.wallNow())); env.setTimer("rtx", RTX) }
            P.TGT_STARTING -> if (env.player().isPlaying()) onPlayerStarted(true) else idle(true)
            P.IDLE -> if (announceTx != 0L) env.setTimer("announce", RTX)
        }
    }

    override fun manualClaim(peerId: Int) {
        if (tx != 0L) idle(true)
        announceTx = 0
        ownerEpoch = maxOf(ownerEpoch, epochFloor) + 1; epochFloor = ownerEpoch; ownerId = env.self
        if (!env.player().isPlaying()) env.player().requestPlay(null)
        claimPeer = peerId; claims = 6
        send(MsgType.OWNER_CLAIM, peerId, 0, ownerEpoch)
        env.setTimer("claim", RTX)
    }

    companion object { const val WINDOW = 4000L; const val GUARD = 500L; const val START_BUDGET = 1000L; const val RTX = 250L }
}
