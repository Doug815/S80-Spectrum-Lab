package com.spectrumlab.s80.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.spectrumlab.s80.core.Observation;
import java.util.ArrayList;
import java.util.List;

/**
 * App-private SQLite event store implementing the docs/07 schema:
 * sessions, observations (append-only), capability_evidence, user_settings, exports.
 * Schema version tracked via PRAGMA user_version; migrations must never drop user data.
 * All calls are expected from a background worker thread (never the UI thread).
 */
public class EventDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "spectrumlab_events.db";
    private static final int DB_VERSION = 1;

    public EventDb(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions (" +
                "id TEXT PRIMARY KEY," +
                "name TEXT NOT NULL," +
                "mode TEXT NOT NULL," +
                "scope_label TEXT NOT NULL," +
                "started_at_utc TEXT NOT NULL," +
                "ended_at_utc TEXT," +
                "firmware_fingerprint TEXT," +
                "location_opt_in INTEGER NOT NULL DEFAULT 0," +
                "redaction_policy TEXT NOT NULL," +
                "synthetic INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE observations (" +
                "id TEXT PRIMARY KEY," +
                "session_id TEXT NOT NULL," +
                "sensor TEXT NOT NULL," +
                "type TEXT NOT NULL," +
                "timestamp_utc TEXT NOT NULL," +
                "elapsed_realtime_ns INTEGER NOT NULL," +
                "source_kind TEXT NOT NULL," +
                "confidence TEXT NOT NULL," +
                "synthetic INTEGER NOT NULL," +
                "privacy TEXT NOT NULL," +
                "payload_json TEXT NOT NULL," +
                "FOREIGN KEY(session_id) REFERENCES sessions(id))");
        db.execSQL("CREATE INDEX idx_obs_session_time ON observations(session_id, elapsed_realtime_ns)");
        db.execSQL("CREATE INDEX idx_obs_sensor_time ON observations(sensor, timestamp_utc)");
        db.execSQL("CREATE TABLE capability_evidence (" +
                "id TEXT PRIMARY KEY," +
                "capability TEXT NOT NULL," +
                "state TEXT NOT NULL," +
                "checked_at_utc TEXT NOT NULL," +
                "build_fingerprint TEXT," +
                "evidence_json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE user_settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE exports (" +
                "id TEXT PRIMARY KEY," +
                "created_at_utc TEXT NOT NULL," +
                "session_id TEXT NOT NULL," +
                "kinds TEXT NOT NULL," +
                "file_names TEXT NOT NULL," +
                "manifest_sha256 TEXT)");
        db.execSQL("CREATE TABLE signal_history (" +
                "id TEXT PRIMARY KEY," +
                "session_id TEXT NOT NULL," +
                "sensor TEXT NOT NULL," +
                "identifier_tag TEXT NOT NULL," +
                "channel INTEGER," +
                "frequency_mhz INTEGER," +
                "band TEXT," +
                "rssi_dbm INTEGER NOT NULL," +
                "timestamp_utc TEXT NOT NULL," +
                "elapsed_realtime_ns INTEGER NOT NULL," +
                "security TEXT," +
                "ssid TEXT)");

        db.execSQL("CREATE INDEX idx_signal_history_session ON signal_history(session_id)");
        db.execSQL("CREATE INDEX idx_signal_history_channel ON signal_history(channel)");
        db.execSQL("CREATE INDEX idx_signal_history_time ON signal_history(timestamp_utc)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        // Future migrations go here. Never DROP user data (docs/07).
        if (oldV < newV) { /* no-op for v1 */ }
    }

    // ----- sessions -----

    public void insertSession(String id, String name, String mode, String scopeLabel,
                              String startedUtc, String firmwareFingerprint,
                              boolean locationOptIn, String redactionPolicy, boolean synthetic) {
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("name", name); v.put("mode", mode); v.put("scope_label", scopeLabel);
        v.put("started_at_utc", startedUtc); v.put("ended_at_utc", (String) null);
        v.put("firmware_fingerprint", firmwareFingerprint);
        v.put("location_opt_in", locationOptIn ? 1 : 0);
        v.put("redaction_policy", redactionPolicy);
        v.put("synthetic", synthetic ? 1 : 0);
        getWritableDatabase().insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void endSession(String id, String endedUtc) {
        ContentValues v = new ContentValues();
        v.put("ended_at_utc", endedUtc);
        getWritableDatabase().update("sessions", v, "id=?", new String[]{id});
    }

    /** Sessions never closed by their process: mark interrupted (T015). */
    public int markStaleOpenSessionsEnded(String nowUtc, String exceptSessionId) {
        String where = "ended_at_utc IS NULL";
        String[] args = null;
        if (exceptSessionId != null) { where += " AND id<>?"; args = new String[]{exceptSessionId}; }
        ContentValues v = new ContentValues();
        v.put("ended_at_utc", nowUtc);
        return getWritableDatabase().update("sessions", v, where, args);
    }

    public static final String[] SESSION_COLS = {"id", "name", "mode", "scope_label",
            "started_at_utc", "ended_at_utc", "firmware_fingerprint",
            "location_opt_in", "redaction_policy", "synthetic"};

    public List<SessionInfo> listSessions(int limit) {
        List<SessionInfo> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("sessions", SESSION_COLS, null, null,
                null, null, "started_at_utc DESC", String.valueOf(limit));
        while (c.moveToNext()) {
            SessionInfo s = new SessionInfo();
            s.id = c.getString(0); s.name = c.getString(1); s.mode = c.getString(2);
            s.scopeLabel = c.getString(3); s.startedUtc = c.getString(4);
            s.endedUtc = c.isNull(5) ? null : c.getString(5);
            s.firmwareFingerprint = c.isNull(6) ? null : c.getString(6);
            s.locationOptIn = c.getInt(7) == 1; s.redactionPolicy = c.getString(8);
            s.synthetic = c.getInt(9) == 1;
            out.add(s);
        }
        c.close();
        return out;
    }

    public SessionInfo getSession(String id) {
        for (SessionInfo s : listSessions(500)) if (s.id.equals(id)) return s;
        return null;
    }

    // ----- observations -----

    public void insertObservation(Observation o) {
        ContentValues v = new ContentValues();
        v.put("id", o.id); v.put("session_id", o.sessionId); v.put("sensor", o.sensor);
        v.put("type", o.type); v.put("timestamp_utc", o.timestampUtc);
        v.put("elapsed_realtime_ns", o.elapsedRealtimeNs); v.put("source_kind", o.sourceKind);
        v.put("confidence", o.confidence); v.put("synthetic", o.synthetic ? 1 : 0);
        v.put("privacy", o.privacy); v.put("payload_json", o.payloadJson);
        getWritableDatabase().insertWithOnConflict("observations", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<Observation> listObservations(String sessionId, String sensorFilterOrNull, int limit) {
        List<Observation> out = new ArrayList<>();
        String where = "session_id=?";
        List<String> args = new ArrayList<>();
        args.add(sessionId);
        if (sensorFilterOrNull != null) { where += " AND sensor=?"; args.add(sensorFilterOrNull); }
        Cursor c = getReadableDatabase().query("observations", null, where,
                args.toArray(new String[0]), null, null,
                "elapsed_realtime_ns DESC", String.valueOf(limit));
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    public List<Observation> listRecentObservations(int limit) {
        List<Observation> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("observations", null, null, null, null, null,
                "elapsed_realtime_ns DESC", String.valueOf(limit));
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    public int countObservations(String sessionId, String sensorOrNull) {
        String where = "session_id=?";
        List<String> args = new ArrayList<>();
        args.add(sessionId);
        if (sensorOrNull != null) { where += " AND sensor=?"; args.add(sensorOrNull); }
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM observations WHERE " + where, args.toArray(new String[0]));
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    public List<Observation> listObservationsBySensor(String sensor, int limit) {
        List<Observation> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("observations", null, "sensor=?",
                new String[]{sensor}, null, null, "elapsed_realtime_ns DESC", String.valueOf(limit));
        while (c.moveToNext()) out.add(fromCursor(c));
        c.close();
        return out;
    }

    private static Observation fromCursor(Cursor c) {
        Observation o = new Observation();
        o.id = c.getString(c.getColumnIndexOrThrow("id"));
        o.sessionId = c.getString(c.getColumnIndexOrThrow("session_id"));
        o.sensor = c.getString(c.getColumnIndexOrThrow("sensor"));
        o.type = c.getString(c.getColumnIndexOrThrow("type"));
        o.timestampUtc = c.getString(c.getColumnIndexOrThrow("timestamp_utc"));
        o.elapsedRealtimeNs = c.getLong(c.getColumnIndexOrThrow("elapsed_realtime_ns"));
        o.sourceKind = c.getString(c.getColumnIndexOrThrow("source_kind"));
        o.confidence = c.getString(c.getColumnIndexOrThrow("confidence"));
        o.synthetic = c.getInt(c.getColumnIndexOrThrow("synthetic")) == 1;
        o.privacy = c.getString(c.getColumnIndexOrThrow("privacy"));
        o.payloadJson = c.getString(c.getColumnIndexOrThrow("payload_json"));
        return o;
    }

    // ----- capability evidence -----

    public void upsertCapabilityEvidence(String capability, String state, String checkedAtUtc,
                                          String buildFingerprint, String evidenceJson) {
        ContentValues v = new ContentValues();
        v.put("id", capability); // one row per capability, latest evidence wins
        v.put("capability", capability);
        v.put("state", state);
        v.put("checked_at_utc", checkedAtUtc);
        v.put("build_fingerprint", buildFingerprint);
        v.put("evidence_json", evidenceJson);
        getWritableDatabase().insertWithOnConflict("capability_evidence", null, v,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public List<String[]> listCapabilityEvidence() {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("capability_evidence", null, null, null,
                null, null, "capability ASC");
        while (c.moveToNext()) {
            out.add(new String[]{
                    c.getString(c.getColumnIndexOrThrow("capability")),
                    c.getString(c.getColumnIndexOrThrow("state")),
                    c.getString(c.getColumnIndexOrThrow("checked_at_utc")),
                    c.getString(c.getColumnIndexOrThrow("evidence_json"))});
        }
        c.close();
        return out;
    }

    /** Current stored state for one capability (null when never probed). */
    public String getCapabilityState(String capability) {
        Cursor c = getReadableDatabase().query("capability_evidence",
                new String[]{"state"}, "capability=?", new String[]{capability},
                null, null, null);
        String state = null;
        if (c.moveToFirst()) state = c.getString(0);
        c.close();
        return state;
    }

    // ----- user settings -----

    public String getSetting(String key, String def) {
        Cursor c = getReadableDatabase().query("user_settings", new String[]{"value"},
                "key=?", new String[]{key}, null, null, null);
        String out = def;
        if (c.moveToFirst()) out = c.getString(0);
        c.close();
        return out;
    }

    public void setSetting(String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key); v.put("value", value);
        getWritableDatabase().insertWithOnConflict("user_settings", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ----- retention / deletion -----

    /** Purge observations older than cutoffUtc (ISO-8601 sorts lexicographically). */
    public int purgeOlderThan(String cutoffUtc) {
        int n = getWritableDatabase().delete("observations", "timestamp_utc<?", new String[]{cutoffUtc});
        // Sessions fully outside retention with no remaining observations are removed.
        getWritableDatabase().delete("sessions",
                "started_at_utc<? AND NOT EXISTS (SELECT 1 FROM observations o WHERE o.session_id=sessions.id)",
                new String[]{cutoffUtc});
        return n;
    }

    public int deleteAllObservations() {
        int n = getWritableDatabase().delete("observations", null, null);
        getWritableDatabase().delete("sessions", null, null);
        getWritableDatabase().delete("exports", null, null);
        return n;
    }

    // ----- exports -----

    public void insertExport(String id, String createdAtUtc, String sessionId, String kinds,
                             String fileNames, String manifestSha256) {
        ContentValues v = new ContentValues();
        v.put("id", id); v.put("created_at_utc", createdAtUtc); v.put("session_id", sessionId);
        v.put("kinds", kinds); v.put("file_names", fileNames); v.put("manifest_sha256", manifestSha256);
        getWritableDatabase().insert("exports", null, v);
    }

    // ----- signal history (P2.01) -----

    public void insertSignalHistory(String id, String sessionId, String sensor,
                                    String identifierTag, Integer channel, Integer frequencyMhz,
                                    String band, int rssiDbm, String timestampUtc,
                                    long elapsedRealtimeNs, String security, String ssid) {
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("session_id", sessionId);
        v.put("sensor", sensor);
        v.put("identifier_tag", identifierTag);
        v.put("channel", channel);
        v.put("frequency_mhz", frequencyMhz);
        v.put("band", band);
        v.put("rssi_dbm", rssiDbm);
        v.put("timestamp_utc", timestampUtc);
        v.put("elapsed_realtime_ns", elapsedRealtimeNs);
        v.put("security", security);
        v.put("ssid", ssid);
        getWritableDatabase().insertWithOnConflict("signal_history", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<SignalPoint> getSignalHistory(String sessionId, String sensorOrNull, Integer channelOrNull, int limit) {
        List<SignalPoint> out = new ArrayList<>();
        StringBuilder where = new StringBuilder("session_id=?");
        List<String> args = new ArrayList<>();
        args.add(sessionId);
        if (sensorOrNull != null) { where.append(" AND sensor=?"); args.add(sensorOrNull); }
        if (channelOrNull != null) { where.append(" AND channel=?"); args.add(String.valueOf(channelOrNull)); }
        Cursor c = getReadableDatabase().query("signal_history", null, where.toString(),
                args.toArray(new String[0]), null, null, "elapsed_realtime_ns ASC", String.valueOf(limit));
        while (c.moveToNext()) {
            SignalPoint p = new SignalPoint();
            p.id = c.getString(c.getColumnIndexOrThrow("id"));
            p.sessionId = c.getString(c.getColumnIndexOrThrow("session_id"));
            p.sensor = c.getString(c.getColumnIndexOrThrow("sensor"));
            p.identifierTag = c.getString(c.getColumnIndexOrThrow("identifier_tag"));
            int chIdx = c.getColumnIndexOrThrow("channel");
            p.channel = c.isNull(chIdx) ? null : c.getInt(chIdx);
            int freqIdx = c.getColumnIndexOrThrow("frequency_mhz");
            p.frequencyMhz = c.isNull(freqIdx) ? null : c.getInt(freqIdx);
            p.band = c.getString(c.getColumnIndexOrThrow("band"));
            p.rssiDbm = c.getInt(c.getColumnIndexOrThrow("rssi_dbm"));
            p.timestampUtc = c.getString(c.getColumnIndexOrThrow("timestamp_utc"));
            p.elapsedRealtimeNs = c.getLong(c.getColumnIndexOrThrow("elapsed_realtime_ns"));
            p.security = c.getString(c.getColumnIndexOrThrow("security"));
            p.ssid = c.getString(c.getColumnIndexOrThrow("ssid"));
            out.add(p);
        }
        c.close();
        return out;
    }

    public Map<Integer, ChannelOccupancy> getChannelOccupancy(String sessionId) {
        Map<Integer, ChannelOccupancy> result = new HashMap<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT channel, band, COUNT(DISTINCT identifier_tag) as ap_count, " +
                "AVG(rssi_dbm) as avg_rssi, MIN(rssi_dbm) as min_rssi, MAX(rssi_dbm) as max_rssi " +
                "FROM signal_history WHERE session_id=? AND channel IS NOT NULL " +
                "GROUP BY channel, band ORDER BY channel",
                new String[]{sessionId});
        while (c.moveToNext()) {
            int ch = c.getInt(0);
            String band = c.getString(1);
            int apCount = c.getInt(2);
            int avgRssi = c.getInt(3);
            int minRssi = c.getInt(4);
            int maxRssi = c.getInt(5);
            ChannelOccupancy occ = new ChannelOccupancy();
            occ.channel = ch;
            occ.band = band;
            occ.apCount = apCount;
            occ.avgRssi = avgRssi;
            occ.minRssi = minRssi;
            occ.maxRssi = maxRssi;
            result.put(ch, occ);
        }
        c.close();
        return result;
    }

    public static class SignalPoint {
        public String id;
        public String sessionId;
        public String sensor;
        public String identifierTag;
        public Integer channel;
        public Integer frequencyMhz;
        public String band;
        public int rssiDbm;
        public String timestampUtc;
        public long elapsedRealtimeNs;
        public String security;
        public String ssid;
    }

    public static class ChannelOccupancy {
        public int channel;
        public String band;
        public int apCount;
        public int avgRssi;
        public int minRssi;
        public int maxRssi;
    }

    public static class SessionInfo {
        public String id, name, mode, scopeLabel, startedUtc, endedUtc, firmwareFingerprint, redactionPolicy;
        public boolean locationOptIn, synthetic;
    }
}
