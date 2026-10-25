package com.alihammoud21.gtavmobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * On-screen controls driven by a Winlator-compatible input-controls profile (.icp).
 * Play mode sends input to a Host (the game); edit mode lets the user drag and configure elements.
 * Sizes follow Winlator: snap = max(width, height) / 100. Written for Game Shell Native.
 */
public final class ControlView extends View {
    public interface Host {
        void key(String source, String code, boolean down);
        void release(String source);
        void look(float dx, float dy);
        void lookVector(float x, float y);
        boolean inVehicle();
        boolean loading();
    }
    public interface Listener { void onSelect(El element); }

    public static final class El {
        public String type = "BUTTON", shape = "CIRCLE", text = "", range = "";
        public final List<String> bind = new ArrayList<>();
        public float scale = 1, x = .5f, y = .5f;
        public boolean toggle, mouseMove;
        float cx, cy, r, w, h, kx, ky;
        boolean on, open, pressed;
        final boolean[] dir = new boolean[4];
        int off;
    }

    private static final class Touch {
        El el; int role; float lx, ly, sx, sy, acc; boolean moved; String src;
    }

    private static final int R_NONE = 0, R_LOOK = 1, R_BTN = 2, R_DIR = 3, R_TRACK = 4, R_RANGE = 5;

    private final List<El> els = new ArrayList<>();
    private final Map<Integer, Touch> touches = new HashMap<>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Host host;
    private float cursor = 1f;
    private int laidW = -1, laidH = -1;

    // profile metadata (kept so a saved profile stays Winlator-compatible)
    public String name = "Custom";
    public int id = 100;
    public float opacity = 0.9f;

    // editing
    private boolean editMode;
    private El selected;
    private Listener listener;
    private float dragLastX, dragLastY;

    public ControlView(Context context, JSONObject json, Host host) throws JSONException {
        super(context);
        this.host = host;
        setFocusable(false);
        if (json != null) {
            name = json.optString("name", "Custom");
            id = json.optInt("id", 100);
            cursor = (float) json.optDouble("cursorSpeed", 1.0);
            opacity = (float) json.optDouble("opacity", 0.9);
            JSONArray a = json.getJSONArray("elements");
            for (int i = 0; i < a.length(); i++) els.add(parse(a.getJSONObject(i)));
        }
        setAlpha(Math.max(0.15f, Math.min(1f, opacity)));
    }

    private static El parse(JSONObject o) {
        El e = new El();
        e.type = o.optString("type", "BUTTON"); e.shape = o.optString("shape", "CIRCLE");
        e.text = o.optString("text", ""); e.range = o.optString("range", "");
        JSONArray b = o.optJSONArray("bindings");
        if (b != null) for (int k = 0; k < b.length(); k++) e.bind.add(b.optString(k, "NONE"));
        normalizeBindings(e);
        e.scale = (float) o.optDouble("scale", 1); e.x = (float) o.optDouble("x", .5); e.y = (float) o.optDouble("y", .5);
        e.toggle = o.optBoolean("toggleSwitch"); e.mouseMove = o.optBoolean("mouseMoveMode");
        return e;
    }

    public static int slotCount(String type) { return type.equals("RADIAL_MENU") ? 6 : type.equals("RANGE_BUTTON") ? 0 : 4; }

    private static void normalizeBindings(El e) {
        int min = e.type.equals("RADIAL_MENU") ? 5 : e.type.equals("RANGE_BUTTON") ? 4 : 4;
        while (e.bind.size() < min) e.bind.add("NONE");
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("name", name); o.put("cursorSpeed", cursor); o.put("opacity", opacity);
        JSONArray a = new JSONArray();
        for (El e : els) {
            JSONObject j = new JSONObject();
            j.put("type", e.type); j.put("shape", e.shape);
            JSONArray b = new JSONArray(); for (String s : e.bind) b.put(s); j.put("bindings", b);
            j.put("scale", e.scale); j.put("x", e.x); j.put("y", e.y);
            j.put("toggleSwitch", e.toggle); j.put("text", e.text); j.put("iconId", 0);
            if (e.mouseMove) j.put("mouseMoveMode", true);
            if (!e.range.isEmpty()) j.put("range", e.range);
            a.put(j);
        }
        o.put("elements", a);
        return o;
    }

