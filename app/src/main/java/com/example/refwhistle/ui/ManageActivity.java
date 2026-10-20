package com.example.refwhistle.ui;

import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.refwhistle.R;
import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.Referee;
import com.example.refwhistle.domain.Team;
import com.example.refwhistle.domain.Venue;

/** Admin screen: add teams, venues (with hours and map position) and referees. */
public class ManageActivity extends AppCompatActivity {
    private DBHelper db;
    private Spinner spKind;
    private EditText etName, etLat, etLng;
    private CheckBox cbOutdoor;
    private Button btOpen, btClose;
    private View venueBox;
    private Spinner spRefTeam;
    private java.util.List<Team> teamList = new java.util.ArrayList<Team>();
    private TextView tvList;
    private int openMin = 8 * 60, closeMin = 20 * 60;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new DBHelper(this, getSharedPreferences("refwhistle", Context.MODE_PRIVATE)
                .getString("device_id", "dev-unknown"));
        if (!MainActivity.isAdminNow(this, db)) {
            Toast.makeText(this, "Only admins can change the setup.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_manage);
        spKind = findViewById(R.id.spKind);
        etName = findViewById(R.id.etName);
        etLat = findViewById(R.id.etLat);
        etLng = findViewById(R.id.etLng);
        cbOutdoor = findViewById(R.id.cbOutdoor);
        btOpen = findViewById(R.id.btOpen);
        btClose = findViewById(R.id.btClose);
        venueBox = findViewById(R.id.venueBox);
        spRefTeam = findViewById(R.id.spRefTeam);
        tvList = findViewById(R.id.tvList);

        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item,
                new String[]{"Team", "Venue", "Referee"});
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spKind.setAdapter(a);
        spKind.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                venueBox.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
                spRefTeam.setVisibility(pos == 2 ? View.VISIBLE : View.GONE);
                if (pos == 2) loadRefTeams();
            }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        btOpen.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pick(true); }
        });
        btClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pick(false); }
        });
        findViewById(R.id.btAddItem).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { add(); }
        });
        findViewById(R.id.btEditItem).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickItem(); }
        });
        showList();
    }

    /** First step of rename / remove: choose which team, venue or referee. */
    private void pickItem() {
        final java.util.List<String> labels = new java.util.ArrayList<String>();
        final java.util.List<String> kinds = new java.util.ArrayList<String>();
        final java.util.List<String> ids = new java.util.ArrayList<String>();
        final java.util.List<String> names = new java.util.ArrayList<String>();
        for (Team t : db.getTeams()) { labels.add("Team: " + t.name); kinds.add("Team"); ids.add(t.id); names.add(t.name); }
        for (Venue v : db.getVenues()) { labels.add("Venue: " + v.name); kinds.add("Venue"); ids.add(v.id); names.add(v.name); }
        for (Referee r : db.getReferees()) { labels.add("Referee: " + r.name); kinds.add("Referee"); ids.add(r.id); names.add(r.name); }
        if (labels.isEmpty()) {
            Toast.makeText(this, "Nothing to edit yet", Toast.LENGTH_SHORT).show();
            return;
        }
        UiKit.dialog(this).setTitle("Which one?")
                .setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        itemActions(kinds.get(which), ids.get(which), names.get(which));
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void itemActions(final String kind, final String id, final String name) {
        final EditText et = new EditText(this);
        et.setText(name);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setPadding(UiKit.dp(this, 24), UiKit.dp(this, 12), UiKit.dp(this, 24), 0);
        box.addView(et, new android.widget.LinearLayout.LayoutParams(-1, -2));
        UiKit.dialog(this).setTitle(kind + ": " + name).setView(box)
                .setPositiveButton("Rename", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        rename(kind, id, et.getText().toString().trim());
                    }
                })
                .setNeutralButton("Remove", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        remove(kind, id, name);
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void rename(String kind, String id, String newName) {
        if (newName.isEmpty()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show();
            return;
        }
        if (kind.equals("Team")) {
            db.addTeam(id, newName);
        } else if (kind.equals("Venue")) {
            for (Venue v : db.getVenues()) {
                if (v.id.equals(id)) db.addVenue(id, newName, v.outdoor, v.openMin, v.closeMin, v.lat, v.lng);
            }
        } else {
            for (Referee r : db.getReferees()) {
                if (r.id.equals(id)) db.addReferee(id, newName, r.teamId);
            }
        }
        showList();
    }

    private void remove(String kind, String id, String name) {
        // a team, venue or referee that still has matches cannot go: those matches would break
        for (com.example.refwhistle.domain.Match m : db.getMatches()) {
            boolean used = kind.equals("Team") ? m.hasTeam(id)
                    : kind.equals("Venue") ? m.venueId.equals(id) : m.refereeId.equals(id);
            if (used) {
                Toast.makeText(this, name + " still has matches. Cancel those matches first.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        if (kind.equals("Team")) db.removeTeam(id);
        else if (kind.equals("Venue")) db.removeVenue(id);
        else db.removeReferee(id);
        showList();
    }

    private void loadRefTeams() {
        teamList = db.getTeams();
        java.util.List<String> names = new java.util.ArrayList<String>();
        names.add("Referee's own team: none");
        for (Team t : teamList) names.add("Referee's own team: " + t.name);
        spRefTeam.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }

    private void pick(final boolean open) {
        int cur = open ? openMin : closeMin;
        new TimePickerDialog(this, new TimePickerDialog.OnTimeSetListener() {
            public void onTimeSet(TimePicker view, int h, int m) {
                if (open) {
                    openMin = h * 60 + m;
                    btOpen.setText("Opens " + MainActivity.fmt(openMin));
                } else {
                    closeMin = h * 60 + m;
                    btClose.setText("Closes " + MainActivity.fmt(closeMin));
                }
            }
        }, cur / 60, cur % 60, true).show();
    }

    private void add() {
        String name = etName.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show();
            return;
        }
        int kind = spKind.getSelectedItemPosition();
        // time + a few random characters: two admins adding at the same moment never get the same id
        String id = (kind == 0 ? "T" : kind == 1 ? "V" : "R") + System.currentTimeMillis() % 100000
                + "-" + java.util.UUID.randomUUID().toString().substring(0, 3);
        if (kind == 0) {
            db.addTeam(id, name);
        } else if (kind == 1) {
            if (closeMin <= openMin) {
                Toast.makeText(this, "Closing time must be after opening", Toast.LENGTH_SHORT).show();
                return;
            }
            db.addVenue(id, name, cbOutdoor.isChecked(), openMin, closeMin,
                    parse(etLat), parse(etLng));
        } else {
            int sel = spRefTeam.getSelectedItemPosition();
            // item 0 = "no team"; the scheduler never gives a referee a match of their own team
            db.addReferee(id, name, sel <= 0 || sel > teamList.size() ? "" : teamList.get(sel - 1).id);
        }
        etName.setText("");
        showList();
    }

    private double parse(EditText e) {
        try {
            return Double.parseDouble(e.getText().toString().trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private void showList() {
        StringBuilder sb = new StringBuilder("Teams:\n");
        for (Team t : db.getTeams()) sb.append("  ").append(t.name).append('\n');
        sb.append("\nVenues:\n");
        for (Venue v : db.getVenues()) {
            sb.append("  ").append(v.name).append(v.outdoor ? " (outdoor) " : " (indoor) ")
                    .append(MainActivity.fmt(v.openMin)).append("-").append(MainActivity.fmt(v.closeMin))
                    .append(v.hasLocation() ? "  GPS set" : "").append('\n');
        }
        sb.append("\nReferees:\n");
        for (Referee r : db.getReferees()) sb.append("  ").append(r.name).append('\n');
        tvList.setText(sb.toString());
    }

    private String teamName(String id) {
        for (Team t : db.getTeams()) if (t.id.equals(id)) return t.name;
        return id;
    }
}
