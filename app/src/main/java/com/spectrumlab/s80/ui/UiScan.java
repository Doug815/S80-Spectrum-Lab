package com.spectrumlab.s80.ui;

import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.core.SessionController;
import com.spectrumlab.s80.core.Theme;
import com.spectrumlab.s80.sensors.BleSurvey;
import com.spectrumlab.s80.sensors.WifiChannels;
import com.spectrumlab.s80.sensors.WifiSurvey;
import java.util.List;
import org.json.JSONObject;

/**
 * SCAN · Scan Center (docs/05 view 02): Wi-Fi / BLE / NFC modules with start/stop,
 * permission explanation and visible scan state; UHF shows locked state with reason.
 */
public final class UiScan {
    public static final int MODULE_WIFI = 0;
    public static final int MODULE_BLE = 1;
    public static final int MODULE_NFC = 2;
    public static final int MODULE_UHF = 3;
    private static volatile int module = MODULE_WIFI;

    public static LinearLayout build(Context ctx) {
        App app = App.get(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 14);
        root.setPadding(pad, Ui.dp(ctx, 8), pad, Ui.dp(ctx, 14));

        // module selector
        LinearLayout seg = Ui.row(ctx);
        seg.addView(segButton(ctx, "WI-FI", MODULE_WIFI));
        seg.addView(segButton(ctx, "BLE", MODULE_BLE));
        seg.addView(segButton(ctx, "NFC", MODULE_NFC));
        seg.addView(segButton(ctx, "UHF ⚿", MODULE_UHF));
        root.addView(seg);
        Ui.spacer(ctx, root, 8);

        switch (module) {
            case MODULE_BLE: root.addView(blePanel(ctx, app)); break;
            case MODULE_NFC: root.addView(nfcPanel(ctx, app)); break;
            case MODULE_UHF: root.addView(uhfPanel(ctx)); break;
            default: root.addView(wifiPanel(ctx, app)); break;
        }
        return root;
    }

    private static TextView segButton(Context ctx, String caption, final int m) {
        TextView v = Ui.text(ctx, caption, 13, m == module ? Theme.BG : Theme.MUTED, true);
        v.setGravity(android.view.Gravity.CENTER);
        v.setMinimumHeight(Ui.dp(ctx, 44));
        v.setMinHeight(Ui.dp(ctx, 44));
        GradientBg bg = new GradientBg();
        bg.setColor(m == module ? Theme.ACCENT : Theme.SURFACE);
        bg.setCornerRadius(Ui.dp(ctx, 5));
        v.setBackground(bg.bg);
        v.setOnClickListener(x -> { module = m; appRefresh(ctx); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2), 0);
        v.setLayoutParams(p);
        return v;
    }

    private static void appRefresh(Context ctx) { App.get(ctx).bus().publish(); }

    // ---------------- WI-FI ----------------

