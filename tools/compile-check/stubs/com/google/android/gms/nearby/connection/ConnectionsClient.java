package com.google.android.gms.nearby.connection;
import com.google.android.gms.tasks.Task;
public interface ConnectionsClient {
    Task<Void> startAdvertising(String name, String serviceId, ConnectionLifecycleCallback connectionLifecycleCallback, AdvertisingOptions options);
    Task<Void> startDiscovery(String serviceId, EndpointDiscoveryCallback endpointDiscoveryCallback, DiscoveryOptions options);
    Task<Void> requestConnection(String name, String endpointId, ConnectionLifecycleCallback connectionLifecycleCallback);
    Task<Void> acceptConnection(String endpointId, PayloadCallback payloadCallback);
    Task<Void> rejectConnection(String endpointId);
    Task<Void> sendPayload(String endpointId, Payload payload);
    void disconnectFromEndpoint(String endpointId);
    void stopAdvertising();
    void stopDiscovery();
    void stopAllEndpoints();
}
