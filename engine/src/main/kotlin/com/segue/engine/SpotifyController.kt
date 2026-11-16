package com.segue.engine

import com.segue.core.CoordinatorConfig
import com.segue.core.PlayerPort
import com.segue.core.Rollback
import com.segue.core.Snapshot
import kotlin.math.abs

/** What the Spotify app reports about its player. */
data class SpotifyState(
    val uri: String?,
    val title: String?,
    val artist: String?,
    val paused: Boolean,
    val positionMs: Long,
    val speed: Float = 1f
)

/**
 * The Spotify app on this device, as the App Remote SDK exposes it, plus two platform facts.
 * Commands are asynchronous and unacknowledged unless they take a callback; callbacks must run on
 * the controller's scheduler.
 */
interface SpotifyApp {
    val connected: Boolean
    /** Try to reach the Spotify app again, without showing any screen. */
    fun reconnect()
    fun pause()
    fun resume()
    fun play(uri: String, done: () -> Unit)
    fun seekTo(positionMs: Long)
    /** Spotify Connect: make this device the one that plays. [done] runs whether or not it worked. */
    fun switchToLocalDevice(done: () -> Unit)
    fun readState(result: (SpotifyState) -> Unit)
    /** Is this device producing media audio right now? (AudioManager.isMusicActive) */
    fun musicActive(): Boolean
    /** Last resort when the Spotify app does not answer: a pause media key to whatever is playing. */
    fun mediaKeyPause()
}

/**
 * Drives the Spotify app as the coordinator's player. Segue never touches the audio: it tells
 * Spotify to pause, to move playback to this device, to play a track and to seek, and it watches
 * Spotify's player state.
 *
 * Local or remote. With one Spotify account on several devices, Spotify Connect makes every
 * device's app report the same session, and a command sent to any app acts on whichever device is
 * active. The SDK does not say which device that is, so "playing here" is Spotify not paused AND
 * this device producing audio. Consequences:
 *  - a pause is only sent while there is local audio, so it cannot pause another device;
 *  - every start first makes this device the active one (Spotify's own transfer). With the same
 *    account the track, position and queue arrive with the session; otherwise the track is started
 *    by URI and sought to the position;
 *  - Spotify starting or stopping here without a command is the user acting in the Spotify app,
 *    and is reported so that the engine claims or releases ownership.
 *
 * Player contract (see the reference player): P1 holds because everything runs on one scheduler,
 * in order. P2: a start that is not audible within [startTimeoutMs] is paused and reported failed.
 * P3 is weaker than for the reference player: pause completion is Spotify's "paused" state, not an
 * observation of the audio hardware.
 *
 * State is confined to [sched]; isPlaying() and snapshot() read volatile copies.
 */
