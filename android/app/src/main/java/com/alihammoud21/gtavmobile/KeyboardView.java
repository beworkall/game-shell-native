package com.alihammoud21.gtavmobile;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * On-screen keyboard drawn inside the game overlay (the system keyboard cannot attach to the game window).
 * Every key sends a real key press plus its character, so text boxes and the cheat console receive typing.
 */
final class KeyboardView extends LinearLayout {
    interface Sink { void tap(String code, String text); void hide(); }

    private final Sink sink;
    private final Ui ui;
    private boolean shift;
    private final List<TextView> letters = new ArrayList<>();

    KeyboardView(Context c, Ui ui, Sink sink) {
        super(c);
        this.ui = ui; this.sink = sink;
        setOrientation(VERTICAL);
        setBackground(ui.round(0xE6101216, 18, Ui.STROKE));
        setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        row(new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}, null);
        row(new String[]{"Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"}, null);
        row(new String[]{"A", "S", "D", "F", "G", "H", "J", "K", "L"}, null);
        LinearLayout r4 = newRow();
        special(r4, "⇧", 1.5f, () -> { shift = !shift; relabel(); });
        for (String k : new String[]{"Z", "X", "C", "V", "B", "N", "M"}) key(r4, k, 1f, codeFor(k), k);
        special(r4, "⌫", 1.5f, () -> sink.tap("Backspace", ""));
        addView(r4);
        LinearLayout r5 = newRow();
        special(r5, "Esc", 1.2f, () -> sink.tap("Escape", ""));
        key(r5, "`", 1f, "Backquote", "`");
        key(r5, "-", 1f, "Minus", "-");
        special(r5, "space", 4f, () -> sink.tap("Space", " "));
        key(r5, ".", 1f, "Period", ".");
        special(r5, "Enter", 1.6f, () -> sink.tap("Enter", ""));
        special(r5, "Hide", 1.3f, sink::hide);
        addView(r5);
    }

    private static String codeFor(String k) {
        char ch = k.charAt(0);
        if (Character.isDigit(ch)) return "Digit" + ch;
        return "Key" + Character.toUpperCase(ch);
    }

    private LinearLayout newRow() {
        LinearLayout r = new LinearLayout(getContext());
        r.setOrientation(HORIZONTAL);
        return r;
    }

    private void row(String[] keys, Object unused) {
        LinearLayout r = newRow();
        for (String k : keys) key(r, k, 1f, codeFor(k), k);
        addView(r);
    }

    private TextView cell(LinearLayout r, String label, float weight) {
        TextView t = new TextView(getContext());
        t.setText(label); t.setGravity(Gravity.CENTER); t.setTextColor(Ui.TEXT); t.setTextSize(16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(ui.round(Ui.SURFACE2, 10, Ui.STROKE));
        LayoutParams lp = new LayoutParams(0, ui.dp(42), weight);
        lp.setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2));
        r.addView(t, lp);
        t.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { v.setBackground(ui.round(Ui.alpha(ui.accent, 0x66), 10, ui.accent)); ui.buzz(0); }
            else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) v.setBackground(ui.round(Ui.SURFACE2, 10, Ui.STROKE));
            return false;
        });
        return t;
    }

    private void key(LinearLayout r, String label, float weight, String code, String text) {
        TextView t = cell(r, label, weight);
        boolean letter = label.length() == 1 && Character.isLetter(label.charAt(0));
        if (letter) { letters.add(t); }
        t.setOnClickListener(v -> {
            String typed = letter ? (shift ? text.toUpperCase(Locale.US) : text.toLowerCase(Locale.US)) : text;
            sink.tap(code, typed);
        });
        if (letter) t.setText(label.toLowerCase(Locale.US));
    }

    private void special(LinearLayout r, String label, float weight, Runnable action) {
        TextView t = cell(r, label, weight);
        t.setTextColor(ui.accent);
        t.setOnClickListener(v -> action.run());
    }

    private void relabel() {
        for (TextView t : letters) {
            String s = t.getText().toString();
            t.setText(shift ? s.toUpperCase(Locale.US) : s.toLowerCase(Locale.US));
        }
    }
}
