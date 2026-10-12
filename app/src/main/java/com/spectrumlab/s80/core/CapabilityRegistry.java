package com.spectrumlab.s80.core;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.util.Log;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.db.EventDb;
import java.io.File;
import org.json.JSONObject;

/**
 * Hardware truth registry (docs/02 state machine: UNKNOWN -> DETECTED -> PROBED -> VERIFIED).
 * Probes ONLY public Android APIs and file-existence checks. Never executes su,
 * never touches vendor UART/HIDL nodes. States shown in UI come from here, so the
 * app cannot claim hardware it does not have (no IR, no 125 kHz RFID, etc.).
 */
public class CapabilityRegistry {
    public static final String CAP_WIFI = "wifi";
    public static final String CAP_BLE = "ble";
    public static final String CAP_NFC = "nfc";
    public static final String CAP_GPS = "gps";
    public static final String CAP_ROOT = "root";
    public static final String CAP_UHF = "uhf";
    public static final String CAP_HCE = "hce";
    public static final String CAP_IR = "ir";
    public static final String CAP_LF_RFID = "lf_rfid_125khz";
    public static final String CAP_WIFI_MONITOR = "wifi_monitor_mode";
    public static final String CAP_1WIRE = "ibutton_1wire";

    private final Context app;
    private final EventDb db;

    public CapabilityRegistry(Context app, EventDb db) {
        this.app = app.getApplicationContext();
        this.db = db;
    }

    public void probeAllAndStore() {
        recordFeature(CAP_WIFI, PackageManager.FEATURE_WIFI, "PackageManager.FEATURE_WIFI");
        recordFeature(CAP_BLE, PackageManager.FEATURE_BLUETOOTH_LE, "PackageManager.FEATURE_BLUETOOTH_LE");
        recordFeature(CAP_NFC, PackageManager.FEATURE_NFC, "PackageManager.FEATURE_NFC");
        recordFeature(CAP_HCE, PackageManager.FEATURE_NFC_HOST_CARD_EMULATION,
                "PackageManager.FEATURE_NFC_HOST_CARD_EMULATION");
        recordFeature(CAP_GPS, PackageManager.FEATURE_LOCATION_GPS, "PackageManager.FEATURE_LOCATION_GPS");
        recordFeature(CAP_IR, PackageManager.FEATURE_CONSUMER_IR,
                "PackageManager.FEATURE_CONSUMER_IR (absent => IR transmit impossible)");
        // Not physically present on this handset class; software cannot add silicon.
        record(CAP_LF_RFID, "NOT_PRESENT", new SafeJson()
                .put("reason", "No 125 kHz LF coil/front-end; phone NFC is 13.56 MHz only"));
        record(CAP_1WIRE, "NOT_PRESENT", new SafeJson()
                .put("reason", "No 1-Wire/iButton contact circuitry on device"));
        probeRoot();
        // UHF module exists but is owned by RumiTX; Spectrum Lab has no broker -> gated.
        record(CAP_UHF, "UNVERIFIED", new SafeJson()
                .put("reason", "Dedicated vendor module; requires RumiTX read-only broker contract")
                .put("policy", "Never open /dev/ttyMT1 or vendor HIDL from this app"));
        // Monitor mode depends on driver support; MediaTek stock driver unverified -> research-gated.
        record(CAP_WIFI_MONITOR, "UNVERIFIED", new SafeJson()
                .put("reason", "Driver nl80211 monitor-mode support unverified on this build")
                .put("policy", "Disabled until separately verified; root does not add monitor mode"));
        Log.i(App.LOG_TAG, "capability probe complete");
    }

    private void recordFeature(String cap, String feature, String method) {
        boolean has = app.getPackageManager().hasSystemFeature(feature);
        record(cap, has ? "DETECTED" : "NOT_PRESENT",
                new SafeJson().put("method", method).put("has_feature", has));
    }

    private void probeRoot() {
        SafeJson ev = new SafeJson()
                .put("method", "su binary file-existence check (never executed)");
        String[] paths = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su", "/su/bin/su"};
        boolean found = false;
        StringBuilder foundAt = new StringBuilder();
        for (String p : paths) {
            try {
                if (new File(p).exists()) {
                    found = true;
                    if (foundAt.length() > 0) foundAt.append(", ");
                    foundAt.append(p);
                }
            } catch (Exception ignored) {}
        }
        ev.put("su_binary_present", found);
        if (found) ev.put("paths", foundAt.toString());
        ev.put("selinux_mode", selinuxMode());
        record(CAP_ROOT, found ? "DETECTED" : "NOT_PRESENT", ev);
    }

    private static String selinuxMode() {
        try {
            Class<?> c = Class.forName("android.os.SELinux");
            return (Boolean) c.getMethod("isSELinuxEnforced").invoke(null) ? "enforcing" : "permissive";
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ---- live adapter states (queried at display time, not cached evidence) ----

    public boolean wifiAdapterOn() {
        WifiManager w = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        return w != null && w.isWifiEnabled();
    }

    public boolean bleAdapterOn() {
        BluetoothManager bm = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        return a != null && a.isEnabled();
    }

    public boolean nfcAdapterOn() {
        NfcAdapter n = NfcAdapter.getDefaultAdapter(app);
        return n != null && n.isEnabled();
    }

    public boolean locationServicesOn() {
        LocationManager lm = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return false;
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    /** Promote to VERIFIED after real data was observed on-device (FR-016). */
    public void markVerified(String capability, String detail) {
        record(capability, "VERIFIED", new SafeJson().put("verified_by", detail));
    }

    private void record(String capability, String state, JSONObject evidenceJson) {
        try {
            evidenceJson.put("build_fingerprint_redacted",
                    Build.FINGERPRINT == null ? "unknown" : Build.FINGERPRINT.replaceAll(":[^:]+$", ":REDACTED"));
            // State machine (docs/02) moves forward only: a re-probe must never
            // downgrade a capability that produced real observed data to DETECTED.
            String existing = db.getCapabilityState(capability);
            String effective = "VERIFIED".equals(existing) && "DETECTED".equals(state)
                    ? "VERIFIED" : state;
            if (!effective.equals(state)) {
                evidenceJson.put("reprobe_note", "kept VERIFIED from prior on-device observation");
            }
            db.upsertCapabilityEvidence(capability, effective, SessionController.utcNow(),
                    "redacted", evidenceJson.toString());
        } catch (Exception e) {
            Log.e(App.LOG_TAG, "capability record failed: " + capability, e);
        }
    }
}
