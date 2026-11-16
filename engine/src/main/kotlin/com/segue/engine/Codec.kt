package com.segue.engine

import com.segue.core.CoordinatorState
import com.segue.core.CoordinatorStateCodec
import com.segue.core.Hkdf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Link frames: one type byte, then the body. Only DATA frames are allowed after the handshake. */
internal object Frame {
    const val HS1 = 0x01
    const val HS2 = 0x02
    const val HS3 = 0x03
    const val DATA = 0x10
    const val ENR_COMMIT = 0x20
    const val ENR_REVEAL_R = 0x21
    const val ENR_REVEAL_I = 0x22
    const val ABORT = 0x30

    fun wrap(type: Int, body: ByteArray): ByteArray {
        val out = ByteArray(body.size + 1)
        out[0] = type.toByte()
        System.arraycopy(body, 0, out, 1, body.size)
        return out
    }
}

/** Kinds of plaintext carried inside sealed DATA frames. */
internal object AppKind {
    const val COORD = 1
    const val PULL = 2
    const val GROUP = 3
    const val STATUS = 4
    const val PING = 5
    const val PONG = 6
}

internal class GroupMsg(val key: ByteArray, val memberCount: Int, val label: String)

internal object Codec {
    private inline fun build(block: (DataOutputStream) -> Unit): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).use(block)
        return bo.toByteArray()
    }

    /** Parses with [block]; null on any error or trailing bytes. */
    private inline fun <T> parse(bytes: ByteArray, block: (DataInputStream) -> T): T? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            val r = block(i)
            if (i.read() != -1) null else r
        }
    } catch (e: Exception) { null }

    fun intBytes(i: Int): ByteArray = ByteBuffer.allocate(4).putInt(i).array()

    /** Length-prefixed fields, same layout as the core handshake messages. */
    fun fields(vararg parts: ByteArray): ByteArray = build { o ->
        for (p in parts) { o.writeShort(p.size); o.write(p) }
    }

    fun readFields(bytes: ByteArray, count: Int): List<ByteArray>? = parse(bytes) { i ->
        List(count) {
            val n = i.readUnsignedShort()
            ByteArray(n).also { i.readFully(it) }
        }
    }

    /** Sender id inside handshake message 1 (idI | ephI | nonceI). */
    fun handshakeSenderId(m1: ByteArray): Int? =
        readFields(m1, 3)?.get(0)?.takeIf { it.size == 4 }?.let { ByteBuffer.wrap(it).int }

    fun encodeGroup(g: GroupMsg): ByteArray = build { o ->
        o.writeShort(g.key.size); o.write(g.key); o.writeInt(g.memberCount); o.writeUTF(g.label)
    }

    fun decodeGroup(b: ByteArray): GroupMsg? = parse(b) { i ->
        val n = i.readUnsignedShort()
        require(n == 32)
        val key = ByteArray(n).also { i.readFully(it) }
        val count = i.readInt()
        require(count in 0..64)
        val label = i.readUTF()
        require(label.length <= 64)
        GroupMsg(key, count, label)
    }

    fun encodeStatus(s: PeerStatus): ByteArray = build { o ->
        o.writeInt(s.ownerId); o.writeLong(s.ownerEpoch); o.writeBoolean(s.playing); o.writeBoolean(s.routeReady)
        o.writeLong(s.positionMs); o.writeUTF(s.phase)
    }

    fun decodeStatus(b: ByteArray): PeerStatus? = parse(b) { i ->
        PeerStatus(i.readInt(), i.readLong(), i.readBoolean(), i.readBoolean(), i.readLong(), i.readUTF())
    }

    fun encodeLongs(vararg v: Long): ByteArray = build { o -> for (x in v) o.writeLong(x) }

    fun decodeLongs(b: ByteArray, n: Int): LongArray? = parse(b) { i -> LongArray(n) { i.readLong() } }

    fun encodeMembers(list: List<Member>): ByteArray = build { o ->
        o.writeByte(1)
        o.writeInt(list.size)
        for (m in list) { o.writeInt(m.id); o.writeShort(m.publicKey.size); o.write(m.publicKey); o.writeUTF(m.label) }
    }

    fun decodeMembers(b: ByteArray): List<Member>? = parse(b) { i ->
        require(i.readUnsignedByte() == 1)
        val n = i.readInt()
        require(n in 0..64)
        List(n) {
            val id = i.readInt()
            val key = ByteArray(i.readUnsignedShort()).also { i.readFully(it) }
            Member(id, key, i.readUTF())
        }
    }

    /** Persisted coordinator record: boot id (to detect a reboot) + coordinator state. */
    fun encodeStored(bootId: String, s: CoordinatorState): ByteArray = build { o ->
        val st = CoordinatorStateCodec.encode(s)
        o.writeByte(1); o.writeUTF(bootId); o.writeInt(st.size); o.write(st)
    }

    fun decodeStored(b: ByteArray): Pair<String, CoordinatorState>? = parse(b) { i ->
        require(i.readUnsignedByte() == 1)
        val boot = i.readUTF()
        val n = i.readInt()
        require(n in 1..1_000_000)
        val st = ByteArray(n).also { i.readFully(it) }
        boot to (CoordinatorStateCodec.decode(st) ?: throw IllegalArgumentException("state"))
    }
}

internal object Names {
    const val PAIR_PREFIX = "pair-"

    /** Per-device advertising key: names rotate together but differ per phone, so a member can tell who is advertising. */
    fun deviceKey(groupKey: ByteArray, deviceId: Int): ByteArray =
        Hkdf.hmac(groupKey, "SG2-dev".toByteArray(), Codec.intBytes(deviceId))
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
