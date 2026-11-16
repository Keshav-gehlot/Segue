package com.segue.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.segue.engine.Campaign
import com.segue.engine.EngineState
import com.segue.engine.PairingView
import com.segue.engine.PeerView

@Composable
fun SegueTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx),
        content = content
    )
}

private enum class Tab(val label: String, val icon: ImageVector) {
    LISTEN("Listen", SegueIcons.Headphones),
    DEVICES("Devices", SegueIcons.Devices),
    LAB("Lab", SegueIcons.Lab)
}

@Composable
fun SegueScreen(ui: UiModel, act: UiActions) {
    var tab by rememberSaveable { mutableStateOf(Tab.LISTEN) }
    val s = ui.state
    val ready = ui.missingPermissions.isEmpty() && ui.serviceRunning && s != null
    Scaffold(
        bottomBar = {
            if (ready) NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Header(if (ready) tab.label else "Segue", s?.label)
            when {
                ui.missingPermissions.isNotEmpty() -> Welcome(
                    "Let Segue find your devices",
                    "Segue moves your music between your phones and tablet over Nearby Connections. " +
                        "It needs the Nearby devices permission; it does not use your location.",
                    "Allow", act::grantPermissions
                )
                !ui.serviceRunning || s == null -> Welcome(
                    "Segue is off",
                    ui.extras.foregroundError ?: "Turn it on to reconnect to your paired devices.",
                    "Turn on Segue", act::startService
                )
                else -> {
                    Alerts(ui, act)
                    when (tab) {
                        Tab.LISTEN -> ListenTab(s, ui.extras, act)
                        Tab.DEVICES -> DevicesTab(s, ui, act)
                        Tab.LAB -> LabTab(s, ui, act)
                    }
                    PairingDialog(s.pairing, act)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------------------- common

@Composable
private fun Header(title: String, device: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (device != null) Text("This device: $device", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Welcome(title: String, body: String, button: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Glyph(SegueIcons.Headphones, 96.dp, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(button) }
    }
}

/** An icon in a filled circle. */
@Composable
private fun Glyph(icon: ImageVector, size: androidx.compose.ui.unit.Dp, bg: Color, fg: Color) {
    Box(Modifier.size(size).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
private fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A setup problem that stops Segue from working, with the fix. */
@Composable
private fun Banner(title: String, body: String, action: String? = null, onClick: () -> Unit = {}) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(SegueIcons.Warning, contentDescription = null, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                if (action != null) TextButton(onClick = onClick, contentPadding = PaddingValues(0.dp)) {
                    Text(action, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
private fun Alerts(ui: UiModel, act: UiActions) {
    if (!ui.bluetoothOn) Banner("Bluetooth is off", "Turn on Bluetooth so Segue can reach your other devices.")
    if (!ui.backgroundUnrestricted) Banner(
        "Segue may be stopped in the background",
        "Allow unrestricted battery use so handoffs keep working with the screen off." +
            if (ui.backgroundTips.isNotEmpty()) "\n" + ui.backgroundTips.joinToString("\n") { "• $it" } else "",
        "Allow background use", act::openBatterySettings
    )
    ui.extras.transportError?.let { Banner("Nearby connection problem", it) }
}

/** Small rounded label. */
@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Surface(shape = RoundedCornerShape(50), color = bg, contentColor = fg) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

@Composable
private fun StatusDot(color: Color, pulsing: Boolean) {
    val a = if (pulsing) {
        val t = rememberInfiniteTransition(label = "dot")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dotAlpha").value
    } else 1f
    Box(Modifier.size(10.dp).alpha(a).clip(CircleShape).background(color))
}

// ---------------------------------------------------------------------------------------- Listen

@Composable
private fun ListenTab(s: EngineState, extras: UiExtras, act: UiActions) {
    val sp = extras.spotify
    if (sp != null && !sp.connected) SpotifyConnect(sp, act)
    NowPlaying(s, extras, act)
    s.lastResult?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
    SectionTitle(if (s.playing) "Send the music to" else "Your devices")
    if (s.peers.isEmpty()) EmptyDevices(act)
    for (p in s.peers) DeviceTile(s, p, act)
}

@Composable
private fun NowPlaying(s: EngineState, extras: UiExtras, act: UiActions) {
    val where = Describe.where(s)
    val cs = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        when (where) {
            Describe.Where.HERE -> cs.primary
            Describe.Where.ELSEWHERE -> cs.tertiary
            else -> cs.outline
        }, label = "accent"
    )
    val (title, subtitle) = Describe.track(extras)
    val busy = Describe.busy(s)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(cs.primaryContainer, cs.tertiaryContainer)))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(accent, pulsing = where == Describe.Where.HERE || where == Describe.Where.ELSEWHERE || busy != null)
                AnimatedContent(busy ?: Describe.headline(s), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "headline") { line ->
                    Text(line, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = cs.onPrimaryContainer)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(cs.surface.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center) {
                    Icon(SegueIcons.Music, contentDescription = null, tint = cs.primary, modifier = Modifier.size(36.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = cs.onPrimaryContainer,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onPrimaryContainer.copy(alpha = 0.75f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.playing || s.iAmOwner) Text(Describe.position(s.positionMs), style = MaterialTheme.typography.labelLarge,
                        color = cs.onPrimaryContainer.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(SegueIcons.Headphones, contentDescription = null, tint = cs.onPrimaryContainer, modifier = Modifier.size(18.dp))
                Text(
                    (if (extras.earbudsReady) "" else "Earbuds not connected here · ") + extras.routeDescription,
                    style = MaterialTheme.typography.bodySmall, color = cs.onPrimaryContainer.copy(alpha = 0.85f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            MainControls(s, extras, act)
        }
    }
}

@Composable
private fun MainControls(s: EngineState, extras: UiExtras, act: UiActions) {
    val where = Describe.where(s)
    val sp = extras.spotify
    val canPlay = sp == null || sp.connected
    val idle = s.phase == "IDLE"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Round play/pause button
        Surface(
            onClick = { if (s.playing) act.pause() else act.play() },
            enabled = idle && (s.playing || canPlay),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(64.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (s.playing) SegueIcons.Pause else SegueIcons.Play, contentDescription = if (s.playing) "Pause" else "Play here",
                    modifier = Modifier.size(32.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            when {
                where == Describe.Where.ELSEWHERE || where == Describe.Where.PAUSED_ELSEWHERE -> {
                    Button(onClick = act::pull, enabled = idle && s.routeReady, modifier = Modifier.fillMaxWidth()) {
                        Icon(SegueIcons.Bring, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Move it here")
                    }
                    Text(if (s.routeReady) "Or tap ▶ to play here right away" else "Connect the earbuds to this device first",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        modifier = Modifier.padding(top = 4.dp))
                }
                s.playing -> Text("Pick a device below to send the music there.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                sp != null && sp.connected -> Text("Play a song in Spotify, or tap ▶ to resume the last one here.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                sp != null -> Text("Connect Spotify to start.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                else -> Text("Tap ▶ to play the test track here.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun DeviceTile(s: EngineState, p: PeerView, act: UiActions) {
    val t = Describe.tile(s, p)
    val cs = MaterialTheme.colorScheme
    Card(
        onClick = { act.handoff(p.id) },
        enabled = t.enabled,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (t.enabled) cs.secondaryContainer else cs.surfaceContainerHigh,
            disabledContainerColor = cs.surfaceContainerHigh,
            disabledContentColor = cs.onSurface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Glyph(
                if (Describe.deviceKind(p.label) == Describe.DeviceKind.TABLET) SegueIcons.Tablet else SegueIcons.Phone,
                44.dp,
                if (t.playingThere) cs.tertiary else if (p.connected) cs.primaryContainer else cs.surfaceVariant,
                if (t.playingThere) cs.onTertiary else if (p.connected) cs.onPrimaryContainer else cs.onSurfaceVariant
            )
            Column(Modifier.weight(1f)) {
                Text(p.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(if (p.connected) Color(0xFF2E9D5B) else cs.outline, pulsing = t.playingThere)
                    Text(t.line, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
            if (t.enabled) Icon(SegueIcons.Send, contentDescription = "Send to ${p.label}", tint = cs.onSecondaryContainer)
        }
    }
}

@Composable
private fun EmptyDevices(act: UiActions) {
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("No devices yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Pair your other phone or tablet once. After that they find each other on their own.",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = act::startPairing) {
                Icon(SegueIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add a device")
            }
        }
    }
}

@Composable
private fun SpotifyConnect(sp: SpotifyUi, act: UiActions) {
    val cs = MaterialTheme.colorScheme
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1DB954).copy(alpha = 0.16f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Glyph(SegueIcons.Music, 36.dp, Color(0xFF1DB954), Color.Black)
                Text("Connect Spotify", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(sp.status, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            if (sp.needsClientId) {
                var id by remember { mutableStateOf("") }
                OutlinedTextField(value = id, onValueChange = { id = it.trim() }, label = { Text("Spotify Client ID") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { act.setSpotifyClientId(id) }, enabled = Describe.looksLikeClientId(id)) { Text("Save and connect") }
            } else {
                Button(
                    onClick = act::connectSpotify,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954), contentColor = Color.Black)
                ) { Text("Connect Spotify", fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- Devices

@Composable
private fun DevicesTab(s: EngineState, ui: UiModel, act: UiActions) {
    ThisDevice(s, ui, act)
    SectionTitle("Paired devices") {
        TextButton(onClick = act::startPairing) {
            Icon(SegueIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add")
        }
    }
    if (s.peers.isEmpty()) EmptyDevices(act)
    for (p in s.peers) PairedDevice(p, act)
    SectionTitle("Music source")
    SourcePicker(s, ui.extras, act)
    OutlinedButton(onClick = act::stopService, modifier = Modifier.fillMaxWidth()) {
        Icon(SegueIcons.Power, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Turn off Segue on this device")
    }
}

@Composable
private fun ThisDevice(s: EngineState, ui: UiModel, act: UiActions) {
    val cs = MaterialTheme.colorScheme
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = cs.surfaceContainerHigh)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Glyph(if (Describe.deviceKind(s.label) == Describe.DeviceKind.TABLET) SegueIcons.Tablet else SegueIcons.Phone,
                    48.dp, cs.primary, cs.onPrimary)
                Column(Modifier.weight(1f)) {
                    Text(s.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("ID " + Describe.fingerprint(s.fingerprint), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = cs.onSurfaceVariant)
                }
            }
            CheckRow(SegueIcons.Bluetooth, "Bluetooth", ui.bluetoothOn, if (ui.bluetoothOn) "On" else "Off")
            CheckRow(SegueIcons.Headphones, "Earbuds", ui.extras.earbudsReady, ui.extras.routeDescription)
            CheckRow(SegueIcons.Power, "Background use", ui.backgroundUnrestricted,
                if (ui.backgroundUnrestricted) "Unrestricted" else "Restricted", if (ui.backgroundUnrestricted) null else act::openBatterySettings)
        }
    }
}

@Composable
private fun CheckRow(icon: ImageVector, label: String, ok: Boolean, detail: String, fix: (() -> Unit)? = null) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (fix != null) TextButton(onClick = fix) { Text("Fix") }
        else Icon(if (ok) SegueIcons.Check else SegueIcons.Warning, contentDescription = if (ok) "OK" else "Problem",
            tint = if (ok) Color(0xFF2E9D5B) else cs.error, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PairedDevice(p: PeerView, act: UiActions) {
    var confirm by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Glyph(if (Describe.deviceKind(p.label) == Describe.DeviceKind.TABLET) SegueIcons.Tablet else SegueIcons.Phone, 40.dp,
                if (p.connected) cs.primaryContainer else cs.surfaceVariant, if (p.connected) cs.onPrimaryContainer else cs.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (p.connected) Pill("Connected", Color(0xFF2E9D5B).copy(alpha = 0.18f), Color(0xFF1E7A44))
                    else Pill("Offline", cs.surfaceVariant, cs.onSurfaceVariant)
                }
                Text(Describe.peerDetail(p), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            TextButton(onClick = { confirm = true }) {
                Icon(SegueIcons.Delete, contentDescription = "Remove ${p.label}", tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Remove ${p.label}?") },
        text = { Text("Segue will stop moving music to and from it. You can pair it again later.") },
        confirmButton = { TextButton(onClick = { confirm = false; act.unpair(p.id) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } }
    )
}

@Composable
private fun SourcePicker(s: EngineState, extras: UiExtras, act: UiActions) {
    val spotify = extras.spotify != null
    val enabled = s.phase == "IDLE" && !s.playing
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SourceOption("Spotify", "Moves your Spotify song. Needs Spotify Premium.", spotify, enabled) { act.useSpotify(true) }
        SourceOption("Test track", "Built-in signal for measurements and automated tests.", !spotify, enabled) { act.useSpotify(false) }
        Text(
            if (enabled) "Choose the same source on every device." else "Pause the music to change the source.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun SourceOption(title: String, body: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled && !selected,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) cs.secondaryContainer else cs.surface,
        modifier = Modifier.fillMaxWidth().border(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) cs.primary else cs.outlineVariant,
            shape = RoundedCornerShape(18.dp)
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) cs.primary else cs.outline, CircleShape),
                contentAlignment = Alignment.Center) {
                if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(cs.primary))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------- Lab

@Composable
private fun LabTab(s: EngineState, ui: UiModel, act: UiActions) {
    val c = ui.campaign
    val cs = MaterialTheme.colorScheme
    Text("Measurements and automated tests for the research paper. Everyday use doesn't need anything here.",
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)

    // Progress of the current or last run
    AnimatedVisibility(c.total > 0) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = cs.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (c.running) "Running: ${c.preset}" else "Last run: ${c.preset}", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (c.running) Text("${c.index + 1} / ${c.total}", style = MaterialTheme.typography.labelLarge)
                }
                LinearProgressIndicator(progress = { Describe.progress(c) }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                Text(if (c.running) c.current else c.lastLine, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("${c.passed} passed", Color(0xFF2E9D5B).copy(alpha = 0.2f), cs.onPrimaryContainer)
                    if (c.failed > 0) Pill("${c.failed} failed", cs.errorContainer, cs.onErrorContainer)
                    if (c.inconclusive > 0) Pill("${c.inconclusive} unclear", cs.surfaceVariant, cs.onSurfaceVariant)
                }
                if (c.running) OutlinedButton(onClick = act::stopCampaign) { Text("Stop the run") }
            }
        }
    }

    SectionTitle("Automated tests")
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Test mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Lets a paired device inject faults here. Turn it on on both devices for a run, off afterwards.",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                Switch(checked = ui.testMode, onCheckedChange = { act.setTestMode(it) })
            }
            val peers = s.peers.filter { it.connected }
            val blocker = when {
                ui.extras.spotify != null -> "The tests use the test track. Choose Test track under Devices → Music source on both devices."
                !ui.testMode -> "Turn on Test mode to start a run."
                peers.isEmpty() -> "Connect a paired device to start a run."
                c.running -> null
                else -> null
            }
            if (blocker != null) Text(blocker, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            else if (!c.running) for (p in peers) {
                HorizontalDivider()
                Text("With ${p.label}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                for (preset in Campaign.Preset.entries) {
                    Surface(onClick = { act.startCampaign(preset, p.id) }, shape = RoundedCornerShape(14.dp),
                        color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(preset.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                Text(Describe.presetHint(preset), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            Icon(SegueIcons.Play, contentDescription = "Start ${preset.label}", tint = cs.primary)
                        }
                    }
                }
            }
        }
    }

    SectionTitle("Logs")
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${ui.logFiles} log file(s) on this device", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = act::exportLogs) { Text("Export logs") }
                OutlinedButton(onClick = act::clearOldLogs, enabled = !c.running) { Text("Delete old") }
            }
            ui.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.primary) }
        }
    }

    Diagnostics(s, ui.extras, act)
}

@Composable
private fun Diagnostics(s: EngineState, extras: UiExtras, act: UiActions) {
    var open by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    SectionTitle("Diagnostics") { TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") } }
    AnimatedVisibility(open) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Mono("phase ${s.phase} · owner ${s.ownerId} · epoch ${s.ownerEpoch}")
                Mono("self ${s.selfId} · policy ${s.policy}")
                extras.logPath?.let { Mono(it) }
                HorizontalDivider()
                Text("Fault on the next handoff", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { act.fault(FaultKind.DROP_READY) }, modifier = Modifier.weight(1f)) { Text("Drop READY", fontSize = 12.sp) }
                    OutlinedButton(onClick = { act.fault(FaultKind.DROP_STATE_TRANSFER) }, modifier = Modifier.weight(1f)) { Text("Drop STATE", fontSize = 12.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { act.fault(FaultKind.KILL_IN_PROVISIONAL) }, modifier = Modifier.weight(1f)) { Text("Kill: provisional", fontSize = 12.sp) }
                    OutlinedButton(onClick = { act.fault(FaultKind.KILL_IN_TRANSFERRING) }, modifier = Modifier.weight(1f)) { Text("Kill: transfer", fontSize = 12.sp) }
                }
                TextButton(onClick = { act.fault(FaultKind.CLEAR) }) { Text("Clear faults") }
                HorizontalDivider()
                Text("Recent events", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(cs.surfaceContainerHighest).padding(10.dp)) {
                    for (line in s.recent.take(40)) Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Mono(text: String) = Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp)

// ---------------------------------------------------------------------------------------- Pairing

@Composable
private fun PairingDialog(view: PairingView, act: UiActions) {
    when (view) {
        PairingView.Idle -> {}
        PairingView.Searching -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(SegueIcons.Devices, contentDescription = null) },
            title = { Text("Looking for your device") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("On the other device, open Segue → Devices → Add. Keep both devices close.")
                }
            },
            confirmButton = { TextButton(onClick = act::cancelPairing) { Text("Cancel") } }
        )
        is PairingView.Compare -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Same code on both?") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CodeBoxes(view.sas)
                    Text("Check that the other device shows exactly this code. It keeps a nearby stranger from pairing instead.",
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            },
            confirmButton = { Button(onClick = { act.confirmPairing(true) }) { Text("They match") } },
            dismissButton = { TextButton(onClick = { act.confirmPairing(false) }) { Text("No") } }
        )
        is PairingView.Waiting -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Waiting for the other device") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CodeBoxes(view.sas)
                    Text("Tap They match on the other device too.", textAlign = TextAlign.Center)
                }
            },
            confirmButton = { TextButton(onClick = act::cancelPairing) { Text("Cancel") } }
        )
        is PairingView.Done -> AlertDialog(
            onDismissRequest = act::dismissPairing,
            icon = { Icon(SegueIcons.Check, contentDescription = null, tint = Color(0xFF2E9D5B)) },
            title = { Text("Paired with ${view.label}") },
            text = { Text("From now on the two devices reconnect by themselves whenever they're near each other.") },
            confirmButton = { Button(onClick = act::dismissPairing) { Text("Done") } }
        )
        is PairingView.Failed -> AlertDialog(
            onDismissRequest = act::dismissPairing,
            icon = { Icon(SegueIcons.Warning, contentDescription = null) },
            title = { Text("Pairing didn't work") },
            text = { Text(view.reason) },
            confirmButton = { Button(onClick = act::dismissPairing) { Text("OK") } }
        )
    }
}

@Composable
private fun CodeBoxes(code: String) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        code.forEachIndexed { i, ch ->
            if (i == 3) Spacer(Modifier.width(8.dp))
            Box(Modifier.size(width = 34.dp, height = 46.dp).clip(RoundedCornerShape(10.dp)).background(cs.primaryContainer),
                contentAlignment = Alignment.Center) {
                Text(ch.toString(), fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = cs.onPrimaryContainer)
            }
        }
    }
}
