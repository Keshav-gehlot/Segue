package com.segue.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.segue.app.bench.EventLogFile
import com.segue.app.player.MediaControls
import com.segue.app.player.ReferencePlayer
import com.segue.app.player.SpotifyPlayer
import com.segue.app.route.RouteMonitor
import com.segue.app.store.BootId
import com.segue.app.store.KeystoreIdentity
import com.segue.app.transport.NearbyTransport
import com.segue.core.CoordinatorConfig
import com.segue.core.Identity
import com.segue.core.PlayerPort
import com.segue.core.Rollback
import com.segue.engine.Campaign
import com.segue.engine.CampaignStatus
import com.segue.engine.EngineConfig
import com.segue.engine.EngineListener
import com.segue.engine.EngineState
import com.segue.engine.EventSink
import com.segue.engine.ExecutorScheduler
import com.segue.engine.FileStore
import com.segue.engine.LogExport
import com.segue.engine.SegueEngine
import com.segue.engine.SpotifyController
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hosts the engine while Segue is on. Foreground service type connectedDevice: the user-visible
 * task is keeping this phone's link to the other enrolled phones and the earbuds' playback in sync.
 * On Android 14+ the type's prerequisite is met by CHANGE_WIFI_STATE (normal permission) and the
 * granted BLUETOOTH_* runtime permissions.
 *
 * The engine object can be replaced while the service runs: tests emulate a coordinator crash (the
 * engine stops, the player keeps playing, a new engine starts from the stored state) and switch the
 * rollback policy. The player, the radio layer, the scheduler and the campaign runner outlive it.
 */
class SegueService : Service(), EngineListener {

