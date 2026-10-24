package com.alihammoud21.gtavmobile;

import android.app.NativeActivity;
import android.os.Bundle;
import android.graphics.*;
import android.view.*;
import java.util.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Native renderer plus multitouch controls and standard Android gamepad input. */
public final class GameActivity extends NativeActivity {
    static { System.loadLibrary("gtav_mobile"); }
    private static native void nativeKey(String key, boolean down);
    private static native void nativeLook(float dx, float dy);
    private static native void nativeButton(int button, boolean down);
    private static native void nativeRelease();
    private static native boolean nativeInVehicle();
    private static native boolean nativeLoading();
    private static native void nativeHorn(boolean down);
    private final Map<String, Set<String>> held = new HashMap<>();
    private Controls controls;
    private Profile profile;
    private android.widget.PopupWindow overlay;
    private boolean active;
    private float lookX, lookY;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable lookTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (Math.abs(lookX) > .15f || Math.abs(lookY) > .15f) nativeLook(lookX * 12, lookY * 12);
            handler.postDelayed(this, 16);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Keep Java input dispatch: winit otherwise consumes the native input queue
        // before the touch overlay or gamepad handlers can receive events.
        getWindow().takeInputQueue(null);
        profile = loadProfile();               // Winlator .icp layout chosen in the launcher
        if (profile == null) controls = new Controls();   // otherwise the original built-in layout
        View ui = profile != null ? profile : controls;
        // NativeActivity owns the main surface. Draw controls in their own window.
        overlay = new android.widget.PopupWindow(ui, -1, -1, false);
        overlay.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        overlay.setClippingEnabled(false);
        overlay.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
    }
    @Override protected void onResume() {
        super.onResume(); active = true;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        handler.post(lookTick);
        getWindow().getDecorView().post(() -> {
            if (active && !isFinishing() && !overlay.isShowing()) overlay.showAtLocation(getWindow().getDecorView(), Gravity.TOP | Gravity.START, 0, 0);
        });
    }
    @Override protected void onPause() {
        overlay.dismiss();
        active = false; handler.removeCallbacks(lookTick); lookX = lookY = 0;
        held.clear(); if (controls != null) controls.contacts.clear(); if (profile != null) profile.reset(); nativeRelease(); nativeHorn(false);
        super.onPause();
    }
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
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            String key = switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BUTTON_A -> "Enter";
                case KeyEvent.KEYCODE_BUTTON_B -> "Backspace";
                case KeyEvent.KEYCODE_BUTTON_X -> "Space";
                case KeyEvent.KEYCODE_BUTTON_Y -> "KeyF";
                case KeyEvent.KEYCODE_BUTTON_L1 -> "Tab";
                case KeyEvent.KEYCODE_BUTTON_R1 -> "KeyQ";
                case KeyEvent.KEYCODE_BUTTON_START -> "Escape";
                case KeyEvent.KEYCODE_BUTTON_SELECT -> "KeyV";
                case KeyEvent.KEYCODE_BUTTON_THUMBL -> "ControlLeft";
                case KeyEvent.KEYCODE_BUTTON_THUMBR -> "KeyC";
                case KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp";
                case KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown";
                case KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft";
                case KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight";
                default -> null;
            };
            if (key != null) {
                key("pad-key-" + event.getKeyCode(), key, event.getAction() == KeyEvent.ACTION_DOWN);
                return true;
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBackPressed();
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
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z) return "Key" + (char)('A' + code - KeyEvent.KEYCODE_A);
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) return "Digit" + (code - KeyEvent.KEYCODE_0);
        return switch (code) {
            case KeyEvent.KEYCODE_ENTER -> "Enter";
            case KeyEvent.KEYCODE_ESCAPE -> "Escape";
            case KeyEvent.KEYCODE_SPACE -> "Space";
            case KeyEvent.KEYCODE_TAB -> "Tab";
            case KeyEvent.KEYCODE_DEL -> "Backspace";
            case KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftLeft";
            case KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlLeft";
            case KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "AltLeft";
            case KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp";
            case KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown";
            case KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft";
            case KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight";
            default -> null;
        };
    }
    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            stick("pad-stick", event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y));
            lookX = event.getAxisValue(MotionEvent.AXIS_Z); lookY = event.getAxisValue(MotionEvent.AXIS_RZ);
            float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
            float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
            boolean car = nativeInVehicle();
            key("pad-trigger", "Fire", !car && rt > .2f); key("pad-trigger", "Aim", !car && lt > .2f);
            key("pad-trigger", "KeyW", car && rt > .2f); key("pad-trigger", "KeyS", car && lt > .2f);
            float hx = event.getAxisValue(MotionEvent.AXIS_HAT_X), hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            key("pad-hat", "ArrowLeft", hx < -.5f); key("pad-hat", "ArrowRight", hx > .5f);
            key("pad-hat", "ArrowUp", hy < -.5f); key("pad-hat", "ArrowDown", hy > .5f);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }
    @Override public void onBackPressed() {
        new android.app.AlertDialog.Builder(this).setMessage("Leave the game?")
            .setNegativeButton("Stay", null).setPositiveButton("Leave", (d, w) -> finish()).show();
    }

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

    // ===================================================================
    // Winlator .icp control profiles (Game Shell Native addition)
    // ===================================================================
    private Profile loadProfile() {
        try {
            String name = new String(Files.readAllBytes(new File(getFilesDir(), "active_profile.txt").toPath()), StandardCharsets.UTF_8).trim();
            if (name.isEmpty() || name.equals("builtin")) return null;
            File f = new File(new File(getFilesDir(), "profiles"), name + ".icp");
            return new Profile(new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)));
        } catch (Exception e) { return null; }
    }

    private static final int R_NONE = 0, R_LOOK = 1, R_BTN = 2, R_DIR = 3, R_TRACK = 4, R_RANGE = 5;

    private static final class El {
        String type, shape, text, range;
        final String[] bind = new String[4];
        float scale = 1, x, y, cx, cy, r, w, h, kx, ky;
        boolean toggle, mouseMove, on, open, pressed;
        final boolean[] dir = new boolean[4];
        int off;
    }

    private static final class Touch {
        El el; int role; float lx, ly, sx, sy; boolean moved; float acc; String src;
    }

    private final class Profile extends View {
        final List<El> els = new ArrayList<>();
        final Map<Integer, Touch> touches = new HashMap<>();
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final float cursor;
        int laidOutWidth = -1, laidOutHeight = -1;

        Profile(JSONObject json) throws JSONException {
            super(GameActivity.this);
            setFocusable(false);
            cursor = (float) json.optDouble("cursorSpeed", 1.0);
            JSONArray a = json.getJSONArray("elements");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                El e = new El();
                e.type = o.optString("type"); e.shape = o.optString("shape", "CIRCLE");
                e.text = o.optString("text", ""); e.range = o.optString("range", "");
                JSONArray b = o.optJSONArray("bindings");
                for (int k = 0; k < 4; k++) e.bind[k] = b != null && k < b.length() ? b.optString(k, "NONE") : "NONE";
                e.scale = (float) o.optDouble("scale", 1); e.x = (float) o.optDouble("x", .5); e.y = (float) o.optDouble("y", .5);
                e.toggle = o.optBoolean("toggleSwitch"); e.mouseMove = o.optBoolean("mouseMoveMode");
                els.add(e);
            }
        }

        // ---- geometry (sizes follow Winlator: snap = max(width, height) / 100) ----
        void layoutAll() {
            float W = getWidth(), H = getHeight(), snap = Math.max(W, H) / 100f;
            for (El e : els) {
                e.cx = e.x * W; e.cy = e.y * H; e.w = e.h = e.r = 0;
                switch (e.type) {
                    case "D_PAD": e.r = 7 * snap * e.scale; break;
                    case "STICK": case "TRACKPAD": e.r = 6 * snap * e.scale; break;
                    case "RADIAL_MENU": e.r = 3 * snap * e.scale; break;
                    case "RANGE_BUTTON": e.w = 16 * snap * e.scale; e.h = 4 * snap * e.scale; break;
                    default:
                        if (e.shape.equals("RECT") || e.shape.equals("ROUND_RECT")) { e.w = 8 * snap * e.scale; e.h = 4 * snap * e.scale; }
                        else if (e.shape.equals("SQUARE")) { e.w = e.h = 6 * snap * e.scale; }
                        else e.r = 3 * snap * e.scale;
                }
            }
            laidOutWidth = getWidth(); laidOutHeight = getHeight();
        }

        float dist(float x, float y, El e) { return (float) Math.hypot(x - e.cx, y - e.cy); }

        boolean hit(El e, float x, float y) {
            switch (e.type) {
                case "D_PAD": return Math.abs(x - e.cx) <= e.r && Math.abs(y - e.cy) <= e.r;
                case "STICK": case "TRACKPAD": return dist(x, y, e) <= e.r;
                case "RADIAL_MENU": return dist(x, y, e) <= (e.open ? e.r * 7f / 3f : e.r);
                case "RANGE_BUTTON": return Math.abs(x - e.cx) <= e.w / 2 && Math.abs(y - e.cy) <= e.h / 2;
                default:
                    if (e.w > 0) return Math.abs(x - e.cx) <= e.w / 2 && Math.abs(y - e.cy) <= e.h / 2;
                    return dist(x, y, e) <= e.r;
            }
        }

        // ---- binding -> engine key names ----
        String resolve(String b) {
            if (b == null || b.equals("NONE")) return null;
            boolean car = nativeInVehicle();
            switch (b) {
                case "MOUSE_LEFT_BUTTON": return "Fire";
                case "MOUSE_RIGHT_BUTTON": return "Aim";
                case "GAMEPAD_BUTTON_R2": return car ? "KeyW" : "Fire";
                case "GAMEPAD_BUTTON_L2": return car ? "KeyS" : "Aim";
                case "GAMEPAD_BUTTON_A": return "Enter";
                case "GAMEPAD_BUTTON_B": return "Backspace";
                case "GAMEPAD_BUTTON_X": return "Space";
                case "GAMEPAD_BUTTON_Y": return "KeyF";
                case "GAMEPAD_BUTTON_L1": return "Tab";
                case "GAMEPAD_BUTTON_R1": return "KeyQ";
                case "GAMEPAD_BUTTON_START": return "Escape";
                case "GAMEPAD_BUTTON_SELECT": return "KeyV";
                case "GAMEPAD_BUTTON_L3": return "ControlLeft";
                case "GAMEPAD_BUTTON_R3": return "KeyC";
                case "GAMEPAD_DPAD_UP": return "ArrowUp";
                case "GAMEPAD_DPAD_DOWN": return "ArrowDown";
                case "GAMEPAD_DPAD_LEFT": return "ArrowLeft";
                case "GAMEPAD_DPAD_RIGHT": return "ArrowRight";
                case "GAMEPAD_LEFT_THUMB_UP": return "KeyW";
                case "GAMEPAD_LEFT_THUMB_DOWN": return "KeyS";
                case "GAMEPAD_LEFT_THUMB_LEFT": return "KeyA";
                case "GAMEPAD_LEFT_THUMB_RIGHT": return "KeyD";
                default: break;
            }
            if (b.startsWith("KEY_")) {
                String k = b.substring(4);
                if (k.length() == 1 && Character.isLetter(k.charAt(0))) return "Key" + k.toUpperCase(Locale.US);
                if (k.length() == 1 && Character.isDigit(k.charAt(0))) return "Digit" + k;
                switch (k) {
                    case "ALT_L": return "AltLeft";
                    case "BKSP": return "Backspace";
                    case "CTRL_L": return "ControlLeft";
                    case "DEL": return "Delete";
                    case "ENTER": return "Enter";
                    case "ESC": return "Escape";
                    case "SHIFT_L": return "ShiftLeft";
                    case "SPACE": return "Space";
                    case "TAB": return "Tab";
                    case "UP": return "ArrowUp";
                    case "DOWN": return "ArrowDown";
                    case "LEFT": return "ArrowLeft";
                    case "RIGHT": return "ArrowRight";
                    default: return null;
                }
            }
            return null;
        }

        String shortName(String b) {
            if (b == null || b.equals("NONE")) return "";
            switch (b) {
                case "MOUSE_LEFT_BUTTON": return "MLB";
                case "MOUSE_RIGHT_BUTTON": return "MRB";
                case "KEY_SHIFT_L": return "L SHIFT";
                case "KEY_CTRL_L": return "L CTRL";
                case "KEY_ALT_L": return "L ALT";
                case "KEY_BKSP": return "BKSP";
                case "KEY_UP": return "\u25B2"; case "KEY_DOWN": return "\u25BC";
                case "KEY_LEFT": return "\u25C0"; case "KEY_RIGHT": return "\u25B6";
                case "GAMEPAD_DPAD_UP": return "\u25B2"; case "GAMEPAD_DPAD_DOWN": return "\u25BC";
                case "GAMEPAD_DPAD_LEFT": return "\u25C0"; case "GAMEPAD_DPAD_RIGHT": return "\u25B6";
                default: break;
            }
            if (b.startsWith("KEY_")) return b.substring(4);
            if (b.startsWith("GAMEPAD_BUTTON_")) return b.substring(15);
            return b;
        }

        void apply(El e, String src, boolean down) {
            for (String b : e.bind) { String code = resolve(b); if (code != null) key(src, code, down); }
        }

        void tap(String code) {
            final String src = "tap-" + code;
            key(src, code, true);
            handler.postDelayed(() -> key(src, code, false), 70);
        }

        // ---- touch ----
        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (laidOutWidth != getWidth() || laidOutHeight != getHeight()) layoutAll();
            int action = ev.getActionMasked(), index = ev.getActionIndex();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                down(ev.getPointerId(index), ev.getX(index), ev.getY(index));
            } else if (action == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < ev.getPointerCount(); i++) move(ev.getPointerId(i), ev.getX(i), ev.getY(i));
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
                up(ev.getPointerId(index), ev.getX(index));
                performClick();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                reset();
            }
            invalidate();
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }

        void down(int id, float x, float y) {
            Touch t = new Touch();
            t.src = "pt-" + id; t.sx = t.lx = x; t.sy = t.ly = y;
            touches.put(id, t);
            for (El e : els) if (e.open) { radialTouch(e, x, y); return; }   // an open radial menu takes the tap first
            El hit = null;
            for (int i = els.size() - 1; i >= 0; i--) if (hit(els.get(i), x, y)) { hit = els.get(i); break; }
            if (hit == null) { t.role = R_LOOK; return; }
            t.el = hit;
            switch (hit.type) {
                case "D_PAD": case "STICK": t.role = R_DIR; dir(t, x, y); break;
                case "TRACKPAD": t.role = R_TRACK; break;
                case "RADIAL_MENU": hit.open = true; t.role = R_NONE; break;
                case "RANGE_BUTTON": t.role = R_RANGE; break;
                default:
                    t.role = R_BTN;
                    if (hit.toggle) { hit.on = !hit.on; apply(hit, "tog-" + els.indexOf(hit), hit.on); }
                    else { hit.pressed = true; apply(hit, t.src, true); }
            }
        }

        void radialTouch(El e, float x, float y) {
            float R = e.r * 7f / 3f, d = dist(x, y, e);
            e.open = false;
            if (d > R || d <= R * 0.3f) return;                       // outside or centre cross closes it
            List<String> codes = new ArrayList<>();
            for (String b : e.bind) { String c = resolve(b); if (c != null) codes.add(c); }
            if (codes.isEmpty()) return;
            double ang = Math.toDegrees(Math.atan2(y - e.cy, x - e.cx));   // -180..180, 0 = right
            double start = -90.0 + 180.0 / codes.size();                    // first sector centre (-54 deg for 5)
            double rel = ((ang - start) % 360 + 360 + 180.0 / codes.size()) % 360;
            int idx = (int) (rel / (360.0 / codes.size()));
            if (idx >= 0 && idx < codes.size()) tap(codes.get(idx));
        }

        void dir(Touch t, float x, float y) {
            El e = t.el;
            float dx = x - e.cx, dy = y - e.cy, th = e.r * 0.28f;
            boolean[] want = {dy < -th, dx > th, dy > th, dx < -th};
            for (int k = 0; k < 4; k++) {
                if (want[k] != e.dir[k]) {
                    e.dir[k] = want[k];
                    String code = resolve(e.bind[k]);
                    if (code != null) key(t.src, code, want[k]);
                }
            }
            e.kx = Math.max(-1f, Math.min(1f, dx / e.r)); e.ky = Math.max(-1f, Math.min(1f, dy / e.r));
        }

        void move(int id, float x, float y) {
            Touch t = touches.get(id);
            if (t == null) return;
            switch (t.role) {
                case R_LOOK:
                    nativeLook((x - t.lx) * 2 * cursor, (y - t.ly) * 2 * cursor); t.lx = x; t.ly = y; break;
                case R_BTN:
                    if (t.el.mouseMove) { nativeLook((x - t.lx) * 2 * cursor, (y - t.ly) * 2 * cursor); t.lx = x; t.ly = y; }
                    break;
                case R_DIR: dir(t, x, y); break;
                case R_TRACK:
                    lookX = Math.max(-1f, Math.min(1f, (x - t.sx) / t.el.r)); lookY = Math.max(-1f, Math.min(1f, (y - t.sy) / t.el.r));
                    t.el.kx = lookX; t.el.ky = lookY; break;
                case R_RANGE: {
                    t.acc += x - t.lx; t.lx = x;
                    float step = t.el.w / 4f * 0.7f;
                    if (Math.abs(t.acc) > step) {
                        t.moved = true;
                        int max = rangeLabels(t.el).length - 4;
                        t.el.off = Math.max(0, Math.min(max, t.el.off - (t.acc > 0 ? 1 : -1)));
                        t.acc = 0;
                    }
                    break;
                }
                default: break;
            }
        }

        void up(int id, float x) {
            Touch t = touches.remove(id);
            if (t == null) return;
            if (t.role == R_RANGE && !t.moved) {
                El e = t.el;
                int cell = (int) Math.max(0, Math.min(3, Math.floor((x - (e.cx - e.w / 2)) / (e.w / 4f))));
                String[] codes = rangeCodes(e);
                if (e.off + cell < codes.length) tap(codes[e.off + cell]);
            }
            releaseTouch(t);
        }

        void releaseTouch(Touch t) {
            release(t.src);
            if (t.el != null) {
                t.el.pressed = false;
                if (t.role == R_DIR) { Arrays.fill(t.el.dir, false); t.el.kx = t.el.ky = 0; }
                if (t.role == R_TRACK) { lookX = lookY = 0; t.el.kx = t.el.ky = 0; }
            }
        }

        void reset() {
            for (Touch t : new ArrayList<>(touches.values())) releaseTouch(t);
            touches.clear();
            for (int i = 0; i < els.size(); i++) {
                El e = els.get(i);
                if (e.on) apply(e, "tog-" + i, false);
                e.on = false; e.open = false; e.pressed = false;
                Arrays.fill(e.dir, false); e.kx = e.ky = 0;
            }
            lookX = lookY = 0;
        }

        // ---- range buttons ----
        String[] rangeLabels(El e) {
            if (e.range.equals("FROM_F1_TO_F12")) {
                String[] a = new String[12]; for (int i = 0; i < 12; i++) a[i] = "F" + (i + 1); return a;
            }
            return new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
        }
        String[] rangeCodes(El e) {
            String[] l = rangeLabels(e), c = new String[l.length];
            for (int i = 0; i < l.length; i++) c[i] = e.range.equals("FROM_F1_TO_F12") ? l[i] : "Digit" + l[i];
            return c;
        }

        // ---- drawing ----
        void body(Canvas c, El e, boolean active) {
            p.setStyle(Paint.Style.FILL); p.setColor(active ? 0x6678FF9A : 0x14FFFFFF);
            drawBody(c, e);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, getWidth() / 600f)); p.setColor(0xB4D2D2D2);
            drawBody(c, e);
            p.setStyle(Paint.Style.FILL);
        }
        void drawBody(Canvas c, El e) {
            if (e.w > 0) {
                float rr = e.shape.equals("ROUND_RECT") ? e.h / 2f : e.shape.equals("SQUARE") ? e.w * .12f : e.w * .03f;
                c.drawRoundRect(new RectF(e.cx - e.w / 2, e.cy - e.h / 2, e.cx + e.w / 2, e.cy + e.h / 2), rr, rr, p);
            } else c.drawCircle(e.cx, e.cy, e.r, p);
        }
        void label(Canvas c, String s, float cx, float cy, float size, float maxWidth) {
            p.setStyle(Paint.Style.FILL); p.setColor(0xFFEEEEEE); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(size);
            float w = p.measureText(s);
            if (w > maxWidth && w > 0) p.setTextSize(size * maxWidth / w);
            c.drawText(s, cx, cy + p.getTextSize() / 3f, p);
        }

        @Override protected void onDraw(Canvas c) {
            if (laidOutWidth != getWidth() || laidOutHeight != getHeight()) layoutAll();
            for (El e : els) {
                switch (e.type) {
                    case "D_PAD": {
                        float R = e.r, a = R * 0.62f;
                        float[][] cells = {{0, -R + a / 2}, {R - a / 2, 0}, {0, R - a / 2}, {-R + a / 2, 0}};
                        String[] arrows = {"\u25B2", "\u25B6", "\u25BC", "\u25C0"};
                        for (int k = 0; k < 4; k++) {
                            El cell = new El(); cell.shape = "SQUARE"; cell.cx = e.cx + cells[k][0]; cell.cy = e.cy + cells[k][1]; cell.w = cell.h = a;
                            body(c, cell, e.dir[k]);
                            label(c, arrows[k], cell.cx, cell.cy, a * 0.5f, a);
                        }
                        break;
                    }
                    case "STICK": case "TRACKPAD": {
                        body(c, e, false);
                        p.setStyle(Paint.Style.FILL); p.setColor(0x66FFFFFF);
                        c.drawCircle(e.cx + e.kx * e.r * 0.5f, e.cy + e.ky * e.r * 0.5f, e.r * 0.38f, p);
                        if (e.type.equals("TRACKPAD")) label(c, "LOOK", e.cx, e.cy, e.r * 0.3f, e.r * 1.2f);
                        break;
                    }
                    case "RADIAL_MENU": {
                        if (!e.open) { body(c, e, false); label(c, "\u2731", e.cx, e.cy, e.r * 0.8f, e.r * 1.4f); break; }
                        float R = e.r * 7f / 3f;
                        p.setStyle(Paint.Style.FILL); p.setColor(0x990A1E32); c.drawCircle(e.cx, e.cy, R, p);
                        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(6f); p.setColor(0xCC2878C8); c.drawCircle(e.cx, e.cy, R, p);
                        List<String> names = new ArrayList<>();
                        for (String b : e.bind) if (resolve(b) != null) names.add(shortName(b));
                        for (int k = 0; k < names.size(); k++) {
                            double ang = Math.toRadians(-90.0 + 180.0 / names.size() + k * 360.0 / names.size());
                            El s = new El(); s.cx = e.cx + (float) Math.cos(ang) * R * 0.66f; s.cy = e.cy + (float) Math.sin(ang) * R * 0.66f; s.r = R * 0.26f;
                            body(c, s, false); label(c, names.get(k), s.cx, s.cy, s.r * 0.9f, s.r * 1.6f);
                        }
                        El x = new El(); x.cx = e.cx; x.cy = e.cy; x.r = R * 0.26f; body(c, x, false);
                        label(c, "\u2715", e.cx, e.cy, x.r, x.r * 1.4f);
                        break;
                    }
                    case "RANGE_BUTTON": {
                        body(c, e, false);
                        String[] labels = rangeLabels(e);
                        for (int k = 0; k < 4; k++) {
                            float cx = e.cx - e.w / 2 + e.w / 8f + k * e.w / 4f;
                            if (e.off + k < labels.length) label(c, labels[e.off + k], cx, e.cy, e.h * 0.5f, e.w / 4f);
                            if (k > 0) { p.setStyle(Paint.Style.STROKE); p.setColor(0x88D2D2D2); c.drawLine(cx - e.w / 8f, e.cy - e.h / 2, cx - e.w / 8f, e.cy + e.h / 2, p); }
                        }
                        break;
                    }
                    default: {
                        body(c, e, e.on || e.pressed);
                        String s = !e.text.isEmpty() ? e.text : shortName(e.bind[0]);
                        float size = (e.w > 0 ? e.h : e.r) * 0.5f;
                        label(c, s, e.cx, e.cy, size, (e.w > 0 ? e.w : e.r * 2f) * 0.86f);
                    }
                }
            }
            if (nativeLoading()) {
                p.setStyle(Paint.Style.FILL); p.setColor(0xEE141815);
                c.drawRoundRect(new RectF(getWidth() * .2f, getHeight() * .35f, getWidth() * .8f, getHeight() * .6f), 20, 20, p);
                label(c, "Preparing your game\u2026", getWidth() / 2f, getHeight() * .45f, getHeight() * .06f, getWidth() * .55f);
                label(c, "First launch can take several minutes.", getWidth() / 2f, getHeight() * .53f, getHeight() * .04f, getWidth() * .55f);
            }
            postInvalidateDelayed(250);
        }
    }
}
