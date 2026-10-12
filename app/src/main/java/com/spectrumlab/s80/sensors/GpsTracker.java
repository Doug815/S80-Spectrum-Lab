package com.spectrumlab.s80.sensors;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.core.SessionController;

/**
 * Opt-in GPS tagging (docs/04-F): location updates ONLY while the user started a session
 * with the GPS checkbox ON (default OFF). GPS + network providers both used (indoor
 * assistance); the provider of each fix is recorded so provenance stays honest.
 * Listeners are removed when the session ends or the service dies — no silent tracking.
 */
public class GpsTracker {
    private final Context app;
    private LocationManager lm;
    private boolean listening;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) {
            if (loc == null) return;
            SessionController sc = App.get(app).sessions();
            sc.onGpsFix(loc.getLatitude(), loc.getLongitude(), loc.getAccuracy(), loc.getProvider());
            App.get(app).capabilities().markVerified("gps", "location fix on-device (provider=" + loc.getProvider() + ")");
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onProviderDisabled(String provider) {}
    };

    public GpsTracker(Context app) { this.app = app.getApplicationContext(); }

    public boolean isListening() { return listening; }

    public void start() {
        if (listening) return;
        lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000, 0, listener, Looper.getMainLooper());
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5_000, 0, listener, Looper.getMainLooper());
            }
            listening = true;
            Log.i(App.LOG_TAG, "GPS tracking started (opt-in session)");
        } catch (SecurityException e) {
            Log.w(App.LOG_TAG, "GPS start denied: " + e.getMessage());
        } catch (Exception e) {
            Log.e(App.LOG_TAG, "GPS start failed", e);
        }
    }

    public void stop() {
        if (!listening) return;
        try {
            if (lm != null) lm.removeUpdates(listener);
        } catch (Exception ignored) {}
        listening = false;
        Log.i(App.LOG_TAG, "GPS tracking stopped");
    }
}
