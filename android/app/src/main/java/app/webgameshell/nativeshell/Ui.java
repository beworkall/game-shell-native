package app.webgameshell.nativeshell;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
 * Hand-made design system for Game Shell Native: bold, flat, high-contrast panels (in the spirit of
 * Liberty Recompiled), bundled fonts (Barlow Condensed for display, Inter for text), Material icons,
 * press animation and haptics. Plain Android views only.
 */
final class Ui {
    // ---------------------------------------------------------------- palette
    static final int BG = 0xFF0E1116, SURFACE = 0xFF181C23, SURFACE2 = 0xFF2B313A, STROKE = 0xFF333A44;
    static final int TEXT = 0xFFF4F5F7, MUTED = 0xFF9AA1AC, OK = 0xFF3DDC97, WARN = 0xFFFFC24B, BAD = 0xFFFF5A5F;
    static final String[] ACCENT_NAMES = {"Classic grey", "Sunset orange", "Electric cyan", "Mint green", "Soft purple", "Hot pink"};
    static final int[] ACCENTS = {0xFFA4A8AE, 0xFFFF7A1A, 0xFF00D8F0, 0xFF3DDC97, 0xFFA78BFA, 0xFFFF4D8D};

    final Context c;
    final int accent;
    final boolean haptics;
    /** Size factor from the real screen: about 0.9 on phones, up to 1.12 on tablets. */
    final float k;
    /** Pixels per "sp" with the system font scale capped, so huge font settings can't break layouts. */
    private final float spPx;
    private final float density;

    Ui(Context c, int accentIndex, boolean haptics) {
        this.c = c;
        this.accent = ACCENTS[Math.max(0, Math.min(ACCENTS.length - 1, accentIndex))];
        this.haptics = haptics;
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        density = m.density;
        float shortDp = Math.min(m.widthPixels, m.heightPixels) / m.density;
        k = Math.max(0.82f, Math.min(1.12f, 0.62f + shortDp * 0.00075f));
        float fontScale = c.getResources().getConfiguration().fontScale;
        spPx = m.density * k * Math.max(0.9f, Math.min(1.12f, fontScale));
    }

    /** Screen width / height in dp (unscaled). */
    float widthDp() { return c.getResources().getDisplayMetrics().widthPixels / density; }
    float heightDp() { return c.getResources().getDisplayMetrics().heightPixels / density; }
    /** Short landscape screens (phones) get the compact layout. */
    boolean shortScreen() { return heightDp() < 520; }

