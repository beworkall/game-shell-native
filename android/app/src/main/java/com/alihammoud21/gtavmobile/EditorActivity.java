package com.alihammoud21.gtavmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;
import org.json.JSONObject;

/** Winlator-style editor for on-screen control profiles (.icp). Game Shell Native. */
public final class EditorActivity extends Activity implements ControlView.Listener {
    private ControlView view;
    private TextView info;
    private String fileName;
    private boolean dirty;

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
        fileName = getIntent().getStringExtra("profile");
        boolean copy = getIntent().getBooleanExtra("copy", false);
        try {
            JSONObject json = null;
            if (fileName != null && !fileName.isEmpty() && !fileName.equals(Shell.BUILTIN)) {
                String text = Shell.read(new File(Shell.profileDir(this), fileName + ".icp"));
                if (text != null) json = new JSONObject(text);
            }
            if (json == null) { json = new JSONObject(); json.put("id", 100); json.put("name", "My layout"); json.put("cursorSpeed", 1.0); json.put("elements", new org.json.JSONArray()); fileName = "My layout"; }
            if (copy) { fileName = fileName + " copy"; json.put("name", fileName); json.put("id", 100 + (int) (System.currentTimeMillis() % 1000)); }
            view = new ControlView(this, json, quiet);
            view.setAlpha(1f);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open the profile: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish(); return;
        }
        view.setEditMode(true);
        view.setListener(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#141815"));
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(0xCC000000);
        bar.setPadding(dp(6), dp(2), dp(6), dp(2));
        add(bar, "+ Add", this::addDialog);
        add(bar, "Keys", this::keysDialog);
        add(bar, "Shape", () -> { view.cycleShape(); dirty = true; });
        add(bar, "Size -", () -> { view.scaleSelected(-0.1f); dirty = true; });
        add(bar, "Size +", () -> { view.scaleSelected(0.1f); dirty = true; });
        add(bar, "Label", this::labelDialog);
        add(bar, "Toggle", () -> { ControlView.El e = view.selected(); if (e != null) { view.setToggle(!e.toggle); dirty = true; refresh(e); } });
        add(bar, "Mouse-move", () -> { ControlView.El e = view.selected(); if (e != null) { view.setMouseMove(!e.mouseMove); dirty = true; refresh(e); } });
        add(bar, "Range", () -> { view.cycleRange(); dirty = true; });
        add(bar, "Delete", () -> { view.deleteSelected(); dirty = true; });
        add(bar, "Opacity -", () -> { view.setOpacity(view.opacity - 0.1f); dirty = true; });
        add(bar, "Opacity +", () -> { view.setOpacity(view.opacity + 0.1f); dirty = true; });
        add(bar, "SAVE", this::saveDialog);
        add(bar, "Exit", this::exitDialog);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.addView(bar);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        info = new TextView(this);
        info.setTextColor(0xFFFFFFFF); info.setBackgroundColor(0x99000000); info.setPadding(dp(8), dp(2), dp(8), dp(2)); info.setTextSize(12);
        info.setText("Tap an element to select it, drag to move. Use the bar above to edit.");
        root.addView(info, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void add(LinearLayout bar, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(12); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setOnClickListener(v -> action.run());
        bar.addView(b, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
    }

    @Override public void onSelect(ControlView.El e) { refresh(e); }

    private void refresh(ControlView.El e) {
        if (e == null) { info.setText("Nothing selected. Tap an element, or + Add."); return; }
        info.setText(e.type + (e.type.equals("BUTTON") ? " / " + e.shape : "") + "  size " + e.scale + (e.toggle ? "  TOGGLE" : "") + (e.mouseMove ? "  MOUSE-MOVE" : "") + "  keys: " + e.bind);
    }

    private void addDialog() {
        final String[][] items = {
            {"Button (circle)", "BUTTON", "CIRCLE"}, {"Button (rectangle)", "BUTTON", "ROUND_RECT"}, {"Button (square)", "BUTTON", "SQUARE"},
            {"D-pad", "D_PAD", ""}, {"Stick", "STICK", ""}, {"Look trackpad", "TRACKPAD", ""},
            {"Radial menu", "RADIAL_MENU", ""}, {"Number bar (1-9, 0)", "RANGE_BUTTON", "FROM_0_TO_9"}, {"Function bar (F1-F12)", "RANGE_BUTTON", "FROM_F1_TO_F12"}};
        String[] names = new String[items.length];
        for (int i = 0; i < items.length; i++) names[i] = items[i][0];
        new AlertDialog.Builder(this).setTitle("Add element").setItems(names, (d, which) -> {
            view.addElement(items[which][1]);
            ControlView.El e = view.selected();
            if (e != null) {
                if (items[which][1].equals("BUTTON")) e.shape = items[which][2];
                if (items[which][1].equals("RANGE_BUTTON")) e.range = items[which][2];
            }
            dirty = true; view.invalidate();
        }).show();
    }

    private void keysDialog() {
        final ControlView.El e = view.selected();
        if (e == null) { Toast.makeText(this, "Select an element first.", Toast.LENGTH_SHORT).show(); return; }
        int slots = ControlView.slotCount(e.type);
        if (slots == 0) { Toast.makeText(this, "This element has no keys to set.", Toast.LENGTH_SHORT).show(); return; }
        String[] labels = e.type.equals("D_PAD") || e.type.equals("STICK") || e.type.equals("TRACKPAD")
            ? new String[]{"Up", "Right", "Down", "Left"} : e.type.equals("RADIAL_MENU") ? new String[]{"Slot 1", "Slot 2", "Slot 3", "Slot 4", "Slot 5", "Slot 6"}
            : new String[]{"Key 1", "Key 2", "Key 3", "Key 4"};
        final String[] choices = Shell.bindingNames();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(16), dp(8), dp(16), dp(8));
        final Spinner[] spinners = new Spinner[slots];
        for (int i = 0; i < slots; i++) {
            TextView t = new TextView(this); t.setText(labels[i]); col.addView(t);
            Spinner s = new Spinner(this);
            s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
            String current = i < e.bind.size() ? e.bind.get(i) : "NONE";
            for (int k = 0; k < choices.length; k++) if (choices[k].equals(current)) s.setSelection(k);
            spinners[i] = s; col.addView(s);
        }
        ScrollView sv = new ScrollView(this); sv.addView(col);
        new AlertDialog.Builder(this).setTitle("Keys for " + e.type).setView(sv)
            .setPositiveButton("OK", (d, w) -> {
                for (int i = 0; i < spinners.length; i++) view.setBinding(i, choices[spinners[i].getSelectedItemPosition()]);
                dirty = true; refresh(e);
            }).setNegativeButton("Cancel", null).show();
    }

    private void labelDialog() {
        final ControlView.El e = view.selected();
        if (e == null) { Toast.makeText(this, "Select an element first.", Toast.LENGTH_SHORT).show(); return; }
        final EditText input = new EditText(this); input.setText(e.text); input.setSingleLine();
        new AlertDialog.Builder(this).setTitle("Label (empty = automatic)").setView(input)
            .setPositiveButton("OK", (d, w) -> { view.setText(input.getText().toString().trim()); dirty = true; })
            .setNegativeButton("Cancel", null).show();
    }

    private void saveDialog() {
        final EditText input = new EditText(this); input.setSingleLine(); input.setText(fileName == null ? "My layout" : fileName);
        new AlertDialog.Builder(this).setTitle("Save profile as").setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String name = input.getText().toString().trim().replaceAll("[^A-Za-z0-9 ._()\\-]", "_");
                if (name.isEmpty()) name = "My layout";
                try {
                    view.name = name;
                    Shell.write(new File(Shell.profileDir(this), name + ".icp"), view.toJson().toString());
                    Shell.backupProfiles(this);
                    fileName = name; dirty = false;
                    getIntent().putExtra("saved", name);
                    setResult(RESULT_OK, new android.content.Intent().putExtra("profile", name));
                    Toast.makeText(this, "Saved \"" + name + "\"", Toast.LENGTH_SHORT).show();
                } catch (Exception e) { Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
            }).setNegativeButton("Cancel", null).show();
    }

    private void exitDialog() {
        if (!dirty) { finish(); return; }
        new AlertDialog.Builder(this).setMessage("Leave without saving your changes?")
            .setPositiveButton("Leave", (d, w) -> finish()).setNegativeButton("Stay", null).show();
    }

    @Override public void onBackPressed() { exitDialog(); }
}
