package com.example.refwhistle.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Base64;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Team logos. A logo is a 96x96 PNG, stored as base64 inside a SET_LOGO operation in the
 * append-only log, so it syncs to every phone (hotspot, file import, cloud) like any other change.
 * apply() decodes the logos found in the replayed state; get() hands out round drawables.
 * A team without a logo gets a generated avatar: coloured circle + first letter.
 */
public class Logos {
    private static final int LOGO = 96;
    private static final int AVATAR = 192;
    private static final Map<String, Bitmap> REAL = new HashMap<String, Bitmap>();
    private static final Map<String, Integer> HASH = new HashMap<String, Integer>();
    private static final Map<String, Bitmap> AVATARS = new HashMap<String, Bitmap>();

    /** Reads the picked image, centre-crops to a square, shrinks to 96 px and returns base64 PNG. */
    public static String encode(Context c, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = c.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, bounds);
            in.close();
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= LOGO && bounds.outHeight / (sample * 2) >= LOGO) sample *= 2;
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inSampleSize = sample;
            in = c.getContentResolver().openInputStream(uri);
            Bitmap src = BitmapFactory.decodeStream(in, null, opt);
            in.close();
            if (src == null) return null;
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2,
                    (src.getHeight() - side) / 2, side, side);
            Bitmap out = Bitmap.createScaledBitmap(square, LOGO, LOGO, true);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            out.compress(Bitmap.CompressFormat.PNG, 100, bo);
            return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** Refreshes the in-memory logos from the replayed log state (teamId -> base64 PNG). */
    public static synchronized void apply(Map<String, String> logos) {
        for (Map.Entry<String, String> e : logos.entrySet()) {
            Integer old = HASH.get(e.getKey());
            int h = e.getValue().hashCode();
            if (old != null && old == h) continue;
            try {
                byte[] raw = Base64.decode(e.getValue(), Base64.NO_WRAP);
                Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                if (b != null) {
                    REAL.put(e.getKey(), b);
                    HASH.put(e.getKey(), h);
                }
            } catch (IllegalArgumentException ignored) {
                // corrupt entry from another phone: keep the avatar
            }
        }
    }

    public static synchronized boolean has(String teamId) {
        return REAL.containsKey(teamId);
    }

    /** Round logo (or generated initial avatar) for a team. */
    public static synchronized Drawable get(Context c, String teamId, String teamName) {
        Bitmap b = REAL.get(teamId);
        if (b == null) {
            b = AVATARS.get(teamId);
            if (b == null) {
                b = avatar(teamId, teamName);
                AVATARS.put(teamId, b);
            }
        }
        RoundedBitmapDrawable d = RoundedBitmapDrawableFactory.create(c.getResources(), b);
        d.setCircular(true);
        d.setAntiAlias(true);
        return d;
    }

    private static Bitmap avatar(String teamId, String name) {
        Bitmap b = Bitmap.createBitmap(AVATAR, AVATAR, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(UiKit.refColor(teamId.hashCode()));
        cv.drawCircle(AVATAR / 2f, AVATAR / 2f, AVATAR / 2f, p);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(AVATAR * 0.5f);
        p.setFakeBoldText(true);
        p.setTextAlign(Paint.Align.CENTER);
        String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
        Rect r = new Rect();
        p.getTextBounds(letter, 0, 1, r);
        cv.drawText(letter, AVATAR / 2f, AVATAR / 2f - r.exactCenterY(), p);
        return b;
    }
}
