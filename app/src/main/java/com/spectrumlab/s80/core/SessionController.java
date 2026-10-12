package com.spectrumlab.s80.core;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.db.EventDb;
import com.spectrumlab.s80.service.SurveyService;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;

/**
 * Survey session lifecycle + observation normalization pipeline (docs/03):
 * sensor event -> envelope (ids, dual clocks, provenance) -> optional GPS join
 * -> bounded queue -> SQLite append -> throttled UI refresh.
 *
 * Sessions are separate from the Activity lifecycle: scanning owned by app-scoped
 * adapters stops only on explicit stop, battery-low, or process death (recovered
 * on next launch as "interrupted"). No silent collection: the SurveyService
 * foreground notification is visible for the entire session.
 */
public class SessionController {
    public static final String MODE_SURVEY = "SURVEY";
    public static final String SCOPE_PERSONAL_LAB = "Personal lab (Phase 1 default)";
    public static final String REDACTION_POLICY = "mask_ids_by_default";
    private static final int QUEUE_CAPACITY = 1024;
    /** GPS fix older than this is not joined onto observations (docs/03: initial 10 s). */
    public static final long GPS_JOIN_MAX_AGE_MS = 10_000;

    private final Context app;
    private final EventDb db;
    private final EventBus bus;
    private final Redaction redaction;
    private final ThreadPoolExecutor disk = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(QUEUE_CAPACITY),
            r -> { Thread t = new Thread(r, "slab-store"); t.setDaemon(true); return t; });
    private final AtomicLong dropped = new AtomicLong();
    private final List<String[]> feed = new ArrayList<>(); // {hh:mm:ss, sensor, summary}

    private String sessionId;
    private String sessionName;
    private boolean gpsOptIn;
    private long startedElapsedMs;
    private String firmwareFingerprint;

    // Latest GPS fix (guarded by this), set by GpsTracker via service.
    private volatile double lastLat = Double.NaN;
    private volatile double lastLon = Double.NaN;
    private volatile float lastAccuracy = Float.NaN;
    private volatile long lastFixElapsedMs = Long.MIN_VALUE;
    private volatile String lastFixProvider;

    public SessionController(Context app, EventDb db, EventBus bus, Redaction redaction) {
        this.app = app.getApplicationContext();
        this.db = db;
        this.bus = bus;
        this.redaction = redaction;
    }

    // ---------- lifecycle ----------

    public synchronized boolean isActive() { return sessionId != null; }
    public synchronized String sessionId() { return sessionId; }
    public synchronized String sessionName() { return sessionName; }
    public synchronized boolean gpsOptIn() { return gpsOptIn; }
    public long sessionElapsedSeconds() {
        return isActive() ? (SystemClock.elapsedRealtime() - startedElapsedMs) / 1000 : 0;
    }

    public synchronized void startSession(String name, boolean optInGps) {
        if (isActive()) return;
        String id = UUID.randomUUID().toString();
        String now = utcNow();
        this.sessionId = id;
        this.sessionName = name == null || name.trim().isEmpty() ? defaultName() : name.trim();
        this.gpsOptIn = optInGps;
        this.startedElapsedMs = SystemClock.elapsedRealtime();
        this.firmwareFingerprint = fingerprintHash();
        disk.execute(() -> db.insertSession(id, sessionName, MODE_SURVEY, SCOPE_PERSONAL_LAB,
                now, firmwareFingerprint, optInGps, REDACTION_POLICY, false));
        app.startForegroundService(new Intent(app, SurveyService.class)
                .putExtra(SurveyService.EXTRA_SESSION_ID, id));
        App.get(app).wifi().start();
        systemEvent("system.session_started", payload()
                .put("session_name", sessionName)
                .put("mode", MODE_SURVEY)
                .put("scope_label", SCOPE_PERSONAL_LAB)
                .put("gps_opt_in", optInGps)
                .put("redaction_policy", REDACTION_POLICY));
        Log.i(App.LOG_TAG, "session started id=" + id + " gps=" + optInGps);
    }

    public synchronized void endSession(String reason) {
        if (!isActive()) return;
        final String id = sessionId;
        final String now = utcNow();
        SafeJson endedPayload = payload().put("reason", reason).put("duration_s", sessionElapsedSeconds());
        sessionId = null;
        sessionName = null;
        gpsOptIn = false;
        lastFixElapsedMs = Long.MIN_VALUE;
        disk.execute(() -> db.endSession(id, now));
        app.stopService(new Intent(app, SurveyService.class));
        App.get(app).wifi().stop();
        App.get(app).ble().forceStopForBackground();
        systemEvent("system.session_stopped", endedPayload);
        Log.i(App.LOG_TAG, "session stopped reason=" + reason);
        bus.publish();
    }

    /** Called at cold start: sessions left open by a dead process are closed as interrupted. */
    public void recoverInterruptedSessions() {
        disk.execute(() -> {
            int n = db.markStaleOpenSessionsEnded(utcNow(), sessionId);
            if (n > 0) Log.i(App.LOG_TAG, "recovered " + n + " interrupted session(s)");
        });
    }

    public void applyRetentionAsync() {
        final int days = getRetentionDays();
        disk.execute(() -> {
            String cutoff = isoFormat().format(new Date(System.currentTimeMillis() - days * 86_400_000L));
            int n = db.purgeOlderThan(cutoff);
            if (n > 0) Log.i(App.LOG_TAG, "retention purge removed " + n + " observations (" + days + "d)");
        });
    }

    public int getRetentionDays() {
        try { return Integer.parseInt(db.getSetting("retention_days", "30")); }
        catch (Exception e) { return 30; }
    }

    public void setRetentionDays(int days) {
        disk.execute(() -> db.setSetting("retention_days", String.valueOf(days)));
    }

    public void deleteAllDataAsync(Runnable onDone) {
        disk.execute(() -> {
            int n = db.deleteAllObservations();
            Log.w(App.LOG_TAG, "deleted all local data: " + n + " observations");
            if (onDone != null) onDone.run();
            bus.publish();
        });
    }

    // ---------- observation pipeline ----------

    public SafeJson payload() { return new SafeJson(); }

    /** Sensors call this with a REDACTED payload. Envelope + GPS join + persistence handled here. */
    public void onObservation(String sensor, String type, JSONObject payload) {
        if (!isActive()) return;
        final Observation o = new Observation();
        o.id = UUID.randomUUID().toString();
        synchronized (this) { o.sessionId = sessionId; }
        o.sensor = sensor;
        o.type = type;
        o.timestampUtc = utcNow();
        o.elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos();
        o.sourceKind = "android_api";
        o.confidence = "observed";
        o.synthetic = false;
        o.privacy = "redacted";
        joinGps(o, payload);
        o.payloadJson = payload.toString();
        rememberFeed(o, payload);
        try {
            disk.execute(() -> {
                try { db.insertObservation(o); }
                catch (Exception e) { Log.e(App.LOG_TAG, "insert failed", e); }
            });
        } catch (RejectedExecutionException queueFull) {
            long n = dropped.incrementAndGet();
            Log.w(App.LOG_TAG, "observation queue full; dropped=" + n);
        }
        bus.publish();
    }

    /** System events (lifecycle, errors, capability tests) attach to the active session. */
    public void systemEvent(String type, JSONObject payload) {
        if (!isActive()) {
            // No active session: important errors still go to logcat; DB rows require a session.
            Log.i(App.LOG_TAG, type + " " + payload);
            return;
        }
        onObservation("system", type, payload);
    }

    private void joinGps(Observation o, JSONObject payload) {
        if (!gpsOptIn) return;
        long fixMs = lastFixElapsedMs;
        if (fixMs == Long.MIN_VALUE) return;
        long age = SystemClock.elapsedRealtime() - fixMs;
        if (age > GPS_JOIN_MAX_AGE_MS || Double.isNaN(lastLat) || Double.isNaN(lastLon)) return;
        try {
            payload.put("latitude", round6(lastLat));
            payload.put("longitude", round6(lastLon));
            if (!Float.isNaN(lastAccuracy)) payload.put("gps_accuracy_m", Math.round(lastAccuracy));
            payload.put("gps_fix_age_s", Math.round(age / 1000.0));
            payload.put("gps_provider", lastFixProvider == null ? "unknown" : lastFixProvider);
        } catch (Exception ignored) {}
    }

    /** GpsTracker (inside SurveyService) publishes fixes here. */
    public void onGpsFix(double lat, double lon, float accuracyM, String provider) {
        lastLat = lat; lastLon = lon; lastAccuracy = accuracyM;
        lastFixElapsedMs = SystemClock.elapsedRealtime();
        lastFixProvider = provider;
        if (isActive()) {
            JSONObject p = payload()
                    .put("latitude", round6(lat))
                    .put("longitude", round6(lon))
                    .put("accuracy_m", Math.round(accuracyM))
                    .put("provider", provider);
            // GPS rows carry the user's actual coordinates: explicit opt-in only (privacy docs/06).
            onObservation("gps", "gps.fix", p);
        }
    }

    public long droppedCount() { return dropped.get(); }

    /** Latest feed rows (UTC hh:mm:ss, sensor, summary), newest first, capped at 24. */
    public List<String[]> recentFeed() {
        synchronized (feed) { return new ArrayList<>(feed); }
    }

    private void rememberFeed(Observation o, JSONObject payload) {
        synchronized (feed) {
            String summary = payload.optString("summary", o.type);
            String hhmmss = o.timestampUtc.length() >= 19
                    ? o.timestampUtc.substring(11, 19) : o.timestampUtc;
            feed.add(0, new String[]{hhmmss, o.sensor, summary});
            while (feed.size() > 24) feed.remove(feed.size() - 1);
        }
    }

    // ---------- helpers ----------

    public String fingerprintHash() {
        String fp = android.os.Build.FINGERPRINT;
        return "sha256:" + Redaction.sha256Hex(fp.getBytes()) + ":" + android.os.Build.VERSION.INCREMENTAL;
    }

    public static String defaultName() {
        return "Survey " + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date());
    }

    public static String utcNow() { return isoFormat().format(new Date()); }

    public static SimpleDateFormat isoFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    public Redaction redaction() { return redaction; }

    public List<EventDb.SessionInfo> recentSessions(int n) { return db.listSessions(n); }

    private static double round6(double v) { return Math.round(v * 1e6) / 1e6; }
}
