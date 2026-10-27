package com.alihammoud21.gtavmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.*;
import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Controls editor modelled on Winlator's: full-screen grid, a floating toolbox you can drag anywhere
 * (move / add / remove / element settings / profile settings / exit), and an element settings sheet with
 * type, shape, bindings per category, scale, opacity, toggle, mouse-move, label and icon.
 * Every change is saved immediately. Written for Game Shell Native (Winlator is LGPL-2.1, credited in About).
 */
public final class EditorActivity extends Activity implements ControlView.Listener {
    private ControlView view;
    private Ui u;
    private String fileName;
    private LinearLayout toolbox;
    private TextView info;
    private Dialog sheet;

    private final ControlView.Host quiet = new ControlView.Host() {
        @Override public void key(String source, String code, boolean down) { }
        @Override public void release(String source) { }
        @Override public void look(float dx, float dy) { }
        @Override public void lookVector(float x, float y) { }
        @Override public boolean inVehicle() { return false; }
        @Override public boolean loading() { return false; }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        u = Ui.of(this);
        fileName = getIntent().getStringExtra("profile");
        boolean copy = getIntent().getBooleanExtra("copy", false);
        try {
            JSONObject json = null;
            if (fileName != null && !fileName.isEmpty() && !fileName.equals(Shell.BUILTIN)) {
                String text = Shell.read(new File(Shell.profileDir(this), fileName + ".icp"));
                if (text != null) json = new JSONObject(text);
            }
            if (json == null) {
                fileName = uniqueName("My layout");
                json = new JSONObject();
                json.put("id", 100 + (int) (System.currentTimeMillis() % 9000)); json.put("name", fileName); json.put("cursorSpeed", 1.0);
                json.put("elements", new JSONArray());
            } else if (copy) {
                fileName = uniqueName(fileName + " copy");
                json.put("name", fileName); json.put("id", 100 + (int) (System.currentTimeMillis() % 9000));
            }
            view = new ControlView(this, json, quiet);
            view.setAlpha(1f);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the layout: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish(); return;
        }
        view.setEditMode(true);
        view.setListener(this);
        save();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0B0D);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        toolbox = new LinearLayout(this);
        toolbox.setOrientation(LinearLayout.HORIZONTAL);
        toolbox.setGravity(Gravity.CENTER_VERTICAL);
        toolbox.setBackground(u.round(0xF0181C23, 18, Ui.STROKE));
        toolbox.setPadding(u.dp(6), u.dp(6), u.dp(10), u.dp(6));
        View handle = tool(R.drawable.ic_drag_indicator, null);
        handle.setOnTouchListener(dragToolbox());
        toolbox.addView(handle);
        toolbox.addView(tool(R.drawable.ic_add, () -> { view.addElement(); u.buzz(1); }));
        toolbox.addView(tool(R.drawable.ic_delete, () -> {
            if (!view.deleteSelected()) toast("Select an element first."); else u.buzz(1);
        }));
        toolbox.addView(tool(R.drawable.ic_tune, () -> {
            if (view.selected() == null) toast("Select an element first."); else elementSheet();
        }));
        toolbox.addView(tool(R.drawable.ic_edit, this::profileSheet));
        toolbox.addView(tool(R.drawable.ic_close, this::done));
        info = u.text(fileName, 14, Ui.TEXT, true);
        info.setTypeface(u.semi());
        info.setPadding(u.dp(8), 0, 0, 0);
        info.setMaxWidth(u.dp(230));
        info.setSingleLine();
        toolbox.addView(info);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = u.dp(10);
        root.addView(toolbox, tl);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private String uniqueName(String base) {
        String n = base.replaceAll("[^A-Za-z0-9 ._()\\-]", "_");
        if (!new File(Shell.profileDir(this), n + ".icp").exists()) return n;
        for (int i = 2; ; i++) if (!new File(Shell.profileDir(this), n + " " + i + ".icp").exists()) return n + " " + i;
    }

