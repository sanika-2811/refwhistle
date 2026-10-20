package com.example.refwhistle.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.Standings;
import com.example.refwhistle.domain.Tournament;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/** League table. The JSON button feeds the small Flutter dashboard (see /flutter_standings). */
public class StandingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DBHelper db = new DBHelper(this, getSharedPreferences("refwhistle", Context.MODE_PRIVATE)
                .getString("device_id", "dev-unknown"));
        Tournament t = db.loadTournament();
        Replayer.Result st = db.state();
        final List<Standings.Row> rows = Standings.compute(t, st.matches, st.results);

        LinearLayout body = UiKit.screen(this, "League table",
                st.results.size() + (st.results.size() == 1 ? " match" : " matches")
                + " played  •  3 points for a win, 1 for a draw");
        LinearLayout card = UiKit.card(this, body);
        card.addView(row("#", "Team", null, "P", "W", "D", "L", "GD", "Pts", true, 0));
        for (int i = 0; i < rows.size(); i++) {
            Standings.Row r = rows.get(i);
            card.addView(row(String.valueOf(i + 1), r.name, r.teamId, String.valueOf(r.played), String.valueOf(r.won),
                    String.valueOf(r.drawn), String.valueOf(r.lost),
                    (r.goalDiff() > 0 ? "+" : "") + r.goalDiff(), String.valueOf(r.points), false, i));
        }

        MaterialButton share = new MaterialButton(this);
        share.setText("Share standings for the live dashboard");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(UiKit.dp(this, 12), UiKit.dp(this, 8), UiKit.dp(this, 12), 0);
        share.setLayoutParams(lp);
        share.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, Standings.toJson(rows));
                startActivity(Intent.createChooser(send, "Standings JSON"));
            }
        });
        body.addView(share);
    }

    private View row(String rank, String team, String teamId, String p, String w, String d, String l, String gd, String pts,
                     boolean header, int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = UiKit.dp(this, header ? 4 : 9);
        row.setPadding(0, pad, 0, pad);
        row.addView(cell(rank, 0.5f, header, false, index < 3 && !header));
        TextView name = cell(team, 3f, header, false, false);
        name.setGravity(Gravity.START);
        row.addView(name);
        row.addView(cell(p, 0.8f, header, false, false));
        row.addView(cell(w, 0.8f, header, false, false));
        row.addView(cell(d, 0.8f, header, false, false));
        row.addView(cell(l, 0.8f, header, false, false));
        row.addView(cell(gd, 1f, header, false, false));
        row.addView(cell(pts, 1f, header, true, false));
        return row;
    }

    private TextView cell(String s, float weight, boolean header, boolean bold, boolean accent) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(header ? 12 : 14);
        if (header || bold || accent) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        if (header) tv.setAlpha(0.65f);
        if (accent) tv.setTextColor(UiKit.primary(tv));
        tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, weight));
        return tv;
    }
}
