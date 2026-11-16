package com.segue.app.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.segue.app.route.RouteMonitor
import com.segue.core.PlayerPort
import com.segue.core.Snapshot

/**
 * In-app player used for the evaluation (plan: ":adapter-reference"). It plays [Signal] through
 * AudioTrack, so start and pause completion are observable precisely:
 *  - "started" is reported when the playback head first advances (audio reached the mixer) AND
 *    AudioTrack.getRoutedDevice() is a Bluetooth headset, i.e. this player's own audio goes to the
 *    earbuds, not just "a headset is connected";
 *  - "paused" is reported after pause() + flush() return.
 * Player contract assumed by the coordinator's safety argument:
 *  P1 commands execute in FIFO order (one control thread);
 *  P2 a start completes or fails within b_start, and a failed start never later produces audio;
 *  P3 after a pause completes, no earlier command produces audio.
 * Position = frames actually played, so the snapshot sent to the other phone is exact.
 *
 * All control runs on one HandlerThread; PCM is written by a separate writer thread.
 */
class ReferencePlayer(
    context: Context,
    private val listener: Listener,
    /** Must not exceed the coordinator's start budget b_start: a start that is not audible by then
     *  is stopped and reported failed, so it can never begin playback later (player contract P2). */
    private val startTimeoutMs: Long = 950,
    /** Report a start as failed unless this AudioTrack is actually routed to a Bluetooth headset. */
    private val requireHeadsetRoute: Boolean = true
) : PlayerPort {

    interface Listener {
        fun onPaused()
        fun onStarted(ok: Boolean)
        /** Audio focus was lost to another app or a call: treat as the user pausing here. */
        fun onExternalStop()
    }

    private val am = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val thread = HandlerThread("segue-player").apply { start() }
    private val handler = Handler(thread.looper)

    @Volatile private var playing = false
    @Volatile private var basePositionMs = 0L
    @Volatile private var track: AudioTrack? = null
    @Volatile private var writing = false
    private var writer: Thread? = null
    private var focus: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (playing) listener.onExternalStop()
        }
    }

    override fun isPlaying(): Boolean = playing

    override fun snapshot(): Snapshot = Snapshot(PROVIDER, CONTENT, positionMs(), true)

    fun positionMs(): Long {
        val t = track
        return if (playing && t != null) basePositionMs + t.playbackHeadPosition.toLong() * 1000L / RATE else basePositionMs
    }

    override fun requestPlay(snapshot: Snapshot?) {
        handler.post {
            if (playing) { listener.onStarted(true); return@post }
            if (snapshot != null) {
                if (snapshot.provider != PROVIDER || snapshot.contentId != CONTENT) {
                    Log.w(TAG, "cannot play ${snapshot.provider}/${snapshot.contentId}")
                    listener.onStarted(false); return@post
                }
                basePositionMs = snapshot.positionMs
            }
            if (!requestFocus()) { listener.onStarted(false); return@post }
            val t = buildTrack() ?: run { abandonFocus(); listener.onStarted(false); return@post }
            track = t
            writing = true
            val startFrame = basePositionMs * RATE / 1000L
            writer = Thread({ writeLoop(t, startFrame) }, "segue-pcm").apply { priority = Thread.MAX_PRIORITY; start() }
            t.play()
            playing = true
            pollStarted(t, SystemClock.elapsedRealtime() + startTimeoutMs)
        }
    }

    override fun requestPause() {
        handler.post {
            if (playing) {
                basePositionMs = positionMs()
                stopTrack()
                abandonFocus()
            }
            listener.onPaused()
        }
    }

    fun release() {
        handler.post { stopTrack(); abandonFocus(); thread.quitSafely() }
    }

    private fun pollStarted(t: AudioTrack, deadline: Long) {
        if (track !== t) return
        val routed = t.routedDevice
        when {
            t.playbackHeadPosition > 0 && (!requireHeadsetRoute || (routed != null && routed.type in RouteMonitor.HEADSET_TYPES)) ->
                listener.onStarted(true)
            t.playbackHeadPosition > 0 && routed != null && routed.type !in RouteMonitor.HEADSET_TYPES -> {
                Log.w(TAG, "routed to ${routed.productName} (type ${routed.type}), not the headset: failing the start")
                stopTrack(); abandonFocus(); listener.onStarted(false)
            }
            SystemClock.elapsedRealtime() > deadline -> { stopTrack(); abandonFocus(); listener.onStarted(false) }
            else -> handler.postDelayed({ pollStarted(t, deadline) }, 5)
        }
    }

    private fun stopTrack() {
        val t = track ?: run { playing = false; return }
        playing = false
        writing = false
        track = null
        try { t.pause(); t.flush() } catch (e: IllegalStateException) { Log.w(TAG, "pause", e) }
        try { writer?.join(200) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        writer = null
        t.release()
    }

    private fun writeLoop(t: AudioTrack, startFrame: Long) {
        val buf = ShortArray(RATE / 50)                 // 20 ms
        var frame = startFrame
        while (writing && track === t) {
            Signal.fill(buf, frame, RATE)
            val n = t.write(buf, 0, buf.size)
            if (n < 0) break
            frame += n
        }
    }

    private fun buildTrack(): AudioTrack? = try {
        val format = AudioFormat.Builder()
            .setSampleRate(RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(maxOf(minBuf, RATE / 10 * 2))   // ~100 ms
            .build()
    } catch (e: Exception) {
        Log.e(TAG, "AudioTrack", e); null
    }

    private fun requestFocus(): Boolean {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .setWillPauseWhenDucked(false)
            .build()
        focus = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    companion object {
        const val PROVIDER = "segue.ref"
        const val CONTENT = "chirp-v1"
        const val RATE = 48_000
        private const val TAG = "SeguePlayer"
    }
}