    private static LinearLayout wifiPanel(Context ctx, App app) {
        LinearLayout panel = Ui.card(ctx);
        WifiSurvey wifi = app.wifi();
        SessionController sc = app.sessions();

        if (!sc.isActive()) {
            panel.addView(Ui.text(ctx, "Wi-Fi survey runs inside a session.", 12, Theme.AMBER, false));
            panel.addView(Ui.text(ctx, "Start one from HOME. Scans are passive framework results — "
                    + "not monitor mode, not packet capture.", 12, Theme.MUTED, false));
            return panel;
        }
        if (!App.hasPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            panel.addView(Ui.text(ctx, "PERMISSION_DENIED: location permission required by Android 8.x "
                    + "for scan results. See HOME to request it.", 12, Theme.RED, false));
            return panel;
        }
        if (!app.capabilities().locationServicesOn()) {
            panel.addView(Ui.text(ctx, "LOCATION_DISABLED: enable Android location services — "
                    + "scan results stay empty otherwise.", 12, Theme.RED, false));
            return panel;
        }
        if (!app.capabilities().wifiAdapterOn()) {
            panel.addView(Ui.text(ctx, "ADAPTER_OFF: enable Wi-Fi to survey.", 12, Theme.RED, false));
            return panel;
        }

        panel.addView(Ui.mono(ctx,
                (wifi.isRunning() ? "SURVEY LOOP ACTIVE" : "loop idle")
                        + " · last scan " + (wifi.lastScanCount() < 0 ? "—" : wifi.lastScanCount() + " APs")
                        + (wifi.lastScanFresh() ? " (fresh)" : " (cached)")
                        + " · next ~" + (WifiSurvey.SCAN_PERIOD_MS / 1000) + "s", 12, Theme.ACCENT));
        panel.addView(Ui.text(ctx, "Last scan (UTC): " + wifi.lastScanUtc()
                + (wifi.lastError() == null ? "" : "  ·  issue: " + wifi.lastError()), 11, Theme.MUTED, false));
        Ui.spacer(ctx, panel, 6);

        List<WifiSurvey.ApRow> rows = wifi.snapshotRows();
        panel.addView(Ui.text(ctx, "2.4GHz: " + wifi.countBand("2.4GHz") + " APs · 5GHz: "
                + wifi.countBand("5GHz") + " APs · " + rows.size() + " merged", 12, Theme.FG, true));
        Ui.spacer(ctx, panel, 4);

        int shown = 0;
        for (WifiSurvey.ApRow r : rows) {
            if (shown++ >= 30) { panel.addView(Ui.text(ctx, "… list capped at 30 rows", 11, Theme.MUTED, false)); break; }
            LinearLayout item = Ui.card(ctx);
            LinearLayout l1 = Ui.row(ctx);
            TextView ssid = Ui.mono(ctx, r.displaySsid, 14, Theme.FG);
            l1.addView(ssid);
            TextView rssi = Ui.mono(ctx, "  " + r.rssiLatest + " dBm", 14, rssiColor(r.rssiLatest));
            l1.addView(rssi);
            item.addView(l1);
            item.addView(Ui.mono(ctx,
                    r.band + " ch" + (r.channel == null ? "?" : r.channel) + " · " + r.frequencyMhz + "MHz · "
                            + r.security, 11, Theme.MUTED));
            item.addView(Ui.mono(ctx, r.bssidMasked + "#" + r.key.substring(0, 4)
                    + " · seen " + r.firstSeenUtc.substring(11, 19) + "→" + r.lastSeenUtc.substring(11, 19)
                    + " · x" + r.observations, 11, Theme.MUTED));
            panel.addView(item);
        }
        if (rows.isEmpty()) {
            panel.addView(Ui.text(ctx, "No APs observed yet — first scan results arrive within seconds "
                    + "of session start (may initially be cached).", 12, Theme.MUTED, false));
        }
        panel.addView(Ui.text(ctx,
                "Channel occupancy = AP counts from framework scans only. No airtime measurement "
                        + "(monitor mode is gated — see TOOLS). BSSIDs masked; last octets withheld.",
                10, Theme.MUTED, false));
        return panel;
    }

    // ---------------- BLE ----------------

