package com.example.refwhistle.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.refwhistle.domain.HybridLogicalClock;
import com.example.refwhistle.domain.LogCodec;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.OpLog;
import com.example.refwhistle.domain.Operation;
import com.example.refwhistle.domain.Referee;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.SetupOps;
import com.example.refwhistle.domain.Stamp;
import com.example.refwhistle.domain.Team;
import com.example.refwhistle.domain.Tournament;
import com.example.refwhistle.domain.Venue;

import java.util.ArrayList;
import java.util.List;

/**
 * All SQLite access.
 *
 * The fixtures are NOT stored as rows. The only source of truth for matches is the append-only
 * op_log table; the current schedule is always "replay the sorted log" (Replayer). That is what
 * lets two phones converge: they just exchange op_log rows.
 * Teams, venues and referees are log entries too (SetupOps); their tables are rebuilt from the log.
 */
public class DBHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "refwhistle.db";
    private static final int DB_VERSION = 3;
    public static final int DAYS = 3;

    /** Called after a local change is written (the auto-sync uses it to push the change). */
    public static volatile Runnable onLocalChange;

    // One clock for the whole process: every DBHelper (screens, sync) shares it, so a stamp is always
    // later than every entry already merged, even from a phone whose wall clock runs ahead.
    private static HybridLogicalClock sharedClock;
    private static String sharedClockDevice;

    private static synchronized HybridLogicalClock clockFor(String deviceId) {
        if (sharedClock == null || !deviceId.equals(sharedClockDevice)) {
            sharedClock = new HybridLogicalClock(deviceId);
            sharedClockDevice = deviceId;
        }
        return sharedClock;
    }

    private final HybridLogicalClock clock;

    public DBHelper(Context context, String deviceId) {
        super(context, DB_NAME, null, DB_VERSION);
        this.clock = clockFor(deviceId);
        // never stamp below anything already in the log
        for (Operation op : getOps()) {
            clock.receive(op.stamp, System.currentTimeMillis());
        }
        migrateOldSetup();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE team (id TEXT PRIMARY KEY, name TEXT)");
        db.execSQL("CREATE TABLE venue (id TEXT PRIMARY KEY, name TEXT, outdoor INTEGER, "
                + "open_min INTEGER, close_min INTEGER, lat REAL, lng REAL)");
        db.execSQL("CREATE TABLE referee (id TEXT PRIMARY KEY, name TEXT, team_id TEXT)");
        db.execSQL("CREATE TABLE op_log (op_id TEXT PRIMARY KEY, physical INTEGER, counter INTEGER, "
                + "device TEXT, type TEXT, match_id TEXT, payload TEXT, data TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS op_log");
        db.execSQL("DROP TABLE IF EXISTS fixture");
        db.execSQL("DROP TABLE IF EXISTS referee");
        db.execSQL("DROP TABLE IF EXISTS venue");
        db.execSQL("DROP TABLE IF EXISTS team");
        onCreate(db);
    }

    // ---------- setup data ----------

    /** Adds the demo teams, venues and referees (same ids on every phone) and clears the matches. */
    public void seedSampleData() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // The log is never wiped (other phones would send it straight back): the matches are
            // cancelled with log entries, and the demo setup is added with fixed ids.
            for (Match old : getMatches()) {
                appendOp(db, Operation.Type.CANCEL_MATCH, old.id, null, null);
            }
            String[] teams = {"Thunder Hawks", "Blue Lions", "Royal Tigers", "Red Falcons", "Storm Riders",
                    "Iron Wolves", "Golden Eagles", "Silver Sharks", "Night Panthers", "Fire Dragons",
                    "Ocean Titans", "Wild Cobras"};
            for (int i = 0; i < teams.length; i++) {
                appendOp(db, Operation.Type.ADD_TEAM, "T" + (i + 1), null, SetupOps.teamData(teams[i]));
            }
            appendOp(db, Operation.Type.ADD_VENUE, "V1", null,
                    SetupOps.venueData("Main Ground", true, 8 * 60, 20 * 60, 18.5204, 73.8567));
            appendOp(db, Operation.Type.ADD_VENUE, "V2", null,
                    SetupOps.venueData("Indoor Court", false, 8 * 60, 20 * 60, 18.5214, 73.8577));
            appendOp(db, Operation.Type.ADD_VENUE, "V3", null,
                    SetupOps.venueData("Back Field", true, 8 * 60, 18 * 60, 18.5194, 73.8547));
            // each referee is also a student of one team: the scheduler never assigns them to it
            String[] refNames = {"Aarav", "Meera", "Rohan", "Isha", "Kabir", "Nisha"};
            for (int i = 1; i <= refNames.length; i++) {
                appendOp(db, Operation.Type.ADD_REFEREE, "R" + i, null, SetupOps.refereeData(refNames[i - 1], "T" + i));
            }
            rebuildSetup(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
    }

    // Setup entries are log entries like any other; the tables below are rebuilt from them, so a
    // phone that receives the entries ends up with exactly the same teams, venues and referees.

    public void addTeam(String id, String name) {
        appendSetup(Operation.Type.ADD_TEAM, id, SetupOps.teamData(name));
    }

    public void addVenue(String id, String name, boolean outdoor, int open, int close, double lat, double lng) {
        appendSetup(Operation.Type.ADD_VENUE, id, SetupOps.venueData(name, outdoor, open, close, lat, lng));
    }

    public void setVenueLocation(String venueId, double lat, double lng) {
        appendSetup(Operation.Type.SET_VENUE_LOCATION, venueId, SetupOps.locationData(lat, lng));
    }

    public void addReferee(String id, String name, String teamId) {
        appendSetup(Operation.Type.ADD_REFEREE, id, SetupOps.refereeData(name, teamId));
    }

    /** Stores the hash of the admin password in the log; the earliest one in the log is the real one. */
    public void setAdminPassword(String passwordHash) {
        append(Operation.Type.SET_ADMIN_PIN, "admin", null, passwordHash);
    }

    public void removeTeam(String id) {
        appendSetup(Operation.Type.REMOVE_TEAM, id, null);
    }

    public void removeVenue(String id) {
        appendSetup(Operation.Type.REMOVE_VENUE, id, null);
    }

    public void removeReferee(String id) {
        appendSetup(Operation.Type.REMOVE_REFEREE, id, null);
    }

    /** Setup rows written by an older version have no log entry yet: give them one, so they sync too. */
    private void migrateOldSetup() {
        Tournament known = new Tournament();
        SetupOps.fold(getOps(), known);
        java.util.Set<String> ids = new java.util.HashSet<String>();
        for (Team x : known.teams) ids.add("T" + x.id);
        for (Venue v : known.venues) ids.add("V" + v.id);
        for (Referee r : known.referees) ids.add("R" + r.id);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            boolean any = false;
            for (Team x : getTeams()) {
                if (ids.contains("T" + x.id)) continue;
                appendOp(db, Operation.Type.ADD_TEAM, x.id, null, SetupOps.teamData(x.name));
                any = true;
            }
            for (Venue v : getVenues()) {
                if (ids.contains("V" + v.id)) continue;
                appendOp(db, Operation.Type.ADD_VENUE, v.id, null,
                        SetupOps.venueData(v.name, v.outdoor, v.openMin, v.closeMin, v.lat, v.lng));
                any = true;
            }
            for (Referee r : getReferees()) {
                if (ids.contains("R" + r.id)) continue;
                appendOp(db, Operation.Type.ADD_REFEREE, r.id, null, SetupOps.refereeData(r.name, r.teamId));
                any = true;
            }
            if (any) rebuildSetup(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private void appendSetup(Operation.Type type, String id, String data) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            appendOp(db, type, id, null, data);
            rebuildSetup(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
    }

    /** Rewrites the setup tables from the log (entries applied in stamp order). */
    private void rebuildSetup(SQLiteDatabase db) {
        Tournament t = new Tournament();
        SetupOps.fold(getOps(), t);
        db.delete("referee", null, null);
        db.delete("venue", null, null);
        db.delete("team", null, null);
        for (Team x : t.teams) {
            ContentValues cv = new ContentValues();
            cv.put("id", x.id);
            cv.put("name", x.name);
            db.insertWithOnConflict("team", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
        for (Venue v : t.venues) {
            ContentValues cv = new ContentValues();
            cv.put("id", v.id);
            cv.put("name", v.name);
            cv.put("outdoor", v.outdoor ? 1 : 0);
            cv.put("open_min", v.openMin);
            cv.put("close_min", v.closeMin);
            cv.put("lat", v.lat);
            cv.put("lng", v.lng);
            db.insertWithOnConflict("venue", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
        for (Referee r : t.referees) {
            ContentValues cv = new ContentValues();
            cv.put("id", r.id);
            cv.put("name", r.name);
            cv.put("team_id", r.teamId);
            db.insertWithOnConflict("referee", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public List<Team> getTeams() {
        List<Team> list = new ArrayList<Team>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id, name FROM team ORDER BY rowid", null);
        while (c.moveToNext()) {
            list.add(new Team(c.getString(0), c.getString(1)));
        }
        c.close();
        return list;
    }

    public List<Venue> getVenues() {
        List<Venue> list = new ArrayList<Venue>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, name, outdoor, open_min, close_min, lat, lng FROM venue ORDER BY rowid", null);
        while (c.moveToNext()) {
            list.add(new Venue(c.getString(0), c.getString(1), c.getInt(2) == 1, c.getInt(3),
                    c.getInt(4), c.getDouble(5), c.getDouble(6)));
        }
        c.close();
        return list;
    }

    public List<Referee> getReferees() {
        List<Referee> list = new ArrayList<Referee>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id, name, team_id FROM referee ORDER BY rowid", null);
        while (c.moveToNext()) {
            list.add(new Referee(c.getString(0), c.getString(1), c.getString(2)));
        }
        c.close();
        return list;
    }

    /** Builds the Tournament object the domain classes need. */
    public Tournament loadTournament() {
        Tournament t = new Tournament();
        t.teams.addAll(getTeams());
        t.venues.addAll(getVenues());
        t.referees.addAll(getReferees());
        t.days = DAYS;
        return t;
    }

    // ---------- the log is the truth ----------

    public List<Operation> getOps() {
        List<Operation> list = new ArrayList<Operation>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT physical, counter, device, type, match_id, payload, data FROM op_log", null);
        while (c.moveToNext()) {
            Operation.Type type;
            try {
                type = Operation.Type.valueOf(c.getString(3));
            } catch (IllegalArgumentException unknown) {
                continue;   // an entry type this version no longer knows
            }
            list.add(new Operation(new Stamp(c.getLong(0), c.getInt(1), c.getString(2)),
                    type, c.getString(4), LogCodec.parseMatch(c.getString(5)), c.getString(6)));
        }
        c.close();
        return list;
    }

    /** Current schedule = replay of the sorted log. */
    public Replayer.Result state() {
        OpLog log = new OpLog();
        log.merge(getOps());
        return Replayer.replay(log.sorted(), loadTournament());
    }

    public List<Match> getMatches() {
        List<Match> list = new ArrayList<Match>(state().matches.values());
        java.util.Collections.sort(list, new java.util.Comparator<Match>() {
            public int compare(Match a, Match b) {
                if (a.day != b.day) return a.day - b.day;
                if (a.startMin != b.startMin) return a.startMin - b.startMin;
                return a.id.compareTo(b.id);
            }
        });
        return list;
    }

    public void addMatch(Match m) {
        append(Operation.Type.ADD_MATCH, m.id, m, null);
    }

    public void moveMatch(Match m) {
        append(Operation.Type.MOVE_MATCH, m.id, m, null);
    }

    public void moveMatches(List<Match> moves) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Match m : moves) {
                appendOp(db, Operation.Type.MOVE_MATCH, m.id, m, null);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
    }

    /** Referee events: real kick-off / full time, in minutes after midnight. */
    public void recordKickoff(String matchId, int minute) {
        append(Operation.Type.KICKOFF, matchId, null, String.valueOf(minute));
    }

    public void recordFullTime(String matchId, int minute) {
        append(Operation.Type.FULLTIME, matchId, null, String.valueOf(minute));
    }

    public void cancelMatch(String matchId) {
        append(Operation.Type.CANCEL_MATCH, matchId, null, null);
    }

    public void setResult(String matchId, int scoreA, int scoreB) {
        append(Operation.Type.SET_RESULT, matchId, null, scoreA + "|" + scoreB);
    }

    /** Replace the whole schedule: cancel what exists, then add the new matches. */
    public void replaceSchedule(List<Match> matches) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Match old : getMatches()) {
                appendOp(db, Operation.Type.CANCEL_MATCH, old.id, null, null);
            }
            for (Match m : matches) {
                appendOp(db, Operation.Type.ADD_MATCH, m.id, m, null);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        changed();
    }

    private void append(Operation.Type type, String matchId, Match payload, String data) {
        appendOp(getWritableDatabase(), type, matchId, payload, data);
        changed();
    }

    private static void changed() {
        Runnable r = onLocalChange;
        if (r != null) r.run();
    }

    private void appendOp(SQLiteDatabase db, Operation.Type type, String matchId, Match payload, String data) {
        Stamp s = clock.tick(System.currentTimeMillis());
        insertOp(db, new Operation(s, type, matchId, payload, data));
    }

    private boolean insertOp(SQLiteDatabase db, Operation op) {
        ContentValues cv = new ContentValues();
        cv.put("op_id", op.id());
        cv.put("physical", op.stamp.physical);
        cv.put("counter", op.stamp.counter);
        cv.put("device", op.stamp.deviceId);
        cv.put("type", op.type.name());
        cv.put("match_id", op.matchId);
        cv.put("payload", LogCodec.matchLine(op.payload));
        cv.put("data", op.data);
        return db.insertWithOnConflict("op_log", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    /** Sync entry point: add operations from another phone. Returns how many were new. */
    public int mergeOps(List<Operation> incoming) {
        SQLiteDatabase db = getWritableDatabase();
        int added = 0;
        boolean setupChanged = false;
        db.beginTransaction();
        try {
            for (Operation op : incoming) {
                clock.receive(op.stamp, System.currentTimeMillis());
                if (insertOp(db, op)) {
                    added++;
                    if (SetupOps.isSetup(op.type)) setupChanged = true;
                }
            }
            if (setupChanged) rebuildSetup(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return added;
    }

    public int opLogSize() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM op_log", null);
        c.moveToFirst();
        int n = c.getInt(0);
        c.close();
        return n;
    }
}
