package com.segue.app.ui

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
import com.segue.app.service.SegueService
import com.segue.app.service.Background
import com.segue.app.service.Permissions
import com.segue.core.MsgType
import com.segue.core.Phase
import com.segue.engine.EngineState

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
            ui = ui.copy(serviceRunning = true, extras = extras())
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
            extras = extras()
        )
    }

    private fun extras() = service?.let { s ->
        UiExtras(
            routeDescription = s.route.describe(),
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
            catch (e: android.content.ActivityNotFoundException) { startActivity(Background.appSettings(ctx)) }
        }
        override fun play() { service?.engine?.play() }
        override fun pause() { service?.engine?.pause() }
        override fun handoff(peerId: Int) { service?.engine?.handoffTo(peerId) }
        override fun pull() { service?.engine?.pullHere() }
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
        override fun startBench(count: Int, peerId: Int) { service?.engine?.startBench(count, 20_000, peerId) }
        override fun stopBench() { service?.engine?.stopBench() }
        override fun stopService() {
            this@MainActivity.startService(Intent(this@MainActivity, SegueService::class.java).setAction(SegueService.ACTION_STOP))
            if (bound) { service?.removeListener(listener); unbindService(connection); bound = false }
            service = null
            ui = ui.copy(serviceRunning = false, state = null)
        }
        override fun startService() { refreshEnvironment(); if (ui.missingPermissions.isEmpty()) startAndBind() }
    }
}
