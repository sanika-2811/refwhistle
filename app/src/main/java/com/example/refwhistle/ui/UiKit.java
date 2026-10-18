package com.example.refwhistle.ui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.color.MaterialColors;

/** Small helpers shared by the screens: dp conversion, palette, and quick card building. */
public class UiKit {
    /** Referee colour palette (also used for match stripes and timeline blocks). */
    public static final int[] PALETTE = {
            0xFF3949AB, 0xFF00897B, 0xFFE64A19, 0xFF8E24AA,
            0xFF1E88E5, 0xFFF9A825, 0xFF6D4C41, 0xFFD81B60};

    public static int refColor(int index) {
        return PALETTE[Math.abs(index) % PALETTE.length];
    }

    /** Alert dialog in the app's glass style (use instead of a plain builder). */
    public static MaterialAlertDialogBuilder dialog(Context c) {
        return new MaterialAlertDialogBuilder(c)
                .setBackground(androidx.core.content.ContextCompat.getDrawable(c, com.example.refwhistle.R.drawable.bg_dialog));
    }

    /** A row of equal "stat tiles": big value on top, small caption below. */
    public static void tiles(Context c, LinearLayout parent, String[] values, String[] captions) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(c, 10);
        parent.addView(row, rp);
        for (int i = 0; i < values.length; i++) {
            LinearLayout tile = new LinearLayout(c);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setBackgroundResource(com.example.refwhistle.R.drawable.bg_stat);
            tile.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(c, i == 0 ? 0 : 4), 0, dp(c, i == values.length - 1 ? 0 : 4), 0);
            row.addView(tile, lp);
            TextView v = new TextView(c);
            v.setText(values[i]);
            v.setTextSize(22);
            v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
            v.setTextColor(0xFFFFFFFF);
            tile.addView(v);
            TextView cap = new TextView(c);
            cap.setText(captions[i]);
            cap.setTextSize(12);
            cap.setTextColor(0xCCFFFFFF);
            tile.addView(cap);
        }
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static int onSurface(View v) {
        return MaterialColors.getColor(v, com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
    }

    public static int surfaceVariant(View v) {
        return MaterialColors.getColor(v, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY);
    }

    public static int primary(View v) {
        return MaterialColors.getColor(v, androidx.appcompat.R.attr.colorPrimary, 0xFF3949AB);
    }

    /** A rounded card with a vertical LinearLayout inside; add your views to the returned layout. */
    public static LinearLayout card(Context c, LinearLayout parent) {
        MaterialCardView card = new MaterialCardView(c);
        card.setRadius(dp(c, 22));
        card.setCardElevation(0);
        card.setCardBackgroundColor(0x24FFFFFF);          // frosted glass
        card.setStrokeColor(0x4DFFFFFF);
        card.setStrokeWidth(dp(c, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        card.setLayoutParams(lp);
        LinearLayout inner = new LinearLayout(c);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        card.addView(inner);
        parent.addView(card);
        return inner;
    }

    public static TextView text(Context c, LinearLayout parent, String s, float sp, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        parent.addView(tv, new LinearLayout.LayoutParams(-1, -2));
        return tv;
    }

    /** Builds a screen with the gradient header + scrolling body; returns the body layout. */
    public static LinearLayout screen(final android.app.Activity a, String title, String subtitle) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        LinearLayout header = new LinearLayout(a);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackgroundResource(com.example.refwhistle.R.drawable.bg_header);
        header.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 22));
        TextView back = new TextView(a);
        back.setText("< Back");
        back.setTextColor(0xCCFFFFFF);
        back.setTextSize(14);
        back.setPadding(0, 0, 0, dp(a, 6));
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                a.finish();
            }
        });
        header.addView(back);
        TextView t = new TextView(a);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(24);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(t);
        if (subtitle != null) {
            TextView st = new TextView(a);
            st.setText(subtitle);
            st.setTextColor(0xCCFFFFFF);
            st.setTextSize(13);
            header.addView(st);
        }
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(a, 8), 0, dp(a, 24));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        a.setContentView(root);
        return body;
    }
}