    /** Sets a scaled text size. Always use this instead of TextView.setTextSize. */
    void ts(TextView t, float sp) { t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sp * spPx); }

    static Ui of(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("shell", Context.MODE_PRIVATE);
        return new Ui(c, p.getInt("accent", 0), p.getBoolean("haptics", true));
    }

    int dp(float v) { return Math.round(v * density * k); }

    static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    /** Readable text colour on top of the accent. */
    int onAccent() {
        int r = (accent >> 16) & 0xFF, g = (accent >> 8) & 0xFF, b = accent & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 140 ? 0xFF0B0D10 : 0xFFFFFFFF;
    }

    GradientDrawable round(int fill, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    // ---------------------------------------------------------------- fonts
    private static Typeface display, displayBold, body, semi, heavy;

    private static Typeface font(Context c, String file, Typeface fallback) {
        try { return Typeface.createFromAsset(c.getAssets(), "fonts/" + file); } catch (Throwable t) { return fallback; }
    }

    /** Barlow Condensed ExtraBold: logo, page titles, the big Play button. */
    Typeface display() { if (display == null) display = font(c, "BarlowCondensed_800ExtraBold.ttf", Typeface.DEFAULT_BOLD); return display; }
    Typeface displayBold() { if (displayBold == null) displayBold = font(c, "BarlowCondensed_700Bold.ttf", Typeface.DEFAULT_BOLD); return displayBold; }
    /** Inter for all running text. */
    Typeface body() { if (body == null) body = font(c, "Inter_400Regular.ttf", Typeface.DEFAULT); return body; }
    Typeface semi() { if (semi == null) semi = font(c, "Inter_600SemiBold.ttf", Typeface.DEFAULT_BOLD); return semi; }
    Typeface heavy() { if (heavy == null) heavy = font(c, "Inter_800ExtraBold.ttf", Typeface.DEFAULT_BOLD); return heavy; }

    // ---------------------------------------------------------------- icons
    Drawable icon(int res, int color, int sizeDp) {
        Drawable d = c.getDrawable(res);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color);
        d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
        return d;
    }

    ImageView iconView(int res, int color, int sizeDp) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(icon(res, color, sizeDp));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return iv;
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
                    view.animate().scaleX(0.965f).scaleY(0.965f).setDuration(90).start();
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
    boolean landscape() {
        android.util.DisplayMetrics m = c.getResources().getDisplayMetrics();
        return m.widthPixels > m.heightPixels;
    }

    LinearLayout column() {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        return col;
    }

    ScrollView scroll(View child) {
        ScrollView sv = new ScrollView(c);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(child);
        return sv;
    }

    /** Single scrolling page; returns the column to fill. */
    LinearLayout page(Activity a) {
        LinearLayout col = column();
        int side = sidePad(720);
        col.setPadding(side, dp(shortScreen() ? 16 : 24), side, dp(32));
        ScrollView sv = scroll(col);
        sv.setBackgroundColor(BG);
        a.setContentView(sv);
        fitInsets(sv);
        enter(col, c);
        return col;
    }

    /**
     * Android 15+ draws apps edge to edge: pad the root by the status bar, navigation bar and camera
     * cutout so nothing sits under the clock or the gesture bar.
     */
    static void fitInsets(View root) {
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int il, it, ir, ib;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = in.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                il = s.left; it = s.top; ir = s.right; ib = s.bottom;
            } else {
                il = in.getSystemWindowInsetLeft(); it = in.getSystemWindowInsetTop(); ir = in.getSystemWindowInsetRight(); ib = in.getSystemWindowInsetBottom();
            }
            v.setPadding(l + il, t + it, r + ir, b + ib);
            return in;
        });
        root.requestApplyInsets();
    }

    /** Side padding that keeps content at most maxDp wide and centred, with at least 16dp gutters. */
    int sidePad(float maxDp) {
        float w = widthDp() / k;
        return dp(Math.max(16, (w - maxDp) / 2f));
    }

    /**
     * Two panes side by side in landscape (left wider, right in a panel), stacked in portrait.
     * Returns {left, right} columns.
     */
    LinearLayout[] panes(Activity a) {
        LinearLayout left = column(), right = column();
        if (landscape()) {
            LinearLayout root = new LinearLayout(c);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setBackgroundColor(BG);
            int wide = (int) Math.max(0, (widthDp() / k - 1100) / 2);
            left.setPadding(dp(28 + wide), dp(14), dp(20), dp(16));
            ScrollView ls = scroll(left);
            root.addView(ls, new LinearLayout.LayoutParams(0, -1, 1.05f));
            right.setPadding(dp(20), dp(16), dp(20), dp(18));
            ScrollView rs = scroll(right);
            rs.setBackground(round(SURFACE, 20, 0));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -1, 1f);
            rl.setMargins(dp(6), dp(12), dp(12 + wide), dp(12));
            root.addView(rs, rl);
            a.setContentView(root);
            fitInsets(root);
            enter(left, c); enter(rs, c);
        } else {
            LinearLayout col = column();
            int side = sidePad(640);
            col.setPadding(side, dp(18), side, dp(28));
            col.addView(left);
            right.setBackground(round(SURFACE, 20, 0));
            right.setPadding(dp(16), dp(16), dp(16), dp(18));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
            rl.topMargin = dp(16);
            col.addView(right, rl);
            col.setGravity(Gravity.CENTER_VERTICAL);   // balanced on tall screens instead of an empty bottom
            ScrollView sv = scroll(col);
            sv.setBackgroundColor(BG);
            a.setContentView(sv);
            fitInsets(sv);
            enter(col, c);
        }
        return new LinearLayout[]{left, right};
    }

    /** Logo: our original icon art + condensed wordmark, in the style of a game launcher header. */
    LinearLayout logo(float scale) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER);
        ImageView art = new ImageView(c);
        art.setImageResource(R.drawable.icon_foreground);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(dp(118 * scale), dp(118 * scale));
        al.rightMargin = dp(2);
        r.addView(art, al);
        LinearLayout words = column();
        TextView name = new TextView(c);
        name.setText("Game Shell");
        name.setTypeface(display());
        ts(name, 58 * scale);
        name.setTextColor(0xFFC3C6CB);
        name.setIncludeFontPadding(false);
        name.setLetterSpacing(-0.01f);
        words.addView(name);
        View bar = new View(c);
        bar.setBackgroundColor(0xFF05070A);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, dp(3 * scale));
        bl.topMargin = dp(1);
        words.addView(bar, bl);
        TextView sub = new TextView(c);
        sub.setText("NATIVE  ·  FOR ANDROID");
        sub.setTypeface(heavy());
        ts(sub, 15 * scale);
        sub.setTextColor(0xFFC3C6CB);
        sub.setGravity(Gravity.END);
        sub.setLetterSpacing(0.02f);
        words.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        r.addView(words, new LinearLayout.LayoutParams(-2, -2));
        return r;
    }

    TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s); ts(t, sp); t.setTextColor(color);
        t.setTypeface(bold ? semi() : body());
        return t;
    }

    TextView title(String s) {
        TextView t = text(s, 30, TEXT, true);
        t.setTypeface(display());
        t.setIncludeFontPadding(false);
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    /** Panel heading, like "Install the game" in the reference design. */
    TextView heading(String s) {
        TextView t = text(s, 20, TEXT, true);
        t.setTypeface(heavy());
        t.setPadding(0, 0, 0, dp(4));
        return t;
    }

    TextView subtitle(String s) { TextView t = text(s, 14, MUTED, false); t.setPadding(0, 0, 0, dp(10)); return t; }
    TextView body(String s) { TextView t = text(s, 13.5f, MUTED, false); t.setLineSpacing(0, 1.12f); return t; }

    TextView label(String s) {
        TextView t = text(s, 14.5f, TEXT, true);
        t.setPadding(0, dp(10), 0, dp(4));
        return t;
    }

    /** Big field label + muted value, the way the reference shows "Base game / Not selected". */
    LinearLayout field(String name, String value) {
        LinearLayout f = column();
        f.addView(label(name));
        TextView v = text(value, 13.5f, MUTED, false);
        v.setPadding(0, 0, 0, dp(4));
        f.addView(v);
        f.setTag(v);
        return f;
    }

    /** One compact line: name on the left, value on the right (ellipsized). */
    LinearLayout info(String name, String value) {
        LinearLayout r = row();
        r.setPadding(0, dp(7), 0, dp(7));
        TextView n = text(name, 13.5f, MUTED, false);
        r.addView(n, new LinearLayout.LayoutParams(-2, -2));
        TextView v = text(value, 13.5f, TEXT, true);
        v.setGravity(Gravity.END);
        v.setSingleLine(); v.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(0, -2, 1f);
        vl.leftMargin = dp(12);
        r.addView(v, vl);
        r.setTag(v);
        return r;
    }

    /** Thin divider line. */
    View divider() {
        View v = new View(c);
        v.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.topMargin = dp(6); lp.bottomMargin = dp(6);
        v.setLayoutParams(lp);
        return v;
    }

    /** Segmented control: small connected chips, one selected. */
    LinearLayout segmented(String[] names, int selected, Pick pick) {
        final LinearLayout r = row();
        r.setBackground(round(SURFACE2, 12, 0));
        r.setPadding(dp(3), dp(3), dp(3), dp(3));
        final int on = accent == ACCENTS[0] ? 0xFFE6E8EB : accent;
        final int onText = accent == ACCENTS[0] ? 0xFF0B0D10 : onAccent();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 13.5f, i == selected ? onText : TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(); t.setEllipsize(TextUtils.TruncateAt.END);
            t.setMinHeight(dp(36));
            t.setPadding(dp(6), dp(6), dp(6), dp(6));
            if (i == selected) t.setBackground(round(on, 10, 0));
            t.setOnClickListener(v -> {
                for (int j = 0; j < r.getChildCount(); j++) {
                    TextView x = (TextView) r.getChildAt(j);
                    x.setBackground(j == idx ? round(on, 10, 0) : null);
                    x.setTextColor(j == idx ? onText : TEXT);
                }
                buzz(0);
                pick.on(idx);
            });
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        r.setLayoutParams(lp);
        return r;
    }

    static void setField(LinearLayout field, String value) { ((TextView) field.getTag()).setText(value); }

    TextView section(String s) {
        TextView t = text(s.toUpperCase(java.util.Locale.US), 11.5f, accent == ACCENTS[0] ? MUTED : accent, true);
        t.setTypeface(heavy());
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(4), dp(16), 0, dp(6));
        return t;
    }

    LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(round(SURFACE, 16, 0));
        l.setPadding(dp(14), dp(10), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        l.setLayoutParams(lp);
        return l;
    }

    LinearLayout row() {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    /** Adds views to a row with equal weight and a gap. */
    LinearLayout split(View... views) {
        LinearLayout r = row();
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            r.addView(views[i], lp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        r.setLayoutParams(rp);
        return r;
    }

    private Button baseButton(String label, int iconRes, Runnable action) {
        Button b = new Button(c);
        b.setText(label); b.setAllCaps(false); ts(b, 14.5f);
        b.setTypeface(semi());
        b.setStateListAnimator(null);
        b.setSingleLine(); b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinHeight(dp(44)); b.setMinimumHeight(dp(44));
        b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setOnClickListener(v -> { try { action.run(); } catch (Throwable t) { Toast.makeText(c, "Error: " + t, Toast.LENGTH_LONG).show(); buzz(2); } });
        b.setTag(iconRes);
        pressFx(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void addIcon(Button b, int iconRes, int color) {
        if (iconRes == 0) return;
        b.setCompoundDrawablesRelative(icon(iconRes, color, 18), null, null, null);
        b.setCompoundDrawablePadding(dp(8));
        centerIcon(b);
    }

    /** Keeps the icon right next to the centred label instead of stuck to the left edge. */
    private void centerIcon(final Button b) {
        final int vt = b.getPaddingTop(), vb = b.getPaddingBottom(), min = dp(10);
        b.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int w = r - l;
            if (w <= 0) return;
            Drawable d = b.getCompoundDrawablesRelative()[0];
            float content = (d == null ? 0 : d.getBounds().width() + b.getCompoundDrawablePadding()) + b.getPaint().measureText(b.getText().toString());
            int side = Math.max(min, (int) ((w - content) / 2f));
            if (side != b.getPaddingLeft()) b.post(() -> b.setPadding(side, vt, side, vb));
        });
    }

    Button primary(String label, Runnable action) { return primary(label, 0, action); }
    Button secondary(String label, Runnable action) { return secondary(label, 0, action); }
    Button danger(String label, Runnable action) { return danger(label, 0, action); }

    Button primary(String label, int iconRes, Runnable action) {
        Button b = baseButton(label, iconRes, action);
        b.setBackground(round(accent, 12, 0));
        b.setTextColor(onAccent());
        b.setTypeface(heavy());
        addIcon(b, iconRes, onAccent());
        return b;
    }

    Button secondary(String label, int iconRes, Runnable action) {
        Button b = baseButton(label, iconRes, action);
        b.setBackground(round(SURFACE2, 12, 0));
        b.setTextColor(TEXT);
        addIcon(b, iconRes, TEXT);
        return b;
    }

    Button danger(String label, int iconRes, Runnable action) {
        Button b = baseButton(label, iconRes, action);
        b.setBackground(round(alpha(BAD, 0x26), 12, 0));
        b.setTextColor(BAD);
        addIcon(b, iconRes, BAD);
        return b;
    }

    /** The giant Play button. */
    Button hero(String label, Runnable action) {
        Button b = baseButton(label, 0, action);
        b.setBackground(round(accent, 14, 0));
        b.setTextColor(onAccent());
        b.setTypeface(display());
        ts(b, shortScreen() ? 30 : 36);
        b.setLetterSpacing(0.04f);
        b.setAllCaps(true);
        int h = shortScreen() ? 64 : 80;
        b.setMinHeight(dp(h)); b.setMinimumHeight(dp(h));
        b.setIncludeFontPadding(false);
        return b;
    }

    /** Square icon-only button. */
    Button iconButton(int iconRes, Runnable action) {
        Button b = baseButton("", iconRes, action);
        b.setBackground(round(SURFACE2, 12, 0));
        b.setPadding(dp(12), 0, 0, 0);
        b.setCompoundDrawablesRelative(icon(iconRes, TEXT, 22), null, null, null);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
        return b;
    }

    private static int statusIcon(int kind) {
        return kind == 0 ? R.drawable.ic_check_circle : kind == 1 ? R.drawable.ic_warning : kind == 2 ? R.drawable.ic_error : R.drawable.ic_info;
    }

    private static int statusColor(int kind) { return kind == 0 ? OK : kind == 1 ? WARN : kind == 2 ? BAD : MUTED; }

    /** An icon followed by text. kind: 0 ok, 1 warn, 2 bad, 3 neutral. */
    LinearLayout status(String s, int kind) {
        LinearLayout r = row();
        r.setGravity(Gravity.TOP);
        ImageView iv = iconView(statusIcon(kind), statusColor(kind), 17);
        ((LinearLayout.LayoutParams) iv.getLayoutParams()).rightMargin = dp(10);
        ((LinearLayout.LayoutParams) iv.getLayoutParams()).topMargin = dp(1);
        r.addView(iv);
        TextView t = text(s, 13.5f, TEXT, false);
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.setPadding(0, dp(4), 0, dp(4));
        r.setTag(t);
        return r;
    }

    static void setStatus(LinearLayout statusRow, String s, int kind) {
        if (statusRow == null) return;
        ((TextView) statusRow.getTag()).setText(s);
        ImageView iv = (ImageView) statusRow.getChildAt(0);
        Drawable d = statusRow.getContext().getDrawable(statusIcon(kind));
        if (d != null) { d = d.mutate(); d.setTint(statusColor(kind)); iv.setImageDrawable(d); }
    }

    TextView pill(String s, int color) {
        TextView t = text(s.toUpperCase(java.util.Locale.US), 10, color, true);
        t.setTypeface(heavy());
        t.setLetterSpacing(0.06f);
        t.setBackground(round(alpha(color, 0x26), 10, 0));
        t.setPadding(dp(8), dp(3), dp(8), dp(3));
        return t;
    }

    Spinner spinner(String[] items, int selected) {
        Spinner s = new Spinner(c);
        final Typeface tf = semi();
        final Ui self = this;
        ArrayAdapter<String> a = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, items) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) { TextView t = (TextView) v; t.setTextColor(TEXT); self.ts(t, 14); t.setTypeface(tf); t.setSingleLine(); t.setEllipsize(TextUtils.TruncateAt.END); }
                return v;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                if (v instanceof TextView) { TextView t = (TextView) v; t.setTextColor(TEXT); self.ts(t, 14); t.setTypeface(tf); }
                return v;
            }
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.max(0, Math.min(items.length - 1, selected)));
        s.setBackground(round(SURFACE2, 12, 0));
        s.setPadding(dp(8), 0, dp(8), 0);
        s.setPopupBackgroundDrawable(round(SURFACE2, 12, STROKE));
        s.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(44)));
        return s;
    }

    EditText input(String value, String hint) {
        EditText e = new EditText(c);
        e.setSingleLine(); e.setText(value); e.setHint(hint);
        e.setTextColor(TEXT); e.setHintTextColor(MUTED); ts(e, 14); e.setTypeface(body());
        e.setBackground(round(SURFACE2, 12, 0));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        return e;
    }

    Switch toggle(String label, boolean on) {
        Switch s = new Switch(c);
        s.setText(label); s.setTextColor(TEXT); ts(s, 14); s.setTypeface(body()); s.setChecked(on);
        s.setPadding(0, dp(8), 0, dp(8));
        int[][] states = {{android.R.attr.state_checked}, {}};
        int on1 = accent == ACCENTS[0] ? 0xFFE6E8EB : accent;
        s.setThumbTintList(new ColorStateList(states, new int[]{on1, 0xFFB0B7C3}));
        s.setTrackTintList(new ColorStateList(states, new int[]{alpha(on1, 0x88), 0xFF3A404A}));
        s.setOnCheckedChangeListener((b, v) -> buzz(0));
        return s;
    }

    /** Seek bar with a live value read-out. */
    interface Fmt { String of(int progress); }

    LinearLayout slider(String label, int max, int value, Fmt fmt, SeekBar[] out) {
        LinearLayout box = column();
        LinearLayout head = row();
        TextView l = text(label, 14, TEXT, false);
        final TextView v = text(fmt.of(value), 13.5f, accent == ACCENTS[0] ? TEXT : accent, true);
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(v);
        head.setPadding(0, dp(8), 0, 0);
        box.addView(head);
        SeekBar s = new SeekBar(c);
        s.setMax(max); s.setProgress(Math.max(0, Math.min(max, value)));
        int tint = accent == ACCENTS[0] ? 0xFFE6E8EB : accent;
        s.setProgressTintList(ColorStateList.valueOf(tint));
        s.setThumbTintList(ColorStateList.valueOf(tint));
        s.setPadding(dp(4), dp(8), dp(4), dp(4));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { v.setText(fmt.of(p)); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { buzz(0); }
        });
        box.addView(s);
        if (out != null && out.length > 0) out[0] = s;
        return box;
    }

    /** Selectable cards (one choice). */
    interface Pick { void on(int index); }

    LinearLayout choices(String[] titles, String[] descs, int selected, int recommended, Pick pick) {
        final LinearLayout box = column();
        final List<LinearLayout> cards = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            final int idx = i;
            LinearLayout cd = column();
            cd.setPadding(dp(14), dp(10), dp(14), dp(10));
            LinearLayout head = row();
            TextView t = text(titles[i], 15, TEXT, true);
            t.setTypeface(heavy());
            head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            if (i == recommended) head.addView(pill("Recommended", accent == ACCENTS[0] ? OK : accent));
            cd.addView(head);
            if (descs != null && descs[i] != null) { TextView d = body(descs[i]); ts(d, 12.5f); d.setPadding(0, dp(2), 0, 0); cd.addView(d); }
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
        int edge = accent == ACCENTS[0] ? 0xFFE6E8EB : accent;
        GradientDrawable d = round(on ? alpha(edge, 0x1C) : SURFACE2, 12, 0);
        if (on) d.setStroke(dp(2), edge);
        cd.setBackground(d);
    }

    /** Segmented progress bar for wizards. */
    View progress(int step, int total) {
        LinearLayout r = row();
        int on = accent == ACCENTS[0] ? 0xFFE6E8EB : accent;
        for (int i = 0; i < total; i++) {
            View seg = new View(c);
            seg.setBackground(round(i <= step ? on : SURFACE2, 3, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1f);
            if (i > 0) lp.leftMargin = dp(8);
            r.addView(seg, lp);
        }
        r.setPadding(0, 0, 0, dp(12));
        return r;
    }

    View spacer(int dpH) { View v = new View(c); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(dpH))); return v; }
}