    private View tool(int icon, Runnable action) {
        ImageView b = new ImageView(this);
        b.setImageDrawable(u.icon(icon, Ui.TEXT, 24));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(u.round(Ui.SURFACE2, 12, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u.dp(46), u.dp(46));
        lp.setMargins(u.dp(3), 0, u.dp(3), 0);
        b.setLayoutParams(lp);
        if (action != null) { b.setOnClickListener(v -> { u.buzz(0); action.run(); }); u.pressFx(b); }
        return b;
    }

    /** The drag handle moves the whole toolbox, clamped to the screen (like Winlator). */
    private View.OnTouchListener dragToolbox() {
        final float[] start = new float[2];
        return (v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: start[0] = e.getRawX() - toolbox.getX(); start[1] = e.getRawY() - toolbox.getY(); break;
                case MotionEvent.ACTION_MOVE: {
                    ViewGroup parent = (ViewGroup) toolbox.getParent();
                    int pad = u.dp(8);
                    float x = Math.max(pad, Math.min(parent.getWidth() - toolbox.getWidth() - pad, e.getRawX() - start[0]));
                    float y = Math.max(pad, Math.min(parent.getHeight() - toolbox.getHeight() - pad, e.getRawY() - start[1]));
                    toolbox.setX(x); toolbox.setY(y);
                    break;
                }
                default: break;
            }
            return true;
        };
    }

    // ================================================================ autosave
    private void save() {
        try {
            view.name = fileName;
            Shell.write(new File(Shell.profileDir(this), fileName + ".icp"), view.toJson().toString());
            setResult(RESULT_OK, new Intent().putExtra("profile", fileName));
        } catch (Exception e) { toast("Save failed: " + e.getMessage()); }
    }

    @Override public void onSelect(ControlView.El e) {
        if (info == null) return;
        info.setText(e == null ? fileName : ControlView.TYPE_NAMES[Math.max(0, Arrays.asList(ControlView.TYPES).indexOf(e.type))] + "  ·  tap ⚙ to edit");
    }

    @Override public void onChanged() { save(); }

    private void done() {
        save();
        Shell.backupProfiles(this);
        finish();
    }

    @Override public void onBackPressed() { done(); }

    // ================================================================ side sheets
    private LinearLayout openSheet(String title) {
        if (sheet != null && sheet.isShowing()) sheet.dismiss();
        Dialog d = new Dialog(this, R.style.SlideMenu);
        sheet = d;
        LinearLayout col = u.column();
        col.setPadding(u.dp(18), u.dp(16), u.dp(18), u.dp(24));
        col.setBackgroundColor(0xF5000000);
        LinearLayout head = u.row();
        TextView t = u.heading(title);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView close = new ImageView(this);
        close.setImageDrawable(u.icon(R.drawable.ic_close, Ui.TEXT, 26));
        close.setPadding(u.dp(10), u.dp(10), u.dp(10), u.dp(10));
        close.setOnClickListener(v -> d.dismiss());
        head.addView(close);
        col.addView(head);
        ScrollView sv = new ScrollView(this); sv.addView(col);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setGravity(Gravity.END);
            w.setLayout(u.dp(400), ViewGroup.LayoutParams.MATCH_PARENT);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        d.show();
        return col;
    }

