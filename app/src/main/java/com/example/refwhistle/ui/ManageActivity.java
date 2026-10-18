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
    private android.widget.LinearLayout logoBox;
    private String pendingLogoTeam;
    private final androidx.activity.result.ActivityResultLauncher<String[]> logoPicker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    new androidx.activity.result.ActivityResultCallback<android.net.Uri>() {
                        public void onActivityResult(android.net.Uri uri) {
                            if (uri == null || pendingLogoTeam == null) return;
                            String b64 = Logos.encode(ManageActivity.this, uri);
                            boolean ok = b64 != null;
                            if (ok) db.setLogo(pendingLogoTeam, b64);
                            Toast.makeText(ManageActivity.this, ok ? "Logo saved" : "Could not read that image",
                                    Toast.LENGTH_SHORT).show();
                            showList();
                        }
                    });
    private Spinner spRefTeam;
    private java.util.List<Team> teamList = new java.util.ArrayList<Team>();
    private TextView tvList;
    private int openMin = 8 * 60, closeMin = 20 * 60;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage);
        db = new DBHelper(this, getSharedPreferences("refwhistle", Context.MODE_PRIVATE)
                .getString("device_id", "dev-unknown"));
        spKind = findViewById(R.id.spKind);
        etName = findViewById(R.id.etName);
        etLat = findViewById(R.id.etLat);
        etLng = findViewById(R.id.etLng);
        cbOutdoor = findViewById(R.id.cbOutdoor);
        btOpen = findViewById(R.id.btOpen);
        btClose = findViewById(R.id.btClose);
        venueBox = findViewById(R.id.venueBox);
        logoBox = findViewById(R.id.logoBox);
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
        String id = (kind == 0 ? "T" : kind == 1 ? "V" : "R") + System.currentTimeMillis() % 100000;
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

    private void showLogos() {
        Logos.apply(db.state().logos);
        logoBox.removeAllViews();
        for (final Team t : db.getTeams()) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
            android.widget.ImageView iv = new android.widget.ImageView(this);
            iv.setImageDrawable(Logos.get(this, t.id, t.name));
            row.addView(iv, new android.widget.LinearLayout.LayoutParams(UiKit.dp(this, 40), UiKit.dp(this, 40)));
            TextView name = new TextView(this);
            name.setText(t.name);
            name.setPadding(UiKit.dp(this, 12), 0, 0, 0);
            row.addView(name, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
            Button pick = new Button(this);
            pick.setText(Logos.has(t.id) ? "Change" : "Set logo");
            pick.setTextSize(12);
            pick.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    pendingLogoTeam = t.id;
                    logoPicker.launch(new String[]{"image/*"});
                }
            });
            row.addView(pick);
            logoBox.addView(row);
        }
    }

    private void showList() {
        showLogos();
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
