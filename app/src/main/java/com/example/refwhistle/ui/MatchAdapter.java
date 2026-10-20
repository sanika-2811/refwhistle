package com.example.refwhistle.ui;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.refwhistle.R;
import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.Tournament;
import com.example.refwhistle.domain.Venue;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;

/** Card list of matches with colour stripe per referee and status chips. */
public class MatchAdapter extends RecyclerView.Adapter<MatchAdapter.VH> {
    public interface Listener {
        void onMatchClick(Match m);
    }

    private final List<Match> items = new ArrayList<Match>();
    private Tournament t = new Tournament();
    private Replayer.Result res;
    private final Listener listener;

    public MatchAdapter(Listener l) {
        this.listener = l;
    }

    public void set(List<Match> matches, Tournament tournament, Replayer.Result result) {
        items.clear();
        items.addAll(matches);
        t = tournament;
        res = result;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_match, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final Match m = items.get(position);
        int refIdx = 0;
        for (int i = 0; i < t.referees.size(); i++) {
            if (t.referees.get(i).id.equals(m.refereeId)) refIdx = i;
        }
        h.stripe.setBackgroundColor(UiKit.refColor(refIdx));
        h.time.setText(MainActivity.fmt(m.startMin));
        h.dur.setText(m.durationMin + " min");
        h.teams.setText(t.teamName(m.teamA) + "  vs  " + t.teamName(m.teamB));
        h.meta.setText(t.venueName(m.venueId) + "  •  Ref " + t.refereeName(m.refereeId));
        h.chips.removeAllViews();
        Venue v = t.venueMap().get(m.venueId);
        if (v != null) addChip(h, v.outdoor ? "Outdoor" : "Indoor", Color.parseColor("#78909C"));
        if (res != null && res.rescheduled.contains(m.id)) addChip(h, "Auto-rescheduled", Color.parseColor("#E53935"));
        if (res != null && res.moved.contains(m.id)) addChip(h, "Re-timed", Color.parseColor("#FB8C00"));
        if (res != null && res.actualStart.containsKey(m.id)) {
            Integer end = res.actualEnd.get(m.id);
            if (end == null) {
                addChip(h, "Live since " + MainActivity.fmt(res.actualStart.get(m.id)), Color.parseColor("#E53935"));
            } else {
                addChip(h, "Played " + (end - res.actualStart.get(m.id)) + " min", Color.parseColor("#00897B"));
            }
        }
        int[] r = res == null ? null : res.results.get(m.id);
        if (r != null) {
            h.score.setText(r[0] + " - " + r[1]);
            addChip(h, "Final", Color.parseColor("#43A047"));
        } else {
            h.score.setText("");
        }
        h.itemView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                listener.onMatchClick(m);
            }
        });
    }

    private void addChip(VH h, String text, int color) {
        Chip c = new Chip(h.itemView.getContext());
        c.setText(text);
        c.setTextSize(11);
        c.setTextColor(Color.WHITE);
        c.setChipBackgroundColor(ColorStateList.valueOf(color));
        c.setChipStrokeWidth(0);
        c.setEnsureMinTouchTargetSize(false);
        c.setClickable(false);
        c.setChipMinHeight(UiKit.dp(h.itemView.getContext(), 22));
        h.chips.addView(c);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View stripe;
        final TextView time, dur, teams, meta, score;
        final ChipGroup chips;

        VH(View v) {
            super(v);
            stripe = v.findViewById(R.id.stripe);
            time = v.findViewById(R.id.tvTime);
            dur = v.findViewById(R.id.tvDur);
            teams = v.findViewById(R.id.tvTeams);
            meta = v.findViewById(R.id.tvMeta);
            score = v.findViewById(R.id.tvScore);
            chips = v.findViewById(R.id.chips);
        }
    }
}