    /** Spinner listener that only fires on a real change. */
    private static AdapterView.OnItemSelectedListener onPick(final int initial, final Ui.Pick pick) {
        return new AdapterView.OnItemSelectedListener() {
            int last = initial;
            @Override public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                if (position == last) return;
                last = position;
                pick.on(position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        };
    }

    private void elementSheet() {
        final ControlView.El e = view.selected();
        if (e == null) return;
        LinearLayout col = openSheet("Element");
        fillElement(col, e);
    }

    private void fillElement(final LinearLayout col, final ControlView.El e) {
        while (col.getChildCount() > 1) col.removeViewAt(1);

        col.addView(u.label("Type"));
        int ti = Math.max(0, Arrays.asList(ControlView.TYPES).indexOf(e.type));
        Spinner type = u.spinner(ControlView.TYPE_NAMES, ti);
        type.setOnItemSelectedListener(onPick(ti, i -> { ControlView.setType(e, ControlView.TYPES[i]); view.changed(); fillElement(col, e); onSelect(e); }));
        col.addView(type);

        if (e.type.equals("BUTTON")) {
            col.addView(u.label("Shape"));
            int si = Math.max(0, Arrays.asList(ControlView.SHAPES).indexOf(e.shape));
            Spinner shape = u.spinner(ControlView.SHAPE_NAMES, si);
            shape.setOnItemSelectedListener(onPick(si, i -> { e.shape = ControlView.SHAPES[i]; view.changed(); }));
            col.addView(shape);
        }

        // ---- bindings
        col.addView(u.section("Bindings"));
        switch (e.type) {
            case "D_PAD": case "STICK": case "TRACKPAD": {
                String[] dirs = {"Up", "Right", "Down", "Left"};
                for (int i = 0; i < 4; i++) col.addView(bindingRow(e, i, dirs[i]));
                break;
            }
            case "RADIAL_MENU": {
                col.addView(stepper("Number of keys", e.bind.size(), 2, 8, n -> {
                    while (e.bind.size() < n) e.bind.add("NONE");
                    while (e.bind.size() > n) e.bind.remove(e.bind.size() - 1);
                    view.changed(); fillElement(col, e);
                }));
                for (int i = 0; i < e.bind.size(); i++) col.addView(bindingRow(e, i, "Key " + (i + 1)));
                break;
            }
            case "RANGE_BUTTON": {
                col.addView(u.label("Range"));
                int ri = Math.max(0, Arrays.asList(ControlView.RANGES).indexOf(e.range));
                Spinner range = u.spinner(ControlView.RANGE_NAMES, ri);
                range.setOnItemSelectedListener(onPick(ri, i -> { e.range = ControlView.RANGES[i]; view.changed(); }));
                col.addView(range);
                col.addView(u.label("Orientation"));
                Spinner orient = u.spinner(new String[]{"Horizontal", "Vertical"}, e.orientation);
                orient.setOnItemSelectedListener(onPick(e.orientation, i -> { e.orientation = i; view.changed(); }));
                col.addView(orient);
                col.addView(stepper("Visible keys", e.bind.size(), 1, 12, n -> {
                    while (e.bind.size() < n) e.bind.add("NONE");
                    while (e.bind.size() > n) e.bind.remove(e.bind.size() - 1);
                    view.changed();
                }));
                break;
            }
            default: {
                int shown = 1;
                for (int i = 1; i < e.bind.size(); i++) if (!e.bind.get(i).equals("NONE")) shown = i + 1;
                for (int i = 0; i < shown; i++) col.addView(bindingRow(e, i, i == 0 ? "Binding" : "Also presses"));
                if (shown < 4) {
                    final int next = shown;
                    col.addView(u.secondary("Add another key", R.drawable.ic_add, () -> {
                        while (e.bind.size() <= next) e.bind.add("NONE");
                        e.bind.set(next, "KEY_SPACE");
                        view.changed(); fillElement(col, e);
                    }));
                }
            }
        }

        // ---- look
        col.addView(u.section("Look"));
        SeekBar[] sb = new SeekBar[1];
        col.addView(u.slider("Scale", 250, Math.round(e.scale * 100) - 50, v -> (v + 50) + "%", sb));
        sb[0].setOnSeekBarChangeListener(liveSeek(v -> { e.scale = (v + 50) / 100f; view.changed(); }));
        col.addView(u.slider("Opacity", 90, Math.round(e.opacity * 100) - 10, v -> (v + 10) + "%", sb));
        sb[0].setOnSeekBarChangeListener(liveSeek(v -> { e.opacity = (v + 10) / 100f; view.changed(); }));

        if (e.type.equals("BUTTON")) {
            Switch tog = u.toggle("Toggle switch (tap on, tap off)", e.toggle);
            tog.setOnCheckedChangeListener((b, on) -> { e.toggle = on; view.changed(); });
            col.addView(tog);
            Switch mm = u.toggle("Mouse move mode (drag to look)", e.mouseMove);
            mm.setOnCheckedChangeListener((b, on) -> { e.mouseMove = on; view.changed(); });
            col.addView(mm);
            col.addView(u.label("Custom text"));
            EditText text = u.input(e.text, "Empty = show the key name");
            text.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) { e.text = s.toString().trim(); view.changed(); }
            });
            col.addView(text);
            col.addView(u.label("Icon"));
            col.addView(iconPicker(e));
        }

        col.addView(u.spacer(10));
        col.addView(u.danger("Delete element", R.drawable.ic_delete, () -> { view.deleteSelected(); if (sheet != null) sheet.dismiss(); }));
    }

    private interface IntPick { void on(int v); }

    private SeekBar.OnSeekBarChangeListener liveSeek(final IntPick pick) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) pick.on(p); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { u.buzz(0); }
        };
    }

    /** "- n +" control. */
    private LinearLayout stepper(String label, int value, final int min, final int max, final IntPick pick) {
        LinearLayout r = u.row();
        r.setPadding(0, u.dp(10), 0, u.dp(4));
        TextView l = u.text(label, 16, Ui.TEXT, false);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        final int[] v = {Math.max(min, Math.min(max, value))};
        final TextView num = u.text(String.valueOf(v[0]), 18, Ui.TEXT, true);
        num.setTypeface(u.heavy());
        num.setGravity(Gravity.CENTER);
        Button minus = u.secondary("−", () -> { if (v[0] > min) { v[0]--; num.setText(String.valueOf(v[0])); pick.on(v[0]); } });
        Button plus = u.secondary("+", () -> { if (v[0] < max) { v[0]++; num.setText(String.valueOf(v[0])); pick.on(v[0]); } });
        for (Button b : new Button[]{minus, plus}) { b.setTextSize(20); b.setLayoutParams(new LinearLayout.LayoutParams(u.dp(52), u.dp(48))); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(0, 0, 0, 0); }
        r.addView(minus);
        r.addView(num, new LinearLayout.LayoutParams(u.dp(44), -2));
        r.addView(plus);
        return r;
    }

    /** Category (Keyboard / Mouse / Gamepad) + key, like Winlator's binding field. */
    private LinearLayout bindingRow(final ControlView.El e, final int slot, String title) {
        LinearLayout box = u.column();
        TextView t = u.text(title, 14, Ui.MUTED, false);
        t.setPadding(0, u.dp(8), 0, u.dp(4));
        box.addView(t);
        final String current = slot < e.bind.size() ? e.bind.get(slot) : "NONE";
        final int cat = Shell.categoryOf(current);
        final Spinner category = u.spinner(Shell.BINDING_CATEGORIES, cat);
        final Spinner key = u.spinner(Shell.bindingLabels(Shell.bindingsFor(cat)), Math.max(0, Arrays.asList(Shell.bindingsFor(cat)).indexOf(current)));
        final int[] activeCat = {cat};
        key.setOnItemSelectedListener(onPick(Math.max(0, Arrays.asList(Shell.bindingsFor(cat)).indexOf(current)), i -> {
            while (e.bind.size() <= slot) e.bind.add("NONE");
            e.bind.set(slot, Shell.bindingsFor(activeCat[0])[i]);
            view.changed();
        }));
        category.setOnItemSelectedListener(onPick(cat, c -> {
            activeCat[0] = c;
            String[] names = Shell.bindingsFor(c);
            String now = slot < e.bind.size() ? e.bind.get(slot) : "NONE";
            int sel = Math.max(0, Arrays.asList(names).indexOf(now));
            ArrayAdapter<String> fresh = (ArrayAdapter<String>) u.spinner(Shell.bindingLabels(names), 0).getAdapter();
            key.setAdapter(fresh);
            key.setSelection(sel, false);
            key.setOnItemSelectedListener(onPick(sel, i -> {
                while (e.bind.size() <= slot) e.bind.add("NONE");
                e.bind.set(slot, Shell.bindingsFor(activeCat[0])[i]);
                view.changed();
            }));
        }));
        LinearLayout r = u.row();
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, u.dp(54), 0.9f);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(0, u.dp(54), 1.3f);
        kl.leftMargin = u.dp(8);
        r.addView(category, cl);
        r.addView(key, kl);
        box.addView(r);
        return box;
    }

    /** Row of Winlator's control icons; the first cell means "no icon". */
    private HorizontalScrollView iconPicker(final ControlView.El e) {
        final LinearLayout row = u.row();
        for (int i = 0; i < ControlView.ICON_COUNT; i++) {
            final int id = i;
            FrameLayout cell = new FrameLayout(this);
            if (i == 0) {
                TextView none = u.text("Aa", 16, Ui.TEXT, true);
                none.setGravity(Gravity.CENTER);
                cell.addView(none, new FrameLayout.LayoutParams(-1, -1));
            } else {
                ImageView iv = new ImageView(this);
                try (InputStream in = getAssets().open("icons/" + i + ".png")) {
                    Bitmap b = BitmapFactory.decodeStream(in);
                    iv.setImageBitmap(b);
                } catch (Exception ignored) { }
                iv.setColorFilter(0xFFFFFFFF);
                iv.setPadding(u.dp(10), u.dp(10), u.dp(10), u.dp(10));
                cell.addView(iv, new FrameLayout.LayoutParams(-1, -1));
            }
            cell.setBackground(u.round(id == e.iconId ? Ui.alpha(0xFFFFFFFF, 0x40) : Ui.SURFACE2, 12, id == e.iconId ? 0xFFFFFFFF : 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u.dp(52), u.dp(52));
            lp.rightMargin = u.dp(6);
            cell.setOnClickListener(v -> {
                e.iconId = id; view.changed(); u.buzz(0);
                for (int k = 0; k < row.getChildCount(); k++)
                    row.getChildAt(k).setBackground(u.round(k == id ? Ui.alpha(0xFFFFFFFF, 0x40) : Ui.SURFACE2, 12, k == id ? 0xFFFFFFFF : 0));
            });
            row.addView(cell, lp);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    private void profileSheet() {
        LinearLayout col = openSheet("Layout");
        col.addView(u.label("Name"));
        final EditText name = u.input(fileName, "Layout name");
        col.addView(name);
        col.addView(u.secondary("Rename", R.drawable.ic_edit, () -> {
            String n = name.getText().toString().trim().replaceAll("[^A-Za-z0-9 ._()\\-]", "_");
            if (n.isEmpty() || n.equals(fileName)) return;
            if (new File(Shell.profileDir(this), n + ".icp").exists()) { toast("A layout with that name exists."); return; }
            File old = new File(Shell.profileDir(this), fileName + ".icp");
            fileName = n; save(); old.delete(); info.setText(fileName); u.buzz(1);
        }));
        SeekBar[] sb = new SeekBar[1];
        col.addView(u.slider("Cursor / look speed", 39, Math.round(view.cursorSpeed() * 10) - 1, v -> String.format(java.util.Locale.US, "%.1fx", (v + 1) / 10f), sb));
        sb[0].setOnSeekBarChangeListener(liveSeek(v -> { view.setCursorSpeed((v + 1) / 10f); save(); }));
        col.addView(u.slider("Whole layout opacity in game", 85, Math.round(view.opacity * 100) - 15, v -> (v + 15) + "%", sb));
        sb[0].setOnSeekBarChangeListener(liveSeek(v -> { view.opacity = (v + 15) / 100f; save(); }));
        TextView n = u.body(view.elementCount() + " elements  ·  saved automatically");
        n.setPadding(0, u.dp(10), 0, 0);
        col.addView(n);
        col.addView(u.secondary("Export to Downloads", R.drawable.ic_save, () -> {
            try {
                File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName + ".icp");
                Shell.write(out, view.toJson().toString());
                toast("Saved Downloads/" + out.getName()); u.buzz(1);
            } catch (Exception ex) { toast("Export failed: " + ex.getMessage()); }
        }));
        col.addView(u.danger("Delete this layout", R.drawable.ic_delete, () -> new AlertDialog.Builder(this)
            .setMessage("Delete \"" + fileName + "\"? This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete", (dd, w) -> {
                new File(Shell.profileDir(this), fileName + ".icp").delete();
                setResult(RESULT_OK, new Intent().putExtra("profile", Shell.BUILTIN));
                finish();
            }).show()));
    }
}
