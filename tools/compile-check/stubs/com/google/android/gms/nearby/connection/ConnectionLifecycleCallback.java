package com.google.android.gms.nearby.connection;
public abstract class ConnectionLifecycleCallback {
    public ConnectionLifecycleCallback() {}
    public abstract void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo);
    public abstract void onConnectionResult(String endpointId, ConnectionResolution resolution);
    public abstract void onDisconnected(String endpointId);
}
