package com.example.refwhistle.ui;

import android.Manifest;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.refwhistle.R;
import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.AdminLock;
import com.example.refwhistle.domain.CsvExporter;
import com.example.refwhistle.domain.DelayPropagator;
import com.example.refwhistle.domain.Diagnosis;
import com.example.refwhistle.domain.GreedyScheduler;
import com.example.refwhistle.domain.LiveOps;
import com.example.refwhistle.domain.Robustness;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.Referee;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.Schedule;
import com.example.refwhistle.domain.ScheduleCost;
import com.example.refwhistle.domain.SimulatedAnnealing;
import com.example.refwhistle.domain.Team;
import com.example.refwhistle.domain.Tournament;
import com.example.refwhistle.domain.Venue;
import com.example.refwhistle.domain.WeatherPlanner;
import com.example.refwhistle.sync.NearbySync;
import com.example.refwhistle.sync.SyncManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationBarView;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements SyncManager.Listener {
    public static final String ADMIN = "ADMIN", REFEREE = "REFEREE", TEAM = "TEAM";

    private DBHelper db;
    private SyncManager sync;
    private SharedPreferences prefs;
    private TextView tvStatus, tvRole, tvEmpty, tvStatMatches, tvStatClash, tvStatSync;
    private RecyclerView rvMatches;
    private ScrollView svTimeline;
    private TimelineView timeline;
    private ExtendedFloatingActionButton fab;
    private LinearLayout suggestBox;
    private ChipGroup chipDays;
    private BottomNavigationView bottomNav;
    private MatchAdapter adapter;
    private int selectedDay = 0;
    private boolean timelineMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Sign in once, or choose "Continue offline" on the login screen.
        // Without Firebase configured (no google-services.json) there is no login at all.
        if (com.example.refwhistle.cloud.Cloud.available(this)
                && com.example.refwhistle.cloud.Cloud.user(this) == null
                && !getSharedPreferences("refwhistle", Context.MODE_PRIVATE).getBoolean("offline_ok", false)) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("refwhistle", Context.MODE_PRIVATE);
        db = new DBHelper(this, loadOrCreateDeviceId());
        tvRole = findViewById(R.id.tvRole);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvStatMatches = findViewById(R.id.tvStatMatches);
        tvStatClash = findViewById(R.id.tvStatClash);
        tvStatSync = findViewById(R.id.tvStatSync);
        rvMatches = findViewById(R.id.rvMatches);
        svTimeline = findViewById(R.id.svTimeline);
        timeline = findViewById(R.id.timeline);
        fab = findViewById(R.id.fabGenerate);
        suggestBox = findViewById(R.id.suggestBox);
        chipDays = findViewById(R.id.chipDays);
        bottomNav = findViewById(R.id.bottomNav);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.main_menu);
        toolbar.setOnMenuItemClickListener(new androidx.appcompat.widget.Toolbar.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem item) {
                return onMenu(item.getItemId());
            }
        });

        adapter = new MatchAdapter(new MatchAdapter.Listener() {
            public void onMatchClick(Match m) {
                matchActions(m);
            }
        });
        rvMatches.setLayoutManager(new LinearLayoutManager(this));
        rvMatches.setAdapter(adapter);
        timeline.setListener(new TimelineView.Listener() {
            public void onMatchClick(Match m) {
                matchActions(m);
            }
        });

        for (int d = 0; d < DBHelper.DAYS; d++) {
            Chip chip = new Chip(this);
            chip.setText("Day " + (d + 1));
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setChipBackgroundColorResource(R.color.chip_glass_bg);
            chip.setChipStrokeColorResource(R.color.glass_stroke_strong);
            chip.setChipStrokeWidth(UiKit.dp(this, 1));
            chip.setTextColor(0xFFFFFFFF);
            chip.setId(1000 + d);
            chipDays.addView(chip);
        }
        chipDays.check(chipDays.getChildAt(0).getId());
        chipDays.setOnCheckedStateChangeListener(new ChipGroup.OnCheckedStateChangeListener() {
            public void onCheckedChanged(ChipGroup g, List<Integer> ids) {
                if (!ids.isEmpty()) {
                    selectedDay = ids.get(0) - 1000;
                    refresh();
                }
            }
        });

        MaterialButtonToggleGroup toggle = findViewById(R.id.toggleView);
        toggle.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            public void onButtonChecked(MaterialButtonToggleGroup g, int id, boolean checked) {
                if (checked) {
                    timelineMode = id == R.id.btnTimeline;
                    refresh();
                }
            }
        });

        fab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                generateSchedule();
            }
        });

        bottomNav.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            public boolean onNavigationItemSelected(android.view.MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.nav_insights) open(InsightsActivity.class);
                else if (id == R.id.nav_sync) open(SyncActivity.class);
                else if (id == R.id.nav_standings) open(StandingsActivity.class);
                else if (id == R.id.nav_setup) open(ManageActivity.class);
                return id == R.id.nav_schedule;
            }
        });

        tvStatSync.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                open(SyncActivity.class);
            }
        });

        // Notifications and the Nearby (Bluetooth) permissions in one go
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        for (String p : NearbySync.neededPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), 7);
        }
        sync = SyncManager.get(this);
        sync.start();                                  // automatic: internet and phones close by
        if (prefs.getString("role", null) == null) {
            chooseProfile();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7 && sync != null) sync.start();
    }

    /** Called when the sync state changes; new changes from another phone refresh the lists. */
    @Override
    public void onSyncState(boolean dataChanged) {
        if (dataChanged) refresh();
        else tvStatSync.setText(sync.tile());
    }

    private void open(Class<?> c) {
        startActivity(new Intent(this, c));
    }

    private boolean onMenu(int id) {
        if (id == R.id.menu_seed) {
            UiKit.dialog(this).setTitle("Load demo tournament?")
                    .setMessage("This adds a ready-made setup (12 teams, 3 venues, 6 referees) and clears the "
                            + "current matches. Other phones get the same setup when they sync.")
                    .setPositiveButton("Load", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            db.seedSampleData();
                            toast("Demo tournament loaded. Tap Generate to build the schedule.");
                            refresh();
                        }
                    }).setNegativeButton("Cancel", null).show();
        } else if (id == R.id.menu_add) {
            open(CreateFixtureActivity.class);
        } else if (id == R.id.menu_map) {
            open(MapActivity.class);
        } else if (id == R.id.menu_rain) {
            askRain();
        } else if (id == R.id.menu_csv) {
            exportCsv();
        } else if (id == R.id.menu_role) {
            chooseProfile();
        } else if (id == R.id.menu_account) {
            account();
        }
        return true;
    }

    private void account() {
        com.google.firebase.auth.FirebaseUser u = com.example.refwhistle.cloud.Cloud.user(this);
        UiKit.dialog(this).setTitle("Account")
                .setMessage(!com.example.refwhistle.cloud.Cloud.available(this) ? "Accounts are not set up on this build."
                        : u == null ? "You are using the app offline. Sign in to sync over the internet."
                        : "Signed in as " + u.getEmail())
                .setPositiveButton(!com.example.refwhistle.cloud.Cloud.available(this) ? "Close"
                        : u == null ? "Sign in" : "Sign out", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (!com.example.refwhistle.cloud.Cloud.available(MainActivity.this)) return;
                        sync.stop();
                        prefs.edit().putBoolean("offline_ok", false).apply();
                        com.example.refwhistle.cloud.Cloud.signOut(MainActivity.this);
                        startActivity(new Intent(MainActivity.this, LoginActivity.class));
                        finish();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bottomNav.getMenu().findItem(R.id.nav_schedule).setChecked(true);
        sync.addListener(this);
        sync.start();
        refresh();
    }

    @Override
    protected void onPause() {
        sync.removeListener(this);
        super.onPause();
    }

    // ---------- profile (role + who am I) ----------

    private void chooseProfile() {
        final Tournament t = db.loadTournament();
        final String[] roles = {ADMIN, REFEREE, TEAM};
        final Spinner spRole = new Spinner(this);
        spRole.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, roles));
        final List<String> ids = new ArrayList<String>();
        final List<String> names = new ArrayList<String>();
        final Spinner spWho = new Spinner(this);
        final ArrayAdapter<String> whoAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_dropdown_item, names);
        spWho.setAdapter(whoAdapter);
        spRole.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                ids.clear();
                names.clear();
                if (roles[pos].equals(REFEREE)) {
                    for (Referee r : t.referees) { ids.add(r.id); names.add(r.name); }
                } else if (roles[pos].equals(TEAM)) {
                    for (Team x : t.teams) { ids.add(x.id); names.add(x.name); }
                }
                spWho.setVisibility(ids.isEmpty() ? View.GONE : View.VISIBLE);
                whoAdapter.notifyDataSetChanged();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 12), UiKit.dp(this, 24), 0);
        box.addView(spRole);
        box.addView(spWho);
        UiKit.dialog(this)
                .setTitle("Who are you?")
                .setMessage("Admins build the schedule. Referees and team members see only their own matches.")
                .setView(box)
                .setCancelable(prefs.getString("role", null) != null)
                .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String role = roles[spRole.getSelectedItemPosition()];
                        int sel = spWho.getSelectedItemPosition();
                        // leaving the admin role forgets the password: it has to be typed again to come back
                        prefs.edit().putString("role", role)
                                .putString("admin_hash", role.equals(ADMIN) ? prefs.getString("admin_hash", "") : "")
                                .putString("who", ids.isEmpty() || sel < 0 || sel >= ids.size() ? "" : ids.get(sel))
                                .putString("snapshot", "").apply();
                        refresh();
                    }
                }).show();
    }

    private String role() { return prefs.getString("role", ADMIN); }

    /** True only on a phone where the admin password was typed (and still matches the one in the log). */
    public static boolean isAdminNow(Context c, DBHelper db) {
        SharedPreferences p = c.getSharedPreferences("refwhistle", Context.MODE_PRIVATE);
        String cur = AdminLock.passwordHash(db.getOps());
        return ADMIN.equals(p.getString("role", ADMIN)) && cur != null && cur.equals(p.getString("admin_hash", ""));
    }

    private boolean isAdmin() {
        return isAdminNow(this, db);
    }

    private boolean lockDialogShowing;

    /**
     * Admin role without the password: ask for it. The first admin creates it; every other admin types
     * the same one. Cancelling sends the person back to choose another role.
     */
    private void askAdminPassword() {
        if (lockDialogShowing) return;
        lockDialogShowing = true;
        final String existing = AdminLock.passwordHash(db.getOps());
        final EditText pw = new EditText(this);
        pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setHint(existing == null ? "New admin password (6+ characters)" : "Admin password");
        LinearLayout box = new LinearLayout(this);
        box.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 12), UiKit.dp(this, 24), 0);
        box.addView(pw, new LinearLayout.LayoutParams(-1, -2));
        UiKit.dialog(this)
                .setTitle(existing == null ? "Create the admin password" : "Admin password")
                .setMessage(existing == null
                        ? "Only people who know this password can be Admin. Share it only with the other admins. "
                        + "It cannot be reset, so remember it."
                        : "Referees and team members cannot change the schedule. Admins type the password the "
                        + "first admin created.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Continue", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        lockDialogShowing = false;
                        String typed = pw.getText().toString();
                        if (existing == null) {
                            if (typed.length() < 6) {
                                toast("Use at least 6 characters.");
                                askAdminPassword();
                                return;
                            }
                            String h = AdminLock.hash(typed);
                            db.setAdminPassword(h);
                            prefs.edit().putString("admin_hash", h).apply();
                            refresh();
                        } else if (AdminLock.hash(typed).equals(existing)) {
                            prefs.edit().putString("admin_hash", existing).apply();
                            refresh();
                        } else {
                            toast("Wrong password.");
                            askAdminPassword();
                        }
                    }
                })
                .setNegativeButton("I am not an admin", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        lockDialogShowing = false;
                        prefs.edit().putString("role", REFEREE).putString("who", "").putString("admin_hash", "").apply();
                        chooseProfile();
                    }
                }).show();
    }
    private String who() { return prefs.getString("who", ""); }

    // ---------- scheduling ----------

    private void generateSchedule() {
        Tournament t = db.loadTournament();
        if (t.teams.size() < 2 || t.venues.isEmpty() || t.referees.isEmpty()) {
            toast("Add teams, venues and referees first, or load the demo tournament from the menu.");
            return;
        }
        int plannedMin = t.matchMin;
        t.matchMin = LiveOps.learnedMatchMin(db.state(), plannedMin);   // real match length, if observed
        Schedule greedy = GreedyScheduler.schedule(t);
        int greedyCost = ScheduleCost.cost(greedy.matches, t);
        List<Match> better = greedy.matches.isEmpty() ? greedy.matches
                : SimulatedAnnealing.improve(greedy.matches, t, greedy.matches.size() * 2000, 1L);
        int betterCost = ScheduleCost.cost(better, t);
        db.replaceSchedule(better);
        int pct = greedyCost == 0 ? 0 : Math.round(100f * (greedyCost - betterCost) / greedyCost);
        java.util.Map<String, Integer> load = new java.util.HashMap<String, Integer>();
        for (com.example.refwhistle.domain.Referee r : t.referees) load.put(r.id, 0);
        for (Match x : better) load.put(x.refereeId, load.get(x.refereeId) + 1);
        int minLoad = Integer.MAX_VALUE, maxLoad = 0;
        for (int v : load.values()) { minLoad = Math.min(minLoad, v); maxLoad = Math.max(maxLoad, v); }
        double blastBase = Robustness.blastRadius(greedy.matches, t, 15).average;
        double blastNow = Robustness.blastRadius(better, t, 15).average;
        StringBuilder msg = new StringBuilder();
        msg.append("\u2022 ").append(better.size()).append(" matches scheduled over ").append(t.days).append(" days\n");
        msg.append("\u2022 Every referee has ").append(minLoad == maxLoad ? "" + maxLoad
                : minLoad + " to " + maxLoad).append(" matches\n");
        msg.append("\u2022 Teams wait about ").append(ScheduleCost.idleMinutes(better, t) / Math.max(1, t.teams.size()))
                .append(" min between their matches\n");
        msg.append("\u2022 ").append(ScheduleCost.heatMatches(better, t)).append(" outdoor match(es) start in the midday heat\n");
        msg.append("\u2022 If one match runs 15 min late, about ").append(String.format("%.1f", blastNow))
                .append(" matches move (a basic scheduler: ").append(String.format("%.1f", blastBase)).append(")");
        if (pct > 0) {
            msg.append("\n\nOverall this is ").append(pct).append("% better than a basic scheduler.");
        }
        if (t.matchMin != plannedMin) {
            msg.append("\n\nMatch length set to ").append(t.matchMin).append(" min, learned from matches already played.");
        }
        if (!greedy.unplaced.isEmpty()) {
            msg.append("\n\n").append(greedy.unplaced.size()).append(" match(es) could not be placed:\n");
            for (String why : Diagnosis.explain(t, greedy)) msg.append("\u2022 ").append(why).append("\n");
        }
        UiKit.dialog(this).setTitle("Schedule ready").setMessage(msg.toString())
                .setPositiveButton("OK", null).show();
        refresh();
    }

    // ---------- per-match actions ----------

    private void matchActions(final Match m) {
        final Tournament t = db.loadTournament();
        List<String> opts = new ArrayList<String>();
        final List<Runnable> acts = new ArrayList<Runnable>();
        if (isAdmin()) {
            opts.add("Mark running late...");
            acts.add(new Runnable() { public void run() { askDelay(m); } });
        }
        if (isAdmin() || role().equals(REFEREE)) {
            final Replayer.Result live = db.state();
            if (!live.actualStart.containsKey(m.id)) {
                opts.add("Kick-off...");
                acts.add(new Runnable() { public void run() { askKickoff(m); } });
            } else if (!live.actualEnd.containsKey(m.id)) {
                opts.add("Full time...");
                acts.add(new Runnable() { public void run() { askFullTime(m, live.actualStart.get(m.id)); } });
            }
            opts.add("Enter result...");
            acts.add(new Runnable() { public void run() { askResult(m, t); } });
        }
        opts.add("Navigate to venue");
        acts.add(new Runnable() { public void run() { navigate(m, t); } });
        if (role().equals(REFEREE)) {
            opts.add("Check in at venue (GPS)");
            acts.add(new Runnable() { public void run() { checkIn(m, t); } });
        }
        if (isAdmin()) {
            opts.add("Cancel match");
            acts.add(new Runnable() { public void run() {
                db.cancelMatch(m.id);
                refresh();
            } });
        }
        UiKit.dialog(this)
                .setTitle(t.teamName(m.teamA) + " vs " + t.teamName(m.teamB))
                .setItems(opts.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        acts.get(which).run();
                    }
                }).show();
    }

    // ---------- live events: kick-off / full time (referee) ----------

    private void askTime(String title, int defaultMin, final android.app.TimePickerDialog.OnTimeSetListener cb) {
        TimePickerDialog d = new TimePickerDialog(this, cb, defaultMin / 60, defaultMin % 60, true);
        d.setTitle(title);
        d.show();
    }

    private int nowMin() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    private void askKickoff(final Match m) {
        askTime("Real kick-off time", nowMin(), new android.app.TimePickerDialog.OnTimeSetListener() {
            public void onTimeSet(android.widget.TimePicker view, int h, int min) {
                int actual = h * 60 + min;
                db.recordKickoff(m.id, actual);
                int late = actual - m.startMin;
                if (late >= LiveOps.LATE_TOLERANCE_MIN) {
                    toast("Kick-off logged: " + late + " min late. The admin gets a re-timing suggestion.");
                } else {
                    toast("Kick-off logged.");
                }
                refresh();
            }
        });
    }

    private void askFullTime(final Match m, final int started) {
        askTime("Real full-time", Math.max(started + m.durationMin, 0) % (24 * 60),
                new android.app.TimePickerDialog.OnTimeSetListener() {
                    public void onTimeSet(android.widget.TimePicker view, int h, int min) {
                        int actual = h * 60 + min;
                        if (actual <= started) {
                            toast("Full time must be after kick-off (" + fmt(started) + ")");
                            return;
                        }
                        db.recordFullTime(m.id, actual);
                        toast("Played " + (actual - started) + " min (planned " + m.durationMin + ")");
                        refresh();
                    }
                });
    }

    /** Banner for the admin: suggestions derived from live events. Disappear once applied. */
    private void showSuggestions(boolean admin, Replayer.Result st, List<Match> all, Tournament t) {
        suggestBox.removeAllViews();
        if (!admin) {
            suggestBox.setVisibility(View.GONE);
            return;
        }
        java.util.Set<String> dismissed = prefs.getStringSet("dismissed", new java.util.HashSet<String>());
        int shown = 0;
        for (final LiveOps.Suggestion sg : LiveOps.suggest(st, all, t)) {
            if (dismissed.contains(sg.key()) || shown >= 3) continue;
            shown++;
            TextView tv = new TextView(this);
            tv.setText((sg.kind == LiveOps.Kind.LATE ? "\u23F1  " : "\u26A1  ") + sg.text + "  \u203A");
            tv.setTextSize(13);
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackgroundResource(R.drawable.bg_glass);
            tv.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 10), UiKit.dp(this, 14), UiKit.dp(this, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = UiKit.dp(this, 6);
            tv.setLayoutParams(lp);
            final List<Match> snapshot = all;
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { showPlan(sg, snapshot); }
            });
            suggestBox.addView(tv);
        }
        suggestBox.setVisibility(shown == 0 ? View.GONE : View.VISIBLE);
    }

    private void showPlan(final LiveOps.Suggestion sg, List<Match> current) {
        final Tournament t = db.loadTournament();
        final Map<String, Match> orig = new HashMap<String, Match>();
        for (Match c : current) orig.put(c.id, c);
        StringBuilder sb = new StringBuilder("Here is what would change:\n\n");
        sb.append(describeMoves(sg.plan.moves, orig, t));
        for (String f : sg.plan.flags) sb.append("\nNote: ").append(f);
        UiKit.dialog(this)
                .setTitle(sg.text)
                .setMessage(sb.toString())
                .setPositiveButton("Apply and notify", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        db.moveMatches(sg.plan.moves);
                        postNotices(t, sg.plan.notices);
                        refresh();
                    }
                })
                .setNeutralButton("Dismiss", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        java.util.Set<String> s = new java.util.HashSet<String>(
                                prefs.getStringSet("dismissed", new java.util.HashSet<String>()));
                        s.add(sg.key());
                        prefs.edit().putStringSet("dismissed", s).apply();
                        refresh();
                    }
                })
                .setNegativeButton("Later", null).show();
    }

    private void askDelay(final Match m) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("Minutes, e.g. 15");
        FrameBox box = new FrameBox(this, et);
        UiKit.dialog(this).setTitle("Starts late by how many minutes?").setView(box)
                .setPositiveButton("Preview", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int mins;
                        try { mins = Integer.parseInt(et.getText().toString().trim()); }
                        catch (NumberFormatException e) { toast("Enter a number"); return; }
                        previewDelay(m, mins);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Dry run: show exactly what would move before anything is written to the log. */
    private void previewDelay(final Match m, final int mins) {
        final Tournament t = db.loadTournament();
        final List<Match> current = db.getMatches();
        final DelayPropagator.Result r = DelayPropagator.propagate(current, t, m.id, mins);
        if (r.moves.isEmpty()) {
            toast("No other matches are affected.");
            return;
        }
        final Map<String, Match> orig = new HashMap<String, Match>();
        for (Match c : current) orig.put(c.id, c);
        StringBuilder sb = new StringBuilder(r.affected() + (r.affected() == 1 ? " match will" : " matches will")
                + " be re-timed:\n\n");
        sb.append(describeMoves(r.moves, orig, t));
        for (String f : r.flags) sb.append("\nNote: ").append(f);
        UiKit.dialog(this).setTitle(t.teamName(m.teamA) + " vs " + t.teamName(m.teamB) + " starts " + mins + " min late")
                .setMessage(sb.toString())
                .setPositiveButton("Apply and notify", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        db.moveMatches(r.moves);
                        postNotices(t, r.notices);
                        toast("Times updated and teams notified.");
                        refresh();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private String describeMoves(List<Match> moves, Map<String, Match> orig, Tournament t) {
        StringBuilder sb = new StringBuilder();
        for (Match f : moves) {
            Match o = orig.get(f.id);
            sb.append(t.teamName(f.teamA)).append(" vs ").append(t.teamName(f.teamB)).append('\n');
            if (o != null && f.day == o.day && f.startMin == o.startMin && f.venueId.equals(o.venueId)) {
                sb.append("     finished, now ends at ").append(fmt(f.endMin())).append("\n\n");
                continue;
            }
            sb.append("     ");
            if (o != null) {
                sb.append(o.day != f.day ? "Day " + (o.day + 1) + " " : "").append(fmt(o.startMin));
                sb.append("  \u2192  ");
            }
            sb.append(o != null && o.day != f.day ? "Day " + (f.day + 1) + " " : "").append(fmt(f.startMin));
            if (o != null && !o.venueId.equals(f.venueId)) sb.append("  at ").append(t.venueName(f.venueId));
            sb.append("\n\n");
        }
        return sb.toString().trim() + "\n";
    }

    private void postNotices(Tournament t, Map<String, List<String>> notices) {
        for (Map.Entry<String, List<String>> e : notices.entrySet()) {
            String name = t.teamName(e.getKey());
            if (name.equals(e.getKey())) name = t.refereeName(e.getKey());
            StringBuilder sb = new StringBuilder();
            for (String s : e.getValue()) sb.append(s).append('\n');
            Notifier.post(this, "Schedule change: " + name, sb.toString().trim());
        }
    }

    // ---------- rain / ground closed ----------

    private void askRain() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 12), UiKit.dp(this, 24), 0);
        final Spinner spDay = new Spinner(this);
        List<String> days = new ArrayList<String>();
        for (int d = 1; d <= DBHelper.DAYS; d++) days.add("Day " + d);
        spDay.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, days));
        spDay.setSelection(selectedDay);
        final Spinner spFrom = new Spinner(this), spTo = new Spinner(this);
        List<String> hours = new ArrayList<String>();
        for (int h = 8; h <= 22; h++) hours.add(String.format("%02d:00", h));
        spFrom.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, hours));
        spTo.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, hours));
        spFrom.setSelection(4);
        spTo.setSelection(8);
        box.addView(label("Day"));
        box.addView(spDay);
        box.addView(label("Outdoor grounds closed from"));
        box.addView(spFrom);
        box.addView(label("until"));
        box.addView(spTo);
        UiKit.dialog(this).setTitle("Rain / ground closed")
                .setMessage("Outdoor matches in this time window move to the nearest free slot, indoors if possible.")
                .setView(box)
                .setPositiveButton("Preview", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        int from = (8 + spFrom.getSelectedItemPosition()) * 60;
                        int to = (8 + spTo.getSelectedItemPosition()) * 60;
                        if (to <= from) { toast("End must be after start"); return; }
                        previewRain(spDay.getSelectedItemPosition(), from, to);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private TextView label(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setPadding(0, UiKit.dp(this, 8), 0, 0);
        return tv;
    }

    private void previewRain(int day, int from, int to) {
        final Tournament t = db.loadTournament();
        List<Match> current = db.getMatches();
        final WeatherPlanner.Result r = WeatherPlanner.plan(current, t, day, from, to);
        if (r.moves.isEmpty() && r.flags.isEmpty()) {
            toast("No outdoor matches are affected.");
            return;
        }
        final Map<String, Match> orig = new HashMap<String, Match>();
        for (Match c : current) orig.put(c.id, c);
        StringBuilder sb = new StringBuilder(r.moves.size() + (r.moves.size() == 1 ? " match" : " matches")
                + " will move to a free slot:\n\n");
        sb.append(describeMoves(r.moves, orig, t));
        for (String f : r.flags) sb.append("\nNote: ").append(f);
        UiKit.dialog(this).setTitle("Rain plan: Day " + (day + 1))
                .setMessage(sb.toString())
                .setPositiveButton("Apply and notify", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        db.moveMatches(r.moves);
                        Map<String, List<String>> notices = new HashMap<String, List<String>>();
                        for (Match f : r.moves) {
                            Match o = orig.get(f.id);
                            String text = t.teamName(f.teamA) + " vs " + t.teamName(f.teamB) + " moved (rain): "
                                    + fmt(o.startMin) + " -> " + fmt(f.startMin) + " at " + t.venueName(f.venueId);
                            for (String party : new String[]{f.teamA, f.teamB, f.refereeId}) {
                                List<String> l = notices.get(party);
                                if (l == null) { l = new ArrayList<String>(); notices.put(party, l); }
                                l.add(text);
                            }
                        }
                        postNotices(t, notices);
                        refresh();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ---------- results, maps, check-in ----------

    private void askResult(final Match m, Tournament t) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 12), UiKit.dp(this, 24), 0);
        final EditText a = new EditText(this), b = new EditText(this);
        a.setInputType(InputType.TYPE_CLASS_NUMBER);
        b.setInputType(InputType.TYPE_CLASS_NUMBER);
        a.setHint(t.teamName(m.teamA));
        b.setHint(t.teamName(m.teamB));
        a.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(a);
        box.addView(b);
        UiKit.dialog(this).setTitle("Final score").setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        try {
                            db.setResult(m.id, Integer.parseInt(a.getText().toString().trim()),
                                    Integer.parseInt(b.getText().toString().trim()));
                            refresh();
                        } catch (NumberFormatException e) {
                            toast("Enter both scores");
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void navigate(Match m, Tournament t) {
        Venue v = t.venueMap().get(m.venueId);
        if (v == null || !v.hasLocation()) {
            toast("This venue has no map location yet. Set it on the Venue map.");
            return;
        }
        Uri uri = Uri.parse("google.navigation:q=" + v.lat + "," + v.lng);
        Intent i = new Intent(Intent.ACTION_VIEW, uri);
        if (i.resolveActivity(getPackageManager()) == null) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + v.lat + "," + v.lng + "?q=" + v.lat + ","
                    + v.lng + "(" + Uri.encode(v.name) + ")"));
        }
        try { startActivity(i); } catch (Exception e) { toast("No maps app installed"); }
    }

    private void checkIn(Match m, Tournament t) {
        Venue v = t.venueMap().get(m.venueId);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 2);
            toast("Allow location, then tap Check in again");
            return;
        }
        if (v == null || !v.hasLocation()) { toast("Venue has no saved location"); return; }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getAccuracy() < best.getAccuracy())) best = l;
            }
        } catch (SecurityException e) { toast("Location permission denied"); return; }
        if (best == null) { toast("No GPS fix yet; open a maps app once and retry"); return; }
        float[] res = new float[1];
        Location.distanceBetween(best.getLatitude(), best.getLongitude(), v.lat, v.lng, res);
        if (res[0] <= 300) {
            toast("Checked in at " + v.name + " (" + Math.round(res[0]) + " m away)");
        } else {
            toast("You are " + Math.round(res[0]) + " m from " + v.name + ". Not checked in.");
        }
    }

    // ---------- export ----------

    private void exportCsv() {
        try {
            Replayer.Result st = db.state();
            Tournament t = db.loadTournament();
            File dir = new File(getCacheDir(), "exports");
            dir.mkdirs();
            File f = new File(dir, "schedule.csv");
            FileWriter w = new FileWriter(f);
            w.write(CsvExporter.schedule(db.getMatches(), t, st.results));
            w.close();
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/csv");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Export schedule"));
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    // ---------- list ----------

    private void refresh() {
        if (role().equals(ADMIN) && !isAdmin() && prefs.getString("role", null) != null) askAdminPassword();
        Tournament t = db.loadTournament();
        Replayer.Result st = db.state();
        List<Match> all = db.getMatches();
        List<Match> day = new ArrayList<Match>();
        boolean admin = isAdmin();
        for (Match m : all) {
            if (role().equals(REFEREE) && !m.refereeId.equals(who())) continue;
            if (role().equals(TEAM) && !m.hasTeam(who())) continue;
            if (m.day == selectedDay) day.add(m);
        }
        adapter.set(day, t, st);
        timeline.setData(day, t, st);

        rvMatches.setVisibility(timelineMode ? View.GONE : View.VISIBLE);
        svTimeline.setVisibility(timelineMode ? View.VISIBLE : View.GONE);
        tvEmpty.setVisibility(day.isEmpty() && !timelineMode ? View.VISIBLE : View.GONE);
        if (all.isEmpty()) {
            tvEmpty.setText(admin ? "No matches yet.\nOpen the menu (\u22EE), choose Load demo tournament, then tap Generate."
                    : "No matches for you yet.\nAsk the admin to generate the schedule, then Sync.");
        } else {
            tvEmpty.setText("Nothing scheduled on this day.");
        }
        fab.setVisibility(admin ? View.VISIBLE : View.GONE);
        // only admins see the screens and menu items that change the schedule or the setup
        android.view.Menu menu = ((MaterialToolbar) findViewById(R.id.toolbar)).getMenu();
        int[] adminOnly = {R.id.menu_seed, R.id.menu_add, R.id.menu_rain};
        for (int mid : adminOnly) {
            android.view.MenuItem item = menu.findItem(mid);
            if (item != null) item.setVisible(admin);
        }
        android.view.MenuItem setupTab = bottomNav.getMenu().findItem(R.id.nav_setup);
        if (setupTab != null) setupTab.setVisible(admin);

        String me = admin ? "Admin" : (role().equals(REFEREE) ? "Referee " + t.refereeName(who())
                : "Team " + t.teamName(who()));
        tvRole.setText(me + "  \u2022  tap to switch");
        tvRole.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { chooseProfile(); }
        });
        tvStatMatches.setText(String.valueOf(all.size()));
        tvStatClash.setText(String.valueOf(st.rescheduled.size()));
        tvStatSync.setText(sync.tile());
        notifyChangesForMe(all, t);
        showSuggestions(admin, st, all, t);
    }

    /** After a sync, tell a referee/team member (locally) if one of their matches moved. */
    private void notifyChangesForMe(List<Match> all, Tournament t) {
        String who = who();
        if (who.isEmpty() || isAdmin()) return;
        Map<String, String> now = new HashMap<String, String>();
        for (Match m : all) {
            if (m.refereeId.equals(who) || m.hasTeam(who)) {
                now.put(m.id, m.day + "," + m.startMin + "," + m.venueId);
            }
        }
        String old = prefs.getString("snapshot", "");
        if (!old.isEmpty()) {
            for (String line : old.split(";")) {
                String[] kv = line.split("=");
                if (kv.length != 2) continue;
                String cur = now.get(kv[0]);
                if (cur != null && !cur.equals(kv[1])) {
                    for (Match m : all) {
                        if (m.id.equals(kv[0])) {
                            Notifier.post(this, "Your match was re-timed", t.teamName(m.teamA) + " vs "
                                    + t.teamName(m.teamB) + " now Day " + (m.day + 1) + " " + fmt(m.startMin)
                                    + " at " + t.venueName(m.venueId));
                        }
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : now.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        prefs.edit().putString("snapshot", sb.toString()).apply();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    public static String fmt(int minutes) {
        return String.format("%02d:%02d", minutes / 60, minutes % 60);
    }

    /** One random id per install. Used as the tie-break in the hybrid logical clock. */
    private String loadOrCreateDeviceId() {
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = "dev-" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    /** Pads a single view inside a dialog. */
    private static class FrameBox extends LinearLayout {
        FrameBox(Context c, View child) {
            super(c);
            setPadding(UiKit.dp(c, 24), UiKit.dp(c, 12), UiKit.dp(c, 24), 0);
            addView(child, new LinearLayout.LayoutParams(-1, -2));
        }
    }
}
