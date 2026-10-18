package com.example.refwhistle.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.refwhistle.domain.HybridLogicalClock;
import com.example.refwhistle.domain.Ledger;
import com.example.refwhistle.domain.LogCodec;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.OpLog;
import com.example.refwhistle.domain.Operation;
import com.example.refwhistle.domain.Referee;
import com.example.refwhistle.domain.Replayer;
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
 * Teams, venues and referees are plain setup tables (seed the same sample data on both phones).
 */
public class DBHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "refwhistle.db";
    private static final int DB_VERSION = 3;
    public static final int DAYS = 3;

    private final HybridLogicalClock clock;

    public DBHelper(Context context, String deviceId) {
        super(context, DB_NAME, null, DB_VERSION);
        this.clock = new HybridLogicalClock(deviceId);
        // never stamp below anything already in the log
        for (Operation op : getOps()) {
            clock.receive(op.stamp, System.currentTimeMillis());
        }
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

    /** Same ids on every phone, so two admins can start from identical setup data. */
    public void seedSampleData() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("op_log", null, null);
            db.delete("referee", null, null);
            db.delete("venue", null, null);
            db.delete("team", null, null);
            String[] teams = {"Thunder Hawks", "Blue Lions", "Royal Tigers", "Red Falcons", "Storm Riders",
                    "Iron Wolves", "Golden Eagles", "Silver Sharks", "Night Panthers", "Fire Dragons",
                    "Ocean Titans", "Wild Cobras"};
            for (int i = 0; i < teams.length; i++) {
                addTeam("T" + (i + 1), teams[i]);
            }
            addVenue("V1", "Main Ground", true, 8 * 60, 20 * 60, 18.5204, 73.8567);
            addVenue("V2", "Indoor Court", false, 8 * 60, 20 * 60, 18.5214, 73.8577);
            addVenue("V3", "Back Field", true, 8 * 60, 18 * 60, 18.5194, 73.8547);
            // each referee is also a student of one team: the scheduler never assigns them to it
            String[] refNames = {"Aarav", "Meera", "Rohan", "Isha", "Kabir", "Nisha"};
            for (int i = 1; i <= refNames.length; i++) {
                addReferee("R" + i, refNames[i - 1], "T" + i);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void addTeam(String id, String name) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name);
        getWritableDatabase().insertWithOnConflict("team", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void addVenue(String id, String name, boolean outdoor, int open, int close, double lat, double lng) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name);
        cv.put("outdoor", outdoor ? 1 : 0);
        cv.put("open_min", open);
        cv.put("close_min", close);
        cv.put("lat", lat);
        cv.put("lng", lng);
        getWritableDatabase().insertWithOnConflict("venue", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void setVenueLocation(String venueId, double lat, double lng) {
        ContentValues cv = new ContentValues();
        cv.put("lat", lat);
        cv.put("lng", lng);
        getWritableDatabase().update("venue", cv, "id = ?", new String[]{venueId});
    }

    public void addReferee(String id, String name, String teamId) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("name", name);
        cv.put("team_id", teamId == null ? "" : teamId);
        getWritableDatabase().insertWithOnConflict("referee", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
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
            list.add(new Operation(new Stamp(c.getLong(0), c.getInt(1), c.getString(2)),
                    Operation.Type.valueOf(c.getString(3)), c.getString(4),
                    LogCodec.parseMatch(c.getString(5)), c.getString(6)));
        }
        c.close();
        return list;
    }

    public List<Operation> getSortedOps() {
        OpLog log = new OpLog();
        log.merge(getOps());
        return log.sorted();
    }

    /** Short SHA-256 chain head of the whole log. Equal on two phones = identical history. */
    public String ledgerHead() {
        return Ledger.shortHead(getSortedOps());
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
    }

    /** Logo = base64 PNG stored in the log (matchId slot holds the team id). */
    public void setLogo(String teamId, String base64Png) {
        append(Operation.Type.SET_LOGO, teamId, null, base64Png);
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
    }

    private void append(Operation.Type type, String matchId, Match payload, String data) {
        appendOp(getWritableDatabase(), type, matchId, payload, data);
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
        db.beginTransaction();
        try {
            for (Operation op : incoming) {
                clock.receive(op.stamp, System.currentTimeMillis());
                if (insertOp(db, op)) added++;
            }
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
