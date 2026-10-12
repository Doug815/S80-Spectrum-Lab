package com.spectrumlab.s80.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.widget.LinearLayout;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.core.Observation;
import com.spectrumlab.s80.core.Theme;
import com.spectrumlab.s80.db.EventDb;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * MAP · Survey map (docs/05 view 03, Phase 1 subset): offline coordinate grid of
 * OBSERVED opt-in GPS points only. No tiles, no interpolation, no triangulation
 * claims. RSSI ≠ distance legend stays visible.
 */
public final class UiMap {

    /** Latest cached GPS points loaded async (main-thread read only). */
    private static volatile List<double[]> points = new ArrayList<>(); // {lat, lon}
    private static volatile String pointsSession = "";
    private static volatile boolean loading;

    public static LinearLayout build(Context ctx) {
        App app = App.get(ctx);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(ctx, 14);
        root.setPadding(pad, Ui.dp(ctx, 8), pad, Ui.dp(ctx, 14));

        String activeSession = app.sessions().sessionId();
        String target = activeSession != null ? activeSession : latestGpsSession(app);
        Ui.label(ctx, "OBSERVED GPS POINTS · OFFLINE GRID", 11, Theme.ACCENT)
                .setPadding(0, 0, 0, Ui.dp(ctx, 4));
        root.addView(Ui.text(ctx,
                "No map tiles: local coordinate grid of opt-in fixes only. Observed points, "
                        + "never interpolated coverage. RSSI is signal, not distance or identity.",
                11, Theme.MUTED, false));

        if (target == null) {
            Ui.spacer(ctx, root, 8);
            LinearLayout none = Ui.card(ctx);
            none.addView(Ui.text(ctx, "NO GPS DATA YET", 12, Theme.AMBER, true));
            none.addView(Ui.text(ctx,
                    "Start a session with the GPS opt-in checkbox ON to collect points. "
                            + "Coordinates are stored only for opted-in sessions.", 12, Theme.MUTED, false));
            root.addView(none);
            return root;
        }

        if (!loading && !target.equals(pointsSession)) {
            loading = true;
            final String sid = target;
            new Thread(() -> {
                List<double[]> pts = new ArrayList<>();
                try {
                    for (Observation o : App.get(ctx).db().listObservationsBySensor("gps", 500)) {
                        if (!o.sessionId.equals(sid)) continue;
                        JSONObject p = o.payload();
                        if (p.has("latitude") && p.has("longitude")) {
                            pts.add(new double[]{p.optDouble("latitude"), p.optDouble("longitude")});
                        }
                    }
                } catch (Exception ignored) {}
                final List<double[]> done = pts;
                android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
                main.post(() -> {
                    points = done;
                    pointsSession = sid;
                    loading = false;
                    App.get(ctx).bus().publish();
                });
            }, "slab-map").start();
        }

        Ui.spacer(ctx, root, 8);
        List<double[]> pts = points;
        if (target.equals(pointsSession) && !pts.isEmpty()) {
            root.addView(Ui.mono(ctx, pointsSession.substring(0, 8) + " · " + pts.size() + " points", 12, Theme.FG));
            root.addView(new PointGrid(ctx, pts));
        } else {
            LinearLayout pending = Ui.card(ctx);
            pending.addView(Ui.text(ctx, loading ? "Loading points…" : "No points recorded for this session yet.",
                    12, Theme.MUTED, false));
            root.addView(pending);
        }
        return root;
    }

    private static String latestGpsSession(App app) {
        for (EventDb.SessionInfo s : app.sessions().recentSessions(50)) {
            if (s.locationOptIn) return s.id;
        }
        return null;
    }

    /** Simple scatter: min/max-normalized lat/lon on a framed grid. */
    private static final class PointGrid extends View {
        private final List<double[]> pts;
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint point = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        PointGrid(Context c, List<double[]> pts) {
            super(c);
            this.pts = pts;
            grid.setColor(Theme.SURFACE2);
            grid.setStrokeWidth(1);
            point.setColor(Theme.ACCENT);
            text.setColor(Theme.MUTED);
            text.setTextSize(24);
            setBackgroundColor(Theme.SURFACE);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            int pad = 24;
            for (int x = pad; x <= w - pad; x += (w - 2 * pad) / 6.0) canvas.drawLine(x, pad, x, h - pad, grid);
            for (int y = pad; y <= h - pad; y += (h - 2 * pad) / 6.0) canvas.drawLine(pad, y, w - pad, y, grid);

            double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
            double minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
            for (double[] p : pts) {
                minLat = Math.min(minLat, p[0]); maxLat = Math.max(maxLat, p[0]);
                minLon = Math.min(minLon, p[1]); maxLon = Math.max(maxLon, p[1]);
            }
            double latSpan = Math.max(maxLat - minLat, 1e-6);
            double lonSpan = Math.max(maxLon - minLon, 1e-6);
            for (double[] p : pts) {
                float x = (float) (pad + (p[1] - minLon) / lonSpan * (w - 2 * pad));
                float y = (float) (h - pad - (p[0] - minLat) / latSpan * (h - 2 * pad));
                canvas.drawCircle(x, y, 5, point);
            }
            canvas.drawText(String.format(java.util.Locale.US, "lat %.5f…%.5f", minLat, maxLat), pad, h - 6, text);
            canvas.drawText(String.format(java.util.Locale.US, "lon %.5f…%.5f", minLon, maxLon), pad, 20, text);
            canvas.drawText("OBSERVED ONLY", w - 200, 20, text);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(View.getDefaultSize(getSuggestedMinimumWidth(), widthSpec),
                    Ui.dp(getContext(), 260));
        }
    }
}
