package com.alihammoud21.gtavmobile;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NativeActivity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.*;
import android.hardware.input.InputManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;
import org.json.JSONObject;

/**
 * Native renderer plus on-screen controls (Winlator profiles), gamepad input and the in-game slide menu.
 * Modified for Game Shell Native (GPL-3.0-or-later); see NOTICE.md.
 */
public final class GameActivity extends NativeActivity {
    static { System.loadLibrary("gtav_mobile"); }
    private static native void nativeKey(String key, boolean down);
    private static native void nativeLook(float dx, float dy);
    private static native void nativeButton(int button, boolean down);
    private static native void nativeRelease();
    private static native boolean nativeInVehicle();
    private static native boolean nativeLoading();
    private static native void nativeHorn(boolean down);
    private static native long nativePresents();
    private static native void nativeMute(boolean mute);

    private final Map<String, Set<String>> held = new HashMap<>();
    private Controls controls;
    private ControlView cv;
    private View ui;
    private FrameLayout overlayRoot;
    private TextView hud, menuButton;
    private android.widget.PopupWindow overlay;
    private boolean active, muted, touchVisible = true, pendingReload;
    private float lookX, lookY;
    private int hudMode, gQuality = 1, gFps, gRes;
    private float uiOpacity = 0.9f;
    private float padDead = 0.20f, padLook = 1.0f;
    private JSONObject padMap = new JSONObject();
    private String profileName = Shell.BUILTIN, padDevice;
    private long hudT, hudPresents;
    private float fps;
    private Dialog menu;
    private final Map<String, String> analogActive = new HashMap<>();
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final ControlView.Host host = new ControlView.Host() {
        @Override public void key(String source, String code, boolean down) { GameActivity.this.key(source, code, down); }
        @Override public void release(String source) { GameActivity.this.release(source); }
        @Override public void look(float dx, float dy) { nativeLook(dx, dy); }
        @Override public void lookVector(float x, float y) { lookX = x; lookY = y; }
        @Override public boolean inVehicle() { return nativeInVehicle(); }
        @Override public boolean loading() { return nativeLoading(); }
    };

