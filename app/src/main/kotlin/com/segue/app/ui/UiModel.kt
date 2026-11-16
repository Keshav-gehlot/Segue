package com.segue.app.ui

import com.segue.engine.EngineState

data class UiExtras(
    val routeDescription: String = "",
    val transportError: String? = null,
    val foregroundError: String? = null,
    val logPath: String? = null
)

data class UiModel(
    val state: EngineState? = null,
    val missingPermissions: List<String> = emptyList(),
    val bluetoothOn: Boolean = true,
    val backgroundUnrestricted: Boolean = true,
    val backgroundTips: List<String> = emptyList(),
    val serviceRunning: Boolean = false,
    val extras: UiExtras = UiExtras()
)

enum class FaultKind { DROP_READY, DROP_STATE_TRANSFER, KILL_IN_PROVISIONAL, KILL_IN_TRANSFERRING, CLEAR }

interface UiActions {
    fun grantPermissions()
    fun openBatterySettings()
    fun play()
    fun pause()
    fun handoff(peerId: Int)
    fun pull()
    fun startPairing()
    fun confirmPairing(match: Boolean)
    fun cancelPairing()
    fun dismissPairing()
    fun unpair(peerId: Int)
    fun fault(kind: FaultKind)
    fun startBench(count: Int, peerId: Int)
    fun stopBench()
    fun stopService()
    fun startService()
}

/** Plain-text view logic, kept out of Compose so it compiles and tests without the UI toolkit. */
object Describe {
    fun playback(s: EngineState): String = when {
        s.playing -> "Playing on this phone"
        s.iAmOwner -> "Paused on this phone"
        else -> {
            val owner = s.peers.firstOrNull { it.id == s.ownerId }
            when {
                owner == null -> "Nothing playing"
                owner.status?.playing == true -> "Playing on ${owner.label}"
                else -> "Paused on ${owner.label}"
            }
        }
    }

    fun position(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    fun peerLine(p: com.segue.engine.PeerView): String {
        if (!p.connected) return "Not connected"
        val st = p.status ?: return "Connected"
        val play = if (st.playing) "playing" else "idle"
        val route = if (st.routeReady) "earbuds ready" else "earbuds not routed here"
        val rtt = p.rttMs?.let { " · ${it} ms" } ?: ""
        return "Connected · $play · $route$rtt"
    }

    fun canSendTo(s: EngineState, p: com.segue.engine.PeerView): Boolean =
        s.playing && s.iAmOwner && s.phase == "IDLE" && p.connected && p.status?.routeReady == true
}
