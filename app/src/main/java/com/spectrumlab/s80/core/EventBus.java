package com.spectrumlab.s80.core;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UI refresh notifier. Sensors publish through SessionController; the bus coalesces
 * bursts into at most one main-thread callback per REFRESH_MS (<=4 Hz, docs/05).
 */
public class EventBus {
    public interface Listener { void onUiRefreshHint(); }

    private static final long REFRESH_MS = 250;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private volatile Listener listener;

    public void setListener(Listener l) { listener = l; }

    public void publish() {
        if (!pending.compareAndSet(false, true)) return; // a refresh is already scheduled
        main.postDelayed(() -> {
            pending.set(false);
            Listener l = listener;
            if (l != null) {
                try { l.onUiRefreshHint(); } catch (Exception ignored) {}
            }
        }, REFRESH_MS);
    }
}