class SpotifyController(
    private val app: SpotifyApp,
    private val sched: Scheduler,
    private val listener: Listener,
    private val startTimeoutMs: Long,
    private val pauseTimeoutMs: Long,
    private val log: (String) -> Unit
) : PlayerPort {

    interface Listener {
        fun onPaused()
        fun onStarted(ok: Boolean)
        /** Spotify started playing on this device without Segue asking (the user pressed play). */
        fun onExternalStart()
        /** The user paused Spotify on this device. */
        fun onExternalPause()
        /** Playback left this device without a pause here (Spotify moved it, or the app went away). */
        fun onMovedAway()
        /** The track changed. */
        fun onChanged()
    }

    private sealed class Op
    private class StartOp(val snapshot: Snapshot?, val deadline: Long, val recover: Boolean = false) : Op() {
        var commandsSent = false
        var seekSent = false
        var lastNudge = 0L
    }
    private class PauseOp(val deadline: Long, val silent: Boolean, var lastSent: Long) : Op()

    private var st: SpotifyState? = null
    private var op: Op? = null
    private var quietSince = 0L
    private var lateStopUntil = 0L
    private var graceUntil = 0L
    private var lastLogged = ""
    private var tick: Cancellable? = null
    private var stopped = false

    @Volatile private var localPlaying = false
    @Volatile private var vUri: String? = null
    @Volatile private var vPaused = true
    @Volatile private var vBasePos = 0L
    @Volatile private var vBaseAt = 0L
    @Volatile private var vSpeed = 1f
    @Volatile var title: String? = null; private set
    @Volatile var artist: String? = null; private set

    // ------------------------------------------------------------------ PlayerPort (any thread)

    override fun isPlaying(): Boolean = localPlaying

    override fun snapshot(): Snapshot {
        val uri = vUri ?: ""
        return Snapshot(PROVIDER, uri, positionMs(), uri.isNotEmpty())
    }

    fun positionMs(): Long =
        if (vPaused) vBasePos else vBasePos + ((sched.nowMs() - vBaseAt) * vSpeed).toLong()

    override fun requestPlay(snapshot: Snapshot?) = sched.post {
        if (stopped) return@post
        val now = sched.nowMs()
        if (snapshot != null && (snapshot.provider != PROVIDER || snapshot.contentId.isEmpty())) {
            log("start refused: cannot play ${snapshot.provider}/${snapshot.contentId}")
            listener.onStarted(false); return@post
        }
        if (!app.connected) {
            log("start refused: Spotify is not connected")
            app.reconnect()
            listener.onStarted(false); return@post
        }
        evaluate()
        if (localPlaying && (snapshot == null || st?.uri == snapshot.contentId)) { listener.onStarted(true); return@post }
        val o = StartOp(snapshot, now + startTimeoutMs)
        op = o
        lateStopUntil = 0
        log("start: switch to this device, then ${snapshot?.let { "${it.contentId} @${it.positionMs}" } ?: "resume"}")
        app.switchToLocalDevice { if (op === o) app.readState { s -> issueStart(o, s) } }
        tickSoon()
    }

    override fun requestPause() = sched.post {
        if (stopped) { listener.onPaused(); return@post }
        val now = sched.nowMs()
        val music = app.musicActive()
        val s = st
        if (!app.connected) {
            // No control channel. If something is audible, a media key reaches the active session.
            if (music) {
                log("pause: Spotify is not connected, sending a media key")
                app.mediaKeyPause()
                op = PauseOp(now + pauseTimeoutMs, silent = false, lastSent = now)
                tickSoon()
            } else listener.onPaused()
            return@post
        }
        if (s == null || s.paused || !music) {
            // Nothing audible here. Spotify may be reporting another device's playback, and a pause
            // sent now would pause that device, so none is sent. If audio returns shortly (it was
            // only a gap between tracks), it is stopped then.
            if (localPlaying && s != null && !s.paused) {
                lateStopUntil = now + LATE_STOP_MS
                log("pause: no local audio right now; watching for $LATE_STOP_MS ms")
            }
            localPlaying = false
            op = null
            listener.onPaused()
            tickSoon()
            return@post
        }
        op = PauseOp(now + pauseTimeoutMs, silent = false, lastSent = now)
        app.pause()
        tickSoon()
    }

    // ------------------------------------------------------------------ from the Spotify app (scheduler thread)

    /** A player-state event from Spotify. */
    fun onState(s: SpotifyState) {
        if (stopped) return
        val trackChanged = s.uri != st?.uri
        st = s
        vUri = s.uri
        title = s.title
        artist = s.artist
        vPaused = s.paused
        vSpeed = if (s.speed > 0f) s.speed else 1f
        vBasePos = s.positionMs
        vBaseAt = sched.nowMs()
        val line = "${s.uri} paused=${s.paused}"
        if (line != lastLogged) { lastLogged = line; log("state $line pos=${s.positionMs} audio=${app.musicActive()}") }
        evaluate()
        if (trackChanged) listener.onChanged()
        tickSoon()
    }

    /** The connection to the Spotify app came up or went down. */
    fun onConnectionChanged() {
        if (stopped) return
        evaluate()
        tickSoon()
    }

    fun stop() = sched.post {
        stopped = true
        op = null
        localPlaying = false
        tick?.cancel()
    }

    // ------------------------------------------------------------------ state machine

    private fun issueStart(o: StartOp, s: SpotifyState) {
        if (op !== o) return
        onState(s)
        if (op !== o) return       // already playing here
        val snap = o.snapshot
        when {
            snap == null -> app.resume()
            s.uri == snap.contentId -> {
                log("start: the session is here (pos=${s.positionMs} paused=${s.paused})")
                if (abs(s.positionMs - snap.positionMs) > SEEK_TOLERANCE_MS) { o.seekSent = true; app.seekTo(snap.positionMs) }
                if (s.paused) app.resume()
            }
            else -> {
                log("start: the session did not come over (have ${s.uri}), playing ${snap.contentId} by URI")
                o.seekSent = snap.positionMs > SEEK_TOLERANCE_MS
                app.play(snap.contentId) { if (op === o && o.seekSent) app.seekTo(snap.positionMs) }
            }
        }
        o.commandsSent = true
        o.lastNudge = sched.nowMs()
    }

    /** Re-derive "playing here" and finish or time out the operation in progress. */
    private fun evaluate() {
        val now = sched.nowMs()
        val connected = app.connected
        val s = st
        val music = app.musicActive()
        val was = localPlaying
        var here = was
        if (!connected || s == null || s.paused || s.uri == null) { here = false; quietSince = 0 }
        else if (music) { here = true; quietSince = 0 }
        else if (was) {
            // Not paused but silent: a gap between tracks, buffering, or the session moved to another device.
            if (quietSince == 0L) quietSince = now
            if (now - quietSince >= QUIET_MS) here = false
        }
        localPlaying = here

        // A pause was reported while this device was silent but Spotify was not paused, and audio came back.
        if (op == null && lateStopUntil != 0L) {
            if (now > lateStopUntil) lateStopUntil = 0
            else if (here && music) {
                lateStopUntil = 0
                log("pause: audio came back, pausing now")
                op = PauseOp(now + pauseTimeoutMs, silent = true, lastSent = now)
                app.pause()
            }
        }

        when (val o = op) {
            is StartOp -> {
                val snap = o.snapshot
                if (here && (snap == null || s?.uri == snap.contentId)) {
                    op = null
                    if (snap != null && !o.seekSent) {
                        val pos = positionMs()
                        if (pos < snap.positionMs - 500 || pos > snap.positionMs + startTimeoutMs + SEEK_TOLERANCE_MS) {
                            log("start: position $pos, expected about ${snap.positionMs}; seeking")
                            app.seekTo(snap.positionMs)
                        }
                    }
                    log("started pos=${positionMs()}")
                    if (!o.recover) { graceUntil = now + START_GRACE_MS; listener.onStarted(true) }
                } else if (now >= o.deadline) {
                    op = null
                    log("start timed out (connected=$connected paused=${s?.paused} track=${s?.uri} audio=$music)")
                    // P2: a failed start must not become audible later.
                    if (connected) app.pause()
                    localPlaying = false
                    if (o.recover) listener.onExternalPause() else listener.onStarted(false)
                } else if (o.commandsSent && connected && s != null && s.paused && now - o.lastNudge >= NUDGE_MS &&
                    (snap == null || s.uri == snap.contentId)) {
                    // Another device's pause can land after the transfer; ask again.
                    o.lastNudge = now
                    app.resume()
                }
            }
            is PauseOp -> {
                val quiet = if (connected && s != null) s.paused else !music
                if (quiet || (now >= o.deadline && !music)) {
                    op = null
                    localPlaying = false
                    if (!o.silent) { log("paused pos=${positionMs()}"); listener.onPaused() }
                } else if (now >= o.deadline) {
                    op = null
                    log("pause not confirmed by Spotify after $pauseTimeoutMs ms; sending a media key")
                    app.mediaKeyPause()
                    localPlaying = false
                    if (!o.silent) listener.onPaused()
                } else if (!connected && now - o.lastSent >= 500) {
                    // Only the media key is repeated. A second pause command could reach Spotify after
                    // the session has moved to another device, and would pause that device.
                    o.lastSent = now
                    app.mediaKeyPause()
                }
            }
            null -> {
                if (!was && here) { log("Spotify started playing here"); listener.onExternalStart() }
                else if (was && !here && s?.paused == true && connected && now < graceUntil) {
                    // Paused right after a start that Segue made: most likely the previous device's own
                    // pause reached Spotify after the session had moved here. Ask once more.
                    graceUntil = 0
                    log("paused within $START_GRACE_MS ms of starting here: resuming once")
                    op = StartOp(null, now + startTimeoutMs, recover = true).also { it.commandsSent = true; it.lastNudge = now }
                    app.resume()
                }
                else if (was && !here) {
                    if (s?.paused == true) { log("Spotify was paused here"); listener.onExternalPause() }
                    else { log("playback left this device"); listener.onMovedAway() }
                }
            }
        }
    }

    private fun tickSoon() {
        if (stopped) return
        tick?.cancel()
        val delay = when {
            op != null -> 100L
            lateStopUntil != 0L || st?.paused == false -> 400L
            else -> 1500L
        }
        tick = sched.schedule(delay) {
            if (stopped) return@schedule
            evaluate()
            if (app.connected || op != null) tickSoon()
        }
    }

    companion object {
        const val PROVIDER = "spotify"
        /** Spotify not paused but no local audio for this long: the session is on another device. */
        const val QUIET_MS = 2_000L
        private const val LATE_STOP_MS = 3_000L
        private const val NUDGE_MS = 700L
        /** A pause this soon after a start made by Segue is taken to be a late pause from the previous device. */
        private const val START_GRACE_MS = 2_000L
        private const val SEEK_TOLERANCE_MS = 1_000L

        /**
         * Protocol timing when Spotify is the player. It answers far slower than the built-in
         * player, so the lease window and the start and pause budgets are longer. Every device in
         * Spotify mode uses these values; a device in another mode refuses a Spotify snapshot.
         */
        fun coordinatorConfig(rollback: Rollback): CoordinatorConfig =
            CoordinatorConfig(windowMs = 10_000, prepTimeoutMs = 2_500, startBudgetMs = 5_000, pauseBudgetMs = 1_500, rollback = rollback)

        /** How the controller's reports drive the engine. Shared by the app and the tests. */
        fun listenerFor(engine: () -> SegueEngine): Listener = object : Listener {
            override fun onPaused() { engine().onPlayerPaused() }
            override fun onStarted(ok: Boolean) { engine().onPlayerStarted(ok) }
            override fun onExternalStart() { engine().play() }
            // Not engine.pause(): that marks the phone "paused by its user" and makes it refuse handoffs
            // until the user pulls playback there. A pause in the Spotify app only stops the music.
            override fun onExternalPause() { engine().onPlayerChanged() }
            override fun onMovedAway() { engine().onPlayerChanged() }
            override fun onChanged() { engine().onPlayerChanged() }
        }
    }
}
