package com.segue.app.service

import android.app.NotificationManager
import android.app.Service
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
import com.segue.app.route.RouteMonitor
import com.segue.app.store.BootId
import com.segue.app.store.KeystoreIdentity
import com.segue.app.transport.NearbyTransport
import com.segue.engine.SegueEngine
import com.segue.engine.EngineConfig
import com.segue.engine.EngineListener
import com.segue.engine.EngineState
import com.segue.engine.EventSink
import com.segue.engine.ExecutorScheduler
import com.segue.engine.FileStore
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Hosts the engine while Segue is on. Foreground service type connectedDevice: the user-visible
 * task is keeping this phone's link to the other enrolled phones and the earbuds' playback in sync.
 * On Android 14+ the type's prerequisite is met by CHANGE_WIFI_STATE (normal permission) and the
 * granted BLUETOOTH_* runtime permissions.
 */
class SegueService : Service(), EngineListener {

    inner class LocalBinder : Binder() {
        val service: SegueService get() = this@SegueService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val uiListeners = CopyOnWriteArrayList<(EngineState) -> Unit>()
    private lateinit var scheduler: ExecutorScheduler
    private lateinit var player: ReferencePlayer
    lateinit var route: RouteMonitor; private set
    lateinit var transport: NearbyTransport; private set
    lateinit var engine: SegueEngine; private set
    private var media: MediaControls? = null
    var eventLog: EventLogFile? = null; private set
    @Volatile var lastState: EngineState? = null; private set
    private var lastSummary: String? = null
    var foregroundError: String? = null; private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        scheduler = ExecutorScheduler("segue-engine", { SystemClock.elapsedRealtime() }) { Log.e(TAG, "engine error", it) }
        eventLog = EventLogFile.open(this)
        val config = EngineConfig(label = Background.deviceLabel(this).take(40))
        player = ReferencePlayer(this, object : ReferencePlayer.Listener {
            override fun onPaused() = engine.onPlayerPaused()
            override fun onStarted(ok: Boolean) = engine.onPlayerStarted(ok)
            override fun onExternalStop() = engine.pause()
        }, startTimeoutMs = config.coordinator.startBudgetMs - 50)
        route = RouteMonitor(this) { engine.onRouteChanged() }
        transport = NearbyTransport(this)
        engine = SegueEngine(
            identity = KeystoreIdentity.loadOrCreate(),
            store = FileStore(File(filesDir, "engine")),
            sched = scheduler,
            platformPlayer = player,
            routeReady = { route.isReady() },
            transport = transport,
            cfg = config,
            bootId = BootId.read(this),
            sink = eventLog ?: EventSink.NONE
        )
        transport.engine = engine
        engine.listener = this
        media = MediaControls(this, onPlay = { engine.play() }, onPause = { engine.pause() })
        route.start()
        engine.start()
        engine.note("device", Background.describeDevice(this))
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
        engine.stop()
        route.stop()
        player.release()
        transport.shutdown()
        media?.release()
        main.postDelayed({ scheduler.shutdown(); eventLog?.close() }, 500)
        super.onDestroy()
    }

    // EngineListener: called on the engine thread.
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

    fun addListener(l: (EngineState) -> Unit) {
        uiListeners.add(l)
        lastState?.let { s -> main.post { l(s) } }
        engine.refresh()
    }

    fun removeListener(l: (EngineState) -> Unit) { uiListeners.remove(l) }

    companion object {
        const val ACTION_STOP = "com.segue.app.STOP"
        private const val TAG = "SegueService"
    }
}
