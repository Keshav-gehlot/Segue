package com.segue.app.ui

import com.segue.engine.Campaign
import com.segue.engine.CampaignStatus
import com.segue.engine.EngineState

/** Spotify's side of things, when Segue controls Spotify on this device. */
data class SpotifyUi(
    val connected: Boolean = false,
    val status: String = "",
    val title: String? = null,
    val artist: String? = null,
    val needsClientId: Boolean = false
)

data class UiExtras(
    val routeDescription: String = "",
    /** The earbuds are routed to this device (independent of the player). */
    val earbudsReady: Boolean = false,
    /** null: the built-in test track is the player. */
    val spotify: SpotifyUi? = null,
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
    val extras: UiExtras = UiExtras(),
    val testMode: Boolean = false,
    val campaign: CampaignStatus = CampaignStatus(),
    val logFiles: Int = 0,
    /** One-line result of the last export or clean-up. */
    val notice: String? = null
)

enum class FaultKind { DROP_READY, DROP_STATE_TRANSFER, KILL_IN_PROVISIONAL, KILL_IN_TRANSFERRING, CLEAR }

interface UiActions {
    fun grantPermissions()
    fun openBatterySettings()
    fun play()
    fun pause()
    fun handoff(peerId: Int)
    fun pull()
    fun useSpotify(on: Boolean)
    fun connectSpotify()
    fun setSpotifyClientId(id: String)
    fun startPairing()
    fun confirmPairing(match: Boolean)
    fun cancelPairing()
    fun dismissPairing()
    fun unpair(peerId: Int)
    fun fault(kind: FaultKind)
    fun setTestMode(on: Boolean)
    fun startCampaign(preset: Campaign.Preset, peerId: Int)
    fun stopCampaign()
    fun exportLogs()
    fun clearOldLogs()
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
        val route = if (st.routeReady) "ready to receive" else "not ready to receive"
        val rtt = p.rttMs?.let { " · ${it} ms" } ?: ""
        return "Connected · $play · $route$rtt"
    }

    fun campaign(c: CampaignStatus): String = when {
        c.total == 0 -> ""
        c.running -> "${c.preset}: step ${c.index + 1} of ${c.total} · ${c.current}\n${c.passed} passed · ${c.failed} failed · ${c.inconclusive} inconclusive"
        else -> "${c.preset}: ${c.lastLine}\n${c.passed} passed · ${c.failed} failed · ${c.inconclusive} inconclusive"
    }

    /** Rough duration, shown on the buttons. */
    fun presetHint(p: Campaign.Preset): String = when (p) {
        Campaign.Preset.QUICK -> "about 25 min"
        Campaign.Preset.TIMING -> "400 handoffs, about 75 min"
        Campaign.Preset.FAULTS -> "about 60 min"
        Campaign.Preset.FULL -> "about 2 h 15 min"
    }

    fun exportName(label: String, stamp: String): String =
        "segue-logs-" + label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "device" } + "-" + stamp + ".zip"

    fun nowPlaying(sp: SpotifyUi): String? = when {
        sp.title.isNullOrBlank() -> null
        sp.artist.isNullOrBlank() -> sp.title
        else -> "${sp.title} · ${sp.artist}"
    }

    /** A Spotify Client ID is 32 hexadecimal characters. */
    fun looksLikeClientId(text: String): Boolean = Regex("[0-9a-fA-F]{32}").matches(text.trim())

    fun canSendTo(s: EngineState, p: com.segue.engine.PeerView): Boolean =
        s.playing && s.iAmOwner && s.phase == "IDLE" && p.connected && p.status?.routeReady == true
}
