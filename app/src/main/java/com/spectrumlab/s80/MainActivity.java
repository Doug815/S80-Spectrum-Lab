package com.spectrumlab.s80;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.spectrumlab.s80.core.Theme;
import com.spectrumlab.s80.ui.Ui;
import com.spectrumlab.s80.ui.UiHome;
import com.spectrumlab.s80.ui.UiLog;
import com.spectrumlab.s80.ui.UiMap;
import com.spectrumlab.s80.ui.UiScan;
import com.spectrumlab.s80.ui.UiTools;

/**
 * Single-activity shell: compact header, content area, anchored 5-tab nav
 * (HOME · SCAN · MAP · LOG · TOOLS). Programmatic dark electric-blue UI so the
 * app ships with zero third-party dependencies. Rotation is handled without
 * recreation (configChanges) so scans are not orphaned (T014).
 */
public class MainActivity extends Activity implements com.spectrumlab.s80.core.EventBus.Listener {
    public static final int REQ_LOCATION = 41;
    public static final int REQ_EXPORT_TREE = 42;

    private static final String[] TABS = {"HOME", "SCAN", "MAP", "LOG", "TOOLS"};
    private FrameLayout content;
    private LinearLayout tabBar;
    private TextView headerStatus;
    private int currentTab = 0;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        App app = App.get(this);
        getWindow().setStatusBarColor(Theme.BG);
        getWindow().setNavigationBarColor(Theme.BG);
        buildShell();
        app.bus().setListener(this);
        Log.i(App.LOG_TAG, "MainActivity created");
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);

        // compact header (docs/05: 48-60dp, list gets priority)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 6));
        LinearLayout titleRow = Ui.row(this);
        TextView title = Ui.text(this, "S80 SPECTRUM LAB", 17, Theme.FG, true);
        titleRow.addView(title);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        title.setLayoutParams(tp);
        headerStatus = Ui.mono(this, "", 11, Theme.ACCENT);
        titleRow.addView(headerStatus);
        header.addView(titleRow);
        root.addView(header);

        // content
        content = new FrameLayout(this);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1f);
        content.setLayoutParams(cp);
        root.addView(content);

        // bottom nav
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, Ui.dp(this, 52));
        tabBar.setLayoutParams(np);
        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView tab = Ui.text(this, TABS[i], 13, Theme.MUTED, true);
            tab.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            tab.setLayoutParams(lp);
            tab.setOnClickListener(v -> showTab(idx));
            tabBar.addView(tab);
        }
        root.addView(tabBar);
        setContentView(root);
        showTab(0);
    }

    private void showTab(int index) {
        currentTab = index;
        refresh();
    }

    /** Rebuild the active tab. Called by the throttled EventBus and tab switches (max 4 Hz). */
    @Override
    public void onUiRefreshHint() {
        if (resumed || currentTab >= 0) refresh();
    }

    private void refresh() {
        App app = App.get(this);
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            TextView t = (TextView) tabBar.getChildAt(i);
            t.setTextColor(i == currentTab ? Theme.ACCENT : Theme.MUTED);
            t.setBackgroundColor(i == currentTab ? Theme.SURFACE : Theme.BG);
        }
        boolean active = app.sessions().isActive();
        headerStatus.setText(active
                ? "● " + app.sessions().sessionName() + " · " + app.sessions().sessionElapsedSeconds() + "s"
                : "○ idle");

        // Preserve the user's scroll across event-driven rebuilds so long lists
        // (LOG/TOOLS) stay reachable while a session streams events.
        int savedScroll = 0;
        if (content.getChildCount() > 0) {
            View child = content.getChildAt(0);
            if (child instanceof ScrollView) savedScroll = ((ScrollView) child).getScrollY();
        }
        content.removeAllViews();
        ScrollView sc = new ScrollView(this);
        sc.setFillViewport(true);
        sc.setBackgroundColor(Theme.BG);
        try {
            switch (currentTab) {
                case 1: sc.addView(UiScan.build(this)); break;
                case 2: sc.addView(UiMap.build(this)); break;
                case 3: sc.addView(UiLog.build(this)); break;
                case 4: sc.addView(UiTools.build(this)); break;
                default: sc.addView(UiHome.build(this)); break;
            }
        } catch (Exception e) {
            Log.e(App.LOG_TAG, "tab build failed", e);
            LinearLayout err = new LinearLayout(this);
            err.setOrientation(LinearLayout.VERTICAL);
            err.setPadding(Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20), Ui.dp(this, 20));
            err.addView(Ui.text(this, "UI error: " + e.getClass().getSimpleName()
                    + "\n(safe — no data lost)", 13, Theme.RED, false));
            sc.addView(err);
        }
        content.addView(sc);
        final int restore = savedScroll;
        if (restore > 0) {
            sc.post(() -> {
                try { sc.scrollTo(0, restore); } catch (Exception ignored) {}
            });
        }
    }

    // ---------- runtime permission ----------

    public boolean ensureLocationPermission(String purpose) {
        if (App.hasPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)) return true;
        new AlertDialog.Builder(this)
                .setTitle("Location permission needed")
                .setMessage(purpose + "\n\nAndroid 8.1 requires location permission for nearby "
                        + "Wi-Fi/BLE discovery (that is an OS rule, not this app's choice). GPS "
                        + "tagging stays OFF unless you opt in per session.")
                .setNegativeButton("Not now", (d, w) -> Log.i(App.LOG_TAG, "location permission declined"))
                .setPositiveButton("Grant", (d, w) ->
                        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION))
                .show();
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "Location permission granted."
                    : "Location permission denied — Wi-Fi/BLE results stay gated.", Toast.LENGTH_SHORT).show();
            App.get(this).sessions().systemEvent("system.permission_result", new com.spectrumlab.s80.core.SafeJson()
                    .put("permission", "ACCESS_FINE_LOCATION")
                    .put("granted", granted)
                    .put("summary", "location permission " + (granted ? "granted" : "denied")));
            refresh();
        }
    }

    // ---------- SAF export result ----------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
            UiLog.onTreeChosen(this, tree);
        }
    }

    // ---------- lifecycle: no orphaned scans, no silent collection ----------

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        App app = App.get(this);
        if (app.sessions().isActive()) {
            app.nfc().armReader(this); // reader mode is foreground-only by design
        }
        refresh();
    }

    @Override
    protected void onPause() {
        resumed = false;
        App app = App.get(this);
        app.nfc().disarmReader(this);
        app.ble().forceStopForBackground(); // T008: no scanning while not visible
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        App app = App.get(this);
        app.bus().setListener(null);
        app.nfc().disarmReader(this);
        // Session (if any) keeps running with its visible notification; it is
        // stopped explicitly from the UI or battery guard. No phantom UI workers here.
        super.onDestroy();
    }
}
