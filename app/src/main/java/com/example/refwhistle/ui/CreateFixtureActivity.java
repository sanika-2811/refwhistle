package com.example.refwhistle.ui;

import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.refwhistle.R;
import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.Conflict;
import com.example.refwhistle.domain.ConflictChecker;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.Referee;
import com.example.refwhistle.domain.Team;
import com.example.refwhistle.domain.Tournament;
import com.example.refwhistle.domain.Venue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CreateFixtureActivity extends AppCompatActivity {
    private DBHelper db;
    private Tournament tournament;
    private Spinner spTeamA, spTeamB, spVenue, spReferee, spDay;
    private Button btTime, btSave;
    private int startMin = 10 * 60; // default 10:00

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sp = getSharedPreferences("refwhistle", Context.MODE_PRIVATE);
        db = new DBHelper(this, sp.getString("device_id", "dev-unknown"));
        if (!MainActivity.isAdminNow(this, db)) {
            android.widget.Toast.makeText(this, "Only admins can add matches.", android.widget.Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_create_fixture);
        tournament = db.loadTournament();

        spTeamA = findViewById(R.id.spTeamA);
        spTeamB = findViewById(R.id.spTeamB);
        spVenue = findViewById(R.id.spVenue);
        spReferee = findViewById(R.id.spReferee);
        spDay = findViewById(R.id.spDay);
        btTime = findViewById(R.id.btTime);
        btSave = findViewById(R.id.btSave);

        List<String> teamNames = new ArrayList<String>();
        for (Team t : tournament.teams) teamNames.add(t.name);
        List<String> venueNames = new ArrayList<String>();
        for (Venue v : tournament.venues) venueNames.add(v.name);
        List<String> refNames = new ArrayList<String>();
        for (Referee r : tournament.referees) refNames.add(r.name);
        List<String> dayNames = new ArrayList<String>();
        for (int d = 1; d <= DBHelper.DAYS; d++) dayNames.add("Day " + d);

        spTeamA.setAdapter(makeAdapter(teamNames));
        spTeamB.setAdapter(makeAdapter(teamNames));
        spVenue.setAdapter(makeAdapter(venueNames));
        spReferee.setAdapter(makeAdapter(refNames));
        spDay.setAdapter(makeAdapter(dayNames));
        if (teamNames.size() > 1) spTeamB.setSelection(1);

        btTime.setText("Start time: " + MainActivity.fmt(startMin));
        btTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new TimePickerDialog(CreateFixtureActivity.this, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker view, int hour, int minute) {
                        startMin = hour * 60 + minute;
                        btTime.setText("Start time: " + MainActivity.fmt(startMin));
                    }
                }, startMin / 60, startMin % 60, true).show();
            }
        });

        btSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
    }

    private ArrayAdapter<String> makeAdapter(List<String> items) {
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return a;
    }

    private void save() {
        if (tournament.teams.size() < 2 || tournament.venues.isEmpty() || tournament.referees.isEmpty()) {
            Toast.makeText(this, "Load the demo tournament from the menu first.", Toast.LENGTH_SHORT).show();
            return;
        }
        Team a = tournament.teams.get(spTeamA.getSelectedItemPosition());
        Team b = tournament.teams.get(spTeamB.getSelectedItemPosition());
        if (a.id.equals(b.id)) {
            Toast.makeText(this, "Pick two different teams", Toast.LENGTH_SHORT).show();
            return;
        }
        Venue v = tournament.venues.get(spVenue.getSelectedItemPosition());
        Referee r = tournament.referees.get(spReferee.getSelectedItemPosition());
        int day = spDay.getSelectedItemPosition();

        Match m = new Match("M" + UUID.randomUUID().toString().substring(0, 6),
                a.id, b.id, v.id, r.id, day, startMin, tournament.matchMin);

        // The same hard-rule checker the scheduler uses
        final List<Match> existing = db.getMatches();
        List<Conflict> conflicts = ConflictChecker.findConflicts(m, existing, tournament);

        if (conflicts.isEmpty()) {
            db.addMatch(m);
            Toast.makeText(this, "Fixture saved", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            StringBuilder sb = new StringBuilder();
            for (Conflict c : conflicts) sb.append("• ").append(c.userMessage()).append("\n");
            final Match suggestion = com.example.refwhistle.domain.DelayPropagator.laterSlot(m, existing, tournament);
            com.google.android.material.dialog.MaterialAlertDialogBuilder dlg =
                    UiKit.dialog(this)
                            .setTitle("That slot is not available")
                            .setMessage(sb.toString() + (suggestion == null ? "" : "\nNearest free slot: Day "
                                    + (suggestion.day + 1) + ", " + MainActivity.fmt(suggestion.startMin) + " at "
                                    + tournament.venueName(suggestion.venueId) + " with referee "
                                    + tournament.refereeName(suggestion.refereeId) + "."))
                            .setNegativeButton("Cancel", null);
            if (suggestion != null) {
                dlg.setPositiveButton("Use that slot", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        db.addMatch(suggestion);
                        Toast.makeText(CreateFixtureActivity.this, "Fixture saved in the free slot",
                                Toast.LENGTH_SHORT).show();
                        finish();
                    }
                });
            }
            dlg.show();
        }
    }
}
