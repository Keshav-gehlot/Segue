package com.segue.app.player

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.segue.core.PlayerPort
import com.segue.engine.Cancellable
import com.segue.engine.Scheduler
import com.segue.engine.SpotifyApp
import com.segue.engine.SpotifyController
import com.segue.engine.SpotifyState
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UnsupportedFeatureVersionException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerState

/**
 * Binds [SpotifyController] (the logic, tested in the engine module) to the Spotify App Remote SDK
 * and to this device's audio state (plan: ":adapter-spotify"). This class owns the connection to
 * the Spotify app: connecting, the one-time approval, reconnecting, and the status text.
 * Everything runs on the main thread.
 */
class SpotifyPlayer(
    context: Context,
    private val clientId: () -> String,
    listener: SpotifyController.Listener,
    startTimeoutMs: Long,
    pauseTimeoutMs: Long,
    private val log: (String) -> Unit,
    /** Connection, status text or track changed. */
    private val onViewChanged: () -> Unit
) {
    data class View(
        val connected: Boolean,
        val status: String,
        val title: String?,
        val artist: String?,
        val needsClientId: Boolean
    )

    private val app = context.applicationContext
    private val am = app.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val scheduler = object : Scheduler {
        override fun nowMs(): Long = SystemClock.elapsedRealtime()
        override fun post(task: Runnable) { if (Looper.myLooper() == main.looper) task.run() else main.post(task) }
        override fun schedule(delayMs: Long, task: Runnable): Cancellable {
            main.postDelayed(task, delayMs)
            return Cancellable { main.removeCallbacks(task) }
        }
    }

    private var remote: SpotifyAppRemote? = null
    private var stateSub: Subscription<PlayerState>? = null
    private var connecting = false
    private var connectAttempt = 0
    private var released = false
    private var status = "Not connected"
    @Volatile private var vConnected = false
    @Volatile private var vView = View(false, "Not connected", null, null, false)

    private val link = object : SpotifyApp {
        override val connected: Boolean get() = remote?.isConnected == true
        override fun reconnect() = connect(false)
        override fun pause() { remote?.playerApi?.pause()?.setErrorCallback { e -> log("pause failed: ${e.message}") } }
        override fun resume() { remote?.playerApi?.resume()?.setErrorCallback { e -> log("resume failed: ${e.message}") } }
        override fun play(uri: String, done: () -> Unit) {
            val r = remote ?: return
            r.playerApi.play(uri)
                .setResultCallback { onMain(done) }
                .setErrorCallback { e -> log("play failed: ${e.message}") }
        }
        override fun seekTo(positionMs: Long) { remote?.playerApi?.seekTo(positionMs)?.setErrorCallback { e -> log("seek failed: ${e.message}") } }
        override fun switchToLocalDevice(done: () -> Unit) {
            val r = remote ?: run { done(); return }
            r.connectApi.connectSwitchToLocalDevice()
                .setResultCallback { onMain(done) }
                .setErrorCallback { e -> log("switch to this device failed: ${e.message}"); onMain(done) }
        }
        override fun readState(result: (SpotifyState) -> Unit) {
            val r = remote ?: return
            r.playerApi.playerState
                .setResultCallback { s -> onMain { result(convert(s)) } }
                .setErrorCallback { e -> log("reading the player state failed: ${e.message}") }
        }
        override fun musicActive(): Boolean = am.isMusicActive
        override fun mediaKeyPause() {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        }
    }

    private val controller = SpotifyController(link, scheduler, object : SpotifyController.Listener by listener {
        override fun onChanged() { publish() }
    }, startTimeoutMs, pauseTimeoutMs, log)

    /** The coordinator's player. */
    val port: PlayerPort get() = controller

    /** Ready to receive playback: the Spotify app answers. */
    fun ready(): Boolean = vConnected

    fun view(): View = vView

    /** [showApproval] may open Spotify's approval screen; pass true only from a tap in Segue's UI. */
    fun connect(showApproval: Boolean) {
        onMain {
            if (released || connecting || remote?.isConnected == true) return@onMain
            val id = clientId().trim()
            if (id.isEmpty()) { setStatus("Enter the Spotify Client ID"); return@onMain }
            if (!SpotifyAppRemote.isSpotifyInstalled(app)) { setStatus("Spotify is not installed on this device"); retryLater(30_000); return@onMain }
            connecting = true
            val attempt = ++connectAttempt
            setStatus("Connecting to Spotify…")
            // The SDK reports neither success nor failure when Spotify cannot be started (for example
            // when the phone blocks it from starting in the background). Give up after a while.
            main.postDelayed({
                if (connecting && attempt == connectAttempt && remote == null) {
                    connecting = false
                    log("no answer from Spotify after ${CONNECT_TIMEOUT_MS} ms")
                    setStatus("Spotify did not answer. Open the Spotify app once, then tap Connect Spotify.")
                    retryLater(15_000)
                }
            }, CONNECT_TIMEOUT_MS)
            val params = ConnectionParams.Builder(id).setRedirectUri(REDIRECT_URI).showAuthView(showApproval).build()
            try {
                SpotifyAppRemote.connect(app, params, object : Connector.ConnectionListener {
                    override fun onConnected(r: SpotifyAppRemote) { onMain { if (attempt == connectAttempt) connected(r) else SpotifyAppRemote.disconnect(r) } }
                    override fun onFailure(error: Throwable) { onMain { if (attempt == connectAttempt) disconnected(error) } }
                })
            } catch (e: RuntimeException) {
                disconnected(e)
            }
        }
    }

    fun release() {
        onMain {
            released = true
            controller.stop()
            stateSub?.cancel()
            stateSub = null
            remote?.let { SpotifyAppRemote.disconnect(it) }
            remote = null
            vConnected = false
            main.removeCallbacksAndMessages(null)
        }
    }

    private fun connected(r: SpotifyAppRemote) {
        if (released) { SpotifyAppRemote.disconnect(r); return }
        remote = r
        connecting = false
        vConnected = true
        log("connected")
        stateSub = r.playerApi.subscribeToPlayerState().setEventCallback { s -> onMain { if (remote === r) controller.onState(convert(s)) } }
        stateSub?.setErrorCallback { e -> log("player state subscription failed: ${e.message}") }
        // Logged only: shows in device logs whether Spotify reports a remote device differently.
        r.connectApi.subscribeToVolumeState()
            .setEventCallback { v -> log("volume ${v.mVolume} controllable=${v.mControllable}") }
        setStatus("Connected")
        controller.onConnectionChanged()
    }

    private fun disconnected(error: Throwable) {
        val wasConnected = remote != null
        remote = null
        stateSub = null
        connecting = false
        vConnected = false
        log("not connected: ${error.javaClass.simpleName}: ${error.message}")
        setStatus(when (error) {
            is CouldNotFindSpotifyApp -> "Spotify is not installed on this device"
            is NotLoggedInException -> "Log in to the Spotify app, then tap Connect Spotify"
            is UserNotAuthorizedException -> "Not approved yet. Tap Connect Spotify and agree in Spotify"
            is AuthenticationFailedException -> "Spotify rejected this app. Check the Client ID, package name, fingerprint and redirect URI in the Spotify dashboard, and that this Spotify account is on the app's user list."
            is OfflineModeException -> "Spotify is in offline mode"
            is UnsupportedFeatureVersionException -> "Update the Spotify app"
            else -> if (wasConnected) "Spotify disconnected; reconnecting…" else "Could not reach Spotify (${error.javaClass.simpleName}); retrying…"
        })
        controller.onConnectionChanged()
        // Approval and registration problems need the user; everything else is retried quietly.
        if (error !is UserNotAuthorizedException && error !is AuthenticationFailedException) retryLater(if (wasConnected) 3_000 else 10_000)
    }

    /** Result of Spotify's approval screen (shown by the activity with the Spotify Auth library). */
    fun onApproval(ok: Boolean, error: String?) {
        onMain {
            log(if (ok) "approved in Spotify" else "approval failed: $error")
            if (ok) { connectAttempt++; connecting = false; connect(false) }
            else setStatus(when (error) {
                null, "", "access_denied", "user_cancelled", "USER_CANCELLED" -> "Not approved. Tap Connect Spotify and agree in Spotify"
                "INVALID_CLIENT", "invalid_client" -> "Spotify rejected this app's Client ID"
                else -> "Spotify approval failed ($error). Check the Android package and fingerprint in the Spotify dashboard."
            })
        }
    }

    private fun retryLater(ms: Long) {
        main.postDelayed({ if (!released && remote == null) connect(false) }, ms)
    }

    private fun convert(s: PlayerState): SpotifyState {
        val t = s.track
        return SpotifyState(t?.uri, t?.name, t?.artist?.name, s.isPaused, s.playbackPosition, s.playbackSpeed)
    }

    private fun setStatus(text: String) { status = text; publish() }

    private fun publish() {
        val v = View(remote?.isConnected == true, status, controller.title, controller.artist, clientId().isBlank())
        if (v != vView) { vView = v; onViewChanged() }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    companion object {
        /** Must be listed as a Redirect URI of the Spotify developer app. */
        const val REDIRECT_URI = "segue://callback"
        /** Lets the SDK control playback; the scope the approval screen asks for. */
        const val SCOPE = "app-remote-control"
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