    inner class LocalBinder : Binder() {
        val service: SegueService get() = this@SegueService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val uiListeners = CopyOnWriteArrayList<(EngineState) -> Unit>()
    private lateinit var scheduler: ExecutorScheduler
    private lateinit var player: PlayerPort
    private var reference: ReferencePlayer? = null
    var spotify: SpotifyPlayer? = null; private set
    private lateinit var identity: Identity
    private lateinit var config: EngineConfig
    lateinit var route: RouteMonitor; private set
    lateinit var transport: NearbyTransport; private set
    @Volatile lateinit var engine: SegueEngine; private set
    lateinit var campaign: Campaign; private set
    private var media: MediaControls? = null
    var eventLog: EventLogFile? = null; private set
    @Volatile var lastState: EngineState? = null; private set
    @Volatile var campaignStatus = CampaignStatus(); private set
    @Volatile var onCampaign: ((CampaignStatus) -> Unit)? = null
    private var lastSummary: String? = null
    var foregroundError: String? = null; private set

    var testMode: Boolean
        get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TEST_MODE, false)
        set(value) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TEST_MODE, value).apply()
            engine.testMode = value
            engine.note("test_mode", "on=$value")
        }

    /** Which player Segue controls on this device: [MODE_REFERENCE] (built-in test track) or [MODE_SPOTIFY]. */
    val playerMode: String
        get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLAYER, MODE_REFERENCE) ?: MODE_REFERENCE

    /** Client ID of the Spotify developer app: built in (res/values/spotify.xml) or typed in by the user. */
    var spotifyClientId: String
        get() = getString(com.segue.app.R.string.spotify_client_id).trim().ifEmpty {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SPOTIFY_CLIENT, "") ?: ""
        }
        set(value) {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SPOTIFY_CLIENT, value.trim()).apply()
            engine.note("spotify", "client id set (${value.trim().length} chars)")
            spotify?.connect(true)
            engine.refresh()
        }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        scheduler = ExecutorScheduler("segue-engine", { SystemClock.elapsedRealtime() }) { Log.e(TAG, "engine error", it) }
        eventLog = EventLogFile.open(this)
        config = EngineConfig(label = Background.deviceLabel(this).take(40))
        identity = KeystoreIdentity.loadOrCreate()
        route = RouteMonitor(this) { engine.onRouteChanged() }
        buildPlayer()
        transport = NearbyTransport(this)
        campaign = Campaign(scheduler, { engine }) { st ->
            campaignStatus = st
            main.post { onCampaign?.invoke(st) }
        }
        startEngine()
        route.start()
    }

    /**
     * Create the player for the current mode and the timing that goes with it. Main thread; no
     * engine may be running. Spotify answers far slower than the built-in player, so the lease
     * window and the start and pause budgets are longer; both devices derive them from the mode,
     * and a device in the other mode refuses the transferred state, so it never plays.
     */
    private fun buildPlayer() {
        reference?.release(); reference = null
        spotify?.release(); spotify = null
        media?.release(); media = null
        val rollback = config.coordinator.rollback
        if (playerMode == MODE_SPOTIFY) {
            val c = SpotifyController.coordinatorConfig(rollback)
            config = config.copy(coordinator = c)
            val p = SpotifyPlayer(this, clientId = { spotifyClientId }, listener = SpotifyController.listenerFor { engine },
                startTimeoutMs = c.startBudgetMs - 100, pauseTimeoutMs = c.pauseBudgetMs - 200,
                log = { engine.note("spotify", it) }, onViewChanged = { engine.onPlayerChanged() })
            spotify = p
            player = p.port
            // No media session of our own here: it would compete with Spotify's for the earbuds' buttons.
        } else {
            config = config.copy(coordinator = CoordinatorConfig(rollback = rollback))
            val p = ReferencePlayer(this, object : ReferencePlayer.Listener {
                override fun onPaused() = engine.onPlayerPaused()
                override fun onStarted(ok: Boolean) = engine.onPlayerStarted(ok)
                override fun onExternalStop() = engine.pause()
            }, startTimeoutMs = config.coordinator.startBudgetMs - 50)
            reference = p
            player = p
            media = MediaControls(this, onPlay = { engine.play() }, onPause = { engine.pause() })
        }
    }

    /** Switch between the built-in test track and Spotify. Stops playback here and restarts the engine. */
    fun setPlayerMode(mode: String) {
        if (mode == playerMode || (mode != MODE_SPOTIFY && mode != MODE_REFERENCE)) return
        campaign.stop()
        engine.note("player_mode", mode)
        engine.halt()
        transport.dropAll()
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PLAYER, mode).apply()
        buildPlayer()
        lastSummary = null
        startEngine()
        spotify?.connect(true)
    }

    /** Create an engine from the stored state and start it. Main thread. */
    private fun startEngine() {
        val e = SegueEngine(
            identity = identity,
            store = FileStore(File(filesDir, "engine")),
            sched = scheduler,
            platformPlayer = player,
            // "Ready to receive playback": the earbuds are routed here and, for Spotify, the app answers.
            routeReady = { route.isReady() && (spotify?.ready() ?: true) },
            transport = transport,
            cfg = config,
            bootId = BootId.read(this),
            sink = eventLog ?: EventSink.NONE
        )
        e.testMode = testMode
        e.listener = this
        engine = e
        transport.engine = e
        e.start()
        e.note("device", Background.describeDevice(this))
        e.note("player_mode", playerMode + " W=" + config.coordinator.windowMs + " b_start=" + config.coordinator.startBudgetMs)
        spotify?.connect(false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(Notifications.ID, Notifications.build(this, lastState, media?.token),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            foregroundError = null
        } catch (e: RuntimeException) {
            // SecurityException / ForegroundServiceStartNotAllowedException: permissions missing or started from background.
            Log.e(TAG, "startForeground failed", e)
            foregroundError = e.javaClass.simpleName + ": " + e.message
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        campaign.stop()
        engine.stop()
        route.stop()
        reference?.release()
        spotify?.release()
        transport.shutdown()
        media?.release()
        main.postDelayed({ scheduler.shutdown(); eventLog?.close() }, 500)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ EngineListener (engine thread)

    override fun onState(state: EngineState) {
        lastState = state
        main.post {
            for (l in uiListeners) l(state)
            media?.update(state.playing, state.positionMs)
            val s = Notifications.summary(state)
            if (s != lastSummary) {
                lastSummary = s
                getSystemService(NotificationManager::class.java)
                    .notify(Notifications.ID, Notifications.build(this, state, media?.token))
            }
        }
    }

    override fun onKillRequested() {
        Log.w(TAG, "fault injection: killing process")
        eventLog?.close()
        Process.killProcess(Process.myPid())
    }

    /** Emulated coordinator crash: the engine has stopped itself; the player is left alone. */
    override fun onCrashRequested(restartAfterMs: Long) {
        Log.w(TAG, "fault injection: coordinator down for $restartAfterMs ms")
        main.post {
            transport.dropAll()
            main.postDelayed({ startEngine() }, restartAfterMs)
        }
    }

    override fun onPolicyRequested(rollback: Rollback) {
        main.post {
            if (config.coordinator.rollback == rollback) return@post
            Log.i(TAG, "switching rollback policy to $rollback")
            engine.halt()
            transport.dropAll()
            config = config.copy(coordinator = config.coordinator.copy(rollback = rollback))
            startEngine()
        }
    }

    // ------------------------------------------------------------------ for the UI (main thread)

    fun addListener(l: (EngineState) -> Unit) {
        uiListeners.add(l)
        lastState?.let { s -> main.post { l(s) } }
        engine.refresh()
    }

    fun removeListener(l: (EngineState) -> Unit) { uiListeners.remove(l) }

    /** All log files of this device, oldest first; the last one is the session being written. */
    fun logFiles(): List<File> =
        EventLogFile.dir(this).listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.sortedBy { it.name } ?: emptyList()

    /** Write every log and a device description as one ZIP. Call from a background thread. */
    fun exportLogs(out: OutputStream): Int {
        val s = lastState
        val info = buildString {
            append("app=Segue\n")
            append(Background.describeDevice(this@SegueService)).append('\n')
            append("label=").append(config.label).append('\n')
            if (s != null) {
                append("device_id=").append(s.selfId).append('\n')
                append("fingerprint=").append(s.fingerprint).append('\n')
                append("policy=").append(s.policy).append('\n')
                for (p in s.peers) append("peer=").append(p.id).append(" label=").append(p.label).append(" connected=").append(p.connected).append('\n')
            }
            append("test_mode=").append(testMode).append('\n')
            append("player_mode=").append(playerMode).append('\n')
            spotify?.view()?.let { v -> append("spotify=").append(v.status).append(" connected=").append(v.connected).append('\n') }
            append("exported_wall_ms=").append(System.currentTimeMillis()).append('\n')
        }
        return LogExport.zip(out, logFiles(), mapOf("device.txt" to info))
    }

    /** Delete logs of earlier sessions; the current session's file is kept. */
    fun clearOldLogs(): Int {
        val current = eventLog?.file
        var n = 0
        for (f in logFiles()) if (f != current && f.delete()) n++
        engine.note("logs", "cleared $n old files")
        return n
    }

    companion object {
        const val ACTION_STOP = "com.segue.app.STOP"
        private const val TAG = "SegueService"
        private const val PREFS = "segue"
        private const val KEY_TEST_MODE = "test_mode"
        private const val KEY_PLAYER = "player"
        private const val KEY_SPOTIFY_CLIENT = "spotify_client_id"
        const val MODE_REFERENCE = "reference"
        const val MODE_SPOTIFY = "spotify"
    }
}
