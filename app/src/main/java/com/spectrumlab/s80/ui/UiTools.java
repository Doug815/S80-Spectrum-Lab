package com.spectrumlab.s80.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.core.CapabilityRegistry;
import com.spectrumlab.s80.core.SessionController;
import com.spectrumlab.s80.core.Theme;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * TOOLS (docs/05 view 05): capability inspector with evidence, permission status,
 * retention + deletion, gated-module explanations, About. No switch here can
 * silently trigger a privileged write.
 */
public final class UiTools {

    private static volatile List<String[]> evidence = new ArrayList<>();
    private static volatile boolean loading;

    public static LinearLayout build(Context ctx) {
        App app = App.get(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 14);
        root.setPadding(pad, Ui.dp(ctx, 8), pad, Ui.dp(ctx, 14));

        root.addView(capabilityCard(ctx, app));
        Ui.spacer(ctx, root, 4);
        root.addView(permissionCard(ctx, app));
        Ui.spacer(ctx, root, 4);
        root.addView(dataCard(ctx, app));
        Ui.spacer(ctx, root, 4);
        root.addView(gatedCard(ctx));        Ui.spacer(ctx, root, 4);
        root.addView(aboutCard(ctx, app));
        return root;
    }

    // ---------- capability inspector ----------

    private static LinearLayout capabilityCard(Context ctx, App app) {
        LinearLayout card = Ui.card(ctx);
        card.addView(Ui.label(ctx, "CAPABILITY INSPECTOR", 11, Theme.ACCENT));
        Button reprobe = Ui.button(ctx, "RE-PROBE HARDWARE", Theme.SURFACE2, v -> {
            new Thread(() -> {
                app.capabilities().probeAllAndStore();
                loadEvidence(ctx);
            }, "slab-probe").start();
            Toast.makeText(ctx, "Probing…", Toast.LENGTH_SHORT).show();
        });
        card.addView(reprobe);
        Ui.spacer(ctx, card, 6);

        if (!loading && evidence.isEmpty()) {
            loading = true;
            new Thread(() -> {
                app.capabilities().probeAllAndStore(); // first run: populate
                loadEvidence(ctx);
            }, "slab-probe-init").start();
        }
        List<String[]> ev = evidence;
        if (ev.isEmpty()) {
            card.addView(Ui.text(ctx, "Probing hardware…", 12, Theme.MUTED, false));
        } else {
            for (String[] row : ev) {
                LinearLayout item = Ui.row(ctx);
                item.addView(Ui.mono(ctx, String.format("%-20s", row[0]), 12, Theme.FG));
                item.addView(Ui.text(ctx, stateText(row[1]), 12, stateColor(row[1]), true));
                card.addView(item);
                JSONObject j = parse(row[3]);
                String method = j.optString("method", j.optString("reason", ""));
                if (!method.isEmpty()) {
                    card.addView(Ui.text(ctx, "  " + method, 10, Theme.MUTED, false));
                }
            }
            card.addView(Ui.text(ctx, "States: DETECTED = present, VERIFIED = observed real data on-device, "
                    + "UNVERIFIED = gated pending evidence, NOT_PRESENT = hardware absent.", 10, Theme.MUTED, false));
        }
        return card;
    }

