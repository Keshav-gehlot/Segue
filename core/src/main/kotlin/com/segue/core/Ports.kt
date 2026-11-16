package com.segue.core

/**
 * Ports the coordinator needs from the platform. On Android these are implemented by the
 * playback adapter (Spotify App Remote / MediaSession / reference Media3 player), the audio-route
 * observer (AudioDeviceCallback) and the peer transport (Nearby Connections + SecureChannel).
 * In tests and in the simulator they are fakes, which is what makes the coordinator testable
 * without Bluetooth hardware.
 */
interface PlayerPort {
    fun isPlaying(): Boolean
    /** Asynchronous. Completion is reported through HandoffNode.onPlayerPaused(). */
    fun requestPause()
    /** Asynchronous. snapshot == null means "resume where paused". Completion: onPlayerStarted(ok). */
    fun requestPlay(snapshot: Snapshot?)
    fun snapshot(): Snapshot
}

interface Env {
    val self: Int
    /** Monotonic local milliseconds (SystemClock.elapsedRealtime on Android). Never wall clock. */
    fun now(): Long
    /** Wall clock; used only by the expiry-rollback ablation variant. */
    fun wallNow(): Long
    fun send(msg: Msg)
    fun setTimer(name: String, delayMs: Long)
    fun cancelTimer(name: String)
    fun player(): PlayerPort
    /** True only when this phone currently has the headset as a usable media output. */
    fun routeReady(): Boolean
    fun randomId(): Long
}

/** Event interface shared by the coordinator and the ablation variants used in the simulator. */
interface HandoffNode {
    fun requestHandoff(target: Int): Boolean
    fun onMessage(m: Msg)
    fun onTimer(name: String)
    fun onPlayerPaused()
    fun onPlayerStarted(ok: Boolean)
    fun onRestart()
    fun manualClaim(peerId: Int)
    val believesOwner: Boolean
}
