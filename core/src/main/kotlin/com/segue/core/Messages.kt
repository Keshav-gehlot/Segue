package com.segue.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Handoff protocol messages (Segue protocol v2).
 *
 * Differences from the handover v1.0 message set:
 *  - READY carries a fresh nonce that STATE_TRANSFER must echo (freshness without clocks).
 *  - Expiry is expressed as a relative window (windowMs) anchored on READY, not as wall-clock
 *    issuedAt/expiresAt timestamps, so safety does not depend on clock synchronisation.
 *  - Abort is split into ABORT_REQ (source -> target, "stop and confirm") and
 *    ABORTED (target -> source, "I am not playing this transaction and never will").
 */
enum class MsgType {
    PREPARE, READY, REJECTED, STATE_TRANSFER, TARGET_PLAYING, OWNER_COMMITTED,
    ABORT_REQ, ABORTED, RECOVERY_QUERY, OWNER_CLAIM
}

data class Snapshot(
    val provider: String,
    val contentId: String,
    val positionMs: Long,
    val confirmed: Boolean
)

data class Msg(
    val type: MsgType,
    val sender: Int,
    val recipient: Int,
    val txId: Long,
    val epoch: Long,
    val windowMs: Long = 0,
    val nonce: Long = 0,
    val snapshot: Snapshot? = null,
    val started: Boolean = false,
    val expiresAtWallMs: Long = 0,   // used only by the expiry-rollback ablation variant
    val reason: String = ""
)

/** Deterministic, dependency-free binary codec. The encoded bytes are what SecureChannel seals. */
object MsgCodec {
    private const val VERSION = 2

    fun encode(m: Msg): ByteArray {
        val bos = ByteArrayOutputStream(96)
        val out = DataOutputStream(bos)
        out.writeByte(VERSION)
        out.writeByte(m.type.ordinal)
        out.writeInt(m.sender)
        out.writeInt(m.recipient)
        out.writeLong(m.txId)
        out.writeLong(m.epoch)
        out.writeLong(m.windowMs)
        out.writeLong(m.nonce)
        out.writeBoolean(m.started)
        out.writeLong(m.expiresAtWallMs)
        out.writeUTF(m.reason)
        val s = m.snapshot
        out.writeBoolean(s != null)
        if (s != null) {
            out.writeUTF(s.provider)
            out.writeUTF(s.contentId)
            out.writeLong(s.positionMs)
            out.writeBoolean(s.confirmed)
        }
        out.flush()
        return bos.toByteArray()
    }

    /** Returns null for anything malformed; never throws on attacker-controlled input. */
    fun decode(bytes: ByteArray): Msg? = try {
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        if (inp.readUnsignedByte() != VERSION) null else {
            val typeIdx = inp.readUnsignedByte()
            if (typeIdx >= MsgType.entries.size) null else {
                val sender = inp.readInt()
                val recipient = inp.readInt()
                val txId = inp.readLong()
                val epoch = inp.readLong()
                val windowMs = inp.readLong()
                val nonce = inp.readLong()
                val started = inp.readBoolean()
                val expires = inp.readLong()
                val reason = inp.readUTF()
                val snap = if (inp.readBoolean())
                    Snapshot(inp.readUTF(), inp.readUTF(), inp.readLong(), inp.readBoolean()) else null
                if (inp.available() != 0) null
                else Msg(MsgType.entries[typeIdx], sender, recipient, txId, epoch, windowMs, nonce,
                    snap, started, expires, reason)
            }
        }
    } catch (e: Exception) {
        null
    }
}