    private static LinearLayout blePanel(Context ctx, App app) {
        LinearLayout panel = Ui.card(ctx);
        BleSurvey ble = app.ble();
        SessionController sc = app.sessions();

        panel.addView(Ui.text(ctx, "BLE: bounded 30-second passive advertisement windows. "
                + "No connections, no tracking claims.", 12, Theme.MUTED, false));
        Ui.spacer(ctx, panel, 6);

        if (!App.hasPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            panel.addView(Ui.text(ctx, "PERMISSION_DENIED: location permission required for BLE scan results.",
                    12, Theme.RED, false));
            return panel;
        }
        if (!app.capabilities().locationServicesOn()) {
            panel.addView(Ui.text(ctx, "LOCATION_DISABLED: enable location services for BLE results.",
                    12, Theme.RED, false));
        }
        if (!app.capabilities().bleAdapterOn()) {
            panel.addView(Ui.text(ctx, "ADAPTER_OFF: enable Bluetooth to scan (BLE needs the adapter on).",
                    12, Theme.RED, false));
            return panel;
        }

        LinearLayout status = Ui.row(ctx);
        status.addView(Ui.mono(ctx, ble.isScanning()
                ? "SCANNING · " + ble.scanElapsedSeconds() + "s elapsed"
                : (ble.inCooldown() ? "COOLDOWN ~10s" : "idle"), 12, Theme.ACCENT));
        Ui.spacer(ctx, status, 10);
        Button b = Ui.button(ctx, ble.isScanning() ? "STOP" : "START 30s SCAN",
                ble.isScanning() ? Theme.RED : Theme.SURFACE2, v -> {
                    String res;
                    if (ble.isScanning()) { ble.stopScan("USER_STOP"); res = "OK"; }
                    else { res = ble.startBoundedScan(); }
                    if (!"OK".equals(res) && !"BUSY".equals(res) && !"COOLDOWN".equals(res)) {
                        Toast.makeText(ctx, "Scan not started: " + res, Toast.LENGTH_SHORT).show();
                    }
                    appRefresh(ctx);
                });
        status.addView(b);
        panel.addView(status);
        Ui.spacer(ctx, panel, 4);
        panel.addView(Ui.mono(ctx, ble.totalAdvertisements() + " advertisements · "
                + ble.rowCount() + " distinct advertisers (this window)", 12, Theme.FG));
        Ui.spacer(ctx, panel, 6);

        List<BleSurvey.AdvRow> rows = ble.snapshotRows();
        int shown = 0;
        for (BleSurvey.AdvRow r : rows) {
            if (shown++ >= 30) { panel.addView(Ui.text(ctx, "… list capped at 30 rows", 11, Theme.MUTED, false)); break; }
            LinearLayout item = Ui.card(ctx);
            LinearLayout l1 = Ui.row(ctx);
            l1.addView(Ui.mono(ctx, r.name.isEmpty() ? "(unnamed)" : r.name, 13, Theme.FG));
            l1.addView(Ui.mono(ctx, "  " + r.rssiLatest + " dBm", 13, rssiColor(r.rssiLatest)));
            item.addView(l1);
            item.addView(Ui.mono(ctx, r.addrMasked + "#" + r.key.substring(0, 4)
                    + " · x" + r.advertisements
                    + (r.serviceUuidCount > 0 ? " · svcUUIDs:" + r.serviceUuidCount : "")
                    + (r.manufacturerIds == null ? "" : " · mfg " + r.manufacturerIds), 11, Theme.MUTED));
            panel.addView(item);
        }
        if (rows.isEmpty()) {
            panel.addView(Ui.text(ctx, ble.isScanning()
                    ? "Listening for advertisements…"
                    : "No advertisers in the last window.", 12, Theme.MUTED, false));
        }
        panel.addView(Ui.text(ctx,
                "Randomized BLE addresses rotate; the #tag is a within-session pseudonym only, "
                        + "never a person identity. RSSI is relative, not distance.",
                10, Theme.MUTED, false));
        return panel;
    }

    // ---------------- NFC ----------------

