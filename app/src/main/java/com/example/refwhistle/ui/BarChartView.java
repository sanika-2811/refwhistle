package com.example.refwhistle.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Minimal horizontal bar chart (labels left, value at the bar end). */
public class BarChartView extends View {
    private final List<String> labels = new ArrayList<String>();
    private final List<Float> values = new ArrayList<Float>();
    private final List<Integer> colors = new ArrayList<Integer>();
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private float max = 1;

    public BarChartView(Context c) {
        this(c, null);
    }

    public BarChartView(Context c, AttributeSet a) {
        super(c, a);
        tp.setTextSize(UiKit.dp(c, 12));
    }

    public void setBars(List<String> l, List<Float> v, List<Integer> c) {
        labels.clear();
        values.clear();
        colors.clear();
        labels.addAll(l);
        values.addAll(v);
        colors.addAll(c);
        max = 1;
        for (float f : v) max = Math.max(max, f);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int rows = Math.max(1, labels.size());
        setMeasuredDimension(resolveSize(UiKit.dp(getContext(), 300), w),
                rows * UiKit.dp(getContext(), 30) + UiKit.dp(getContext(), 6));
    }

    @Override
    protected void onDraw(Canvas c) {
        float row = UiKit.dp(getContext(), 30);
        float labelW = UiKit.dp(getContext(), 92);
        float valueW = UiKit.dp(getContext(), 40);
        float barMax = getWidth() - labelW - valueW;
        tp.setColor(UiKit.onSurface(this));
        for (int i = 0; i < labels.size(); i++) {
            float y = i * row + UiKit.dp(getContext(), 3);
            String lab = android.text.TextUtils.ellipsize(labels.get(i), tp, labelW - 8,
                    android.text.TextUtils.TruncateAt.END).toString();
            c.drawText(lab, 0, y + row * 0.62f, tp);
            float w = Math.max(UiKit.dp(getContext(), 3), barMax * values.get(i) / max);
            bar.setColor(colors.get(i));
            c.drawRoundRect(new RectF(labelW, y + 4, labelW + w, y + row - 6),
                    UiKit.dp(getContext(), 6), UiKit.dp(getContext(), 6), bar);
            float v = values.get(i);
            String vs = v == Math.round(v) ? String.valueOf((int) v) : String.format("%.1f", v);
            c.drawText(vs, labelW + w + UiKit.dp(getContext(), 6), y + row * 0.62f, tp);
        }
    }
}
