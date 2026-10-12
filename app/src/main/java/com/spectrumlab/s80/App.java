package com.spectrumlab.s80;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import com.spectrumlab.s80.core.CapabilityRegistry;
import com.spectrumlab.s80.core.EventBus;
import com.spectrumlab.s80.core.Redaction;
import com.spectrumlab.s80.core.SessionController;
import com.spectrumlab.s80.db.EventDb;

/**
 * Application entry point. Owns process-wide singletons:
 * - EventDb (app-private SQLite store, docs/07 schema)
 * - SessionController (survey session lifecycle + observation pipeline)
 * - CapabilityRegistry (hardware truth, probed from OS APIs only)
 * - EventBus (throttled UI refresh notifier)
 *
 * No network code, no root execution, no vendor UART/HIDL access anywhere in this app.
 */
public class App extends Application {
    public static final String LOG_TAG = "SLAB";
    public static final String NOTIF_CHANNEL_SESSION = "survey_session";
    public static final String NOTIF_CHANNEL_ALERTS = "survey_alerts";

    private EventDb db;
    private SessionController sessions;
    private CapabilityRegistry capabilities;
    private EventBus bus;
    private Redaction redaction;
    private com.spectrumlab.s80.sensors.WifiSurvey wifiSurvey;
    private com.spectrumlab.s80.sensors.BleSurvey bleSurvey;
    private com.spectrumlab.s80.sensors.NfcLab nfcLab;

    @Override
    public void onCreate() {
        super.onCreate();
        try { db = new EventDb(this); } catch (Exception e) { Log.e(LOG_TAG, "db open failed", e); }
        redaction = new Redaction(this);
        bus = new EventBus();
        sessions = new SessionController(this, db, bus, redaction);
        capabilities = new CapabilityRegistry(this, db);
        wifiSurvey = new com.spectrumlab.s80.sensors.WifiSurvey(this);
        bleSurvey = new com.spectrumlab.s80.sensors.BleSurvey(this);
        nfcLab = new com.spectrumlab.s80.sensors.NfcLab(this);
        createNotificationChannels();
        // Mark sessions left open by a previous process as interrupted (T015 recovery).
        sessions.recoverInterruptedSessions();
        // Purge observations past the retention window (NFR: default 30 days).
        sessions.applyRetentionAsync();
        Log.i(LOG_TAG, "App init: version=" + BuildConfig.VERSION_NAME
                + " fingerprint=" + Build.FINGERPRINT);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            NotificationChannel session = new NotificationChannel(
                    NOTIF_CHANNEL_SESSION, "Survey sessions", NotificationManager.IMPORTANCE_LOW);
            session.setDescription("Visible disclosure while a passive survey session is running");
            nm.createNotificationChannel(session);
            NotificationChannel alerts = new NotificationChannel(
                    NOTIF_CHANNEL_ALERTS, "Survey alerts", NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(alerts);
        }
    }

    public static App get(Context context) {
        return (App) context.getApplicationContext();
    }

    public EventDb db() { return db; }
    public SessionController sessions() { return sessions; }
    public CapabilityRegistry capabilities() { return capabilities; }
    public EventBus bus() { return bus; }
    public Redaction redaction() { return redaction; }
    public com.spectrumlab.s80.sensors.WifiSurvey wifi() { return wifiSurvey; }
    public com.spectrumlab.s80.sensors.BleSurvey ble() { return bleSurvey; }
    public com.spectrumlab.s80.sensors.NfcLab nfc() { return nfcLab; }

    public static String appVersion() { return BuildConfig.VERSION_NAME; }

    public static boolean hasFeature(Context c, String feature) {
        return c.getPackageManager().hasSystemFeature(feature);
    }

    public static boolean hasPermission(Context c, String perm) {
        return c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }
}
