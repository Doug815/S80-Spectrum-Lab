package com.spectrumlab.s80.core;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import com.spectrumlab.s80.App;
import com.spectrumlab.s80.db.EventDb;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Deliberate, user-initiated export (docs/07): JSONL + CSV + (opt-in) GeoJSON +
 * SHA-256 manifest, written to a folder the user picks via SAF. Never automatic,
 * never shared without explicit user action. Identifiers are already redacted at
 * capture time; CSV cells additionally guard against spreadsheet formula injection.
 */
public class Exporter {
    public static final String SCHEMA_VERSION = "1.0.0";

    public interface Progress {
        void onDone(ExportResult r);
        void onError(String message);
    }

    public static class ExportResult {
        public String exportId;
        public List<String> fileNames = new ArrayList<>();
        public List<String> fileSha256 = new ArrayList<>();
        public int observationCount;
        public boolean geojsonWritten;
        public String manifestSha256;
    }

    public static void exportSessionAsync(Context context, Uri treeUri, String sessionId, Progress cb) {
        new Thread(() -> {
            try {
                ExportResult r = exportSession(context, treeUri, sessionId);
                cb.onDone(r);
            } catch (Exception e) {
                Log.e(App.LOG_TAG, "export failed", e);
                cb.onError(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }, "slab-export").start();
    }

    private static ExportResult exportSession(Context context, Uri treeUri, String sessionId) throws Exception {
        App app = App.get(context);
        EventDb db = app.db();
        EventDb.SessionInfo session = db.getSession(sessionId);
        if (session == null) throw new IllegalStateException("session not found");
        List<Observation> observations = db.listObservations(sessionId, null, 100_000);

        // The tree URI names the chosen folder; derive its root *document* URI for writes.
        String rootDocId = DocumentsContract.getTreeDocumentId(treeUri);
        Uri rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId);
        if (rootDocUri == null) throw new IllegalStateException("invalid tree uri");

        String shortId = sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
        ExportResult result = new ExportResult();
        result.exportId = UUID.randomUUID().toString();
        result.observationCount = observations.size();

        // 1. JSONL — 1 event per line, full envelope (authoritative machine format).
        byte[] jsonl = buildJsonl(observations).getBytes(StandardCharsets.UTF_8);
        writeFile(context, rootDocUri, "spectrumlab-" + shortId + ".jsonl", "application/x-ndjson", jsonl);
        result.fileNames.add("spectrumlab-" + shortId + ".jsonl");
        result.fileSha256.add(sha256Hex(jsonl));

        // 2. CSV — normalized fields + payload JSON column, RFC4180 + formula-injection guard.
        byte[] csv = buildCsv(observations).getBytes(StandardCharsets.UTF_8);
        writeFile(context, rootDocUri, "spectrumlab-" + shortId + ".csv", "text/csv", csv);
        result.fileNames.add("spectrumlab-" + shortId + ".csv");
        result.fileSha256.add(sha256Hex(csv));

        // 3. GeoJSON — only when the session opted into GPS and coordinates exist.
        if (session.locationOptIn) {
            byte[] geo = buildGeoJson(observations);
            if (geo != null) {
                writeFile(context, rootDocUri, "spectrumlab-" + shortId + ".geojson", "application/geo+json", geo);
                result.fileNames.add("spectrumlab-" + shortId + ".geojson");
                result.fileSha256.add(sha256Hex(geo));
                result.geojsonWritten = true;
            }
        }

        // 4. Manifest — integrity hashes + provenance; written last, contains its own siblings.
        byte[] manifest = buildManifest(result, session, observations).getBytes(StandardCharsets.UTF_8);
        writeFile(context, rootDocUri, "spectrumlab-" + shortId + "-manifest.json", "application/json", manifest);
        result.fileNames.add("spectrumlab-" + shortId + "-manifest.json");
        result.fileSha256.add(sha256Hex(manifest));
        result.manifestSha256 = sha256Hex(manifest);

        db.insertExport(result.exportId, SessionController.utcNow(), sessionId,
                result.geojsonWritten ? "jsonl,csv,geojson,manifest" : "jsonl,csv,manifest",
                join(result.fileNames), result.manifestSha256);
        return result;
    }

    private static String buildJsonl(List<Observation> observations) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Observation o : observations) {
            JSONObject e = new JSONObject();
            e.put("schema_version", SCHEMA_VERSION);
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
            sb.append(e.toString()).append('\n');
        }
        return sb.toString();
    }

    private static String buildCsv(List<Observation> observations) {
        StringBuilder sb = new StringBuilder();
        sb.append("event_id,session_id,timestamp_utc,elapsed_realtime_ns,sensor,type,source_kind,synthetic,confidence,privacy,summary,latitude,longitude,payload_json\n");
        for (Observation o : observations) {
            JSONObject payload = o.payload();
            List<String> cells = new ArrayList<>();
            cells.add(o.id);
            cells.add(o.sessionId);
            cells.add(o.timestampUtc);
            cells.add(String.valueOf(o.elapsedRealtimeNs));
            cells.add(o.sensor);
            cells.add(o.type);
            cells.add(o.sourceKind);
            cells.add(String.valueOf(o.synthetic));
            cells.add(o.confidence);
            cells.add(o.privacy);
            cells.add(payload.optString("summary", ""));
            cells.add(payload.has("latitude") ? String.valueOf(payload.optDouble("latitude")) : "");
            cells.add(payload.has("longitude") ? String.valueOf(payload.optDouble("longitude")) : "");
            cells.add(o.payloadJson);
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(csvCell(cells.get(i)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** RFC4180 quoting + spreadsheet formula-injection protection (docs/07). */
    private static String csvCell(String raw) {
        String v = raw == null ? "" : raw;
        if (!v.isEmpty()) {
            char c = v.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@') v = "'" + v;
        }
        boolean needQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needQuote) return v;
        return '"' + v.replace("\"", "\"\"") + '"';
    }

    private static byte[] buildGeoJson(List<Observation> observations) throws Exception {
        JSONObject collection = new JSONObject();
        collection.put("type", "FeatureCollection");
        collection.put("properties", new JSONObject()
                .put("note", "Opted-in GPS-tagged observations only; no map tiles downloaded"));
        JSONArray features = new JSONArray();
        for (Observation o : observations) {
            JSONObject payload = o.payload();
            if (!payload.has("latitude") || !payload.has("longitude")) continue;
            JSONObject feature = new JSONObject();
            feature.put("type", "Feature");
            JSONObject geom = new JSONObject();
            geom.put("type", "Point");
            geom.put("coordinates", new JSONArray()
                    .put(payload.optDouble("longitude"))
                    .put(payload.optDouble("latitude")));
            feature.put("geometry", geom);
            JSONObject props = new JSONObject()
                    .put("event_id", o.id)
                    .put("sensor", o.sensor)
                    .put("type", o.type)
                    .put("timestamp_utc", o.timestampUtc)
                    .put("privacy", o.privacy);
            if (payload.has("gps_accuracy_m")) props.put("accuracy_m", payload.optDouble("gps_accuracy_m"));
            if (payload.has("rssi_dbm")) props.put("rssi_dbm", payload.optInt("rssi_dbm"));
            feature.put("properties", props);
            features.put(feature);
        }
        if (features.length() == 0) return null;
        collection.put("features", features);
        return collection.toString(2).getBytes(StandardCharsets.UTF_8);
    }

    private static String buildManifest(ExportResult result, EventDb.SessionInfo session,
                                        List<Observation> observations) throws Exception {
        JSONObject m = new JSONObject();
        m.put("schema_version", SCHEMA_VERSION);
        m.put("export_id", result.exportId);
        m.put("created_utc", SessionController.utcNow());
        m.put("app_version", App.appVersion());
        m.put("package", "com.spectrumlab.s80");
        m.put("session_id", session.id);
        m.put("session_name", session.name);
        m.put("session_started_utc", session.startedUtc);
        m.put("session_ended_utc", session.endedUtc == null ? "(open)" : session.endedUtc);
        m.put("mode", session.mode);
        m.put("scope_label", session.scopeLabel);
        m.put("gps_opt_in", session.locationOptIn);
        m.put("redaction_mode", SessionController.REDACTION_POLICY);
        m.put("synthetic", false);
        m.put("observation_count", observations.size());
        m.put("integrity_note", "SHA-256 hashes establish byte integrity of generated files, "
                + "not proof of sensor accuracy or immunity to device tampering.");
        JSONArray files = new JSONArray();
        for (int i = 0; i < result.fileNames.size(); i++) {
            files.put(new JSONObject()
                    .put("name", result.fileNames.get(i))
                    .put("sha256", result.fileSha256.get(i)));
        }
        m.put("files", files);
        return m.toString(2);
    }

    private static void writeFile(Context context, Uri treeUri, String displayName,
                                  String mime, byte[] data) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri doc = DocumentsContract.createDocument(resolver, treeUri, mime, displayName);
        if (doc == null) throw new IllegalStateException("createDocument failed for " + displayName);
        try (OutputStream out = resolver.openOutputStream(doc)) {
            if (out == null) throw new IllegalStateException("no output stream for " + displayName);
            out.write(data);
            out.flush();
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(p);
        }
        return sb.toString();
    }
}
