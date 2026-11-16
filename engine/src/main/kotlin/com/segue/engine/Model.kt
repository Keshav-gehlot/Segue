package com.segue.engine

import com.segue.core.CoordinatorConfig
import com.segue.core.MsgType
import com.segue.core.Phase
import java.io.Writer

/** An enrolled phone: its identity key is pinned at pairing and never changes. */
class Member(val id: Int, val publicKey: ByteArray, val label: String) {
    override fun equals(other: Any?) =
        other is Member && other.id == id && other.publicKey.contentEquals(publicKey) && other.label == label
    override fun hashCode() = id
}

/** What a peer last told us about itself over the authenticated link (display + ownership sync). */
data class PeerStatus(
    val ownerId: Int,
    val ownerEpoch: Long,
    val playing: Boolean,
    val routeReady: Boolean,
    val positionMs: Long,
    val phase: String
)

data class PeerView(
    val id: Int,
    val label: String,
    val fingerprint: String,
    val connected: Boolean,
    val status: PeerStatus?,
    val clockOffsetMs: Long?,
    val rttMs: Long?
)

sealed class PairingView {
    object Idle : PairingView()
    object Searching : PairingView()
    /** Show [sas] large on both phones; the user confirms they match. */
    data class Compare(val sas: String, val peerFingerprint: String) : PairingView()
    data class Waiting(val sas: String) : PairingView()
    data class Done(val label: String) : PairingView()
    data class Failed(val reason: String) : PairingView()
}

data class EngineState(
    val selfId: Int,
    val fingerprint: String,
    val label: String,
    val ownerId: Int,
    val ownerEpoch: Long,
    val phase: String,
    val playing: Boolean,
    val positionMs: Long,
    val routeReady: Boolean,
    val peers: List<PeerView>,
    val pairing: PairingView,
    val lastResult: String?,
    val recent: List<String>,
    /** Automatic handoffs still to run (0 = not running). */
    val benchLeft: Int = 0
) {
    val iAmOwner: Boolean get() = ownerId == selfId
}

interface EngineListener {
    fun onState(state: EngineState)
    /** Fault injection asked the app to die now (bench builds only). */
    fun onKillRequested() {}
}

/** What the engine needs from the radio layer (Nearby Connections on Android, in-memory in tests). */
interface Transport {
    fun transmit(endpointId: String, bytes: ByteArray)
    fun disconnect(endpointId: String)
    /** Advertise [localName] and discover other group members on the normal service. */
    fun startNormal(localName: String)
    fun stopNormal()
    /** Advertise and discover on the separate pairing service. */
    fun startPairing(localName: String)
    fun stopPairing()
}

fun interface EventSink {
    fun event(monoMs: Long, wallMs: Long, device: Int, kind: String, detail: String)

    companion object {
        val NONE = EventSink { _, _, _, _, _ -> }
    }
}

/** CSV event log: t_mono_ms,t_wall_ms,device,kind,detail. Read by scripts/analyze_handoffs.py. */
class CsvEventSink(private val out: Writer) : EventSink {
    init { synchronized(this) { out.write("t_mono_ms,t_wall_ms,device,kind,detail\n"); out.flush() } }

    @Synchronized
    override fun event(monoMs: Long, wallMs: Long, device: Int, kind: String, detail: String) {
        out.write("$monoMs,$wallMs,$device,$kind,\"${detail.replace("\"", "'")}\"\n")
        out.flush()
    }
}

data class EngineConfig(
    val coordinator: CoordinatorConfig = CoordinatorConfig(),
    /** Shown to the other phone at pairing, e.g. Build.MODEL. */
    val label: String = "phone",
    val handshakeTimeoutMs: Long = 10_000,
    val pairingTimeoutMs: Long = 180_000,
    val pingIntervalMs: Long = 5_000,
    val recentEvents: Int = 80
)

/** Fault hooks for on-device experiments. All no-ops unless set. */
class FaultPlan {
    private val drops = HashMap<MsgType, Int>()
    /** Ask the app to kill its process right after the coordinator persists this phase. */
    @Volatile var killInPhase: Phase? = null
    /** Extra one-way delay added to every outgoing protocol message. */
    @Volatile var extraDelayMs: Long = 0

    @Synchronized fun dropNext(type: MsgType, count: Int = 1) { drops[type] = (drops[type] ?: 0) + count }

    @Synchronized fun consumeDrop(type: MsgType): Boolean {
        val n = drops[type] ?: return false
        if (n <= 1) drops.remove(type) else drops[type] = n - 1
        return true
    }

    @Synchronized fun clear() { drops.clear(); killInPhase = null; extraDelayMs = 0 }
}
