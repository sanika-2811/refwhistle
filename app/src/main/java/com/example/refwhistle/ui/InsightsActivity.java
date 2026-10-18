package com.example.refwhistle.ui;

import android.content.Context;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.refwhistle.data.DBHelper;
import com.example.refwhistle.domain.ConflictChecker;
import com.example.refwhistle.domain.GreedyScheduler;
import com.example.refwhistle.domain.LiveOps;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.Referee;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.Robustness;
import com.example.refwhistle.domain.Schedule;
import com.example.refwhistle.domain.ScheduleCost;
import com.example.refwhistle.domain.Team;
import com.example.refwhistle.domain.Tournament;
import com.example.refwhistle.domain.Venue;

import java.util.ArrayList;
import java.util.List;

/** Short, plain-language summary of how well the schedule works. */
public class InsightsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DBHelper db = new DBHelper(this, getSharedPreferences("refwhistle", Context.MODE_PRIVATE)
                .getString("device_id", "dev-unknown"));
        Tournament t = db.loadTournament();
        Replayer.Result st = db.state();
        List<Match> ms = db.getMatches();

        LinearLayout body = UiKit.screen(this, "Insights", null);
        if (ms.isEmpty()) {
            LinearLayout c = UiKit.card(this, body);
            UiKit.text(this, c, "No schedule yet", 18, true);
            note(c, "Generate a schedule on the main screen and this page will show how well it works.");
            return;
        }

        Schedule basic = GreedyScheduler.schedule(t);

        // ---- at a glance
        int breaks = ruleBreaks(ms, t);
        LinearLayout glance = UiKit.card(this, body);
        UiKit.text(this, glance, "At a glance", 18, true);
        UiKit.tiles(this, glance,
                new String[]{String.valueOf(ms.size()), String.valueOf(breaks),
                        String.valueOf(ScheduleCost.heatMatches(ms, t))},
                new String[]{"Matches", "Rule breaks", "Midday heat"});
        int base = ScheduleCost.cost(basic.matches, t), now = ScheduleCost.cost(ms, t);
        if (base > 0 && now < base) {
            note(glance, "About " + Math.round(100f * (base - now) / base)
                    + "% better overall than a basic scheduler.");
        }

        // ---- referees
        LinearLayout refCard = UiKit.card(this, body);
        UiKit.text(this, refCard, "Referee workload", 18, true);
        List<String> labels = new ArrayList<String>();
        List<Float> values = new ArrayList<Float>();
        List<Integer> colors = new ArrayList<Integer>();
        for (int i = 0; i < t.referees.size(); i++) {
            Referee r = t.referees.get(i);
            int n = 0;
            for (Match m : ms) if (m.refereeId.equals(r.id)) n++;
            labels.add(r.name);
            values.add((float) n);
            colors.add(UiKit.refColor(i));
        }
        chart(refCard, labels, values, colors);
        int gap = ScheduleCost.refereeSpread(ms, t);
        note(refCard, gap == 0 ? "Every referee has the same number of matches."
                : "The busiest referee has " + gap + (gap == 1 ? " match" : " matches") + " more than the least busy.");

        // ---- team waiting time
        LinearLayout waitCard = UiKit.card(this, body);
        UiKit.text(this, waitCard, "Waiting between matches", 18, true);
        labels = new ArrayList<String>();
        values = new ArrayList<Float>();
        colors = new ArrayList<Integer>();
        int totalWait = 0;
        for (Team team : t.teams) {
            List<Match> one = new ArrayList<Match>();
            for (Match m : ms) if (m.hasTeam(team.id)) one.add(m);
            Tournament single = new Tournament();
            single.teams.add(team);
            single.days = t.days;
            int idle = ScheduleCost.idleMinutes(one, single);
            totalWait += idle;
            labels.add(team.name);
            values.add((float) idle);
            colors.add(0xFF7986CB);
        }
        chart(waitCard, labels, values, colors);
        note(waitCard, "Minutes each team spends waiting between its matches on the same day. "
                + "Average: " + totalWait / Math.max(1, t.teams.size()) + " min.");

        // ---- venues
        LinearLayout venueCard = UiKit.card(this, body);
        UiKit.text(this, venueCard, "How busy are the venues", 18, true);
        labels = new ArrayList<String>();
        values = new ArrayList<Float>();
        colors = new ArrayList<Integer>();
        for (Venue ve : t.venues) {
            int used = 0;
            for (Match m : ms) if (m.venueId.equals(ve.id)) used += m.durationMin;
            int open = (ve.closeMin - ve.openMin) * t.days;
            labels.add(ve.name);
            values.add(open == 0 ? 0f : Math.round(100f * used / open));
            colors.add(ve.outdoor ? 0xFFFFA726 : 0xFF26A69A);
        }
        chart(venueCard, labels, values, colors);
        note(venueCard, "Percent of opening time in use. Orange is outdoor, green is indoor.");

        // ---- delay resilience
        Robustness.Stats rBase = Robustness.blastRadius(basic.matches, t, 15);
        Robustness.Stats rNow = Robustness.blastRadius(ms, t, 15);
        LinearLayout delayCard = UiKit.card(this, body);
        UiKit.text(this, delayCard, "If a match runs late", 18, true);
        UiKit.tiles(this, delayCard,
                new String[]{String.format("%.1f", rNow.average), String.format("%.1f", rBase.average)},
                new String[]{"This schedule", "Basic scheduler"});
        note(delayCard, "Average number of matches that must be re-timed when one match starts 15 minutes late. "
                + "Lower is better. Worst case here: " + rNow.worst + " (basic scheduler: " + rBase.worst + ").");
        int[] obs = LiveOps.observedLength(st);
        if (obs[1] > 0) {
            note(delayCard, "Played so far: " + obs[1] + (obs[1] == 1 ? " match" : " matches")
                    + ", " + obs[0] + " min on average (planned " + t.matchMin + " min).");
        }

        // ---- checks
        int coi = 0;
        for (Match m : ms) {
            String own = t.refereeTeamMap().get(m.refereeId);
            if (own != null && !own.isEmpty() && m.hasTeam(own)) coi++;
        }
        LinearLayout checks = UiKit.card(this, body);
        UiKit.text(this, checks, "Safety checks", 18, true);
        line(checks, breaks == 0, breaks == 0 ? "No double-bookings or clashes"
                : breaks + " match(es) break the scheduling rules");
        line(checks, coi == 0, coi == 0 ? "No referee officiates their own team"
                : coi + " referee(s) officiate their own team");
        if (!st.rescheduled.isEmpty()) {
            line(checks, true, st.rescheduled.size() + " clash(es) from syncing were fixed automatically");
        }
        TextView code = UiKit.text(this, checks, "Sync code " + db.ledgerHead()
                + ". Phones with the same code have the same data.", 12, false);
        code.setAlpha(0.7f);
        code.setPadding(0, UiKit.dp(this, 8), 0, 0);
    }

    private void chart(LinearLayout card, List<String> labels, List<Float> values, List<Integer> colors) {
        BarChartView chart = new BarChartView(this);
        chart.setBars(labels, values, colors);
        card.addView(chart);
    }

    private void note(LinearLayout card, String text) {
        TextView tv = UiKit.text(this, card, text, 13, false);
        tv.setAlpha(0.8f);
        tv.setPadding(0, UiKit.dp(this, 8), 0, 0);
    }

    private void line(LinearLayout card, boolean ok, String text) {
        TextView tv = UiKit.text(this, card, (ok ? "✓  " : "✗  ") + text, 14, false);
        tv.setPadding(0, UiKit.dp(this, 6), 0, 0);
    }

    private int ruleBreaks(List<Match> ms, Tournament t) {
        int n = 0;
        for (Match m : ms) {
            if (!ConflictChecker.isFeasible(m, ms, t)) n++;
        }
        return n;
    }
}
