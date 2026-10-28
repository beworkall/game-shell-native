package app.webgameshell.nativeshell;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * On-screen controls driven by a Winlator-compatible input-controls profile (.icp).
 * Same element types, shapes, sizes (snap = view width / 100), ranges, icons, per-element opacity and
 * orientation as Winlator, so layouts look and behave the same. Play mode sends input to a Host (the game);
 * edit mode lets the user select and drag elements on a snapping grid. Written for Game Shell Native.
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

    public interface Listener {
        void onSelect(El element);
        void onChanged();
    }

    public static final String[] TYPES = {"BUTTON", "D_PAD", "RANGE_BUTTON", "STICK", "TRACKPAD", "RADIAL_MENU"};
    public static final String[] TYPE_NAMES = {"Button", "D-pad", "Range button", "Stick", "Trackpad", "Radial menu"};
    public static final String[] SHAPES = {"CIRCLE", "RECT", "ROUND_RECT", "SQUARE"};
    public static final String[] SHAPE_NAMES = {"Circle", "Rect", "Round rect", "Square"};
    public static final String[] RANGES = {"FROM_A_TO_Z", "FROM_0_TO_9", "FROM_F1_TO_F12", "FROM_NP0_TO_NP9"};
    public static final String[] RANGE_NAMES = {"A to Z", "0 to 9", "F1 to F12", "Numpad 0 to 9"};
    public static final int ICON_COUNT = 18;

    public static final class El {
        public String type = "BUTTON", shape = "CIRCLE", text = "", range = "";
        public final List<String> bind = new ArrayList<>();
        public float scale = 1, x = .5f, y = .5f, opacity = 1f;
        public boolean toggle, mouseMove;
        public int iconId, orientation;
        float cx, cy, r, w, h, kx, ky;
        boolean on, open, pressed;
        final boolean[] dir = new boolean[4];
        int off, pressedCell = -1;
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
    private float snap = 1;
    private static Typeface font;
    private static final Bitmap[] ICONS = new Bitmap[ICON_COUNT];

    // profile metadata (kept so a saved profile stays Winlator-compatible)
    public String name = "Custom";
    public int id = 100;
    public float opacity = 0.9f;
    private JSONArray controllers;
    private boolean haptics = true;

    // editing
    private boolean editMode;
    private El selected;
    private Listener listener;
    private float dragOffX, dragOffY, downX, downY;
    private boolean dragging;

    public ControlView(Context context, JSONObject json, Host host) throws JSONException {
        super(context);
        this.host = host;
        setFocusable(false);
        if (font == null) {
            try { font = Typeface.createFromAsset(context.getAssets(), "fonts/Inter_600SemiBold.ttf"); } catch (Throwable t) { font = Typeface.DEFAULT_BOLD; }
        }
        if (json != null) {
            name = json.optString("name", "Custom");
            id = json.optInt("id", 100);
            cursor = (float) json.optDouble("cursorSpeed", 1.0);
            opacity = (float) json.optDouble("opacity", 0.9);
            controllers = json.optJSONArray("controllers");
            JSONArray a = json.optJSONArray("elements");
            if (a != null) for (int i = 0; i < a.length(); i++) els.add(parse(a.getJSONObject(i)));
        }
        setAlpha(Math.max(0.15f, Math.min(1f, opacity)));
    }

    private static El parse(JSONObject o) {
        El e = new El();
        e.type = o.optString("type", "BUTTON");
        if (e.type.equals("MIDI_KEY")) e.type = "BUTTON";
        e.shape = o.optString("shape", "CIRCLE");
        e.text = o.optString("text", ""); e.range = o.optString("range", "");
        JSONArray b = o.optJSONArray("bindings");
        if (b != null) for (int k = 0; k < b.length(); k++) e.bind.add(b.optString(k, "NONE"));
        e.scale = (float) o.optDouble("scale", 1); e.x = (float) o.optDouble("x", .5); e.y = (float) o.optDouble("y", .5);
        e.toggle = o.optBoolean("toggleSwitch"); e.mouseMove = o.has("mouseMoveMode") && o.optBoolean("mouseMoveMode", true);
        e.iconId = Math.max(0, Math.min(ICON_COUNT - 1, o.optInt("iconId", 0)));
        e.orientation = o.optInt("orientation", 0);
        e.opacity = (float) o.optDouble("opacity", 1.0);
        normalize(e);
        return e;
    }

    /** Makes sure each type has a sensible number of binding slots. */
    static void normalize(El e) {
        switch (e.type) {
            case "D_PAD": case "STICK": case "TRACKPAD": while (e.bind.size() < 4) e.bind.add("NONE"); break;
            case "RADIAL_MENU": while (e.bind.size() < 2) e.bind.add("NONE"); while (e.bind.size() > 8) e.bind.remove(e.bind.size() - 1); break;
            case "RANGE_BUTTON":
                if (e.range.isEmpty()) e.range = "FROM_0_TO_9";
                while (e.bind.size() < 1) e.bind.add("NONE");
                while (e.bind.size() > 12) e.bind.remove(e.bind.size() - 1);
                break;
            default: while (e.bind.size() < 4) e.bind.add("NONE");
        }
    }

    /** Switches an element's type and gives it Winlator-like defaults. */
    public static void setType(El e, String type) {
        if (e.type.equals(type)) return;
        e.type = type;
        e.bind.clear();
        switch (type) {
            case "D_PAD": e.bind.addAll(Arrays.asList("KEY_W", "KEY_D", "KEY_S", "KEY_A")); break;
            case "STICK": e.bind.addAll(Arrays.asList("GAMEPAD_LEFT_THUMB_UP", "GAMEPAD_LEFT_THUMB_RIGHT", "GAMEPAD_LEFT_THUMB_DOWN", "GAMEPAD_LEFT_THUMB_LEFT")); break;
            case "TRACKPAD": e.bind.addAll(Arrays.asList("GAMEPAD_RIGHT_THUMB_UP", "GAMEPAD_RIGHT_THUMB_RIGHT", "GAMEPAD_RIGHT_THUMB_DOWN", "GAMEPAD_RIGHT_THUMB_LEFT")); break;
            case "RADIAL_MENU": for (int i = 0; i < 5; i++) e.bind.add("NONE"); break;
            case "RANGE_BUTTON": e.range = "FROM_0_TO_9"; e.orientation = 0; for (int i = 0; i < 4; i++) e.bind.add("NONE"); break;
            default: break;
        }
        normalize(e);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("name", name); o.put("cursorSpeed", cursor);
        if (Math.abs(opacity - 0.9f) > 0.001f) o.put("opacity", opacity);
        JSONArray a = new JSONArray();
        for (El e : els) {
            JSONObject j = new JSONObject();
            j.put("type", e.type); j.put("shape", e.shape);
            JSONArray b = new JSONArray(); for (String s : e.bind) b.put(s); j.put("bindings", b);
            j.put("scale", e.scale); j.put("x", e.x); j.put("y", e.y);
            j.put("toggleSwitch", e.toggle); j.put("text", e.text); j.put("iconId", e.iconId);
            if (e.mouseMove) j.put("mouseMoveMode", true);
            if (e.type.equals("RANGE_BUTTON")) { j.put("range", e.range); j.put("orientation", e.orientation); }
            if (Math.abs(e.opacity - 1f) > 0.001f) j.put("opacity", e.opacity);
            a.put(j);
        }
        o.put("elements", a);
        if (controllers != null) o.put("controllers", controllers);
        return o;
    }

    public void setHaptics(boolean on) { haptics = on; }
    public float cursorSpeed() { return cursor; }
    public void setCursorSpeed(float v) { cursor = Math.max(0.1f, Math.min(4f, v)); }
    public int elementCount() { return els.size(); }

    // ================================================================ editing API
    public void setEditMode(boolean on) { editMode = on; reset(); selected = null; invalidate(); }
    public void setListener(Listener l) { listener = l; }
    public El selected() { return selected; }
    public void setOpacity(float o) { opacity = Math.max(0.15f, Math.min(1f, o)); setAlpha(opacity); }

    public El addElement() {
        El e = new El();
        e.type = "BUTTON"; e.shape = "CIRCLE"; e.x = .5f; e.y = .5f;
        e.bind.add("KEY_SPACE");
        normalize(e);
        els.add(e); select(e); changed();
        return e;
    }

    public boolean deleteSelected() {
        if (selected == null) return false;
        els.remove(selected); select(null); changed();
        return true;
    }

    /** Call after editing an element's fields: re-lays out, redraws and notifies (autosave). */
    public void changed() {
        laidW = -1;
        invalidate();
        if (listener != null) listener.onChanged();
    }

    private void select(El e) { selected = e; if (listener != null) listener.onSelect(e); invalidate(); }

    // ================================================================ geometry (Winlator sizes)
    private void layoutAll() {
        float W = getWidth(), H = getHeight();
        snap = Math.max(1f, Math.max(W, H) / 100f);
        for (El e : els) {
            e.cx = e.x * W; e.cy = e.y * H; e.w = e.h = e.r = 0;
            switch (e.type) {
                case "D_PAD": e.w = e.h = 14 * snap * e.scale; break;
                case "STICK": case "TRACKPAD": e.r = 6 * snap * e.scale; break;
                case "RADIAL_MENU": e.r = 3 * snap * e.scale; break;
                case "RANGE_BUTTON": {
                    int n = Math.max(1, e.bind.size());
                    float a = n * 4 * snap * e.scale, b = 4 * snap * e.scale;
                    if (e.orientation == 1) { e.w = b; e.h = a; } else { e.w = a; e.h = b; }
                    break;
                }
                default:
                    if (e.shape.equals("RECT") || e.shape.equals("ROUND_RECT")) { e.w = 8 * snap * e.scale; e.h = 4 * snap * e.scale; }
                    else if (e.shape.equals("SQUARE")) { e.w = e.h = 5 * snap * e.scale; }
                    else e.r = 3 * snap * e.scale;
            }
        }
        laidW = getWidth(); laidH = getHeight();
    }

    private float dist(float x, float y, El e) { return (float) Math.hypot(x - e.cx, y - e.cy); }
    private float halfW(El e) { return e.w > 0 ? e.w / 2 : e.r; }
    private float halfH(El e) { return e.h > 0 ? e.h / 2 : e.r; }

    private boolean hit(El e, float x, float y) {
        switch (e.type) {
            case "STICK": case "TRACKPAD": return dist(x, y, e) <= e.r;
            case "RADIAL_MENU": return dist(x, y, e) <= (e.open && !editMode ? e.r * 7f / 3f : e.r);
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
    private String resolve(String b) { return Shell.resolve(b, host != null && host.inVehicle()); }

    static String label(String b) { return Shell.bindingLabel(b); }

    private void apply(El e, String src, boolean down) {
        for (String b : e.bind) { String code = resolve(b); if (code != null) host.key(src, code, down); }
    }

    private void tap(final String code) {
        final String src = "tap-" + code;
        host.key(src, code, true);
        handler.postDelayed(() -> host.key(src, code, false), 70);
    }

    private void buzz() { if (haptics && !editMode) Ui.buzz(getContext(), 0); }

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
            up(ev.getPointerId(index), ev.getX(index), ev.getY(index));
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
            case MotionEvent.ACTION_DOWN: {
                El h = topAt(x, y);
                select(h);
                downX = x; downY = y; dragging = false;
                if (h != null) { dragOffX = x - h.cx; dragOffY = y - h.cy; }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (selected != null && getWidth() > 0) {
                    if (!dragging && Math.hypot(x - downX, y - downY) > snap * 1.2f) dragging = true;
                    if (dragging) {
                        float nx = Math.round((x - dragOffX) / snap) * snap, ny = Math.round((y - dragOffY) / snap) * snap;
                        selected.x = Math.max(0f, Math.min(1f, nx / getWidth()));
                        selected.y = Math.max(0f, Math.min(1f, ny / getHeight()));
                        laidW = -1;
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
                if (dragging && listener != null) listener.onChanged();
                dragging = false;
                break;
            default: break;
        }
    }

    private void down(int id, float x, float y) {
        Touch t = new Touch();
        t.src = "pt-" + id; t.sx = t.lx = x; t.sy = t.ly = y;
        touches.put(id, t);
        for (El e : els) if (e.open) { radialTouch(e, x, y); return; }   // an open radial menu takes the tap first
        El h = topAt(x, y);
        if (h == null) { t.role = R_LOOK; return; }
        t.el = h;
        buzz();
        switch (h.type) {
            case "D_PAD": case "STICK": t.role = R_DIR; dir(t, x, y); break;
            case "TRACKPAD": t.role = R_TRACK; break;
            case "RADIAL_MENU": h.open = true; t.role = R_NONE; break;
            case "RANGE_BUTTON": t.role = R_RANGE; h.pressedCell = cellAt(h, x, y); break;
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
        int n = e.bind.size();
        if (n == 0) return;
        double ang = Math.toDegrees(Math.atan2(y - e.cy, x - e.cx));
        double rel = ((ang + 90.0) % 360 + 360) % 360;                  // 0 at the top, clockwise
        int idx = (int) (rel / (360.0 / n));
        if (idx >= 0 && idx < n) {
            String code = resolve(e.bind.get(idx));
            if (code != null) { buzz(); tap(code); }
        }
    }

    private void dir(Touch t, float x, float y) {
        El e = t.el;
        float rad = halfW(e);
        float dx = x - e.cx, dy = y - e.cy, th = rad * 0.28f;
        boolean[] want = {dy < -th, dx > th, dy > th, dx < -th};
        for (int k = 0; k < 4; k++) {
            if (want[k] != e.dir[k]) {
                e.dir[k] = want[k];
                String code = k < e.bind.size() ? resolve(e.bind.get(k)) : null;
                if (code != null) host.key(t.src, code, want[k]);
            }
        }
        e.kx = Math.max(-1f, Math.min(1f, dx / rad)); e.ky = Math.max(-1f, Math.min(1f, dy / rad));
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
                El e = t.el;
                float along = e.orientation == 1 ? y - t.ly : x - t.lx;
                t.acc += along; t.lx = x; t.ly = y;
                float cell = (e.orientation == 1 ? e.h : e.w) / Math.max(1, e.bind.size());
                if (Math.abs(t.acc) > cell * 0.7f) {
                    t.moved = true; e.pressedCell = -1;
                    int max = Math.max(0, rangeLabels(e).length - e.bind.size());
                    e.off = Math.max(0, Math.min(max, e.off - (t.acc > 0 ? 1 : -1)));
                    t.acc = 0;
                }
                break;
            }
            default: break;
        }
    }

    private int cellAt(El e, float x, float y) {
        int n = Math.max(1, e.bind.size());
        float rel = e.orientation == 1 ? (y - (e.cy - e.h / 2)) / (e.h / n) : (x - (e.cx - e.w / 2)) / (e.w / n);
        return (int) Math.max(0, Math.min(n - 1, Math.floor(rel)));
    }

    private void up(int id, float x, float y) {
        Touch t = touches.remove(id);
        if (t == null) return;
        if (t.role == R_RANGE && !t.moved) {
            El e = t.el;
            int cell = cellAt(e, x, y);
            String[] codes = rangeCodes(e);
            if (e.off + cell < codes.length) tap(codes[e.off + cell]);
        }
        if (t.el != null) t.el.pressedCell = -1;
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
            e.on = false; e.open = false; e.pressed = false; e.pressedCell = -1;
            Arrays.fill(e.dir, false); e.kx = e.ky = 0;
        }
        host.lookVector(0, 0);
    }

    // ================================================================ ranges (same order as Winlator)
    static String[] rangeLabels(El e) {
        String[] a;
        switch (e.range) {
            case "FROM_A_TO_Z": a = new String[26]; for (int i = 0; i < 26; i++) a[i] = String.valueOf((char) ('A' + i)); return a;
            case "FROM_F1_TO_F12": a = new String[12]; for (int i = 0; i < 12; i++) a[i] = "F" + (i + 1); return a;
            case "FROM_NP0_TO_NP9": a = new String[10]; for (int i = 0; i < 10; i++) a[i] = "NP" + ((i + 1) % 10); return a;
            default: a = new String[10]; for (int i = 0; i < 10; i++) a[i] = String.valueOf((i + 1) % 10); return a;
        }
    }

    private static String[] rangeCodes(El e) {
        String[] l = rangeLabels(e), c = new String[l.length];
        for (int i = 0; i < l.length; i++) {
            switch (e.range) {
                case "FROM_A_TO_Z": c[i] = "Key" + l[i]; break;
                case "FROM_F1_TO_F12": c[i] = l[i]; break;
                case "FROM_NP0_TO_NP9": c[i] = "Numpad" + ((i + 1) % 10); break;
                default: c[i] = "Digit" + l[i];
            }
        }
        return c;
    }

    // ================================================================ icons (Winlator icon set, LGPL-2.1)
    private Bitmap iconBitmap(int id) {
        if (id <= 0 || id >= ICON_COUNT) return null;
        if (ICONS[id] == null) {
            try (InputStream in = getContext().getAssets().open("icons/" + id + ".png")) { ICONS[id] = BitmapFactory.decodeStream(in); } catch (Exception ignored) { }
        }
        return ICONS[id];
    }

    // ================================================================ drawing
    private int a(El e, int alpha) { return Math.round(alpha * Math.max(0.05f, Math.min(1f, e.opacity))); }

    private void body(Canvas c, El e, float cx, float cy, float hw, float hh, boolean round, boolean active) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(active ? 0xFFFFFFFF : 0xFF000000);
        p.setAlpha(a(e, active ? 0x50 : 0x22));
        drawShape(c, e, cx, cy, hw, hh, round);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(snap * 0.25f);
        p.setColor(0xFFFFFFFF);
        p.setAlpha(a(e, 0xC8));
        drawShape(c, e, cx, cy, hw, hh, round);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawShape(Canvas c, El e, float cx, float cy, float hw, float hh, boolean round) {
        if (round) { c.drawCircle(cx, cy, hw, p); return; }
        float rr = e.shape.equals("ROUND_RECT") ? hh : e.type.equals("RANGE_BUTTON") ? snap * 0.75f * e.scale : snap * 0.5f;
        c.drawRoundRect(new RectF(cx - hw, cy - hh, cx + hw, cy + hh), rr, rr, p);
    }

    private void text(Canvas c, El e, String s, float cx, float cy, float size, float maxWidth) {
        if (s == null || s.isEmpty()) return;
        p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); p.setAlpha(a(e, 0xF0));
        p.setTypeface(font); p.setTextAlign(Paint.Align.CENTER); p.setTextSize(size);
        float w = p.measureText(s);
        if (w > maxWidth && w > 0) p.setTextSize(size * maxWidth / w);
        c.drawText(s, cx, cy + p.getTextSize() / 3f, p);
    }

    private void icon(Canvas c, El e, int id, float cx, float cy, float box) {
        Bitmap b = iconBitmap(id);
        if (b == null) return;
        Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG);
        ip.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        ip.setAlpha(a(e, 0xF0));
        float s = box * 0.62f;
        c.drawBitmap(b, null, new RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), ip);
    }

    @Override protected void onDraw(Canvas c) {
        if (laidW != getWidth() || laidH != getHeight()) layoutAll();
        if (editMode) drawGrid(c);
        for (El e : els) drawElement(c, e);
        if (editMode && selected != null) {
            El e = selected;
            float hw = halfW(e), hh = halfH(e), pad = snap * 0.8f;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(snap * 0.3f); p.setColor(0xFFFFD54A); p.setAlpha(255);
            p.setPathEffect(new DashPathEffect(new float[]{snap, snap * 0.6f}, 0));
            c.drawRoundRect(new RectF(e.cx - hw - pad, e.cy - hh - pad, e.cx + hw + pad, e.cy + hh + pad), snap, snap, p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
        }
        if (!editMode && host != null && host.loading()) {
            p.setStyle(Paint.Style.FILL); p.setColor(0xEE0E1116); p.setAlpha(0xEE);
            c.drawRoundRect(new RectF(getWidth() * .22f, getHeight() * .36f, getWidth() * .78f, getHeight() * .6f), snap * 2, snap * 2, p);
            El tmp = new El();
            text(c, tmp, "Preparing your game…", getWidth() / 2f, getHeight() * .45f, getHeight() * .06f, getWidth() * .5f);
            text(c, tmp, "The first launch can take several minutes.", getWidth() / 2f, getHeight() * .53f, getHeight() * .035f, getWidth() * .5f);
        }
        if (!editMode) postInvalidateDelayed(250);
    }

    private void drawGrid(Canvas c) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, snap * 0.0625f));
        p.setColor(0xFFFFFFFF);
        p.setAlpha(0x14);
        for (float gx = 0; gx < getWidth(); gx += snap) c.drawLine(gx, 0, gx, getHeight(), p);
        for (float gy = 0; gy < getHeight(); gy += snap) c.drawLine(0, gy, getWidth(), gy, p);
        p.setAlpha(0x50);
        float cx = Math.round(getWidth() * 0.5f / snap) * snap, cy = Math.round(getHeight() * 0.5f / snap) * snap;
        for (float i = 0; i < Math.max(getWidth(), getHeight()); i += snap * 2) {
            c.drawLine(cx, i, cx, i + snap, p);
            c.drawLine(i, cy, i + snap, cy, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawElement(Canvas c, El e) {
        switch (e.type) {
            case "D_PAD": {
                float R = e.w / 2, cell = R * 2f / 3f;
                float[][] cells = {{0, -cell}, {cell, 0}, {0, cell}, {-cell, 0}};
                String[] arrows = {"▲", "▶", "▼", "◀"};
                for (int k = 0; k < 4; k++) {
                    El shapeEl = e;
                    String sh = e.shape; e.shape = "SQUARE";
                    body(c, shapeEl, e.cx + cells[k][0], e.cy + cells[k][1], cell / 2, cell / 2, false, e.dir[k]);
                    e.shape = sh;
                    text(c, e, arrows[k], e.cx + cells[k][0], e.cy + cells[k][1], cell * 0.45f, cell);
                }
                break;
            }
            case "STICK": case "TRACKPAD": {
                body(c, e, e.cx, e.cy, e.r, e.r, true, false);
                if (e.type.equals("STICK")) {
                    p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); p.setAlpha(a(e, 0x55));
                    c.drawCircle(e.cx + e.kx * e.r * 0.45f, e.cy + e.ky * e.r * 0.45f, snap * 3.5f * e.scale, p);
                } else {
                    p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); p.setAlpha(a(e, 0x40));
                    c.drawCircle(e.cx + e.kx * e.r * 0.5f, e.cy + e.ky * e.r * 0.5f, e.r * 0.3f, p);
                }
                break;
            }
            case "RADIAL_MENU": {
                if (!e.open || editMode) {
                    body(c, e, e.cx, e.cy, e.r, e.r, true, false);
                    text(c, e, "✱", e.cx, e.cy, e.r * 0.9f, e.r * 1.4f);
                    break;
                }
                float R = e.r * 7f / 3f;
                p.setStyle(Paint.Style.FILL); p.setColor(0xFF0A1E32); p.setAlpha(a(e, 0xB4)); c.drawCircle(e.cx, e.cy, R, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(snap * 0.35f); p.setColor(0xFF2878C8); p.setAlpha(a(e, 0xE0)); c.drawCircle(e.cx, e.cy, R, p);
                int n = e.bind.size();
                for (int k = 0; k < n; k++) {
                    double ang = Math.toRadians(-90.0 + 180.0 / n + k * 360.0 / n);
                    float sx = e.cx + (float) Math.cos(ang) * R * 0.66f, sy = e.cy + (float) Math.sin(ang) * R * 0.66f, sr = R * 0.24f;
                    body(c, e, sx, sy, sr, sr, true, false);
                    text(c, e, label(e.bind.get(k)), sx, sy, sr * 0.85f, sr * 1.6f);
                    double edge = Math.toRadians(-90.0 + k * 360.0 / n);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(snap * 0.2f); p.setColor(0xFF2878C8); p.setAlpha(a(e, 0x90));
                    c.drawLine(e.cx + (float) Math.cos(edge) * R * 0.32f, e.cy + (float) Math.sin(edge) * R * 0.32f, e.cx + (float) Math.cos(edge) * R, e.cy + (float) Math.sin(edge) * R, p);
                }
                float xr = R * 0.24f;
                body(c, e, e.cx, e.cy, xr, xr, true, false);
                text(c, e, "✕", e.cx, e.cy, xr, xr * 1.4f);
                break;
            }
            case "RANGE_BUTTON": {
                body(c, e, e.cx, e.cy, e.w / 2, e.h / 2, false, false);
                String[] labels = rangeLabels(e);
                int n = Math.max(1, e.bind.size());
                boolean vertical = e.orientation == 1;
                float cell = (vertical ? e.h : e.w) / n;
                for (int k = 0; k < n; k++) {
                    float ccx = vertical ? e.cx : e.cx - e.w / 2 + cell * (k + 0.5f);
                    float ccy = vertical ? e.cy - e.h / 2 + cell * (k + 0.5f) : e.cy;
                    if (k == e.pressedCell) {
                        p.setStyle(Paint.Style.FILL); p.setColor(0xFFFFFFFF); p.setAlpha(a(e, 0x50));
                        if (vertical) c.drawRect(e.cx - e.w / 2, ccy - cell / 2, e.cx + e.w / 2, ccy + cell / 2, p);
                        else c.drawRect(ccx - cell / 2, e.cy - e.h / 2, ccx + cell / 2, e.cy + e.h / 2, p);
                    }
                    if (e.off + k < labels.length) text(c, e, labels[e.off + k], ccx, ccy, snap * 2 * e.scale, cell * 0.8f);
                    if (k > 0) {
                        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(snap * 0.25f); p.setColor(0xFFFFFFFF); p.setAlpha(a(e, 0xC8));
                        if (vertical) c.drawLine(e.cx - e.w / 2, ccy - cell / 2, e.cx + e.w / 2, ccy - cell / 2, p);
                        else c.drawLine(ccx - cell / 2, e.cy - e.h / 2, ccx - cell / 2, e.cy + e.h / 2, p);
                    }
                }
                break;
            }
            default: {
                boolean round = e.w <= 0;
                body(c, e, e.cx, e.cy, halfW(e), halfH(e), round, e.on || e.pressed);
                float box = Math.min(halfW(e), halfH(e)) * 2;
                if (e.iconId > 0 && iconBitmap(e.iconId) != null) { icon(c, e, e.iconId, e.cx, e.cy, box); break; }
                String s = !e.text.isEmpty() ? e.text : label(firstBinding(e));
                if (s.isEmpty() && editMode) s = "?";
                text(c, e, s, e.cx, e.cy, Math.min(snap * 2 * e.scale, halfH(e) * 0.9f), halfW(e) * 2 * 0.86f);
            }
        }
    }

    private static String firstBinding(El e) {
        for (String b : e.bind) if (!b.equals("NONE")) return b;
        return "NONE";
    }
}
