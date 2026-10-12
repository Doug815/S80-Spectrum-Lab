package com.spectrumlab.s80.core;

import org.json.JSONObject;

/**
 * JSONObject subclass whose put() never throws (guards NaN/Infinity by skipping).
 * Keeps sensor/adapter call sites readable; malformed keys are a programming error
 * and are logged rather than crashing a scan thread.
 */
public class SafeJson extends JSONObject {
    @Override
    public SafeJson put(String key, Object value) {
        if (value instanceof Double && (Double.isNaN((Double) value) || Double.isInfinite((Double) value))) {
            return this; // refuse invalid numbers silently (callers guard GPS values)
        }
        if (value instanceof Float && (Float.isNaN((Float) value) || Float.isInfinite((Float) value))) {
            return this;
        }
        try {
            super.put(key, value);
        } catch (Exception ignored) {}
        return this;
    }

    // JSONObject has primitive put overloads returning JSONObject; override them so
    // builder chains stay typed as SafeJson.
    @Override public SafeJson put(String key, int value) {
        try { super.put(key, value); } catch (Exception ignored) {}
        return this;
    }
    @Override public SafeJson put(String key, long value) {
        try { super.put(key, value); } catch (Exception ignored) {}
        return this;
    }
    @Override public SafeJson put(String key, boolean value) {
        try { super.put(key, value); } catch (Exception ignored) {}
        return this;
    }
    @Override public SafeJson put(String key, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return this;
        try { super.put(key, value); } catch (Exception ignored) {}
        return this;
    }

    /** Convenience: put a value only when condition is true. */
    public SafeJson putIf(boolean cond, String key, Object value) {
        return cond ? put(key, value) : this;
    }
}