    // ================================================================ editing API
    public void setEditMode(boolean on) { editMode = on; reset(); selected = null; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public El selected() { return selected; }
    public void setOpacity(float o) { opacity = Math.max(0.15f, Math.min(1f, o)); setAlpha(opacity); }

    public void addElement(String type) {
        El e = new El(); e.type = type; e.x = .5f; e.y = .5f;
        switch (type) {
            case "D_PAD": e.bind.addAll(Arrays.asList("KEY_W", "KEY_D", "KEY_S", "KEY_A")); break;
            case "STICK": e.bind.addAll(Arrays.asList("GAMEPAD_LEFT_THUMB_UP", "GAMEPAD_LEFT_THUMB_RIGHT", "GAMEPAD_LEFT_THUMB_DOWN", "GAMEPAD_LEFT_THUMB_LEFT")); break;
            case "RANGE_BUTTON": e.range = "FROM_0_TO_9"; break;
            case "BUTTON": e.shape = "CIRCLE"; break;
            default: break;
        }
        normalizeBindings(e);
        els.add(e); select(e); invalidate();
    }

    public void deleteSelected() { if (selected != null) { els.remove(selected); select(null); invalidate(); } }

    public void scaleSelected(float delta) {
        if (selected == null) return;
        selected.scale = Math.max(0.4f, Math.min(3f, Math.round((selected.scale + delta) * 20f) / 20f));
        laidW = -1; invalidate();
    }

    public void cycleShape() {
        if (selected == null || !selected.type.equals("BUTTON")) return;
        String[] order = {"CIRCLE", "SQUARE", "RECT", "ROUND_RECT"};
        int i = Arrays.asList(order).indexOf(selected.shape);
        selected.shape = order[(i + 1) % order.length]; laidW = -1; invalidate();
    }

    public void cycleRange() {
        if (selected == null || !selected.type.equals("RANGE_BUTTON")) return;
        selected.range = selected.range.equals("FROM_F1_TO_F12") ? "FROM_0_TO_9" : "FROM_F1_TO_F12"; selected.off = 0; invalidate();
    }

    public void setToggle(boolean v) { if (selected != null) { selected.toggle = v; invalidate(); } }
    public void setMouseMove(boolean v) { if (selected != null) { selected.mouseMove = v; invalidate(); } }
    public void setText(String s) { if (selected != null) { selected.text = s; invalidate(); } }
    public void setBinding(int slot, String value) {
        if (selected == null) return;
        while (selected.bind.size() <= slot) selected.bind.add("NONE");
        selected.bind.set(slot, value); invalidate();
    }

    private void select(El e) { selected = e; if (listener != null) listener.onSelect(e); }

    // ================================================================ geometry
    private void layoutAll() {
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
        laidW = getWidth(); laidH = getHeight();
    }

    private float dist(float x, float y, El e) { return (float) Math.hypot(x - e.cx, y - e.cy); }

    private boolean hit(El e, float x, float y) {
        switch (e.type) {
            case "D_PAD": return Math.abs(x - e.cx) <= e.r && Math.abs(y - e.cy) <= e.r;
            case "STICK": case "TRACKPAD": return dist(x, y, e) <= e.r;
            case "RADIAL_MENU": return dist(x, y, e) <= (e.open && !editMode ? e.r * 7f / 3f : e.r);
            case "RANGE_BUTTON": return Math.abs(x - e.cx) <= e.w / 2 && Math.abs(y - e.cy) <= e.h / 2;
            default:
                if (e.w > 0) return Math.abs(x - e.cx) <= e.w / 2 && Math.abs(y - e.cy) <= e.h / 2;
                return dist(x, y, e) <= e.r;
        }
    }

    private El topAt(float x, float y) {
        for (int i = els.size() - 1; i >= 0; i--) if (hit(els.get(i), x, y)) return els.get(i);
        return null;
    }

    // ================================================================ bindings
    private String resolve(String b) {
        return Shell.resolve(b, host != null && host.inVehicle());
    }

    private static String shortName(String b) {
        if (b == null || b.equals("NONE")) return "";
        switch (b) {
            case "MOUSE_LEFT_BUTTON": return "MLB";
            case "MOUSE_RIGHT_BUTTON": return "MRB";
            case "KEY_SHIFT_L": return "L SHIFT";
            case "KEY_CTRL_L": return "L CTRL";
            case "KEY_ALT_L": return "L ALT";
            case "KEY_BKSP": return "BKSP";
            case "KEY_UP": case "GAMEPAD_DPAD_UP": return "\u25B2";
            case "KEY_DOWN": case "GAMEPAD_DPAD_DOWN": return "\u25BC";
            case "KEY_LEFT": case "GAMEPAD_DPAD_LEFT": return "\u25C0";
            case "KEY_RIGHT": case "GAMEPAD_DPAD_RIGHT": return "\u25B6";
            default: break;
        }
        if (b.startsWith("KEY_")) return b.substring(4);
        if (b.startsWith("GAMEPAD_BUTTON_")) return b.substring(15);
        return b;
    }

    private void apply(El e, String src, boolean down) {
        for (String b : e.bind) { String code = resolve(b); if (code != null) host.key(src, code, down); }
    }

    private void tap(final String code) {
        final String src = "tap-" + code;
        host.key(src, code, true);
        handler.postDelayed(() -> host.key(src, code, false), 70);
    }

    // ================================================================ touch
    @Override public boolean onTouchEvent(MotionEvent ev) {
        if (laidW != getWidth() || laidH != getHeight()) layoutAll();
        if (editMode) { editTouch(ev); return true; }
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

    private void editTouch(MotionEvent ev) {
        float x = ev.getX(), y = ev.getY();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                select(topAt(x, y)); dragLastX = x; dragLastY = y; break;
            case MotionEvent.ACTION_MOVE:
                if (selected != null && getWidth() > 0) {
                    float snap = Math.max(getWidth(), getHeight()) / 100f;
                    selected.cx += x - dragLastX; selected.cy += y - dragLastY; dragLastX = x; dragLastY = y;
                    float gx = Math.round(selected.cx / (snap / 2)) * (snap / 2), gy = Math.round(selected.cy / (snap / 2)) * (snap / 2);
                    selected.x = Math.max(0.02f, Math.min(0.98f, gx / getWidth()));
                    selected.y = Math.max(0.03f, Math.min(0.97f, gy / getHeight()));
                    laidW = -1; layoutAll();
                }
                break;
            default: break;
        }
        invalidate();
    }

    private void down(int id, float x, float y) {
        Touch t = new Touch();
        t.src = "pt-" + id; t.sx = t.lx = x; t.sy = t.ly = y;
        touches.put(id, t);
        for (El e : els) if (e.open) { radialTouch(e, x, y); return; }   // an open radial menu takes the tap first
        El h = topAt(x, y);
        if (h == null) { t.role = R_LOOK; return; }
        t.el = h;
        switch (h.type) {
            case "D_PAD": case "STICK": t.role = R_DIR; dir(t, x, y); break;
            case "TRACKPAD": t.role = R_TRACK; break;
            case "RADIAL_MENU": h.open = true; t.role = R_NONE; break;
            case "RANGE_BUTTON": t.role = R_RANGE; break;
            default:
                t.role = R_BTN;
                if (h.toggle) { h.on = !h.on; apply(h, "tog-" + els.indexOf(h), h.on); }
                else { h.pressed = true; apply(h, t.src, true); }
        }
    }

    private void radialTouch(El e, float x, float y) {
        float R = e.r * 7f / 3f, d = dist(x, y, e);
        e.open = false;
        if (d > R || d <= R * 0.3f) return;                          // outside or centre cross just closes it
        List<String> codes = new ArrayList<>();
        for (String b : e.bind) { String c = resolve(b); if (c != null) codes.add(c); }
        if (codes.isEmpty()) return;
        double ang = Math.toDegrees(Math.atan2(y - e.cy, x - e.cx));
        double rel = ((ang + 90.0) % 360 + 360) % 360;                  // 0 at the top, clockwise
        int idx = (int) (rel / (360.0 / codes.size()));
        if (idx >= 0 && idx < codes.size()) tap(codes.get(idx));
    }

    private void dir(Touch t, float x, float y) {
        El e = t.el;
        float dx = x - e.cx, dy = y - e.cy, th = e.r * 0.28f;
        boolean[] want = {dy < -th, dx > th, dy > th, dx < -th};
        for (int k = 0; k < 4; k++) {
            if (want[k] != e.dir[k]) {
                e.dir[k] = want[k];
                String code = k < e.bind.size() ? resolve(e.bind.get(k)) : null;
                if (code != null) host.key(t.src, code, want[k]);
            }
        }
        e.kx = Math.max(-1f, Math.min(1f, dx / e.r)); e.ky = Math.max(-1f, Math.min(1f, dy / e.r));
    }

    private void move(int id, float x, float y) {
        Touch t = touches.get(id);
        if (t == null) return;
        switch (t.role) {
            case R_LOOK:
                host.look((x - t.lx) * 2 * cursor, (y - t.ly) * 2 * cursor); t.lx = x; t.ly = y; break;
            case R_BTN:
                if (t.el.mouseMove) { host.look((x - t.lx) * 2 * cursor, (y - t.ly) * 2 * cursor); t.lx = x; t.ly = y; }
                break;
            case R_DIR: dir(t, x, y); break;
            case R_TRACK: {
                float lx = Math.max(-1f, Math.min(1f, (x - t.sx) / t.el.r)), ly = Math.max(-1f, Math.min(1f, (y - t.sy) / t.el.r));
                host.lookVector(lx, ly); t.el.kx = lx; t.el.ky = ly; break;
            }
            case R_RANGE: {
                t.acc += x - t.lx; t.lx = x;
                float step = t.el.w / 4f * 0.7f;
                if (Math.abs(t.acc) > step) {
                    t.moved = true;
                    int max = Math.max(0, rangeLabels(t.el).length - 4);
                    t.el.off = Math.max(0, Math.min(max, t.el.off - (t.acc > 0 ? 1 : -1)));
                    t.acc = 0;
                }
                break;
            }
            default: break;
        }
    }

    private void up(int id, float x) {
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

    private void releaseTouch(Touch t) {
        host.release(t.src);
        if (t.el != null) {
            t.el.pressed = false;
            if (t.role == R_DIR) { Arrays.fill(t.el.dir, false); t.el.kx = t.el.ky = 0; }
            if (t.role == R_TRACK) { host.lookVector(0, 0); t.el.kx = t.el.ky = 0; }
        }
    }

    /** Releases every key and clears latched toggles. */
    public void reset() {
        if (host == null) return;
        for (Touch t : new ArrayList<>(touches.values())) releaseTouch(t);
        touches.clear();
        for (int i = 0; i < els.size(); i++) {
            El e = els.get(i);
            if (e.on) apply(e, "tog-" + i, false);
            e.on = false; e.open = false; e.pressed = false;
            Arrays.fill(e.dir, false); e.kx = e.ky = 0;
        }
        host.lookVector(0, 0);
    }

    // ================================================================ range buttons
    private String[] rangeLabels(El e) {
        if (e.range.equals("FROM_F1_TO_F12")) {
            String[] a = new String[12]; for (int i = 0; i < 12; i++) a[i] = "F" + (i + 1); return a;
        }
        return new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};
    }

    private String[] rangeCodes(El e) {
        String[] l = rangeLabels(e), c = new String[l.length];
        for (int i = 0; i < l.length; i++) c[i] = e.range.equals("FROM_F1_TO_F12") ? l[i] : "Digit" + l[i];
        return c;
    }

    // ================================================================ drawing
    private void body(Canvas c, El e, boolean active) {
        p.setStyle(Paint.Style.FILL); p.setColor(active ? 0x6678FF9A : 0x14FFFFFF);
        drawBody(c, e);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2f, getWidth() / 600f)); p.setColor(0xB4D2D2D2);
        drawBody(c, e);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawBody(Canvas c, El e) {
        if (e.w > 0) {
            float rr = e.shape.equals("ROUND_RECT") ? e.h / 2f : e.shape.equals("SQUARE") ? e.w * .12f : e.w * .03f;
            c.drawRoundRect(new RectF(e.cx - e.w / 2, e.cy - e.h / 2, e.cx + e.w / 2, e.cy + e.h / 2), rr, rr, p);
        } else c.drawCircle(e.cx, e.cy, e.r, p);
    }

