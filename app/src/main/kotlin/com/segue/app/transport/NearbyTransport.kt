package com.segue.app.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.segue.engine.SegueEngine
import com.segue.engine.Transport
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/**
 * Nearby Connections link layer (plan: ":transport-nearby").
 *
 * Nearby's own uKey2 encryption is kept, but Segue does not rely on it for authentication:
 * the engine runs its signed handshake over every connection and binds it to
 * ConnectionInfo.getRawAuthenticationToken(), so a relay that runs two Nearby connections fails.
 *
 * One ConnectionsClient can advertise and discover one service at a time, so pairing mode
 * temporarily replaces normal advertising/discovery; established connections are unaffected.
 * All Nearby calls are made on the main thread.
 */
class NearbyTransport(context: Context) : Transport {
    lateinit var engine: SegueEngine

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val pending = HashMap<String, Pending>()          // endpointId -> info captured at initiation
    private val connected = HashMap<String, Boolean>()        // endpointId -> pairing service?
    private var normalName: String? = null
    private var pairName: String? = null
    private var mode = Mode.OFF
    @Volatile var lastError: String? = null; private set

    private enum class Mode { OFF, NORMAL, PAIRING }
    private class Pending(val incoming: Boolean, val rawToken: ByteArray?, val pairing: Boolean)

    // ------------------------------------------------------------------ Transport

    override fun transmit(endpointId: String, bytes: ByteArray) {
        main.post {
            client.sendPayload(endpointId, Payload.fromBytes(bytes))
                .addOnFailureListener { e -> Log.w(TAG, "send to $endpointId failed", e) }
        }
    }

    override fun disconnect(endpointId: String) {
        main.post { client.disconnectFromEndpoint(endpointId) }
    }

    override fun startNormal(localName: String) {
        main.post {
            normalName = localName
            if (mode != Mode.PAIRING) startMode(Mode.NORMAL)
        }
    }

    override fun stopNormal() {
        main.post {
            normalName = null
            if (mode == Mode.NORMAL) stopRadios()
        }
    }

    override fun startPairing(localName: String) {
        main.post {
            pairName = localName
            startMode(Mode.PAIRING)
        }
    }

    override fun stopPairing() {
        main.post {
            pairName = null
            if (mode == Mode.PAIRING) {
                stopRadios()
                if (normalName != null) startMode(Mode.NORMAL)
            }
        }
    }

    fun shutdown() {
        main.post {
            stopRadios()
            client.stopAllEndpoints()
        }
    }

    /**
     * Drop every connection and stop advertising and discovery, as a process death would. Used when
     * the engine is replaced (emulated coordinator crash, rollback-policy switch); the new engine
     * starts the radios again.
     */
    fun dropAll() {
        main.post {
            stopRadios()
            client.stopAllEndpoints()
            connected.clear(); pending.clear()
            normalName = null; pairName = null
        }
    }

    // ------------------------------------------------------------------ radios

    private fun startMode(m: Mode) {
        stopRadios()
        val pairing = m == Mode.PAIRING
        val name = (if (pairing) pairName else normalName) ?: return
        val service = if (pairing) SERVICE_PAIR else SERVICE_NORMAL
        mode = m
        client.startAdvertising(name, service, lifecycle(pairing), AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { e -> lastError = "advertise: ${e.message}"; Log.w(TAG, "advertise", e) }
        client.startDiscovery(service, discovery(pairing), DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
            .addOnFailureListener { e -> lastError = "discover: ${e.message}"; Log.w(TAG, "discover", e) }
    }

    private fun stopRadios() {
        if (mode == Mode.OFF) return
        client.stopAdvertising()
        client.stopDiscovery()
        mode = Mode.OFF
    }

    /**
     * Discovery reports an endpoint once, so restart it after a drop or a refused request to find the
     * peer again. Advertising is left alone so that incoming requests are not disturbed.
     */
    private fun restartDiscoverySoon(delayMs: Long = 1500) {
        main.postDelayed({
            if (mode == Mode.NORMAL) {
                client.stopDiscovery()
                client.startDiscovery(SERVICE_NORMAL, discovery(false), DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
                    .addOnFailureListener { e -> lastError = "discover: ${e.message}"; Log.w(TAG, "discover", e) }
            }
        }, delayMs)
    }

    // ------------------------------------------------------------------ callbacks

    private fun lifecycle(pairing: Boolean) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val accept = !info.isIncomingConnection || engine.shouldAccept(info.endpointName, pairing)
            if (!accept) { client.rejectConnection(endpointId); return }
            pending[endpointId] = Pending(info.isIncomingConnection, info.rawAuthenticationToken, pairing)
            client.acceptConnection(endpointId, payloads)
                .addOnFailureListener { e -> Log.w(TAG, "accept", e) }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val p = pending.remove(endpointId)
            if (result.status.isSuccess && p != null) {
                connected[endpointId] = p.pairing
                engine.onLinkUp(endpointId, p.incoming, p.rawToken, p.pairing)
            } else {
                if (p?.pairing == false) restartDiscoverySoon(3000)
                engine.onLinkFailed(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            val wasPairing = connected.remove(endpointId)
            engine.onLinkDown(endpointId)
            if (wasPairing == false) restartDiscoverySoon()
        }
    }

    private fun discovery(pairing: Boolean) = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val name = engine.requestNameFor(endpointId, info.endpointName, pairing) ?: return
            client.requestConnection(name, endpointId, lifecycle(pairing))
                .addOnFailureListener { e ->
                    Log.w(TAG, "requestConnection $endpointId", e)
                    engine.onLinkFailed(endpointId)
                }
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) payload.asBytes()?.let { engine.onBytes(endpointId, it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    companion object {
        const val SERVICE_NORMAL = "com.segue.v2"
        const val SERVICE_PAIR = "com.segue.pair"
        /** M-to-N topology. Segue messages are a few hundred bytes, so bandwidth is irrelevant;
         *  whether a strategy disturbs the phone's Wi-Fi must be checked on the test devices. */
        val STRATEGY: Strategy = Strategy.P2P_CLUSTER
        private const val TAG = "SegueNearby"
    }
}
