package com.spectrumlab.s80.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.MainActivity;
import com.spectrumlab.s80.core.Exporter;
import com.spectrumlab.s80.core.Observation;
import com.spectrumlab.s80.core.SessionController;
import com.spectrumlab.s80.core.Theme;
import com.spectrumlab.s80.db.EventDb;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * LOG · Event timeline + export wizard (docs/05 view 04): filterable session events,
 * detail inspection, deliberate JSONL/CSV(/GeoJSON)+manifest export via SAF.
 */
public final class UiLog {
    private static final String[] FILTERS = {"ALL", "WIFI", "BLE", "NFC", "GPS", "SYSTEM"};
    private static volatile String filter = "ALL";
    private static volatile List<Observation> cached = new ArrayList<>();
    private static volatile String loadedFor = "";
    private static volatile boolean loading;

    public static LinearLayout build(Context ctx) {
        App app = App.get(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 14);
        root.setPadding(pad, Ui.dp(ctx, 8), pad, Ui.dp(ctx, 14));

        // filter chips
        android.widget.HorizontalScrollView chips = Ui.chipRow(ctx);
        LinearLayout chipRow = (LinearLayout) chips.getTag();
        for (String f : FILTERS) {
            boolean active = f.equals(filter);
            chipRow.addView(Ui.chip(ctx, f, active ? Theme.ACCENT : Theme.SURFACE,
                    active ? Theme.BG : Theme.MUTED));
            chipRow.getChildAt(chipRow.getChildCount() - 1).setOnClickListener(v -> {
                filter = f;
                loadedFor = ""; // force reload
                appRefresh(ctx);
            });
        }
        root.addView(chips);
        Ui.spacer(ctx, root, 6);

        // async (re)load
        String key = filter + "|" + app.sessions().sessionId();
        if (!loading && !key.equals(loadedFor)) {
            loading = true;
            final String k = key;
            new Thread(() -> {
                List<Observation> obs;
                try {
                    String sensor = "ALL".equals(filter) ? null : filter.toLowerCase();
                    String session = App.get(ctx).sessions().sessionId();
                    if (session != null) {
                        obs = App.get(ctx).db().listObservations(session, sensor, 100);
                    } else {
                        obs = latestSessionObservations(ctx, sensor);
                    }
                } catch (Exception e) { obs = new ArrayList<>(); }
                final List<Observation> done = obs;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    cached = done; loadedFor = k; loading = false;
                    App.get(ctx).bus().publish();
                });
            }, "slab-log").start();
        }

        // event list
        List<Observation> obs = cached;
        LinearLayout list = Ui.card(ctx);
        list.addView(Ui.mono(ctx, (app.sessions().isActive() ? "ACTIVE session · " : "latest session · ")
                + obs.size() + " events shown (max 100)", 11, Theme.ACCENT));
        Ui.spacer(ctx, list, 4);
        if (obs.isEmpty()) {
            list.addView(Ui.text(ctx, "No stored events for this filter yet.", 12, Theme.MUTED, false));
        }
        for (final Observation o : obs) {
            LinearLayout row = Ui.row(ctx);
            row.setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3));
            row.addView(Ui.mono(ctx, o.timestampUtc.substring(11, 19), 11, Theme.MUTED));
            row.addView(Ui.text(ctx, " " + o.sensor.toUpperCase() + " ", 11, sensorColor(o.sensor), true));
            TextView t = Ui.text(ctx, shorten(o.type, 46), 12, Theme.FG, false);
            row.addView(t);
            row.setOnClickListener(v -> showDetail(ctx, o));
            list.addView(row);
        }
        root.addView(list);

        // export wizard
        Ui.spacer(ctx, root, 8);
        LinearLayout export = Ui.card(ctx);
        export.addView(Ui.label(ctx, "EXPORT WIZARD", 11, Theme.ACCENT));
        export.addView(Ui.text(ctx,
                "Writes spectrumlab-<session>.jsonl + .csv (+ .geojson when the session opted into GPS) "
                        + "and a SHA-256 manifest, to a folder you choose. Identifiers are already "
                        + "masked at capture. Export is manual — nothing is ever auto-shared.",
                11, Theme.MUTED, false));
        Ui.spacer(ctx, export, 6);
        Button pick = Ui.button(ctx, "EXPORT LATEST SESSION…", Theme.SURFACE2, v ->
                ((Activity) ctx).startActivityForResult(
                        new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), MainActivity.REQ_EXPORT_TREE));
        export.addView(pick);
        root.addView(export);
        return root;
    }

    public static void onTreeChosen(Context ctx, Uri treeUri) {
        App app = App.get(ctx);
        List<EventDb.SessionInfo> sessions = app.db().listSessions(500);
        EventDb.SessionInfo target = null;
        for (EventDb.SessionInfo s : sessions) { target = s; break; } // latest
        if (target == null) { Toast.makeText(ctx, "No sessions to export.", Toast.LENGTH_SHORT).show(); return; }
        final EventDb.SessionInfo session = target;
        Toast.makeText(ctx, "Exporting session…", Toast.LENGTH_SHORT).show();
        Exporter.exportSessionAsync(ctx, treeUri, session.id, new Exporter.Progress() {
            @Override public void onDone(Exporter.ExportResult r) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Export complete.\n\n");
                    for (int i = 0; i < r.fileNames.size(); i++) {
                        sb.append(r.fileNames.get(i)).append("\nsha256: ")
                                .append(r.fileSha256.get(i)).append("\n\n");
                    }
                    sb.append("Observations: ").append(r.observationCount)
                            .append(" · manifest sha256: ").append(r.manifestSha256);
                    new android.app.AlertDialog.Builder(ctx)
                            .setTitle("Export result")
                            .setMessage(sb.toString())
                            .setPositiveButton("OK", null)
                            .show();
                    App.get(ctx).sessions().systemEvent("system.export_completed",
                            new com.spectrumlab.s80.core.SafeJson()
                                    .put("files", r.fileNames.size())
                                    .put("observations", r.observationCount)
                                    .put("manifest_sha256", r.manifestSha256)
                                    .put("geojson", r.geojsonWritten)
                                    .put("summary", "Exported " + r.observationCount + " observations, "
                                            + r.fileNames.size() + " files"));
                });
            }
            @Override public void onError(String message) {
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> new android.app.AlertDialog.Builder(ctx)
                        .setTitle("Export failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show());
            }
        });
    }

    private static List<Observation> latestSessionObservations(Context ctx, String sensor) {
        App app = App.get(ctx);
        List<EventDb.SessionInfo> sessions = app.db().listSessions(500);
        for (EventDb.SessionInfo s : sessions) {
            return app.db().listObservations(s.id, sensor, 100); // latest session only
        }
        return new ArrayList<>();
    }

    private static void showDetail(Context ctx, Observation o) {
        String pretty;
        try {
            JSONObject e = new JSONObject();
            e.put("event_id", o.id);
            e.put("session_id", o.sessionId);
            e.put("timestamp_utc", o.timestampUtc);
            e.put("elapsed_realtime_ns", o.elapsedRealtimeNs);
            e.put("sensor", o.sensor);
            e.put("type", o.type);
            e.put("source_kind", o.sourceKind);
            e.put("synthetic", o.synthetic);
            e.put("confidence", o.confidence);
            e.put("privacy", o.privacy);
            e.put("payload", new JSONObject(o.payloadJson));
            pretty = e.toString(2);
            if (pretty.length() > 4000) pretty = pretty.substring(0, 4000) + "\n…(capped)";
        } catch (Exception e) {
            pretty = o.payloadJson;
        }
        new android.app.AlertDialog.Builder(ctx)
                .setTitle(o.type)
                .setMessage(pretty)
                .setPositiveButton("OK", null)
                .show();
    }

    private static void appRefresh(Context ctx) { App.get(ctx).bus().publish(); }

    private static int sensorColor(String sensor) {
        switch (sensor) {
            case "wifi": return Theme.ACCENT;
            case "ble": return Theme.INDIGO;
            case "nfc": return Theme.GREEN;
            case "gps": return Theme.AMBER;
            default: return Theme.MUTED;
        }
    }

    private static String shorten(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n - 1) + "…");
    }
}