    private static void loadEvidence(Context ctx) {
        List<String[]> rows = new ArrayList<>();
        try { rows = App.get(ctx).db().listCapabilityEvidence(); }
        catch (Exception ignored) {}
        final List<String[]> done = rows;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            evidence = done;
            loading = false;
            App.get(ctx).bus().publish();
        });
    }

    private static JSONObject parse(String s) {
        try { return new JSONObject(s); } catch (Exception e) { return new JSONObject(); }
    }

    // ---------- permissions ----------

    private static LinearLayout permissionCard(Context ctx, App app) {
        LinearLayout card = Ui.card(ctx);
        card.addView(Ui.label(ctx, "PERMISSIONS & RADIOS", 11, Theme.ACCENT));
        boolean locPerm = App.hasPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION);
        CapabilityRegistry caps = app.capabilities();
        card.addView(Ui.mono(ctx, "location perm : " + (locPerm ? "GRANTED" : "DENIED")
                + "  · location svc: " + (caps.locationServicesOn() ? "ON" : "OFF"), 12,
                locPerm ? Theme.FG : Theme.AMBER));
        card.addView(Ui.mono(ctx, "wifi: " + (caps.wifiAdapterOn() ? "ON" : "OFF")
                + "  · bluetooth: " + (caps.bleAdapterOn() ? "ON" : "OFF")
                + "  · nfc: " + (caps.nfcAdapterOn() ? "ON" : "OFF"), 12, Theme.FG));
        Ui.spacer(ctx, card, 6);
        LinearLayout row = Ui.row(ctx);
        Button appSettings = Ui.button(ctx, "APP SETTINGS", Theme.SURFACE2, v ->
                ctx.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", "com.spectrumlab.s80", null))));
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, -2, 1f);
        p1.setMargins(0, 0, Ui.dp(ctx, 4), 0);
        appSettings.setLayoutParams(p1);
        row.addView(appSettings);
        Button locSettings = Ui.button(ctx, "LOCATION SETTINGS", Theme.SURFACE2, v ->
                ctx.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, -2, 1f);
        p2.setMargins(Ui.dp(ctx, 4), 0, 0, 0);
        locSettings.setLayoutParams(p2);
        row.addView(locSettings);
        card.addView(row);
        return card;
    }

    // ---------- data / retention ----------

    private static LinearLayout dataCard(Context ctx, App app) {
        final SessionController sc = app.sessions();
        LinearLayout card = Ui.card(ctx);
        card.addView(Ui.label(ctx, "DATA & RETENTION", 11, Theme.ACCENT));
        card.addView(Ui.text(ctx, "Retention: observations older than the selected window are purged "
                + "at app start. Everything lives in app-private storage.", 11, Theme.MUTED, false));
        Ui.spacer(ctx, card, 6);
        final int[] options = {1, 7, 30, 90};
        LinearLayout row = Ui.row(ctx);
        int current = sc.getRetentionDays();
        for (final int days : options) {
            TextView opt = Ui.chip(ctx, days + "d", days == current ? Theme.ACCENT : Theme.SURFACE,
                    days == current ? Theme.BG : Theme.MUTED);
            opt.setOnClickListener(v -> {
                sc.setRetentionDays(days);
                Toast.makeText(ctx, "Retention set to " + days + " days", Toast.LENGTH_SHORT).show();
                App.get(ctx).bus().publish();
            });
            row.addView(opt);
        }
        card.addView(row);
        Ui.spacer(ctx, card, 6);
        Button delete = Ui.button(ctx, "DELETE ALL LOCAL DATA", Theme.RED, v ->
                new android.app.AlertDialog.Builder(ctx)
                        .setTitle("Delete all observations?")
                        .setMessage("Permanently removes every session, observation and export record "
                                + "from this device. RumiTX is not touched.")
                        .setPositiveButton("Delete", (d, w) -> sc.deleteAllDataAsync(() ->
                                Toast.makeText(ctx, "All local data deleted.", Toast.LENGTH_SHORT).show()))
                        .setNegativeButton("Cancel", null)
                        .show());
        card.addView(delete);
        card.addView(Ui.mono(ctx, "dropped events (backpressure): " + sc.droppedCount(), 10, Theme.MUTED));
        return card;
    }

    // ---------- gated modules ----------

    private static LinearLayout gatedCard(Context ctx) {
        LinearLayout card = Ui.card(ctx);
        card.addView(Ui.label(ctx, "GATED / NOT PRESENT MODULES", 11, Theme.ACCENT));
        gated(ctx, card, "UHF / DMR telemetry", "UNVERIFIED",
                "Awaiting authenticated read-only RumiTX broker. Never touching /dev/ttyMT1 or vendor HIDL.");
        gated(ctx, card, "Wi-Fi monitor mode", "UNVERIFIED",
                "Depends on driver nl80211 support; root does not add it. Disabled until verified.");
        gated(ctx, card, "Infrared", "NOT_PRESENT",
                "No ConsumerIrManager feature on this handset — no IR blaster exists.");
        gated(ctx, card, "125 kHz RFID", "NOT_PRESENT",
                "Phone NFC is 13.56 MHz only; no LF coil. Education simulator only, no fake Read button.");
        gated(ctx, card, "iButton / 1-Wire", "NOT_PRESENT",
                "No physical 1-Wire contact circuitry.");
        gated(ctx, card, "NFC HCE", "RESEARCH",
                "Feature reported present; synthetic lab AID only in a later phase. No credential emulation.");
        return card;
    }

    private static void gated(Context ctx, LinearLayout card, String name, String state, String why) {
        card.addView(Ui.text(ctx, name + " — " + state, 12,
                "NOT_PRESENT".equals(state) ? Theme.MUTED : Theme.AMBER, true));
        card.addView(Ui.text(ctx, "  " + why, 10, Theme.MUTED, false));
    }

    // ---------- about ----------

    private static LinearLayout aboutCard(Context ctx, App app) {
        LinearLayout card = Ui.card(ctx);
        card.addView(Ui.label(ctx, "ABOUT", 11, Theme.ACCENT));
        card.addView(Ui.mono(ctx, "S80 Spectrum Lab " + App.appVersion()
                + " · com.spectrumlab.s80", 12, Theme.FG));
        card.addView(Ui.text(ctx,
                "Offline-first field diagnostics. No INTERNET permission, no analytics, no cloud. "
                        + "No root commands are executed by this app. Separate from RumiTX — "
                        + "different package, different signature, shared hardware respected.",
                11, Theme.MUTED, false));
        card.addView(Ui.mono(ctx, "firmware: " + app.sessions().fingerprintHash(), 10, Theme.MUTED));
        return card;
    }

    private static String stateText(String state) {
        return state;
    }

    private static int stateColor(String state) {
        switch (state) {
            case "VERIFIED": return Theme.GREEN;
            case "DETECTED": return Theme.ACCENT;
            case "UNVERIFIED": return Theme.AMBER;
            default: return Theme.MUTED; // NOT_PRESENT is a hardware fact, not a fault
        }
    }
}
