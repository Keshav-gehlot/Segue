package com.segue.app.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.segue.app.service.Background
import com.segue.app.service.Permissions
import com.segue.app.service.SegueService
import com.segue.core.MsgType
import com.segue.core.Phase
import com.segue.engine.Campaign
import com.segue.engine.EngineState
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var service: SegueService? = null
    private var bound = false
    private var ui by mutableStateOf(UiModel())

    private val listener: (EngineState) -> Unit = { s -> ui = ui.copy(state = s, extras = extras()) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as SegueService.LocalBinder).service
            service = s
            s.addListener(listener)
            s.onCampaign = { st -> ui = ui.copy(campaign = st, logFiles = s.logFiles().size) }
            ui = ui.copy(serviceRunning = true, extras = extras(), testMode = s.testMode,
                campaign = s.campaignStatus, logFiles = s.logFiles().size)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            ui = ui.copy(serviceRunning = false)
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshEnvironment()
        if (ui.missingPermissions.isEmpty()) startAndBind()
    }

    /** The user picks where the ZIP goes (Downloads, Drive, ...); no storage permission is needed. */
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val s = service
        if (uri != null && s != null) {
            ui = ui.copy(notice = "Exporting…")
            Thread {
                val msg = try {
                    val n = contentResolver.openOutputStream(uri)?.use { s.exportLogs(it) }
                    if (n != null) "Saved $n log file(s) in one ZIP" else "Could not open the chosen file"
                } catch (e: IOException) {
                    "Export failed: ${e.message}"
                }
                runOnUiThread { ui = ui.copy(notice = msg) }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SegueTheme { SegueScreen(ui, actions) } }
        refreshEnvironment()
        if (ui.missingPermissions.isEmpty()) startAndBind()
    }

    override fun onResume() {
        super.onResume()
        refreshEnvironment()
    }

    override fun onDestroy() {
        service?.removeListener(listener)
        service?.onCampaign = null
        if (bound) unbindService(connection)
        bound = false
        super.onDestroy()
    }

    private fun refreshEnvironment() {
        ui = ui.copy(
            missingPermissions = Permissions.missingRequired(this),
            bluetoothOn = Permissions.bluetoothOn(this),
            backgroundUnrestricted = Background.unrestricted(this),
            backgroundTips = Background.vendorTips(),
            extras = extras(),
            logFiles = service?.logFiles()?.size ?: ui.logFiles
        )
    }

    private fun extras() = service?.let { s ->
        UiExtras(
            routeDescription = s.route.describe(),
            earbudsReady = s.route.isReady(),
            spotify = s.spotify?.view()?.let { v -> SpotifyUi(v.connected, v.status, v.title, v.artist, v.needsClientId) },
            transportError = s.transport.lastError,
            foregroundError = s.foregroundError,
            logPath = s.eventLog?.file?.absolutePath
        )
    } ?: UiExtras()

    private fun startAndBind() {
        val intent = Intent(this, SegueService::class.java)
        startForegroundService(intent)
        if (!bound) bound = bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private val actions = object : UiActions {
        override fun grantPermissions() = permissionLauncher.launch(Permissions.toRequest(this@MainActivity))
        override fun openBatterySettings() {
            val ctx = this@MainActivity
            try { startActivity(Background.requestUnrestricted(ctx)) }
            catch (e: ActivityNotFoundException) { startActivity(Background.appSettings(ctx)) }
        }
        override fun play() { service?.engine?.play() }
        override fun pause() { service?.engine?.pause() }
        override fun handoff(peerId: Int) { service?.engine?.handoffTo(peerId) }
        override fun pull() { service?.engine?.pullHere() }
        override fun useSpotify(on: Boolean) {
            val s = service ?: return
            s.setPlayerMode(if (on) SegueService.MODE_SPOTIFY else SegueService.MODE_REFERENCE)
            ui = ui.copy(extras = extras())
        }
        override fun connectSpotify() { service?.spotify?.connect(true) }
        override fun setSpotifyClientId(id: String) {
            service?.spotifyClientId = id
            ui = ui.copy(extras = extras())
        }
        override fun startPairing() { service?.engine?.beginPairing() }
        override fun confirmPairing(match: Boolean) { service?.engine?.confirmPairing(match) }
        override fun cancelPairing() { service?.engine?.cancelPairing() }
        override fun dismissPairing() { service?.engine?.dismissPairingResult() }
        override fun unpair(peerId: Int) { service?.engine?.unpair(peerId) }
        override fun fault(kind: FaultKind) {
            val f = service?.engine?.faults ?: return
            when (kind) {
                FaultKind.DROP_READY -> f.dropNext(MsgType.READY)
                FaultKind.DROP_STATE_TRANSFER -> f.dropNext(MsgType.STATE_TRANSFER, 3)
                FaultKind.KILL_IN_PROVISIONAL -> f.killInPhase = Phase.PROVISIONAL
                FaultKind.KILL_IN_TRANSFERRING -> f.killInPhase = Phase.TRANSFERRING
                FaultKind.CLEAR -> f.clear()
            }
        }
        override fun setTestMode(on: Boolean) {
            service?.testMode = on
            ui = ui.copy(testMode = on)
        }
        override fun startCampaign(preset: Campaign.Preset, peerId: Int) { service?.campaign?.start(preset, peerId) }
        override fun stopCampaign() { service?.campaign?.stop() }
        override fun exportLogs() {
            val label = ui.state?.label ?: "device"
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            try { exportLauncher.launch(Describe.exportName(label, stamp)) }
            catch (e: ActivityNotFoundException) { ui = ui.copy(notice = "No file picker is available on this device") }
        }
        override fun clearOldLogs() {
            val s = service ?: return
            val n = s.clearOldLogs()
            ui = ui.copy(notice = "Deleted $n old log file(s)", logFiles = s.logFiles().size)
        }
        override fun stopService() {
            this@MainActivity.startService(Intent(this@MainActivity, SegueService::class.java).setAction(SegueService.ACTION_STOP))
            if (bound) { service?.removeListener(listener); service?.onCampaign = null; unbindService(connection); bound = false }
            service = null
            ui = ui.copy(serviceRunning = false, state = null)
        }
        override fun startService() { refreshEnvironment(); if (ui.missingPermissions.isEmpty()) startAndBind() }
    }
}
