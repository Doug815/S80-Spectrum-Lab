package com.spectrumlab.s80.sensors;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.core.SafeJson;
import com.spectrumlab.s80.core.SessionController;
import com.spectrumlab.s80.db.EventDb;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Passive Wi-Fi survey loop (docs/04-B, docs/08):
 * - Android framework scans only (WifiManager.startScan + SCAN_RESULTS_AVAILABLE_ACTION).
 * - Foreground pacing ~30 s with a hard local floor of 15 s; OS throttling surfaced, never looped.
 * - ScanResults normalized to frequency/channel/RSSI/security; BSSIDs masked + HMAC-tagged.
 * - Not raw capture, not monitor mode, no deauth/injection — those are gated research items.
 *
 * Lives on the Application context so tab navigation/rotation does not orphan it;
 * stops with the session (SurveyService lifetime).
 */
public class WifiSurvey {
    public static final long SCAN_PERIOD_MS = 30_000;
    public static final long MIN_REQUEST_GAP_MS = 15_000;

    public static final class ApRow {
        public String displaySsid;
        public String key;            // hmac tag of BSSID (within-install pseudonym)
        public String bssidMasked;    // OUI-only display form
        public String security;
        public int frequencyMhz;
        public Integer channel;      // null when unknown mapping
        public String band;
        public int rssiLatest;
        public int rssiMin, rssiMax;
        public String firstSeenUtc, lastSeenUtc;
        public int observations;
        public boolean fresh;
        public long lastResultAgeMs;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ApRow> rows = new HashMap<>(); // key -> row
    private final Map<String, Long> lastStoredBucket = new HashMap<>(); // key -> elapsed bucket ms
    private final Map<String, Integer> lastStoredRssi = new HashMap<>();
    private final Map<String, String> lastStoredSecurity = new HashMap<>();
    private boolean running;
    private boolean receiverRegistered;
    private long lastRequestElapsed = Long.MIN_VALUE;
    private long lastRequestAcceptedElapsed = Long.MIN_VALUE;
    private String lastScanUtc = "—";
    private boolean lastScanFresh;
    private int lastScanCount = -1;
    private String lastError;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            boolean fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
            handleResults(fresh);
        }
    };

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!running) return;
            requestScan();
            main.postDelayed(this, SCAN_PERIOD_MS);
        }
    };

    private final Map<String, Long> lastSignalHistoryBucket = new HashMap<>(); // key -> elapsed bucket ms

    public WifiSurvey(Context app) { this.app = app.getApplicationContext(); }

    public synchronized boolean isRunning() { return running; }
    public synchronized int rowCount() { return rows.size(); }
    public synchronized String lastScanUtc() { return lastScanUtc; }
    public synchronized boolean lastScanFresh() { return lastScanFresh; }
    public synchronized int lastScanCount() { return lastScanCount; }
    public synchronized String lastError() { return lastError; }

    public synchronized List<ApRow> snapshotRows() {
        List<ApRow> out = new ArrayList<>(rows.values());
        out.sort(Comparator.comparingInt((ApRow r) -> r.rssiLatest).reversed());
        return out;
    }

    public synchronized int countBand(String band) {
        int n = 0;
        for (ApRow r : rows.values()) if (band.equals(r.band)) n++;
        return n;
    }

    public void start() {
        if (running) return;
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) { lastError = "ADAPTER_OFF"; return; }
        resetForNewSession();
        running = true;
        lastError = null;
        if (!receiverRegistered) {
            app.registerReceiver(receiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            receiverRegistered = true;
        }
        main.post(loop);
        Log.i(App.LOG_TAG, "wifi survey loop started");
    }

    /** A new session starts with an empty merged set (no carry-over from prior sessions). */
    private synchronized void resetForNewSession() {
        rows.clear();
        lastStoredBucket.clear();
        lastStoredRssi.clear();
        lastStoredSecurity.clear();
        lastScanUtc = "—";
        lastScanCount = -1;
        lastScanFresh = false;
    }

    public void stop() {
        running = false;
        main.removeCallbacks(loop);
        if (receiverRegistered) {
            try { app.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        Log.i(App.LOG_TAG, "wifi survey loop stopped");
    }

    private void requestScan() {
        SessionController sc = App.get(app).sessions();
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null || !wifi.isWifiEnabled()) {
            lastError = "ADAPTER_OFF";
            sc.systemEvent("system.error", new SafeJson()
                    .put("sensor", "wifi").put("error", "ADAPTER_OFF")
                    .put("action", "Enable Wi-Fi to continue the survey"));
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastRequestElapsed != Long.MIN_VALUE && now - lastRequestElapsed < MIN_REQUEST_GAP_MS) {
            return; // local pacing floor
        }
        lastRequestElapsed = now;
        boolean accepted;
        try {
            accepted = wifi.startScan();
        } catch (SecurityException e) {
            lastError = "PERMISSION_DENIED";
            sc.systemEvent("system.error", new SafeJson()
                    .put("sensor", "wifi").put("error", "PERMISSION_DENIED")
                    .put("action", "Grant location permission (Android 8.x gates scan results)"));
            return;
        }
        if (accepted) {
            lastRequestAcceptedElapsed = now;
        } else {
            lastError = "SCAN_THROTTLED";
            sc.systemEvent("wifi.scan_declined", new SafeJson()
                    .put("error", "SCAN_THROTTLED")
                    .put("summary", "OS declined startScan (throttle); cached results shown"));
            Log.w(App.LOG_TAG, "startScan declined by OS (throttle)");
        }
    }

    private void handleResults(boolean fresh) {
        SessionController sc = App.get(app).sessions();
        WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        if (wifi == null) return;
        List<ScanResult> results;
        try {
            results = wifi.getScanResults();
        } catch (SecurityException e) {
            lastError = "PERMISSION_DENIED";
            return;
        }
        if (results == null) return;
        synchronized (this) {
            lastScanUtc = SessionController.utcNow();
            lastScanFresh = fresh;
            lastScanCount = results.size();
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        int stored = 0;
        for (ScanResult r : results) {
            try { processOne(r, fresh, nowElapsed); stored++; } catch (Exception e) {
                Log.w(App.LOG_TAG, "scanresult parse issue: " + e);
            }
        }
        if (sc.isActive()) {
            sc.onObservation("wifi", "wifi.scan_completed", new SafeJson()
                    .put("ap_count", results.size())
                    .put("fresh_results", fresh)
                    .put("summary", results.size() + " APs " + (fresh ? "(fresh scan)" : "(cached)"))
                    .put("dedup_stored", stored));
        }
        App.get(app).capabilities().markVerified("wifi", "scan results received on-device");
    }

    private void processOne(ScanResult r, boolean fresh, long nowElapsed) {
        SessionController sc = App.get(app).sessions();
        String bssid = r.BSSID == null ? "" : r.BSSID;
        String key = App.get(app).redaction().tag(bssid);
        String ssid = com.spectrumlab.s80.core.Redaction.sanitize(r.SSID, 48);
        if (ssid.isEmpty()) ssid = "(hidden)";
        Integer channel = WifiChannels.fromFrequencyMHz(r.frequency);
        String security = WifiChannels.securitySummary(r.capabilities);
        long ageMs = r.timestamp > 0 ? Math.max(0, nowElapsed - r.timestamp / 1000) : -1;

        ApRow row;
        synchronized (this) {
            row = rows.get(key);
            if (row == null) {
                row = new ApRow();
                row.key = key;
                row.displaySsid = ssid;
                row.bssidMasked = com.spectrumlab.s80.core.Redaction.maskMac(bssid);
                row.security = security;
                row.frequencyMhz = r.frequency;
                row.channel = channel;
                row.band = WifiChannels.bandOf(r.frequency);
                row.firstSeenUtc = SessionController.utcNow();
                row.observations = 0;
                rows.put(key, row);
            }
            row.rssiLatest = r.level;
            row.rssiMin = row.observations == 0 ? r.level : Math.min(row.rssiMin, r.level);
            row.rssiMax = row.observations == 0 ? r.level : Math.max(row.rssiMax, r.level);
            row.lastSeenUtc = SessionController.utcNow();
            row.observations++;
            row.fresh = fresh;
            row.lastResultAgeMs = ageMs;
        }

        if (!sc.isActive()) return;
        // Dedup per docs/03: (key, 10 s time bucket); store on first sight, or when a new
        // bucket begins AND the signal actually moved >= 2 dB (or config changed).
        long bucket = nowElapsed / 10_000;
        Long lastBucket = lastStoredBucket.get(key);
        Integer lastRssi = lastStoredRssi.get(key);
        boolean first = lastBucket == null;
        boolean changed = lastBucket != null && lastBucket != bucket
                && (lastRssi == null || Math.abs(r.level - lastRssi) >= 2
                    || !row.security.equals(lastStoredSecurity.get(key)));
        if (!(first || changed)) return;
        lastStoredBucket.put(key, bucket);
        lastStoredRssi.put(key, r.level);
        lastStoredSecurity.put(key, row.security);
        SafeJson p = new SafeJson()
                .put("ssid", ssid)
                .put("bssid_masked", row.bssidMasked)
                .put("bssid_tag", key)
                .put("frequency_mhz", r.frequency)
                .put("channel", channel == null ? "unknown" : channel)
                .put("band", row.band)
                .put("rssi_dbm", r.level)
                .put("security", security)
                .put("result_age_ms", ageMs)
                .put("fresh", fresh)
                .put("summary", ssid + " ch" + (channel == null ? "?" : channel) + " " + r.level + "dBm " + security);
        sc.onObservation("wifi", "wifi.ap_observed", p);

        // P2.01: Store signal history for charts (deduplicated by 10s bucket)
        long shBucket = nowElapsed / 10_000;
        Long lastShBucket = lastSignalHistoryBucket.get(key);
        if (lastShBucket == null || lastShBucket != shBucket) {
            lastSignalHistoryBucket.put(key, shBucket);
            String histId = UUID.randomUUID().toString();
            App.get(app).db().insertSignalHistory(
                    histId, sc.sessionId(), "wifi", key, channel, r.frequency,
                    row.band, r.level, SessionController.utcNow(),
                    SystemClock.elapsedRealtimeNanos(), security, ssid);
        }
    }
}
