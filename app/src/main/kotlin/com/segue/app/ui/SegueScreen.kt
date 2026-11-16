package com.segue.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segue.engine.Campaign
import com.segue.engine.EngineState
import com.segue.engine.PairingView

@Composable
fun SegueTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx),
        content = content
    )
}

@Composable
fun SegueScreen(ui: UiModel, act: UiActions) {
    Scaffold { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Segue", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            val s = ui.state
            if (s != null) Text("${s.label} · ${s.fingerprint}", style = MaterialTheme.typography.bodySmall)

            when {
                ui.missingPermissions.isNotEmpty() -> PermissionCard(act)
                !ui.serviceRunning || s == null -> {
                    InfoCard("Segue is off", ui.extras.foregroundError ?: "Start it to connect to your other phones.")
                    Button(onClick = act::startService) { Text("Start Segue") }
                }
                else -> {
                    if (!ui.bluetoothOn) InfoCard("Bluetooth is off", "Turn Bluetooth on. Segue can't switch radios on by itself.")
                    if (!ui.backgroundUnrestricted) BackgroundCard(ui.backgroundTips, act)
                    ui.extras.transportError?.let { InfoCard("Nearby Connections", it) }
                    PlaybackCard(s, ui.extras, act)
                    PhonesCard(s, act)
                    TestingCard(ui, s, act)
                    DiagnosticsCard(s, ui.extras, act)
                    TextButton(onClick = act::stopService) { Text("Stop Segue") }
                    PairingDialog(s.pairing, act)
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(act: UiActions) {
    InfoCard(
        "Permissions needed",
        "Segue finds your other phone with Nearby Connections, which needs the Nearby devices " +
            "permission. It does not use your location. Notifications are optional."
    )
    Button(onClick = act::grantPermissions) { Text("Grant") }
}

@Composable
private fun PlaybackCard(s: EngineState, extras: UiExtras, act: UiActions) {
    val sp = extras.spotify
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Describe.playback(s), style = MaterialTheme.typography.titleLarge)
            if (sp != null) Describe.nowPlaying(sp)?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            Text(
                (if (extras.earbudsReady) "Earbuds: " else "Earbuds not ready: ") + extras.routeDescription +
                    (if (s.playing || s.iAmOwner) " · " + Describe.position(s.positionMs) else ""),
                style = MaterialTheme.typography.bodyMedium
            )
            if (sp != null) SpotifyStatus(sp, act)
            s.lastResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            val othersPlaying = s.peers.any { it.connected && it.status?.playing == true }
            when {
                s.playing -> {
                    Button(onClick = act::pause) { Text("Pause") }
                    for (p in s.peers) {
                        OutlinedButton(onClick = { act.handoff(p.id) }, enabled = Describe.canSendTo(s, p), modifier = Modifier.fillMaxWidth()) {
                            Text("Send to ${p.label}")
                        }
                    }
                }
                othersPlaying -> {
                    Button(onClick = act::pull, enabled = s.routeReady, modifier = Modifier.fillMaxWidth()) { Text("Move playback here") }
                    OutlinedButton(onClick = act::play, modifier = Modifier.fillMaxWidth()) { Text("Play here now (other phone pauses)") }
                }
                sp == null -> Button(onClick = act::play) { Text("Play test track") }
                sp.connected -> {
                    Text("Play a song in Spotify on this device, or resume the last one:", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = act::play) { Text("Resume Spotify here") }
                }
            }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Use Spotify", fontWeight = FontWeight.Medium)
                    Text("Off: the built-in test track. Set it the same on every device.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = sp != null, onCheckedChange = { act.useSpotify(it) }, enabled = s.phase == "IDLE")
            }
        }
    }
}

@Composable
private fun SpotifyStatus(sp: SpotifyUi, act: UiActions) {
    Text("Spotify: ${sp.status}", style = MaterialTheme.typography.bodyMedium)
    if (sp.needsClientId) {
        var id by remember { mutableStateOf("") }
        OutlinedTextField(
            value = id,
            onValueChange = { id = it.trim() },
            label = { Text("Spotify Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { act.setSpotifyClientId(id) }, enabled = Describe.looksLikeClientId(id)) { Text("Save and connect") }
    } else if (!sp.connected) {
        Button(onClick = act::connectSpotify) { Text("Connect Spotify") }
    }
}

@Composable
private fun PhonesCard(s: EngineState, act: UiActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your phones", style = MaterialTheme.typography.titleMedium)
            if (s.peers.isEmpty()) Text("No phones paired yet. Open Segue on your other phone and pair them.")
            for (p in s.peers) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, fontWeight = FontWeight.Medium)
                        Text(Describe.peerLine(p), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { act.unpair(p.id) }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = act::startPairing) { Text("Pair a phone") }
        }
    }
}

@Composable
private fun TestingCard(ui: UiModel, s: EngineState, act: UiActions) {
    var open by remember { mutableStateOf(false) }
    val c = ui.campaign
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Testing", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open || c.running) "Hide" else "Show") }
            }
            if (c.total > 0) Text(Describe.campaign(c), style = MaterialTheme.typography.bodyMedium)
            if (c.running) OutlinedButton(onClick = act::stopCampaign) { Text("Stop the run") }
            if (open && !c.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Test mode", fontWeight = FontWeight.Medium)
                        Text("Lets your paired devices inject faults here. Turn it on on both devices for a run, and off afterwards.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = ui.testMode, onCheckedChange = { act.setTestMode(it) })
                }
                val peers = s.peers.filter { it.connected }
                if (ui.extras.spotify != null) Text("The automated tests use the built-in test track. Turn off Use Spotify on both devices to run them.", style = MaterialTheme.typography.bodySmall)
                else if (!ui.testMode) Text("Turn on Test mode to run the automated tests.", style = MaterialTheme.typography.bodySmall)
                else if (peers.isEmpty()) Text("Connect a paired device to run the automated tests.", style = MaterialTheme.typography.bodySmall)
                else for (p in peers) {
                    Text("Automated tests with ${p.label}", style = MaterialTheme.typography.labelLarge)
                    for (preset in Campaign.Preset.entries) {
                        OutlinedButton(onClick = { act.startCampaign(preset, p.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${preset.label} (${Describe.presetHint(preset)})")
                        }
                    }
                }
            }
            if (open) {
                HorizontalDivider()
                Text("Logs: ${ui.logFiles} file(s) on this device", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = act::exportLogs) { Text("Export logs") }
                    OutlinedButton(onClick = act::clearOldLogs, enabled = !c.running) { Text("Delete old logs") }
                }
                ui.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
private fun DiagnosticsCard(s: EngineState, extras: UiExtras, act: UiActions) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Diagnostics", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (open) {
                Text("phase ${s.phase} · owner ${s.ownerId} · epoch ${s.ownerEpoch} · id ${s.selfId}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text("rollback policy ${s.policy}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                extras.logPath?.let { Text("Log: $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                Text("Manual fault injection (next handoff)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { act.fault(FaultKind.DROP_READY) }) { Text("Drop READY") }
                    OutlinedButton(onClick = { act.fault(FaultKind.DROP_STATE_TRANSFER) }) { Text("Drop STATE") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { act.fault(FaultKind.KILL_IN_PROVISIONAL) }) { Text("Kill app: provisional") }
                    OutlinedButton(onClick = { act.fault(FaultKind.KILL_IN_TRANSFERRING) }) { Text("Kill app: transferring") }
                }
                TextButton(onClick = { act.fault(FaultKind.CLEAR) }) { Text("Clear faults") }
                HorizontalDivider()
                for (line in s.recent.take(40)) Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PairingDialog(view: PairingView, act: UiActions) {
    when (view) {
        PairingView.Idle -> {}
        PairingView.Searching -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Pairing") },
            text = { Text("Open Segue on the other phone and tap Pair a phone there too. Keep the phones close.") },
            confirmButton = { TextButton(onClick = act::cancelPairing) { Text("Cancel") } }
        )
        is PairingView.Compare -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Do both phones show this code?") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(view.sas.chunked(3).joinToString(" "), fontSize = 40.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("Other phone: ${view.peerFingerprint}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = { act.confirmPairing(true) }) { Text("They match") } },
            dismissButton = { TextButton(onClick = { act.confirmPairing(false) }) { Text("They don't match") } }
        )
        is PairingView.Waiting -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Code ${view.sas}") },
            text = { Text("Waiting for the other phone to confirm…") },
            confirmButton = { TextButton(onClick = act::cancelPairing) { Text("Cancel") } }
        )
        is PairingView.Done -> AlertDialog(
            onDismissRequest = act::dismissPairing,
            title = { Text("Paired") },
            text = { Text("Paired with ${view.label}. The phones will now reconnect on their own.") },
            confirmButton = { Button(onClick = act::dismissPairing) { Text("OK") } }
        )
        is PairingView.Failed -> AlertDialog(
            onDismissRequest = act::dismissPairing,
            title = { Text("Pairing failed") },
            text = { Text(view.reason) },
            confirmButton = { Button(onClick = act::dismissPairing) { Text("OK") } }
        )
    }
}

@Composable
private fun BackgroundCard(tips: List<String>, act: UiActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Keep Segue running", style = MaterialTheme.typography.titleMedium)
            Text("This device may stop Segue in the background, which breaks handoffs. Allow it to run unrestricted, then:",
                style = MaterialTheme.typography.bodyMedium)
            for (t in tips) Text("• $t", style = MaterialTheme.typography.bodySmall)
            Button(onClick = act::openBatterySettings) { Text("Allow background use") }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
