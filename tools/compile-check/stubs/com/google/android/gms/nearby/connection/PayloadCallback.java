package com.google.android.gms.nearby.connection;
public abstract class PayloadCallback {
    public PayloadCallback() {}
    public abstract void onPayloadReceived(String endpointId, Payload payload);
    public abstract void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update);
}
