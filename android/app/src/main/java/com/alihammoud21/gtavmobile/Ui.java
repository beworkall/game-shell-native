package com.alihammoud21.gtavmobile;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Small hand-made design system for Game Shell Native: OLED black, rounded cards, one accent colour,
 * press animation and haptics. Plain Android views only (no AndroidX / Compose needed).
 */
final class Ui {
    // ---------------------------------------------------------------- palette
    static final int BG = 0xFF000000, SURFACE = 0xFF111316, SURFACE2 = 0xFF1B1E24, STROKE = 0xFF2A2F37;
    static final int TEXT = 0xFFF2F4F7, MUTED = 0xFF98A2B3, OK = 0xFF3DDC97, WARN = 0xFFFFC24B, BAD = 0xFFFF5A5F;
    static final String[] ACCENT_NAMES = {"Sunset orange", "Electric cyan", "Mint green", "Soft purple", "Hot pink"};
    static final int[] ACCENTS = {0xFFFF7A1A, 0xFF00D8F0, 0xFF3DDC97, 0xFFA78BFA, 0xFFFF4D8D};

    final Context c;
    final int accent;
    final boolean haptics;

    Ui(Context c, int accentIndex, boolean haptics) {
        this.c = c;
        this.accent = ACCENTS[Math.max(0, Math.min(ACCENTS.length - 1, accentIndex))];
        this.haptics = haptics;
    }

    static Ui of(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("shell", Context.MODE_PRIVATE);
        return new Ui(c, p.getInt("accent", 0), p.getBoolean("haptics", true));
    }