    private static LinearLayout nfcPanel(Context ctx, App app) {
        LinearLayout panel = Ui.card(ctx);
        com.spectrumlab.s80.sensors.NfcLab nfc = app.nfc();
        SessionController sc = app.sessions();

        if (!nfc.adapterPresent()) {
            panel.addView(Ui.text(ctx, "NOT_PRESENT: no NFC adapter on this device.", 12, Theme.MUTED, false));
            return panel;
        }
        if (!nfc.adapterOn()) {
            panel.addView(Ui.text(ctx, "ADAPTER_OFF: enable NFC in system settings to read tags.",
                    12, Theme.RED, false));
            return panel;
        }
        if (!sc.isActive()) {
            panel.addView(Ui.text(ctx, "Reader mode is armed while a session is active and this app "
                    + "is in the foreground. Start a session from HOME.", 12, Theme.AMBER, false));
            return panel;
        }
        panel.addView(Ui.mono(ctx, nfc.readerArmed() ? "READER ARMED (foreground)" : "reader idle",
                13, Theme.GREEN));
        panel.addView(Ui.text(ctx, "Hold an owned tag near the back of the phone.", 13, Theme.FG, false));
        Ui.spacer(ctx, panel, 6);

        JSONObject last = nfc.lastTagSummary();
        if (last == null) {
            panel.addView(Ui.text(ctx, "No tag read yet in this session.", 12, Theme.MUTED, false));
        } else {
            panel.addView(Ui.text(ctx, "LAST TAG · " + nfc.lastTagUtc(), 11, Theme.ACCENT, true));
            panel.addView(Ui.mono(ctx, last.optString("technologies", "?"), 12, Theme.FG));
            panel.addView(Ui.mono(ctx, "UID " + last.optString("uid_masked", "?"), 11, Theme.MUTED));
            if (last.has("ndef_record_count")) {
                panel.addView(Ui.mono(ctx, "NDEF records: " + last.optInt("ndef_record_count")
                        + (last.optBoolean("writable") ? " · writable" : " · read-only")
                        + (last.has("capacity_bytes") ? " · " + last.optInt("capacity_bytes") + "B" : ""),
                        11, Theme.MUTED));
                Object recs = last.opt("records");
                if (recs instanceof org.json.JSONArray) {
                    org.json.JSONArray a = (org.json.JSONArray) recs;
                    for (int i = 0; i < a.length() && i < 4; i++) {
                        JSONObject rec = a.optJSONObject(i);
                        if (rec != null) panel.addView(Ui.mono(ctx,
                                "  · " + rec.optString("kind") + ": " + rec.optString("content"), 11, Theme.MUTED));
                    }
                }
            } else {
                panel.addView(Ui.text(ctx, "Not NDEF formatted (raw tag).", 11, Theme.MUTED, false));
            }
        }
        Ui.spacer(ctx, panel, 6);
        panel.addView(Ui.text(ctx,
                "Phase 1 is read-only. The owned-tag NDEF writer (explicit confirmation + read-back) "
                        + "is gated for a later phase. Unknown/access tags are never written.",
                10, Theme.MUTED, false));
        return panel;
    }

    // ---------------- UHF (locked) ----------------

    private static LinearLayout uhfPanel(Context ctx) {
        LinearLayout panel = Ui.card(ctx);
        panel.addView(Ui.text(ctx, "UHF / DMR — LOCKED", 14, Theme.MUTED, true));
        panel.addView(Ui.text(ctx,
                "The built-in UHF module is exclusively owned by RumiTX (com.rumitx.app) via "
                        + "/dev/ttyMT1 and vendor HIDL. Spectrum Lab will never open those interfaces. "
                        + "Read-only telemetry becomes possible only after an authenticated RumiTX "
                        + "broker contract exists (docs/09).",
                12, Theme.FG, false));
        Ui.spacer(ctx, panel, 4);
        panel.addView(Ui.text(ctx,
                "This app cannot and will not: tune sub-GHz bands, decode arbitrary remotes, "
                        + "capture raw I/Q, transmit, or disrupt RumiTX TX/RX.",
                11, Theme.MUTED, false));
        return panel;
    }

    private static int rssiColor(int rssi) {
        if (rssi >= -60) return Theme.GREEN;
        if (rssi >= -75) return Theme.ACCENT;
        return Theme.MUTED;
    }

    private static class GradientBg {
        final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        void setColor(int c) { bg.setColor(c); }
        void setCornerRadius(int r) { bg.setCornerRadius(r); }
    }
}
