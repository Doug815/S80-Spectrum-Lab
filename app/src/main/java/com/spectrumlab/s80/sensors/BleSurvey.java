package com.spectrumlab.s80.sensors;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.core.Redaction;
import com.spectrumlab.s80.core.SafeJson;
import com.spectrumlab.s80.core.SessionController;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded BLE advertisement survey (docs/04-C, docs/08):
 * - Fixed 30 s windows via BluetoothLeScanner; explicit stop; 10 s cooldown between windows.
 * - No solicited connections. Randomized addresses are HMAC-tagged for within-session
 *   merging only and are NEVER treated as a person identity (R-07 disclaimer shown in UI).
 * - Advertisement payload summarized: service UUID count + manufacturer IDs (no raw bytes).
 */
public class BleSurvey {
    public static final long SCAN_WINDOW_MS = 30_000;
    public static final long COOLDOWN_MS = 10_000;

    public static final class AdvRow {
        public String key;          // hmac tag of advertiser address
        public String addrMasked;   // OUI-only display
        public String name;         // sanitized, may be empty
        public int rssiLatest;
        public int rssiMin, rssiMax;
        public int advertisements;
        public int serviceUuidCount;
        public String manufacturerIds; // e.g. "0x004C,0x0075" capped
        public String firstSeenUtc, lastSeenUtc;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, AdvRow> rows = new HashMap<>();
    private final Map<String, Long> lastStoredBucket = new HashMap<>();
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private boolean scanning;
    private long cooldownUntilElapsed = Long.MIN_VALUE;
    private long scanStartedElapsed;
    private int totalAdvertisements;
    private String lastError;

    public BleSurvey(Context app) { this.app = app.getApplicationContext(); }

    public synchronized boolean isScanning() { return scanning; }
    public synchronized int rowCount() { return rows.size(); }
    public synchronized int totalAdvertisements() { return totalAdvertisements; }
    public synchronized String lastError() { return lastError; }
    public synchronized boolean inCooldown() {
        return cooldownUntilElapsed != Long.MIN_VALUE
                && SystemClock.elapsedRealtime() < cooldownUntilElapsed;
    }
    public synchronized long scanElapsedSeconds() {
        return scanning ? (SystemClock.elapsedRealtime() - scanStartedElapsed) / 1000 : 0;
    }

    public synchronized List<AdvRow> snapshotRows() {
        List<AdvRow> out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparingInt((AdvRow r) -> r.rssiLatest).reversed());
        return out;
    }