    int dp(float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    /** Readable text colour on top of the accent. */
    int onAccent() {
        int r = (accent >> 16) & 0xFF, g = (accent >> 8) & 0xFF, b = accent & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 150 ? 0xFF000000 : 0xFFFFFFFF;
    }

    GradientDrawable round(int fill, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    // ---------------------------------------------------------------- haptics
    static void buzz(Context c, int kind) {
        try {
            Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (Build.VERSION.SDK_INT >= 29) {
                int effect = kind == 2 ? VibrationEffect.EFFECT_DOUBLE_CLICK : kind == 1 ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK;
                v.vibrate(VibrationEffect.createPredefined(effect));
            } else {
                v.vibrate(VibrationEffect.createOneShot(kind == 2 ? 40 : kind == 1 ? 18 : 8, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Throwable ignored) { }
    }

    /** 0 = tick (tap), 1 = click (success), 2 = double (error). */
    void buzz(int kind) { if (haptics) buzz(c, kind); }

    /** Scale-down press animation plus a light tick. */
    void pressFx(final View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    buzz(0);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
                default: break;
            }
            return false;
        });
    }

    static void enter(View v, Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        v.setAlpha(0f);
        v.setTranslationY(14 * d);
        v.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(new DecelerateInterpolator()).start();
    }

    /** Fades a list of children in one after another. */
    static void stagger(ViewGroup g, Context c) {
        float d = c.getResources().getDisplayMetrics().density;
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            v.setAlpha(0f); v.setTranslationY(12 * d);
            v.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(i, 10) * 45L).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    // ---------------------------------------------------------------- page scaffolding
    /** Black scrolling page; returns the column to fill. */
    LinearLayout page(Activity a) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(28), dp(18), dp(36));
        ScrollView sv = new ScrollView(c);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        sv.addView(col);
        a.setContentView(sv);
        enter(col, c);
        return col;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    TextView title(String s) { TextView t = text(s, 28, TEXT, true); t.setPadding(0, 0, 0, dp(2)); return t; }
    TextView subtitle(String s) { TextView t = text(s, 14, MUTED, false); t.setPadding(0, 0, 0, dp(14)); return t; }
    TextView body(String s) { TextView t = text(s, 14, MUTED, false); t.setLineSpacing(0, 1.15f); return t; }
    TextView label(String s) { TextView t = text(s, 13, TEXT, false); t.setPadding(0, dp(10), 0, dp(4)); return t; }

    TextView section(String s) {
        TextView t = text(s.toUpperCase(java.util.Locale.US), 12, accent, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(4), dp(18), 0, dp(8));
        return t;
    }

    LinearLayout card() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(round(SURFACE, 18, STROKE));
        l.setPadding(dp(16), dp(14), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        l.setLayoutParams(lp);
        return l;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    /** Adds views to a row with equal weight and a small gap. */
    LinearLayout split(View... views) {
        LinearLayout r = row();
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            r.addView(views[i], lp);
        }
        return r;
    }

    private Button baseButton(String label, Runnable action) {
        Button b = new Button(c);
        b.setText(label); b.setAllCaps(false); b.setTextSize(15);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        b.setOnClickListener(v -> { try { action.run(); } catch (Throwable t) { Toast.makeText(c, "Error: " + t, Toast.LENGTH_LONG).show(); buzz(2); } });
        pressFx(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    Button primary(String label, Runnable action) {
        Button b = baseButton(label, action);
        b.setBackground(round(accent, 14, 0));
        b.setTextColor(onAccent());
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return b;
    }

    Button secondary(String label, Runnable action) {
        Button b = baseButton(label, action);
        b.setBackground(round(SURFACE2, 14, STROKE));
        b.setTextColor(TEXT);
        return b;
    }

    Button danger(String label, Runnable action) {
        Button b = baseButton(label, action);
        b.setBackground(round(alpha(BAD, 0x22), 14, alpha(BAD, 0x88)));
        b.setTextColor(BAD);
        return b;
    }

    /** A coloured dot followed by text. kind: 0 ok, 1 warn, 2 bad, 3 neutral. */
    LinearLayout status(String s, int kind) {
        LinearLayout r = row();
        View dot = new View(c);
        int col = kind == 0 ? OK : kind == 1 ? WARN : kind == 2 ? BAD : MUTED;
        dot.setBackground(round(col, 6, 0));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(10), dp(10));
        dl.rightMargin = dp(10);
        r.addView(dot, dl);
        TextView t = text(s, 14, TEXT, false);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setPadding(0, dp(4), 0, dp(4));
        r.setTag(t);
        return r;
    }

    static void setStatus(LinearLayout statusRow, String s, int kind) {
        if (statusRow == null) return;
        ((TextView) statusRow.getTag()).setText(s);
        int col = kind == 0 ? OK : kind == 1 ? WARN : kind == 2 ? BAD : MUTED;
        GradientDrawable d = (GradientDrawable) statusRow.getChildAt(0).getBackground();
        d.setColor(col);
    }

    TextView pill(String s, int color) {
        TextView t = text(s, 11, color, true);
        t.setBackground(round(alpha(color, 0x26), 10, alpha(color, 0x66)));
        t.setPadding(dp(8), dp(2), dp(8), dp(2));
        return t;
    }

    Spinner spinner(String[] items, int selected) {
        Spinner s = new Spinner(c);
        ArrayAdapter<String> a = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) { ((TextView) v).setTextColor(TEXT); ((TextView) v).setTextSize(15); ((TextView) v).setEllipsize(TextUtils.TruncateAt.END); }
                return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.max(0, Math.min(items.length - 1, selected)));
        s.setBackground(round(SURFACE2, 12, STROKE));
        s.setPadding(dp(8), dp(4), dp(8), dp(4));
        s.setPopupBackgroundDrawable(round(SURFACE2, 12, STROKE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        s.setLayoutParams(lp);
        return s;
    }

    EditText input(String value, String hint) {
        EditText e = new EditText(c);
        e.setSingleLine(); e.setText(value); e.setHint(hint);
        e.setTextColor(TEXT); e.setHintTextColor(MUTED); e.setTextSize(15);
        e.setBackground(round(SURFACE2, 12, STROKE));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    Switch toggle(String label, boolean on) {
        Switch s = new Switch(c);
        s.setText(label); s.setTextColor(TEXT); s.setTextSize(15); s.setChecked(on);
        s.setPadding(0, dp(10), 0, dp(10));
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{accent, 0xFFB0B7C3}));
        s.setTrackTintList(new ColorStateList(states, new int[]{alpha(accent, 0x88), 0xFF3A404A}));
        s.setOnCheckedChangeListener((b, v) -> buzz(0));
        return s;
    }

    /** Seek bar with a live value read-out. format turns the progress into text. */
    interface Fmt { String of(int progress); }

    LinearLayout slider(String label, int max, int value, Fmt fmt, SeekBar[] out) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = row();
        TextView l = text(label, 13, TEXT, false);
        final TextView v = text(fmt.of(value), 13, accent, true);
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(v);
        head.setPadding(0, dp(10), 0, 0);
        box.addView(head);
        SeekBar s = new SeekBar(c);
        s.setMax(max); s.setProgress(Math.max(0, Math.min(max, value)));
        s.setProgressTintList(ColorStateList.valueOf(accent));
        s.setThumbTintList(ColorStateList.valueOf(accent));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { v.setText(fmt.of(p)); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { buzz(0); }
        });
        box.addView(s);
        if (out != null && out.length > 0) out[0] = s;
        return box;
    }

    /** Selectable cards (one choice). Returns the container; current choice via the callback. */
    interface Pick { void on(int index); }

    LinearLayout choices(String[] titles, String[] descs, int selected, int recommended, Pick pick) {
        final LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        final List<LinearLayout> cards = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            final int idx = i;
            LinearLayout cd = new LinearLayout(c);
            cd.setOrientation(LinearLayout.VERTICAL);
            cd.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout head = row();
            head.addView(text(titles[i], 16, TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
            if (i == recommended) head.addView(pill("RECOMMENDED", accent));
            cd.addView(head);
            if (descs != null && descs[i] != null) { TextView d = body(descs[i]); d.setTextSize(13); cd.addView(d); }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            box.addView(cd, lp);
            cards.add(cd);
            cd.setOnClickListener(v -> {
                for (int k = 0; k < cards.size(); k++) paintChoice(cards.get(k), k == idx);
                buzz(0);
                pick.on(idx);
            });
            pressFx(cd);
        }
        for (int k = 0; k < cards.size(); k++) paintChoice(cards.get(k), k == selected);
        return box;
    }

    private void paintChoice(LinearLayout cd, boolean on) {
        GradientDrawable d = round(on ? alpha(accent, 0x1F) : SURFACE2, 14, on ? accent : STROKE);
        if (on) d.setStroke(dp(2), accent);
        cd.setBackground(d);
    }

    /** Progress bar for wizards. */
    View progress(int step, int total) {
        LinearLayout r = row();
        for (int i = 0; i < total; i++) {
            View seg = new View(c);
            seg.setBackground(round(i <= step ? accent : STROKE, 3, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(5), 1f);
            if (i > 0) lp.leftMargin = dp(6);
            r.addView(seg, lp);
        }
        r.setPadding(0, 0, 0, dp(18));
        return r;
    }

    View spacer(int dpH) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dpH))); return v; }
}
