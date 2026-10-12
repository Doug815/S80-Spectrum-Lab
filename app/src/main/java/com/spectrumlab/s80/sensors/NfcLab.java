package com.spectrumlab.s80.sensors;

import android.app.Activity;
import android.content.Context;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.SystemClock;
import android.util.Log;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.core.Redaction;
import com.spectrumlab.s80.core.SafeJson;
import com.spectrumlab.s80.core.SessionController;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * NFC tag laboratory — reader only in Phase 1 (docs/04-D):
 * - Foreground reader mode (NfcAdapter.enableReaderMode), never background polling.
 * - Shows tag technologies, NDEF record summary, capacity; UIDs masked (never retained raw).
 * - NO write functions: the owned-tag writer is a Phase 2 confirmation flow, gated here.
 * - Untrusted NDEF content is capped + sanitized; never executed, never interpreted as HTML.
 */
public class NfcLab {
    private final Context app;
    private final Map<String, Long> lastSeenElapsed = new HashMap<>(); // per-uid-tag debounce
    private volatile boolean readerArmed;
    private volatile SafeJson lastTagSummary;   // sanitized display model
    private volatile String lastTagUtc = "—";
    private volatile String lastError;

    public NfcLab(Context app) { this.app = app.getApplicationContext(); }

    public boolean readerArmed() { return readerArmed; }
    public SafeJson lastTagSummary() { return lastTagSummary; }
    public String lastTagUtc() { return lastTagUtc; }
    public String lastError() { return lastError; }

    public boolean adapterPresent() {
        return NfcAdapter.getDefaultAdapter(app) != null;
    }

    public boolean adapterOn() {
        NfcAdapter n = NfcAdapter.getDefaultAdapter(app);
        return n != null && n.isEnabled();
    }

    public void armReader(Activity activity) {
        NfcAdapter n = NfcAdapter.getDefaultAdapter(app);
        if (n == null || !n.isEnabled()) {
            lastError = "ADAPTER_OFF";
            return;
        }
        try {
            n.enableReaderMode(activity, this::onTagDiscovered,
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NFC_B
                            | NfcAdapter.FLAG_READER_NFC_F | NfcAdapter.FLAG_READER_NFC_V
                            | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    null);
            readerArmed = true;
            lastError = null;
            Log.i(App.LOG_TAG, "NFC reader mode armed");
        } catch (Exception e) {
            lastError = "READER_START_FAILED";
            Log.e(App.LOG_TAG, "enableReaderMode failed", e);
        }
    }

    public void disarmReader(Activity activity) {
        NfcAdapter n = NfcAdapter.getDefaultAdapter(app);
        if (n != null) {
            try { n.disableReaderMode(activity); } catch (Exception ignored) {}
        }
        readerArmed = false;
    }

    private void onTagDiscovered(Tag tag) {
        SessionController sc = App.get(app).sessions();
        Redaction red = App.get(app).redaction();
        String uidTag = red.tag(Redaction.hex(tag.getId()));
        // Debounce the same physical tag within 3 s (tap storms should not flood the store).
        long now = SystemClock.elapsedRealtime();
        synchronized (lastSeenElapsed) {
            Long last = lastSeenElapsed.get(uidTag);
            if (last != null && now - last < 3_000) return;
            lastSeenElapsed.put(uidTag, now);
        }

        StringBuilder tech = new StringBuilder();
        for (String t : tag.getTechList()) {
            if (tech.length() > 0) tech.append(" · ");
            tech.append(shortTech(t));
        }
        SafeJson payload = new SafeJson()
                .put("technologies", tech.toString())
                .put("uid_masked", red.maskUid(tag.getId()))
                .put("uid_tag", uidTag);

        Ndef ndef = Ndef.get(tag);
        int recordCount = 0;
        if (ndef == null) {
            payload.put("ndef", "not formatted (raw tag)")
                    .put("summary", "Tag: " + tech + " · not NDEF formatted");
        } else {
            boolean writable = false;
            int capacity = -1;
            android.nfc.NdefMessage msg = null;
            try {
                writable = ndef.isWritable();
                capacity = ndef.getMaxSize();
                msg = ndef.getCachedNdefMessage();
            } catch (Exception e) {
                payload.put("read_error", e.getClass().getSimpleName());
            }
            payload.put("writable", writable)
                    .put("capacity_bytes", capacity);
            if (msg != null) {
                NdefRecord[] records = msg.getRecords();
                recordCount = records.length;
                SafeJson[] parsed = new SafeJson[Math.min(recordCount, 8)];
                StringBuilder preview = new StringBuilder();
                for (int i = 0; i < parsed.length; i++) {
                    parsed[i] = describeRecord(records[i]);
                    preview.append(parsed[i].optString("kind")).append(": ")
                            .append(parsed[i].optString("content")).append("  ");
                }
                payload.put("ndef_record_count", recordCount);
                payload.put("records", parsed);
                payload.put("ndef_preview", Redaction.sanitize(preview.toString(), 200));
                payload.put("summary", "Tag: " + tech + " · NDEF x" + recordCount
                        + " · " + Redaction.sanitize(preview.toString(), 80));
            } else {
                payload.put("ndef", "empty")
                        .put("summary", "Tag: " + tech + " · NDEF empty");
            }
        }
        payload.put("record_count", recordCount);
        lastTagSummary = payload;
        lastTagUtc = SessionController.utcNow();
        App.get(app).capabilities().markVerified("nfc", "tag read on-device");
        if (sc.isActive()) {
            sc.onObservation("nfc", "nfc.tag_observed", payload);
        }
        Log.i(App.LOG_TAG, "NFC tag: " + payload.optString("summary"));
    }