    public String startBoundedScan() {
        SessionController sc = App.get(app).sessions();
        if (scanning) return "BUSY";
        if (inCooldown()) return "COOLDOWN";
        BluetoothAdapter adapter = adapter();
        if (adapter == null || !adapter.isEnabled()) { lastError = "ADAPTER_OFF"; return "ADAPTER_OFF"; }
        BluetoothLeScanner s = adapter.getBluetoothLeScanner();
        if (s == null) { lastError = "UNAVAILABLE"; return "UNAVAILABLE"; }
        scanner = s;
        callback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                handleResult(result);
            }
            @Override public void onScanFailed(int errorCode) {
                main.post(() -> {
                    lastError = "SCAN_FAILED_" + errorCode;
                    Log.e(App.LOG_TAG, "BLE scan failed code=" + errorCode);
                    stopScan("SCAN_FAILED_" + errorCode);
                });
            }
        };
        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
                    .build();
            s.startScan(null, settings, callback);
        } catch (IllegalStateException | SecurityException e) {
            lastError = "START_DENIED";
            scanner = null;
            callback = null;
            return "START_DENIED";
        }
        scanning = true;
        scanStartedElapsed = SystemClock.elapsedRealtime();
        totalAdvertisements = 0;
        rows.clear(); // each window is its own bounded observation set
        main.postDelayed(() -> stopScan("WINDOW_COMPLETE"), SCAN_WINDOW_MS);
        Log.i(App.LOG_TAG, "BLE bounded scan started (30s)");
        return "OK";
    }

    /** Idempotent stop; reason recorded in the event stream when a session is active. */
    public void stopScan(String reason) {
        main.removeCallbacksAndMessages(null);
        BluetoothLeScanner s;
        ScanCallback cb;
        synchronized (this) {
            if (!scanning && "WINDOW_COMPLETE".equals(reason)) return;
            s = scanner; cb = callback;
            scanner = null; callback = null;
            boolean wasScanning = scanning;
            scanning = false;
            if (wasScanning) cooldownUntilElapsed = SystemClock.elapsedRealtime() + COOLDOWN_MS;
            else return;
        }
        if (s != null && cb != null) {
            try { s.stopScan(cb); } catch (Exception ignored) {}
        }
        SessionController sc = App.get(app).sessions();
        if (sc.isActive()) {
            sc.onObservation("ble", "ble.scan_completed", new SafeJson()
                    .put("reason", reason)
                    .put("advertisements_total", totalAdvertisements)
                    .put("distinct_advertisers", rows.size())
                    .put("window_s", SCAN_WINDOW_MS / 1000)
                    .put("summary", "BLE window complete: " + totalAdvertisements
                            + " advertisements from " + rows.size() + " advertisers"));
        }
        Log.i(App.LOG_TAG, "BLE scan stopped: " + reason + " total=" + totalAdvertisements);
        App.get(app).bus().publish();
    }

    public void forceStopForBackground() {
        if (scanning) stopScan("ACTIVITY_PAUSED"); // T008: no silent screen-off scanning
    }

    private BluetoothAdapter adapter() {
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    private void handleResult(ScanResult result) {
        SessionController sc = App.get(app).sessions();
        String address = result.getDevice() == null ? "" : result.getDevice().getAddress();
        if (address.isEmpty()) return;
        String key = App.get(app).redaction().tag(address);
        ScanRecord record = result.getScanRecord();
        String name = record == null ? "" : Redaction.sanitize(record.getDeviceName(), 24);
        int rssi = result.getRssi();
        int uuidCount = 0;
        String mfgIds = "";
        if (record != null) {
            List<android.os.ParcelUuid> uuids = record.getServiceUuids();
            if (uuids != null) uuidCount = Math.min(uuids.size(), 32);
            android.util.SparseArray<byte[]> mfg = record.getManufacturerSpecificData();
            if (mfg != null && mfg.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < mfg.size() && sb.length() < 40; i++) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(String.format("0x%04X", mfg.keyAt(i)));
                }
                mfgIds = sb.toString();
            }
        }
        AdvRow row;
        synchronized (this) {
            totalAdvertisements++;
            row = rows.get(key);
            if (row == null) {
                row = new AdvRow();
                row.key = key;
                row.addrMasked = Redaction.maskMac(address);
                row.name = name;
                row.firstSeenUtc = SessionController.utcNow();
                row.advertisements = 0;
                rows.put(key, row);
            }
            row.rssiLatest = rssi;
            row.rssiMin = row.advertisements == 0 ? rssi : Math.min(row.rssiMin, rssi);
            row.rssiMax = row.advertisements == 0 ? rssi : Math.max(row.rssiMax, rssi);
            row.serviceUuidCount = Math.max(row.serviceUuidCount, uuidCount);
            if (!mfgIds.isEmpty() && (row.manufacturerIds == null || row.manufacturerIds.isEmpty())) {
                row.manufacturerIds = mfgIds;
            }
            row.lastSeenUtc = SessionController.utcNow();
            row.advertisements++;
        }
        App.get(app).capabilities().markVerified("ble", "advertisement received on-device");

        if (!sc.isActive()) return;
        // Dedup: store at most once per (key, 5 s bucket) with a >= 2 dB significance rule.
        long bucket = SystemClock.elapsedRealtime() / 5_000;
        Long last = lastStoredBucket.get(key);
        if (last != null && last == bucket) return;
        lastStoredBucket.put(key, bucket);
        String display = name.isEmpty() ? "(unnamed)" : name;
        sc.onObservation("ble", "ble.advertisement_observed", new SafeJson()
                .put("address_masked", row.addrMasked)
                .put("address_tag", key)
                .put("name", display)
                .put("rssi_dbm", rssi)
                .put("service_uuid_count", row.serviceUuidCount)
                .putIf(!mfgIds.isEmpty(), "manufacturer_ids", mfgIds)
                .put("connectable", result.isConnectable())
                .put("randomized_disclaimer", "BLE addresses rotate; tag is a within-session pseudonym only")
                .put("summary", display + " " + rssi + "dBm #" + key.substring(0, 4)));
    }
}
