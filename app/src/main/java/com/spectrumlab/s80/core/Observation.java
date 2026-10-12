package com.spectrumlab.s80.core;

import org.json.JSONObject;

/** Immutable observation row (docs/07 envelope). Payload JSON is already redacted at capture. */
public final class Observation {
    public String id;
    public String sessionId;
    public String sensor;        // wifi | ble | nfc | gps | system
    public String type;         // e.g. wifi.ap_observed
    public String timestampUtc; // RFC3339 UTC
    public long elapsedRealtimeNs;
    public String sourceKind;   // android_api | root_probe | radio_broker | synthetic
    public String confidence;   // observed | inferred | unverified
    public boolean synthetic;
    public String privacy;      // redacted | minimized | explicit_user_owned
    public String payloadJson;

    public JSONObject payload() {
        try { return new JSONObject(payloadJson); }
        catch (Exception e) { return new JSONObject(); }
    }
}