    /** Bounded, sanitized record description. Content capped, control characters stripped. */
    private static SafeJson describeRecord(NdefRecord r) {
        SafeJson out = new SafeJson();
        try {
            short tnf = r.getTnf();
            String mime = r.toMimeType(); // may be null
            String type = new String(r.getType(), StandardCharsets.US_ASCII);
            switch (tnf) {
                case NdefRecord.TNF_WELL_KNOWN:
                    if (java.util.Arrays.equals(r.getType(), NdefRecord.RTD_TEXT)) {
                        out.put("kind", "text");
                        out.put("content", decodeText(r));
                    } else if (java.util.Arrays.equals(r.getType(), NdefRecord.RTD_URI)) {
                        out.put("kind", "uri");
                        out.put("content", decodeUri(r));
                    } else {
                        out.put("kind", "well-known:" + Redaction.sanitize(type, 24));
                        out.put("content", "(payload " + r.getPayload().length + "B)");
                    }
                    break;
                case NdefRecord.TNF_MIME_MEDIA:
                    out.put("kind", "mime:" + Redaction.sanitize(mime == null ? type : mime, 40));
                    out.put("content", Redaction.sanitize(new String(r.getPayload(), StandardCharsets.UTF_8), 120));
                    break;
                case NdefRecord.TNF_EXTERNAL_TYPE:
                    out.put("kind", "external:" + Redaction.sanitize(type, 40));
                    out.put("content", "(payload " + r.getPayload().length + "B)");
                    break;
                case NdefRecord.TNF_ABSOLUTE_URI:
                    out.put("kind", "absolute-uri");
                    out.put("content", Redaction.sanitize(new String(r.getPayload(), StandardCharsets.UTF_8), 120));
                    break;
                default:
                    out.put("kind", "tnf" + tnf);
                    out.put("content", "(payload " + r.getPayload().length + "B)");
            }
        } catch (Exception e) {
            out.put("kind", "parse_error");
            out.put("content", "(unreadable)");
        }
        return out;
    }

    private static String decodeText(NdefRecord r) {
        byte[] p = r.getPayload();
        if (p == null || p.length < 2) return "(empty)";
        int langLen = p[0] & 0x3F;
        boolean utf16 = (p[0] & 0x80) != 0;
        int start = 1 + langLen;
        if (start > p.length) return "(short)";
        String s = utf16
                ? new String(p, start, p.length - start, StandardCharsets.UTF_16LE)
                : new String(p, start, p.length - start, StandardCharsets.UTF_8);
        return Redaction.sanitize(s, 160);
    }

    private static String decodeUri(NdefRecord r) {
        byte[] p = r.getPayload();
        if (p == null || p.length < 1) return "(empty)";
        String prefix = uriPrefix(p[0] & 0xFF);
        String rest = new String(p, 1, p.length - 1, StandardCharsets.UTF_8);
        return Redaction.sanitize(prefix + rest, 160);
    }

    private static String uriPrefix(int code) {
        switch (code) {
            case 0x00: return "";
            case 0x01: return "http://www.";
            case 0x02: return "https://www.";
            case 0x03: return "http://";
            case 0x04: return "https://";
            case 0x05: return "tel:";
            case 0x06: return "mailto:";
            case 0x07: return "ftp://anonymous:anonymous@";
            case 0x08: return "ftp://ftp.";
            case 0x09: return "ftps://";
            case 0x0A: return "sftp://";
            case 0x0B: return "smb://";
            case 0x0C: return "nfs://";
            case 0x0D: return "ftp://";
            case 0x0E: return "dns:";
            case 0x0F: return "dns://";
            default: return "uri" + code + ":";
        }
    }

    private static String shortTech(String tech) {
        int dot = tech.lastIndexOf('.');
        String s = dot >= 0 ? tech.substring(dot + 1) : tech;
        return s.isEmpty() ? tech : s;
    }
}
