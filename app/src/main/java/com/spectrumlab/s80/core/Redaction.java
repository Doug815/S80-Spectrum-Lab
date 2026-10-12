package com.spectrumlab.s80.core;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Identifier redaction (docs/06, docs/07):
 * - Default policy mask_ids_by_default: BSSID/MAC/UID are never stored raw.
 * - Display keeps only the manufacturer OUI (first 3 octets) of MAC-like addresses.
 * - A per-install random HMAC key provides a stable *within-session* pseudonym
 *   (short tag) for dedup/merge without keeping the raw identifier. The key never
 *   leaves app-private storage; rotating it intentionally breaks cross-session identity.
 */
public final class Redaction {
    private final byte[] key;

    public Redaction(Context context) {
        this.key = loadOrCreateKey(new File(context.getFilesDir(), ".redaction.key"));
    }

    private static byte[] loadOrCreateKey(File f) {
        try {
            if (f.exists() && f.length() == 32) {
                byte[] k = new byte[32];
                try (FileInputStream in = new FileInputStream(f)) {
                    if (in.read(k) != 32) throw new IllegalStateException("short key");
                }
                return k;
            }
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(k);
            }
            return k;
        } catch (Exception e) {
            // Deterministic fallback so redaction never silently breaks; still per-boot random.
            byte[] k = new byte[32];
            new SecureRandom().nextBytes(k);
            return k;
        }
    }

    /** Stable short pseudonym tag for an identifier (hex, 8 chars). */
    public String tag(String identifier) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal(identifier.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "--------".substring(0, 8);
        }
    }

    /**
     * Display-safe mask for MAC-like strings AA:BB:CC:DD:EE:FF -> AA:BB:CC:xx:xx:xx.
     * Non-MAC inputs fall back to a full mask.
     */
    public static String maskMac(String mac) {
        if (mac == null) return "(unknown)";
        String[] parts = mac.split(":");
        if (parts.length == 6) {
            return parts[0] + ":" + parts[1] + ":" + parts[2] + ":xx:xx:xx";
        }
        return "(masked)";
    }

    /** NFC UID: mask fully (never retain raw UID), keep only length + tag. */
    public String maskUid(byte[] uid) {
        if (uid == null || uid.length == 0) return "(none)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < uid.length; i++) sb.append("xx").append(i < uid.length - 1 ? ":" : "");
        return sb + "#" + tag(hex(uid));
    }

    /** Sanitize untrusted broadcast strings for display/storage: strip control chars, cap length. */
    public static String sanitize(String raw, int maxLen) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), maxLen));
        for (int i = 0; i < raw.length() && sb.length() < maxLen; i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7F) sb.append(c);
        }
        return sb.toString().trim();
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }

    public static String hex(byte[] data) {
        if (data == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