    private void label(Canvas c, String s, float cx, float cy, float size, float maxWidth) {
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFEEEEEE); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(size);
        float w = p.measureText(s);
        if (w > maxWidth && w > 0) p.setTextSize(size * maxWidth / w);
        c.drawText(s, cx, cy + p.getTextSize() / 3f, p);
    }

    @Override protected void onDraw(Canvas c) {
        if (laidW != getWidth() || laidH != getHeight()) layoutAll();
        if (editMode) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1f); p.setColor(0x22FFFFFF);
            float snap = Math.max(getWidth(), getHeight()) / 100f;
            for (float gx = 0; gx < getWidth(); gx += snap * 2) c.drawLine(gx, 0, gx, getHeight(), p);
            for (float gy = 0; gy < getHeight(); gy += snap * 2) c.drawLine(0, gy, getWidth(), gy, p);
        }
        for (El e : els) drawElement(c, e);
        if (editMode && selected != null) {
            El e = selected;
            float hw = e.w > 0 ? e.w / 2 : e.r, hh = e.h > 0 ? e.h / 2 : e.r;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(5f); p.setColor(0xFFFFD54A);
            c.drawRect(e.cx - hw - 8, e.cy - hh - 8, e.cx + hw + 8, e.cy + hh + 8, p);
            p.setStyle(Paint.Style.FILL);
        }
        if (!editMode && host != null && host.loading()) {
            p.setStyle(Paint.Style.FILL); p.setColor(0xEE141815);
            c.drawRoundRect(new RectF(getWidth() * .2f, getHeight() * .35f, getWidth() * .8f, getHeight() * .6f), 20, 20, p);
            label(c, "Preparing your game\u2026", getWidth() / 2f, getHeight() * .45f, getHeight() * .06f, getWidth() * .55f);
            label(c, "First launch can take several minutes.", getWidth() / 2f, getHeight() * .53f, getHeight() * .04f, getWidth() * .55f);
        }
        if (!editMode) postInvalidateDelayed(250);
    }

    private void drawElement(Canvas c, El e) {
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
                if (!e.open || editMode) { body(c, e, false); label(c, "\u2731", e.cx, e.cy, e.r * 0.8f, e.r * 1.4f); break; }
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
                String s = !e.text.isEmpty() ? e.text : shortName(e.bind.isEmpty() ? "NONE" : e.bind.get(0));
                if (s.isEmpty() && editMode) s = "?";
                float size = (e.w > 0 ? e.h : e.r) * 0.5f;
                label(c, s, e.cx, e.cy, size, (e.w > 0 ? e.w : e.r * 2f) * 0.86f);
            }
        }
    }
}
