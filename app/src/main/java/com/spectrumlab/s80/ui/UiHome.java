package com.spectrumlab.s80.ui;

import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.core.CapabilityRegistry;
import com.spectrumlab.s80.core.SessionController;
import com.spectrumlab.s80.core.Theme;

/**
 * HOME · Mission Control (docs/05 view 01): capability strip, session control,
 * live cards, recent events. Em dash for not-observed, never fake numbers.
 */
public final class UiHome {

    public static LinearLayout build(Context ctx) {
        App app = App.get(ctx);
        SessionController sc = app.sessions();
        CapabilityRegistry caps = app.capabilities();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 14);
        root.setPadding(pad, Ui.dp(ctx, 8), pad, Ui.dp(ctx, 14));

        // --- capability strip (live states, not marketing claims) ---
        Ui.label(ctx, "CAPABILITY STATUS", 11, Theme.ACCENT).setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4));
        root.addView(chipRow(ctx,
                chipCap(ctx, "WIFI", caps.wifiAdapterOn()),
                chipCap(ctx, "BLE", caps.bleAdapterOn()),
                chipCap(ctx, "NFC", caps.nfcAdapterOn()),
                chipCap(ctx, "GPS", App.hasPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
                        && caps.locationServicesOn()),
                chipLocked(ctx, "UHF")));

        Ui.spacer(ctx, root, 6);

        // --- session control ---
        LinearLayout sessionCard = Ui.card(ctx);
        if (sc.isActive()) {
            sessionCard.addView(Ui.text(ctx, "SESSION ACTIVE", 11, Theme.GREEN, true));
            sessionCard.addView(Ui.mono(ctx, sc.sessionName() + "  ·  " + sc.sessionElapsedSeconds() + "s", 13, Theme.FG));
            sessionCard.addView(Ui.text(ctx,
                    "GPS tagging: " + (sc.gpsOptIn() ? "ON (opted in)" : "OFF (not opted in)")
                            + " · redaction: " + SessionController.REDACTION_POLICY, 12, Theme.MUTED, false));
            Ui.spacer(ctx, sessionCard, 6);
            Button stop = Ui.button(ctx, "STOP SESSION", Theme.RED, v -> sc.endSession("USER_STOP"));
            sessionCard.addView(stop);
        } else {
            sessionCard.addView(Ui.text(ctx, "NO ACTIVE SESSION", 11, Theme.AMBER, true));
            sessionCard.addView(Ui.text(ctx,
                    "Passive surveys run inside a session with a visible notification. "
                            + "Wi-Fi scans pace ~30 s; BLE windows are 30 s; GPS is opt-in per session. "
                            + "Nothing is collected with the app idle.", 12, Theme.MUTED, false));
            Ui.spacer(ctx, sessionCard, 6);
            Button start = Ui.button(ctx, "START SURVEY SESSION", Theme.SURFACE2, v ->
                    startSessionDialog(ctx));
            sessionCard.addView(start);
        }
        root.addView(sessionCard);

        // --- live cards 2x2 ---
        Ui.spacer(ctx, root, 4);
        LinearLayout gridRow = Ui.row(ctx);
        gridRow.addView(liveCard(ctx, "Wi-Fi APs", String.valueOf(app.wifi().rowCount()), "session merged"));
        gridRow.addView(liveCard(ctx, "BLE advertisers", String.valueOf(app.ble().rowCount()), "last window"));
        root.addView(gridRow);
        LinearLayout gridRow2 = Ui.row(ctx);
        String nfcTxt = app.nfc().lastTagSummary() == null ? "—" :
                app.nfc().lastTagSummary().optString("technologies", "—");
        gridRow2.addView(liveCard(ctx, "NFC last tag", shorten(nfcTxt, 18), app.nfc().lastTagUtc()));
        gridRow2.addView(liveCard(ctx, "UHF / DMR", "GATED", "awaiting RumiTX broker"));
        root.addView(gridRow2);

        // --- recent events ---
        Ui.spacer(ctx, root, 8);
        Ui.label(ctx, "RECENT EVENTS", 11, Theme.ACCENT).setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 3));
        LinearLayout feed = Ui.card(ctx);
        java.util.List<String[]> recent = sc.recentFeed();
        if (recent.isEmpty()) {
            feed.addView(Ui.text(ctx, "No events yet — start a session.", 12, Theme.MUTED, false));
        } else {
            for (String[] e : recent) {
                LinearLayout r = Ui.row(ctx);
                TextView time = Ui.mono(ctx, e[0], 11, Theme.MUTED);
                r.addView(time);
                TextView tag = Ui.text(ctx, "  " + e[1].toUpperCase() + " ", 11, colorOf(e[1]), true);
                r.addView(tag);
                r.addView(Ui.text(ctx, shorten(e[2], 64), 11, Theme.FG, false));
                feed.addView(r);
            }
        }
        root.addView(feed);

        // --- truth footer ---
        Ui.spacer(ctx, root, 8);
        root.addView(Ui.text(ctx,
                "Offline-first: no INTERNET permission, no telemetry, no cloud. All data local, "
                        + "identifiers masked by default. UHF stays gated until a RumiTX read-only "
                        + "broker exists. No IR / 125 kHz / monitor mode — see TOOLS.",
                11, Theme.MUTED, false));
        return root;
    }

    private static LinearLayout liveCard(Context ctx, String title, String value, String sub) {
        LinearLayout c = Ui.card(ctx);
        c.addView(Ui.text(ctx, title, 11, Theme.MUTED, true));
        c.addView(Ui.mono(ctx, value, 20, Theme.ACCENT));
        c.addView(Ui.text(ctx, sub, 10, Theme.MUTED, false));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(Ui.dp(ctx, 2), 0, Ui.dp(ctx, 2), 0);
        c.setLayoutParams(p);
        return c;
    }

    private static void startSessionDialog(Context ctx) {
        final App app = App.get(ctx);
        final SessionController sc = app.sessions();
        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 18);
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(Ui.text(ctx, "Session name:", 13, Theme.FG, false));
        final android.widget.EditText name = new android.widget.EditText(ctx);
        name.setText(SessionController.defaultName());
        name.setTextColor(Theme.FG);
        layout.addView(name);
        final android.widget.CheckBox gps = new android.widget.CheckBox(ctx);
        gps.setText("Tag observations with GPS location (opt-in)");
        gps.setTextColor(Theme.FG);
        gps.setChecked(false);
        layout.addView(gps);
        layout.addView(Ui.text(ctx,
                "Disclosure: with GPS ON, coordinates are stored locally and included in exports "
                        + "for this session only. Randomized BLE addresses are not identities.",
                11, Theme.MUTED, false));
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("Start survey session")
                .setView(layout)
                .setPositiveButton("Start", (d, w) -> {
                    if (!App.hasPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                        Toast.makeText(ctx, "Location permission is required for Wi-Fi/BLE scans — "
                                + "grant it when prompted, then start the session.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    sc.startSession(name.getText().toString(), gps.isChecked());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static android.widget.HorizontalScrollView chipRow(Context ctx, TextView... chips) {
        android.widget.HorizontalScrollView h = Ui.chipRow(ctx);
        LinearLayout row = (LinearLayout) h.getTag();
        for (TextView c : chips) row.addView(c);
        return h;
    }

    private static TextView chipCap(Context ctx, String cap, boolean on) {
        return Ui.chip(ctx, cap, on ? Theme.SURFACE2 : Theme.BG,
                on ? Theme.GREEN : Theme.AMBER);
    }

    private static TextView chipLocked(Context ctx, String cap) {
        return Ui.chip(ctx, cap + " ⚿", Theme.BG, Theme.MUTED);
    }

    private static int colorOf(String sensor) {
        switch (sensor) {
            case "wifi": return Theme.ACCENT;
            case "ble": return Theme.INDIGO;
            case "nfc": return Theme.GREEN;
            case "gps": return Theme.AMBER;
            default: return Theme.MUTED;
        }
    }

    private static String shorten(String s, int n) {
        if (s == null) return "—";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
