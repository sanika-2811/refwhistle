package com.example.refwhistle.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import com.example.refwhistle.domain.Match;
import com.example.refwhistle.domain.Replayer;
import com.example.refwhistle.domain.ScheduleCost;
import com.example.refwhistle.domain.Tournament;
import com.example.refwhistle.domain.Venue;

import java.util.ArrayList;
import java.util.List;

/**
 * Gantt-style day view: one lane per venue, time across. Blocks are coloured by referee.
 * Outdoor lanes show the midday-heat window; clash-fixed matches get a red outline, matches with
 * a result are dimmed. Tap a block to open its actions.
 */
public class TimelineView extends View {
    public interface Listener {
        void onMatchClick(Match m);
    }

    private static final int START_H = 8, END_H = 22;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final List<Match> matches = new ArrayList<Match>();
    private final List<RectF> rects = new ArrayList<RectF>();
    private Tournament t = new Tournament();
    private Replayer.Result res;
    private Listener listener;
    private final float left, hourW, laneH, top, pad;
    private final GestureDetector detector;

    public TimelineView(Context c) {
        this(c, null);
    }

    public TimelineView(Context c, AttributeSet a) {
        super(c, a);
        left = UiKit.dp(c, 92);
        hourW = UiKit.dp(c, 78);
        laneH = UiKit.dp(c, 76);
        top = UiKit.dp(c, 30);
        pad = UiKit.dp(c, 4);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(UiKit.dp(c, 2.5f));
        line.setStrokeWidth(1);
        detector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                for (int i = rects.size() - 1; i >= 0; i--) {
                    if (rects.get(i).contains(e.getX(), e.getY()) && listener != null) {
                        listener.onMatchClick(matches.get(i));
                        return true;
                    }
                }
                return false;
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setData(List<Match> dayMatches, Tournament tournament, Replayer.Result result) {
        matches.clear();
        matches.addAll(dayMatches);
        t = tournament;
        res = result;
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = (int) (left + (END_H - START_H) * hourW + pad * 2);
        int h = (int) (top + Math.max(1, t.venues.size()) * laneH + pad * 2);
        setMeasuredDimension(w, h);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return detector.onTouchEvent(e) || super.onTouchEvent(e);
    }

    @Override
    protected void onDraw(Canvas c) {
        int ink = UiKit.onSurface(this);
        int faint = (ink & 0x00FFFFFF) | 0x22000000;
        rects.clear();
        text.setColor(ink);
        text.setTextSize(UiKit.dp(getContext(), 11));

        // hour grid and labels
        line.setColor(faint);
        for (int h = START_H; h <= END_H; h++) {
            float x = left + (h - START_H) * hourW;
            c.drawLine(x, top, x, top + t.venues.size() * laneH, line);
            if (h < END_H) {
                text.setColor(ink);
                c.drawText(String.format("%02d:00", h), x + pad, top - UiKit.dp(getContext(), 8), text);
            }
        }

        float radius = UiKit.dp(getContext(), 10);
        for (int i = 0; i < t.venues.size(); i++) {
            Venue v = t.venues.get(i);
            float y = top + i * laneH;
            // lane background stripe
            fill.setColor(i % 2 == 0 ? (ink & 0x00FFFFFF) | 0x0C000000 : 0x00000000);
            c.drawRect(0, y, getWidth(), y + laneH, fill);
            // closed hours
            fill.setColor((ink & 0x00FFFFFF) | 0x22000000);
            if (v.openMin > START_H * 60) {
                c.drawRect(left, y, x(v.openMin), y + laneH, fill);
            }
            if (v.closeMin < END_H * 60) {
                c.drawRect(x(v.closeMin), y, x(END_H * 60), y + laneH, fill);
            }
            // heat window on outdoor lanes
            if (v.outdoor) {
                fill.setColor(0x33FF9800);
                c.drawRect(x(ScheduleCost.HEAT_START), y, x(ScheduleCost.HEAT_END), y + laneH, fill);
            }
            text.setColor(ink);
            text.setFakeBoldText(true);
            String label = TextUtils.ellipsize(v.name, text, left - pad * 2, TextUtils.TruncateAt.END).toString();
            c.drawText(label, pad, y + laneH / 2 - UiKit.dp(getContext(), 2), text);
            text.setFakeBoldText(false);
            text.setTextSize(UiKit.dp(getContext(), 10));
            c.drawText(v.outdoor ? "outdoor" : "indoor", pad, y + laneH / 2 + UiKit.dp(getContext(), 11), text);
            text.setTextSize(UiKit.dp(getContext(), 11));
        }

        // match blocks
        for (Match m : matches) {
            int lane = laneOf(m.venueId);
            if (lane < 0) {
                rects.add(new RectF());
                continue;
            }
            float y = top + lane * laneH + pad * 2;
            RectF r = new RectF(x(m.startMin) + 1, y, x(m.endMin()) - 1, y + laneH - pad * 4);
            rects.add(r);
            int refIdx = 0;
            for (int i = 0; i < t.referees.size(); i++) {
                if (t.referees.get(i).id.equals(m.refereeId)) refIdx = i;
            }
            boolean done = res != null && res.results.containsKey(m.id);
            fill.setColor(UiKit.refColor(refIdx));
            fill.setAlpha(done ? 110 : 215);                      // slightly see-through: glass blocks
            c.drawRoundRect(r, radius, radius, fill);
            stroke.setColor(0x66FFFFFF);
            c.drawRoundRect(r, radius, radius, stroke);
            if (res != null && res.rescheduled.contains(m.id)) {
                stroke.setColor(0xFFE53935);
                c.drawRoundRect(r, radius, radius, stroke);
            } else if (res != null && res.moved.contains(m.id)) {
                stroke.setColor(0xFFFFB300);
                c.drawRoundRect(r, radius, radius, stroke);
            }
            text.setColor(0xFFFFFFFF);
            text.setFakeBoldText(true);
            float room = r.width() - pad * 3;
            String teams = t.teamName(m.teamA) + " v " + t.teamName(m.teamB);
            c.drawText(TextUtils.ellipsize(teams, text, room, TextUtils.TruncateAt.END).toString(),
                    r.left + pad * 1.5f, r.top + UiKit.dp(getContext(), 17), text);
            text.setFakeBoldText(false);
            int[] sc = res == null ? null : res.results.get(m.id);
            String sub = (sc != null ? sc[0] + "-" + sc[1] + "  " : "") + MainActivity.fmt(m.startMin) + "  "
                    + t.refereeName(m.refereeId);
            c.drawText(TextUtils.ellipsize(sub, text, room, TextUtils.TruncateAt.END).toString(),
                    r.left + pad * 1.5f, r.top + UiKit.dp(getContext(), 32), text);
        }
    }

    private float x(int minutes) {
        return left + (minutes - START_H * 60) / 60f * hourW;
    }

    private int laneOf(String venueId) {
        for (int i = 0; i < t.venues.size(); i++) {
            if (t.venues.get(i).id.equals(venueId)) return i;
        }
        return -1;
    }
}
