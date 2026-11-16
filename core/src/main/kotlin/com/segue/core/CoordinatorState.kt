package com.segue.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Persistent image of a HandoffCoordinator (see HandoffCoordinator.exportState / importState).
 * `anchor` is in the monotonic timebase of Env.now(); after a reboot the engine rebases it,
 * because SystemClock.elapsedRealtime restarts from zero.
 */
data class CoordinatorState(
    val ownerEpoch: Long,
    val ownerId: Int,
    val phase: Phase,
    val staleRejected: Long,
    val epochFloor: Long,
    val tx: Long,
    val txEpoch: Long,
    val peer: Int,
    val anchor: Long,
    val win: Long,
    val nonce: Long,
    val snap: Snapshot?,
    val abortDecided: Boolean,
    val abortWhilePausing: Boolean,
    val stopThen: String,
    val stopAfterStart: String,
    val autoSuspended: Boolean,
    val claimsLeft: Int,
    val claimPeer: Int,
    val dead: List<Long>,
    val committed: List<Pair<Long, Long>>
)

/** Versioned binary codec. decode() returns null on any malformed or unknown input. */
object CoordinatorStateCodec {
    private const val VERSION = 1

    fun encode(s: CoordinatorState): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).use { o ->
            o.writeByte(VERSION)
            o.writeLong(s.ownerEpoch); o.writeInt(s.ownerId); o.writeUTF(s.phase.name); o.writeLong(s.staleRejected)
            o.writeLong(s.epochFloor); o.writeLong(s.tx); o.writeLong(s.txEpoch); o.writeInt(s.peer)
            o.writeLong(s.anchor); o.writeLong(s.win); o.writeLong(s.nonce)
            o.writeBoolean(s.snap != null)
            s.snap?.let { o.writeUTF(it.provider); o.writeUTF(it.contentId); o.writeLong(it.positionMs); o.writeBoolean(it.confirmed) }
            o.writeBoolean(s.abortDecided); o.writeBoolean(s.abortWhilePausing)
            o.writeUTF(s.stopThen); o.writeUTF(s.stopAfterStart)
            o.writeBoolean(s.autoSuspended); o.writeInt(s.claimsLeft); o.writeInt(s.claimPeer)
            o.writeInt(s.dead.size); for (d in s.dead) o.writeLong(d)
            o.writeInt(s.committed.size); for ((k, v) in s.committed) { o.writeLong(k); o.writeLong(v) }
        }
        return bo.toByteArray()
    }

    fun decode(bytes: ByteArray): CoordinatorState? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            require(i.readUnsignedByte() == VERSION)
            val ownerEpoch = i.readLong(); val ownerId = i.readInt(); val phase = Phase.valueOf(i.readUTF())
            val stale = i.readLong(); val floor = i.readLong(); val tx = i.readLong(); val txEpoch = i.readLong()
            val peer = i.readInt(); val anchor = i.readLong(); val win = i.readLong(); val nonce = i.readLong()
            val snap = if (i.readBoolean()) Snapshot(i.readUTF(), i.readUTF(), i.readLong(), i.readBoolean()) else null
            val abortDecided = i.readBoolean(); val abortWhilePausing = i.readBoolean()
            val stopThen = i.readUTF(); val stopAfterStart = i.readUTF()
            val autoSuspended = i.readBoolean(); val claimsLeft = i.readInt(); val claimPeer = i.readInt()
            val nDead = i.readInt(); require(nDead in 0..100_000)
            val dead = List(nDead) { i.readLong() }
            val nCommitted = i.readInt(); require(nCommitted in 0..100_000)
            val committed = List(nCommitted) { i.readLong() to i.readLong() }
            require(i.read() == -1)
            CoordinatorState(ownerEpoch, ownerId, phase, stale, floor, tx, txEpoch, peer, anchor, win, nonce, snap,
                abortDecided, abortWhilePausing, stopThen, stopAfterStart, autoSuspended, claimsLeft, claimPeer,
                dead, committed)
        }
    } catch (e: Exception) { null }
}