    private final Runnable lookTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (Math.abs(lookX) > .15f || Math.abs(lookY) > .15f) nativeLook(lookX * 12 * padLook, lookY * 12 * padLook);
            handler.postDelayed(this, 16);
        }
    };

    private final Runnable hudTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            updateHud();
            handler.postDelayed(this, 500);
        }
    };

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ================================================================ lifecycle
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Keep Java input dispatch: winit otherwise consumes the native input queue
        // before the touch overlay or gamepad handlers can receive events.
        getWindow().takeInputQueue(null);
        loadUiConfig();
        buildOverlay();
    }

    private void loadUiConfig() {
        try {
            String text = Shell.read(new File(getFilesDir(), "ui.json"));
            JSONObject j = text == null ? new JSONObject() : new JSONObject(text);
            hudMode = j.optInt("hud", 0);
            uiOpacity = (float) j.optDouble("opacity", 0.9);
            padDead = (float) (j.optInt("padDead", 20) / 100.0);
            padLook = (float) (j.optInt("padLook", 10) / 10.0);
            muted = j.optBoolean("mute", false);
            gQuality = j.optInt("quality", 1); gFps = j.optInt("fps", 0); gRes = j.optInt("res", 0);
            JSONObject m = j.optJSONObject("padmap");
            padMap = m == null ? new JSONObject() : m;
        } catch (Exception ignored) { }
        String name = Shell.read(new File(getFilesDir(), "active_profile.txt"));
        profileName = name == null || name.trim().isEmpty() ? Shell.BUILTIN : name.trim();
    }

    private void buildOverlay() {
        overlayRoot = new FrameLayout(this);
        ui = buildUi(profileName);
        overlayRoot.addView(ui, new FrameLayout.LayoutParams(-1, -1));
        hud = new TextView(this);
        hud.setTextColor(0xFFFFFFFF); hud.setBackgroundColor(0x88000000); hud.setTextSize(11);
        hud.setPadding(dp(6), dp(1), dp(6), dp(1)); hud.setVisibility(View.GONE);
        overlayRoot.addView(hud, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        menuButton = new TextView(this);
        menuButton.setText("\u2630"); menuButton.setTextColor(0xCCFFFFFF); menuButton.setTextSize(20);
        menuButton.setBackgroundColor(0x44000000); menuButton.setPadding(dp(14), dp(2), dp(14), dp(2));
        menuButton.setOnClickListener(v -> showMenu());
        overlayRoot.addView(menuButton, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        // NativeActivity owns the main surface. Draw controls in their own window.
        overlay = new android.widget.PopupWindow(overlayRoot, -1, -1, false);
        overlay.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        overlay.setClippingEnabled(false);
        overlay.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
    }

    /** Builds the Winlator profile view, or the original built-in layout when none is selected. */
    private View buildUi(String name) {
        controls = null; cv = null;
        if (name != null && !name.equals(Shell.BUILTIN)) {
            try {
                String text = Shell.read(new File(new File(getFilesDir(), "profiles"), name + ".icp"));
                if (text != null) {
                    cv = new ControlView(this, new JSONObject(text), host);
                    cv.setAlpha(Math.max(0.15f, Math.min(1f, uiOpacity)));
                    return cv;
                }
            } catch (Exception ignored) { }
        }
        controls = new Controls();
        return controls;
    }

    private void swapUi(String name) {
        if (cv != null) cv.reset();
        profileName = name;
        View old = ui;
        ui = buildUi(name);
        overlayRoot.addView(ui, 0, new FrameLayout.LayoutParams(-1, -1));
        if (old != null) overlayRoot.removeView(old);
        ui.setVisibility(touchVisible ? View.VISIBLE : View.INVISIBLE);
        try { Shell.write(new File(getFilesDir(), "active_profile.txt"), name); } catch (Exception ignored) { }
        saveOverride("profile", name);
    }

    @Override protected void onResume() {
        super.onResume(); active = true;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (pendingReload) { pendingReload = false; swapUi(profileName); }
        try { nativeMute(muted); } catch (Throwable ignored) { }
        applyHudMode();
        handler.post(lookTick);
        handler.post(hudTick);
        getWindow().getDecorView().post(() -> {
            if (active && !isFinishing() && !overlay.isShowing()) overlay.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, 0, 0);
        });
    }

    @Override protected void onPause() {
        overlay.dismiss();
        if (menu != null && menu.isShowing()) menu.dismiss();
        active = false; handler.removeCallbacks(lookTick); handler.removeCallbacks(hudTick); lookX = lookY = 0;
        held.clear(); analogActive.clear();
        if (controls != null) controls.contacts.clear();
        if (cv != null) cv.reset();
        nativeRelease(); nativeHorn(false);
        super.onPause();
    }

    // ================================================================ key plumbing
    private void key(String source, String key, boolean down) {
        if (!active) return;
        Set<String> owners = held.computeIfAbsent(key, k -> new HashSet<>());
        boolean before = !owners.isEmpty();
        if (down) owners.add(source); else owners.remove(source);
        boolean after = !owners.isEmpty();
        if (before != after) {
            if (key.equals("Fire")) nativeButton(0, after);
            else if (key.equals("Aim")) nativeButton(2, after);
            else if (key.equals("Horn")) nativeHorn(after);
            else nativeKey(key, after);
        }
    }

    private void release(String source) {
        for (String key : new ArrayList<>(held.keySet())) key(source, key, false);
    }

    private void stick(String source, float x, float y) {
        key(source, "KeyA", x < -.25f); key(source, "KeyD", x > .25f);
        key(source, "KeyW", y < -.25f); key(source, "KeyS", y > .25f);
    }

    /** Holds a remappable analog/hat/trigger input; releases the old key if the binding changed (e.g. entering a car). */
    private void analog(String name, String binding, boolean on) {
        String src = "pad-" + name;
        String last = analogActive.get(src);
        String code = on ? Shell.resolve(binding, nativeInVehicle()) : null;
        if (last != null && (!on || code == null || !last.equals(code))) { key(src, last, false); analogActive.remove(src); last = null; }
        if (on && code != null && last == null) { key(src, code, true); analogActive.put(src, code); }
    }

    // ================================================================ gamepad
    private String padInput(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return "A";
            case KeyEvent.KEYCODE_BUTTON_B: return "B";
            case KeyEvent.KEYCODE_BUTTON_X: return "X";
            case KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case KeyEvent.KEYCODE_BUTTON_L1: return "L1";
            case KeyEvent.KEYCODE_BUTTON_R1: return "R1";
            case KeyEvent.KEYCODE_BUTTON_L2: return "L2";
            case KeyEvent.KEYCODE_BUTTON_R2: return "R2";
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return "L3";
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return "R3";
            case KeyEvent.KEYCODE_BUTTON_START: case KeyEvent.KEYCODE_BUTTON_MODE: return "START";
            case KeyEvent.KEYCODE_BUTTON_SELECT: return "SELECT";
            case KeyEvent.KEYCODE_DPAD_UP: return "DPAD_UP";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "DPAD_DOWN";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "DPAD_LEFT";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "DPAD_RIGHT";
            default: return null;
        }
    }

    private String defaultBinding(String input) {
        if (input.startsWith("DPAD_")) return "GAMEPAD_" + input;
        return "GAMEPAD_BUTTON_" + input;
    }

    private String padBinding(String input) { return padMap.optString(input, defaultBinding(input)); }

    private void noticePad(InputEvent event) {
        InputDevice d = event.getDevice();
        if (d != null && (padDevice == null || !padDevice.equals(d.getName()))) {
            padDevice = d.getName();
            Toast.makeText(this, "Controller: " + padDevice, Toast.LENGTH_SHORT).show();
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int source = event.getSource();
        boolean padSource = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (padSource || KeyEvent.isGamepadButton(event.getKeyCode())) {
            String input = padInput(event.getKeyCode());
            if (input != null) {
                noticePad(event);
                if (input.equals("START") && event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE && event.getAction() == KeyEvent.ACTION_UP) { showMenu(); return true; }
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                String code = Shell.resolve(padBinding(input), nativeInVehicle());
                if (code != null) key("pad-key-" + event.getKeyCode(), code, down);
                return true;
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) showMenu();
            return true;
        }
        String keyboard = keyboardKey(event.getKeyCode());
        if (keyboard != null) {
            key("keyboard-" + event.getKeyCode(), keyboard, event.getAction() == KeyEvent.ACTION_DOWN);
            return true;
        }
        // Bypass NativeActivity.dispatchKeyEvent, which would redirect to the
        // disabled native queue. Activity's KeyEvent callbacks handle volume.
        return event.dispatch(this);
    }

    private static String keyboardKey(int code) {
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return "Key" + (char) ('A' + code - KeyEvent.KEYCODE_A);
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return "Digit" + (code - KeyEvent.KEYCODE_0);
        if (code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12) return "F" + (code - KeyEvent.KEYCODE_F1 + 1);
        switch (code) {
            case KeyEvent.KEYCODE_ENTER: return "Enter";
            case KeyEvent.KEYCODE_ESCAPE: return "Escape";
            case KeyEvent.KEYCODE_SPACE: return "Space";
            case KeyEvent.KEYCODE_TAB: return "Tab";
            case KeyEvent.KEYCODE_DEL: return "Backspace";
            case KeyEvent.KEYCODE_FORWARD_DEL: return "Delete";
            case KeyEvent.KEYCODE_SHIFT_LEFT: case KeyEvent.KEYCODE_SHIFT_RIGHT: return "ShiftLeft";
            case KeyEvent.KEYCODE_CTRL_LEFT: case KeyEvent.KEYCODE_CTRL_RIGHT: return "ControlLeft";
            case KeyEvent.KEYCODE_ALT_LEFT: case KeyEvent.KEYCODE_ALT_RIGHT: return "AltLeft";
            case KeyEvent.KEYCODE_DPAD_UP: return "ArrowUp";
            case KeyEvent.KEYCODE_DPAD_DOWN: return "ArrowDown";
            case KeyEvent.KEYCODE_DPAD_LEFT: return "ArrowLeft";
            case KeyEvent.KEYCODE_DPAD_RIGHT: return "ArrowRight";
            default: return null;
        }
    }

    private float dead(float v) {
        float a = Math.abs(v);
        if (a < padDead) return 0f;
        return Math.signum(v) * (a - padDead) / (1f - padDead);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            noticePad(event);
            float lx = event.getAxisValue(MotionEvent.AXIS_X), ly = event.getAxisValue(MotionEvent.AXIS_Y);
            boolean mag = Math.hypot(lx, ly) > Math.max(0.25f, padDead);
            key("pad-stick", "KeyA", mag && lx < -.35f); key("pad-stick", "KeyD", mag && lx > .35f);
            key("pad-stick", "KeyW", mag && ly < -.35f); key("pad-stick", "KeyS", mag && ly > .35f);
            // right stick: Z/RZ on most pads, RX/RY on some
            float rx = event.getAxisValue(MotionEvent.AXIS_Z), ry = event.getAxisValue(MotionEvent.AXIS_RZ);
            if (rx == 0f && ry == 0f) { rx = event.getAxisValue(MotionEvent.AXIS_RX); ry = event.getAxisValue(MotionEvent.AXIS_RY); }
            lookX = dead(rx); lookY = dead(ry);
            float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
            float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
            analog("R2", padBinding("R2"), rt > .2f);
            analog("L2", padBinding("L2"), lt > .2f);
            float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X), hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            analog("DPAD_LEFT", padBinding("DPAD_LEFT"), hx < -.5f); analog("DPAD_RIGHT", padBinding("DPAD_RIGHT"), hx > .5f);
            analog("DPAD_UP", padBinding("DPAD_UP"), hy < -.5f); analog("DPAD_DOWN", padBinding("DPAD_DOWN"), hy > .5f);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    // ================================================================ HUD
    private void applyHudMode() {
        hud.setVisibility(hudMode == 0 ? View.GONE : View.VISIBLE);
        hudT = 0;
        updateHud();
    }

    private void updateHud() {
        if (hudMode == 0) return;
        long now = SystemClock.elapsedRealtime();
        try {
            long presents = nativePresents();
            if (hudT != 0 && now > hudT) fps = (presents - hudPresents) * 1000f / (now - hudT);
            hudT = now; hudPresents = presents;
        } catch (Throwable t) { fps = -1; }
        StringBuilder sb = new StringBuilder();
        sb.append(fps < 0 ? "FPS ?" : String.format(Locale.US, "%.0f FPS", fps));
        if (hudMode >= 2) {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                float temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
                sb.append(String.format(Locale.US, "  |  batt %d%% %.1f\u00B0C", level * 100 / Math.max(1, scale), temp));
            }
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
            sb.append(String.format(Locale.US, "  |  RAM free %.1f GB", mi.availMem / 1e9));
            try {
                int th = getSystemService(PowerManager.class).getCurrentThermalStatus();
                String[] names = {"cool", "light", "moderate", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN"};
                sb.append("  |  thermal ").append(names[Math.max(0, Math.min(names.length - 1, th))]);
            } catch (Throwable ignored) { }
        }
        hud.setText(sb.toString());
    }

    // ================================================================ in-game slide menu
    private void saveOverride(String key, Object value) {
        try {
            File f = new File(getFilesDir(), "overrides.json");
            String text = Shell.read(f);
            JSONObject j = text == null ? new JSONObject() : new JSONObject(text);
            j.put(key, value);
            Shell.write(f, j.toString());
        } catch (Exception ignored) { }
    }

    private void restart() {
        Intent i = new Intent(this, LauncherActivity.class).putExtra("autoplay", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
        handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 700);
    }

    private void exitGame() {
        finish();
        handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 700);
    }

    @Override public void onBackPressed() { showMenu(); }

    private Button menuButton(LinearLayout col, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); col.addView(b); return b;
    }

    private TextView menuHeading(LinearLayout col, String label) {
        TextView t = new TextView(this); t.setText(label); t.setTextSize(13); t.setTextColor(0xFF8FB3FF); t.setPadding(0, dp(12), 0, dp(2)); col.addView(t); return t;
    }

    private void showMenu() {
        if (menu != null && menu.isShowing()) { menu.dismiss(); return; }
        final Dialog d = new Dialog(this, R.style.SlideMenu);
        menu = d;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(16), dp(16), dp(16), dp(16));
        TextView title = new TextView(this); title.setText("Game menu"); title.setTextSize(22); title.setTextColor(0xFFFFFFFF); col.addView(title);

        menuButton(col, "Resume", d::dismiss);
        menuButton(col, "Restart game", () -> new AlertDialog.Builder(this).setMessage("Restart the game? Unsaved progress is lost.")
            .setNegativeButton("Cancel", null).setPositiveButton("Restart", (x, w) -> restart()).show());
        menuButton(col, "Exit game", () -> new AlertDialog.Builder(this).setMessage("Exit the game? Unsaved progress is lost.")
            .setNegativeButton("Stay", null).setPositiveButton("Exit", (x, w) -> exitGame()).show());

        menuHeading(col, "On-screen HUD");
        final Spinner hudSpin = new Spinner(this);
        hudSpin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Off", "FPS", "Detailed (FPS, battery, RAM, thermal)"}));
        hudSpin.setSelection(hudMode);
        hudSpin.setOnItemSelectedListener(new SimpleSelect(pos -> { if (pos != hudMode) { hudMode = pos; saveOverride("hud", pos); applyHudMode(); } }));
        col.addView(hudSpin);

        menuHeading(col, "Controls");
        final Switch show = new Switch(this); show.setText("Show touch controls"); show.setChecked(touchVisible);
        show.setOnCheckedChangeListener((b, on) -> { touchVisible = on; ui.setVisibility(on ? View.VISIBLE : View.INVISIBLE); });
        col.addView(show);
        final List<String> names = Shell.listProfiles(this);
        String[] shown = new String[names.size()];
        for (int i = 0; i < shown.length; i++) shown[i] = names.get(i).equals(Shell.BUILTIN) ? "Built-in (original)" : names.get(i);
        final Spinner prof = new Spinner(this);
        prof.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, shown));
        prof.setSelection(Math.max(0, names.indexOf(profileName)));
        prof.setOnItemSelectedListener(new SimpleSelect(pos -> { if (!names.get(pos).equals(profileName)) swapUi(names.get(pos)); }));
        col.addView(prof);
        menuButton(col, "Edit this layout", () -> {
            if (profileName.equals(Shell.BUILTIN)) { Toast.makeText(this, "Pick a profile first.", Toast.LENGTH_SHORT).show(); return; }
            pendingReload = true; d.dismiss();
            startActivity(new Intent(this, EditorActivity.class).putExtra("profile", profileName));
        });
        TextView op = new TextView(this); op.setText("Controls opacity"); op.setTextColor(0xFFFFFFFF); col.addView(op);
        SeekBar opacity = new SeekBar(this); opacity.setMax(85); opacity.setProgress(Math.round(uiOpacity * 100) - 15);
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                uiOpacity = (p + 15) / 100f; if (ui != null) ui.setAlpha(uiOpacity); saveOverride("opacity", uiOpacity);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        col.addView(opacity);

        menuHeading(col, "Sound");
        final Switch mute = new Switch(this); mute.setText("Mute"); mute.setChecked(muted);
        mute.setOnCheckedChangeListener((b, on) -> { muted = on; try { nativeMute(on); } catch (Throwable ignored) { } saveOverride("mute", on); });
        col.addView(mute);

        menuHeading(col, "Graphics (needs a restart)");
        final Spinner scale = new Spinner(this);
        scale.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Shell.QUALITY_NAMES));
        scale.setSelection(gQuality);
        col.addView(scale);
        final Spinner cap = new Spinner(this);
        cap.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Shell.FPS_NAMES));
        cap.setSelection(gFps);
        col.addView(cap);
        final Spinner res = new Spinner(this);
        res.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, Shell.RES_NAMES));
        res.setSelection(gRes);
        col.addView(res);
        menuButton(col, "Apply graphics and restart", () -> {
            saveOverride("quality", scale.getSelectedItemPosition()); saveOverride("fps", cap.getSelectedItemPosition()); saveOverride("res", res.getSelectedItemPosition());
            restart();
        });
        menuButton(col, "Clear graphics cache and restart", () -> new AlertDialog.Builder(this)
            .setMessage("Delete the compile cache and temp files? Saves are kept. The next launch takes several minutes.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear", (x, w) -> { Shell.clearGraphicsAndTemp(this); restart(); }).show());

        menuHeading(col, "Controller");
        TextView pad = new TextView(this); pad.setTextColor(0xFFCCCCCC);
        pad.setText(padDevice == null ? "No controller input yet. Connect one by USB or Bluetooth and press a button." : "Connected: " + padDevice);
        col.addView(pad);

        ScrollView sv = new ScrollView(this); sv.addView(col);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setGravity(Gravity.END);
            w.setLayout(dp(330), ViewGroup.LayoutParams.MATCH_PARENT);
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        d.show();
    }

    /** Spinner listener that ignores the automatic first call. */
    private static final class SimpleSelect implements AdapterView.OnItemSelectedListener {
        interface Pick { void on(int position); }
        private final Pick pick; private boolean first = true;
        SimpleSelect(Pick pick) { this.pick = pick; }
        @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (first) { first = false; return; }
            pick.on(position);
        }
        @Override public void onNothingSelected(AdapterView<?> parent) { }
    }

    // ================================================================ original built-in layout (fallback)
    private final class Controls extends View {
        final Map<Integer, Contact> contacts = new HashMap<>();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final String[] labels = {"PAUSE", "PHONE", "WEAPON", "CAM", "SWITCH", "◀", "▲", "▼", "▶", "OK", "BACK", "FIRE", "AIM", "JUMP", "ENTER", "RUN", "RELOAD"};
        final String[] actions = {"Escape", "ArrowUp", "Tab", "KeyV", "AltLeft", "ArrowLeft", "ArrowUp", "ArrowDown", "ArrowRight", "Enter", "Backspace", "Fire", "Aim", "Space", "KeyF", "ShiftLeft", "KeyR"};
        Controls() { super(GameActivity.this); setFocusable(false); setContentDescription("Game controls: left stick moves, drag the right side to look, buttons perform actions"); }
        float unit() { return Math.min(getHeight() / 8f, getWidth() / 16f); }
        RectF box(int i) {
            float u = unit();
            if (i < 11) { float width = getWidth() / 11f; return new RectF(i * width + 2, 6, (i + 1) * width - 2, u * .8f); }
            int n = i - 11;
            float x = getWidth() - (3 - n % 3) * u * 1.1f;
            float y = getHeight() - (2 - n / 3) * u * 1.05f;
            return new RectF(x, y, x + u, y + u * .9f);
        }
        @Override protected void onDraw(Canvas canvas) {
            boolean car = nativeInVehicle();
            for (int i = 0; i < labels.length; i++) {
                RectF rect = box(i); paint.setColor(0x66464D48); canvas.drawRoundRect(rect, 16, 16, paint);
                paint.setColor(0xEEFFFFFF); paint.setTextSize(unit() * .18f); paint.setTextAlign(Paint.Align.CENTER);
                String label = car && i == 11 ? "GAS" : car && i == 12 ? "BRAKE" : car && i == 16 ? "HORN" : labels[i];
                canvas.drawText(label, rect.centerX(), rect.centerY() + paint.getTextSize() / 3, paint);
            }
            if (nativeLoading()) {
                paint.setColor(0xEE141815);
                canvas.drawRoundRect(new RectF(getWidth() * .2f, getHeight() * .35f, getWidth() * .8f, getHeight() * .6f), 20, 20, paint);
                paint.setColor(Color.WHITE); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(unit() * .28f);
                canvas.drawText("Preparing your game…", getWidth() / 2f, getHeight() * .45f, paint);
                paint.setTextSize(unit() * .18f);
                canvas.drawText("First launch can take several minutes.", getWidth() / 2f, getHeight() * .52f, paint);
            }
            paint.setColor(0x55464D48); canvas.drawCircle(unit() * 1.5f, getHeight() - unit() * 1.5f, unit(), paint);
            paint.setColor(0xBBFFFFFF); paint.setTextSize(unit() * .2f);
            canvas.drawText("MOVE", unit() * 1.5f, getHeight() - unit() * 1.5f, paint);
            postInvalidateDelayed(250);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked(), index = e.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                int id = e.getPointerId(index); float x = e.getX(index), y = e.getY(index);
                Contact c = new Contact(x, y); contacts.put(id, c);
                for (int i = 0; i < actions.length; i++) if (box(i).contains(x, y)) {
                    c.action = nativeInVehicle() && i == 11 ? "KeyW" : nativeInVehicle() && i == 12 ? "KeyS" : nativeInVehicle() && i == 16 ? "Horn" : actions[i];
                    key("touch-" + id, c.action, true); return true;
                }
                c.move = x < getWidth() * .35f;
            } else if (action == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i); Contact c = contacts.get(id); if (c == null || c.action != null) continue;
                    float x = e.getX(i), y = e.getY(i);
                    if (c.move) stick("touch-" + id, (x - c.x) / unit(), (y - c.y) / unit());
                    else { nativeLook((x - c.x) * 2, (y - c.y) * 2); c.x = x; c.y = y; }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                int id = e.getPointerId(index); release("touch-" + id); contacts.remove(id); performClick();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                for (Integer id : contacts.keySet()) release("touch-" + id); contacts.clear();
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
    private static final class Contact {
        float x, y; boolean move; String action;
        Contact(float x, float y) { this.x = x; this.y = y; }
    }
}
