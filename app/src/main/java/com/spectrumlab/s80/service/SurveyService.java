package com.spectrumlab.s80.service;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.core.SessionController;
import com.spectrumlab.s80.sensors.GpsTracker;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service for an active survey session (Android 8.x background rules, docs/08):
 * - Visible notification the whole time (no silent collection).
 * - Hosts the opt-in GPS listener and the battery-low guard (T016).
 * - The user stops the session explicitly; stopService also tears this down.
 */
public class SurveyService extends Service {
    public static final String EXTRA_SESSION_ID = "session_id";
    private static final int NOTIF_ID = 91;
    private static volatile boolean running;

    private GpsTracker gps;
    private BroadcastReceiver batteryLow;

    public static boolean isRunning() { return running; }

    @Override
    public void onCreate() {
        super.onCreate();
        gps = new GpsTracker(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SessionController sc = App.get(this).sessions();
        startForeground(NOTIF_ID, buildNotification(sc));
        if (sc.gpsOptIn()) {
            gps.start();
        }
        if (batteryLow == null) {
            batteryLow = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) {
                    Log.w(App.LOG_TAG, "battery low -> ending survey session");
                    App.get(c).sessions().endSession("BATTERY_LOW");
                }
            };
            registerReceiver(batteryLow, new IntentFilter(Intent.ACTION_BATTERY_LOW));
        }
        running = true;
        return START_NOT_STICKY;
    }

    private Notification buildNotification(SessionController sc) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, App.NOTIF_CHANNEL_SESSION);
        } else {
            b = new Notification.Builder(this);
        }
        String text = "Passive survey in progress"
                + (sc.gpsOptIn() ? " · GPS tagging ON (opt-in)" : " · GPS tagging OFF");
        return b
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth) // system icon; no custom art in Phase 1
                .setContentTitle("S80 Spectrum Lab — " + (sc.sessionName() == null ? "session" : sc.sessionName()))
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(android.app.PendingIntent.getActivity(
                        this, 0,
                        new Intent(this, com.spectrumlab.s80.MainActivity.class)
                                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT))
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        gps.stop();
        if (batteryLow != null) {
            try { unregisterReceiver(batteryLow); } catch (Exception ignored) {}
            batteryLow = null;
        }
        Log.i(App.LOG_TAG, "SurveyService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    public static String nowLocal() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
    }
}
