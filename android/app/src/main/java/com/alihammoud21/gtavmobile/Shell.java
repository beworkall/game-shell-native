package com.alihammoud21.gtavmobile;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Environment;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;


/** Shared helpers for the launcher, editor and game (Game Shell Native). */
final class Shell {
    private Shell() { }

    static final String DEFAULT_PATH = "/storage/emulated/0/Download/1GTA";
    static final String BUILTIN = "builtin";
    static final String[] QUALITY_NAMES = {"0.40 (fastest)", "0.50 (default)", "0.65", "0.80", "1.00 (sharpest)"};
    static final double[] QUALITY = {0.40, 0.50, 0.65, 0.80, 1.00};
    static final String[] FPS_NAMES = {"30 FPS cap (cooler)", "60 FPS cap", "No cap"};
    static final int[] FPS = {30, 60, 0};
    static final String[] TEX_NAMES = {"Leave as is", "Low", "Medium", "High"};
    static final String[] MODE_NAMES = {"Story", "Sandbox"};
    static final String[] RES_NAMES = {"Automatic (screen x scale)", "960 x 540", "1280 x 720", "1600 x 720", "1920 x 1080", "2560 x 1080"};
    static final String[] RES_VALUES = {"", "960x540", "1280x720", "1600x720", "1920x1080", "2560x1080"};

    // ------------------------------------------------------------ files
    static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    static String read(File f) {
        try { return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
        catch (IOException e) { return null; }
    }

    static void write(File f, String text) throws IOException {
        File parent = f.getParentFile(); if (parent != null) parent.mkdirs();
        try (FileOutputStream os = new FileOutputStream(f)) { os.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    static long copyTree(File src, File dst) throws IOException {
        long bytes = 0;
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) bytes += copyTree(k, new File(dst, k.getName()));
        } else if (src.isFile()) {
            File parent = dst.getParentFile(); if (parent != null) parent.mkdirs();
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) { copy(in, out); }
            bytes += src.length();
        }
        return bytes;
    }

    static long deleteTree(File f) {
        long freed = 0;
        if (f.isDirectory()) { File[] kids = f.listFiles(); if (kids != null) for (File k : kids) freed += deleteTree(k); }
        else freed += f.length();
        f.delete();
        return freed;
    }

    // ------------------------------------------------------------ folders
    /** Documents/GTA5NativeData: survives uninstall (needs file access). */
    static File docs() {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "GTA5NativeData");
        d.mkdirs();
        return d;
    }

    static boolean hasAccess() { return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager(); }

    static File profileDir(Context c) { File d = new File(c.getFilesDir(), "profiles"); d.mkdirs(); return d; }

    static List<String> listProfiles(Context c) {
        List<String> names = new ArrayList<>();
        File[] files = profileDir(c).listFiles((d, n) -> n.endsWith(".icp"));
        if (files != null) { Arrays.sort(files); for (File f : files) names.add(f.getName().substring(0, f.getName().length() - 4)); }
        names.add(BUILTIN);
        return names;
    }

    /** First run: copy the bundled profiles; if a backup exists in Documents, restore it instead. */
    static void installDefaultProfiles(Context c) {
        File marker = new File(c.getFilesDir(), "profiles-installed-v2");
        if (marker.exists()) return;
        try {
            if (hasAccess()) {
                File backup = new File(docs(), "profiles");
                File[] saved = backup.listFiles((d, n) -> n.endsWith(".icp"));
                if (saved != null && saved.length > 0) copyTree(backup, profileDir(c));
            }
            String[] names = c.getAssets().list("profiles");
            if (names != null) for (String n : names) {
                File out = new File(profileDir(c), n);
                if (out.exists()) continue;
                try (InputStream in = c.getAssets().open("profiles/" + n); OutputStream os = new FileOutputStream(out)) { copy(in, os); }
            }
            marker.createNewFile();
        } catch (IOException ignored) { }
    }

    static void backupProfiles(Context c) {
        if (!hasAccess()) return;
        try { copyTree(profileDir(c), new File(docs(), "profiles")); } catch (IOException ignored) { }
    }

    /** Folders inside the game's file root that are scratch space, not saves. */
    private static final Set<String> SKIP = new HashSet<>(Arrays.asList("tmp", "dev", "proc"));

    /** Copies only files that changed (size or newer time). Returns bytes copied. */
    static long syncTree(File src, File dst, boolean top) throws IOException {
        long bytes = 0;
        if (src.isDirectory()) {
            dst.mkdirs();
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) {
                if (top && SKIP.contains(k.getName())) continue;
                bytes += syncTree(k, new File(dst, k.getName()), false);
            }
        } else if (src.isFile()) {
            if (dst.isFile() && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) return 0;
            File parent = dst.getParentFile(); if (parent != null) parent.mkdirs();
            try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) { copy(in, out); }
            dst.setLastModified(src.lastModified());
            bytes += src.length();
        }
        return bytes;
    }

    static String backupSaves(Context c) {
        if (!hasAccess()) return "Allow file access first.";
        try {
            File saves = new File(c.getFilesDir(), "saves");
            if (!saves.isDirectory()) return "No saves yet.";
            long n = syncTree(saves, new File(docs(), "saves"), true);
            backupProfiles(c);
            c.getSharedPreferences("shell", Context.MODE_PRIVATE).edit().putLong("lastBackup", System.currentTimeMillis()).apply();
            return n == 0 ? "Backup is already up to date." : "Backed up " + Math.max(1, n >> 10) + " KB to Documents/GTA5NativeData";
        } catch (IOException e) { return "Backup failed: " + e.getMessage(); }
    }

    static String restoreSaves(Context c) {
        if (!hasAccess()) return "Allow file access first.";
        File src = new File(docs(), "saves");
        if (!src.isDirectory()) return "No backup found in Documents/GTA5NativeData/saves";
        try { long n = copyTree(src, new File(c.getFilesDir(), "saves")); return "Restored " + Math.max(1, n >> 10) + " KB of saves"; }
        catch (IOException e) { return "Restore failed: " + e.getMessage(); }
    }

    // ------------------------------------------------------------ freeze reports
    static File reportsDir() { File d = new File(docs(), "reports"); d.mkdirs(); return d; }

    /** Writes a freeze report (summary + the last part of the runtime log). Returns the file or null. */
    static File freezeReport(Context c, String summary) {
        if (!hasAccess()) return null;
        try {
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File out = new File(reportsDir(), "freeze-" + stamp + ".txt");
            try (FileOutputStream os = new FileOutputStream(out)) {
                os.write(("Game Shell Native freeze report\n" + new Date() + "\n" + socName() + ", Android " + Build.VERSION.RELEASE + "\n" + summary + "\n\n===== runtime.log (tail) =====\n").getBytes(StandardCharsets.UTF_8));
                File log = new File(c.getFilesDir(), "runtime.log");
                if (log.isFile()) {
                    try (RandomAccessFile raf = new RandomAccessFile(log, "r")) {
                        long len = raf.length(), start = Math.max(0, len - 400_000);
                        raf.seek(start);
                        byte[] buf = new byte[(int) (len - start)];
                        raf.readFully(buf);
                        os.write(buf);
                    }
                }
            }
            return out;
        } catch (IOException e) { return null; }
    }

    /** Deletes the compile cache and temp files that can cause crashes, blank screens or lag. Saves are never touched. */
    static String clearGraphicsAndTemp(Context c) {
        long freed = 0;
        File[] files = c.getFilesDir().listFiles();
        if (files != null) for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".cwasm") || n.equals("runtime.log") || n.equals("last-error.txt") || n.equals("crash.txt")) freed += deleteTree(f);
        }
        freed += deleteTree(c.getCacheDir());
        c.getCacheDir().mkdirs();
        return "Cleared " + (freed >> 20) + " MB. The next launch recompiles the game (a few minutes).";
    }

    // ------------------------------------------------------------ games
    static JSONObject defaultGame(String name, String path) throws Exception {
        JSONObject g = new JSONObject();
        g.put("name", name);
        g.put("path", path);
        g.put("root", "");
        g.put("mode", 1); g.put("quality", 1); g.put("fps", 0); g.put("texture", 0); g.put("res", 0);
        g.put("touch", 1.0); g.put("mute", false); g.put("opacity", 0.9);
        g.put("profile", "FPS");
        g.put("mp", false); g.put("host", false); g.put("server", ""); g.put("player", "");
        g.put("padDead", 20); g.put("padLook", 10); g.put("hideTouchOnPad", false);
        g.put("id", java.util.UUID.randomUUID().toString()); g.put("preset", 1); g.put("hud", 0);
        return g;
    }

    // ------------------------------------------------------------ presets
    static final String[] PRESET_NAMES = {"Performance", "Balanced", "Quality"};
    static final String[] PRESET_DESCS = {
        "Render scale 0.40, 30 FPS cap, low textures. Coolest and smoothest.",
        "Render scale 0.50, 30 FPS cap, low textures. Good mix for most phones.",
        "Render scale 0.65, 30 FPS cap, medium textures. Sharper, runs warmer."};
    static final int[][] PRESETS = {{0, 0, 1}, {1, 0, 1}, {2, 0, 2}};   // quality index, fps index, texture index (1 = Low, 2 = Medium)

    static double ramGb(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.totalMem / 1e9;
    }

    static int recommendedPreset(Context c) {
        double gb = ramGb(c);
        return gb >= 10.5 ? 2 : gb >= 7 ? 1 : 0;
    }

    static void applyPreset(JSONObject g, int preset) {
        int[] p = PRESETS[Math.max(0, Math.min(PRESETS.length - 1, preset))];
        try { g.put("quality", p[0]); g.put("fps", p[1]); g.put("texture", p[2]); g.put("res", 0); g.put("preset", preset); } catch (Exception ignored) { }
    }

    /** Fills quality settings from the phone's RAM and chip. Returns a short explanation. */
    static String autoConfigure(Context c, JSONObject g) {
        int p = recommendedPreset(c);
        applyPreset(g, p);
        return String.format(Locale.US, "Detected %.1f GB RAM on %s: applied the %s preset.", ramGb(c), socName(), PRESET_NAMES[p]);
    }

    // ------------------------------------------------------------ build check
    private static String expectedBuild;
    private static long expectedWasmBytes = -1;

    private static synchronized void loadExpected(Context c) {
        if (expectedBuild != null) return;
        try (InputStream in = c.getAssets().open("game-manifest.json")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); copy(in, b);
            JSONObject m = new JSONObject(b.toString("UTF-8"));
            expectedBuild = m.optString("build", "");
            org.json.JSONArray files = m.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                if (f.optString("relative").endsWith("/game.wasm")) { expectedWasmBytes = f.optLong("bytes", -1); break; }
            }
        } catch (Exception e) { expectedBuild = ""; }
    }

    static String expectedBuild(Context c) { loadExpected(c); return expectedBuild; }

    /** null when the folder holds the build this runtime expects; otherwise a short explanation. */
    static String buildProblem(Context c, File root) {
        loadExpected(c);
        if (expectedBuild == null || expectedBuild.isEmpty()) return null;
        File wasm = new File(root, "b/" + expectedBuild + "/game.wasm");
        if (!wasm.isFile()) {
            List<String> found = new ArrayList<>();
            File[] builds = new File(root, "b").listFiles(File::isDirectory);
            if (builds != null) for (File b : builds) found.add(b.getName());
            return "This folder has game build " + (found.isEmpty() ? "(none)" : found) + ", but this app is made for build " + expectedBuild + ". It will probably not start correctly.";
        }
        if (expectedWasmBytes > 0 && wasm.length() != expectedWasmBytes)
            return "game.wasm has the wrong size (" + wasm.length() + " bytes, expected " + expectedWasmBytes + "). The file is damaged or from a different release. Run Full verify.";
        return null;
    }

    static String socName() {
        String s = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "";
        if (s == null || s.isEmpty() || s.equals("unknown")) s = Build.HARDWARE;
        return (Build.MANUFACTURER + " " + Build.MODEL + ", " + s).trim();
    }

    // ------------------------------------------------------------ bindings (Winlator names and order)
    static final String[] KEYBOARD_BINDINGS = ("NONE KEY_UP KEY_RIGHT KEY_DOWN KEY_LEFT KEY_ENTER KEY_ESC KEY_BKSP KEY_DEL KEY_INSERT KEY_TAB KEY_SPACE "
        + "KEY_CTRL_L KEY_CTRL_R KEY_SHIFT_L KEY_SHIFT_R KEY_ALT_L KEY_ALT_R KEY_HOME KEY_PRTSCN KEY_PG_UP KEY_PG_DOWN KEY_END KEY_CAPS_LOCK KEY_NUM_LOCK "
        + "KEY_0 KEY_1 KEY_2 KEY_3 KEY_4 KEY_5 KEY_6 KEY_7 KEY_8 KEY_9 KEY_A KEY_B KEY_C KEY_D KEY_E KEY_F KEY_G KEY_H KEY_I KEY_J KEY_K KEY_L KEY_M "
        + "KEY_N KEY_O KEY_P KEY_Q KEY_R KEY_S KEY_T KEY_U KEY_V KEY_W KEY_X KEY_Y KEY_Z KEY_BRACKET_LEFT KEY_BRACKET_RIGHT KEY_BACKSLASH KEY_SLASH "
        + "KEY_SEMICOLON KEY_COMMA KEY_PERIOD KEY_APOSTROPHE KEY_KP_ADD KEY_MINUS KEY_F1 KEY_F2 KEY_F3 KEY_F4 KEY_F5 KEY_F6 KEY_F7 KEY_F8 KEY_F9 KEY_F10 "
        + "KEY_F11 KEY_F12 KEY_KP_0 KEY_KP_1 KEY_KP_2 KEY_KP_3 KEY_KP_4 KEY_KP_5 KEY_KP_6 KEY_KP_7 KEY_KP_8 KEY_KP_9").split(" ");
    static final String[] MOUSE_BINDINGS = ("NONE MOUSE_LEFT_BUTTON MOUSE_MIDDLE_BUTTON MOUSE_RIGHT_BUTTON MOUSE_MOVE_LEFT MOUSE_MOVE_RIGHT MOUSE_MOVE_UP "
        + "MOUSE_MOVE_DOWN MOUSE_SCROLL_UP MOUSE_SCROLL_DOWN").split(" ");
    static final String[] GAMEPAD_BINDINGS = ("NONE GAMEPAD_BUTTON_A GAMEPAD_BUTTON_B GAMEPAD_BUTTON_X GAMEPAD_BUTTON_Y GAMEPAD_BUTTON_L1 GAMEPAD_BUTTON_R1 "
        + "GAMEPAD_BUTTON_SELECT GAMEPAD_BUTTON_START GAMEPAD_BUTTON_L3 GAMEPAD_BUTTON_R3 GAMEPAD_BUTTON_L2 GAMEPAD_BUTTON_R2 GAMEPAD_LEFT_THUMB_UP "
        + "GAMEPAD_LEFT_THUMB_RIGHT GAMEPAD_LEFT_THUMB_DOWN GAMEPAD_LEFT_THUMB_LEFT GAMEPAD_RIGHT_THUMB_UP GAMEPAD_RIGHT_THUMB_RIGHT GAMEPAD_RIGHT_THUMB_DOWN "
        + "GAMEPAD_RIGHT_THUMB_LEFT GAMEPAD_DPAD_UP GAMEPAD_DPAD_RIGHT GAMEPAD_DPAD_DOWN GAMEPAD_DPAD_LEFT").split(" ");
    static final String[] BINDING_CATEGORIES = {"Keyboard", "Mouse", "Gamepad"};

    static String[] bindingsFor(int category) { return category == 1 ? MOUSE_BINDINGS : category == 2 ? GAMEPAD_BINDINGS : KEYBOARD_BINDINGS; }

    static int categoryOf(String b) { return b == null ? 0 : b.startsWith("MOUSE_") ? 1 : b.startsWith("GAMEPAD_") ? 2 : 0; }

    /** All bindings (used by the gamepad remap screen). */
    static String[] bindingNames() {
        List<String> all = new ArrayList<>(Arrays.asList(KEYBOARD_BINDINGS));
        all.addAll(Arrays.asList(MOUSE_BINDINGS).subList(1, MOUSE_BINDINGS.length));
        all.addAll(Arrays.asList(GAMEPAD_BINDINGS).subList(1, GAMEPAD_BINDINGS.length));
        return all.toArray(new String[0]);
    }

    static String[] bindingLabels(String[] names) {
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) out[i] = names[i].equals("NONE") ? "None" : bindingLabel(names[i]);
        return out;
    }

    /** Short on-button text, like Winlator's Binding.toString(). */
    static String bindingLabel(String b) {
        if (b == null || b.equals("NONE")) return "";
        switch (b) {
            case "KEY_SHIFT_L": return "L SHIFT";
            case "KEY_SHIFT_R": return "R SHIFT";
            case "KEY_CTRL_L": return "L CTRL";
            case "KEY_CTRL_R": return "R CTRL";
            case "KEY_ALT_L": return "L ALT";
            case "KEY_ALT_R": return "R ALT";
            case "KEY_BRACKET_LEFT": return "[";
            case "KEY_BRACKET_RIGHT": return "]";
            case "KEY_BACKSLASH": return "\\";
            case "KEY_SLASH": return "/";
            case "KEY_SEMICOLON": return ";";
            case "KEY_COMMA": return ",";
            case "KEY_PERIOD": return ".";
            case "KEY_APOSTROPHE": return "'";
            case "KEY_MINUS": return "-";
            case "KEY_KP_ADD": return "+";
            case "MOUSE_LEFT_BUTTON": return "MLB";
            case "MOUSE_RIGHT_BUTTON": return "MRB";
            case "MOUSE_MIDDLE_BUTTON": return "MMB";
            case "KEY_UP": case "GAMEPAD_DPAD_UP": return "▲";
            case "KEY_DOWN": case "GAMEPAD_DPAD_DOWN": return "▼";
            case "KEY_LEFT": case "GAMEPAD_DPAD_LEFT": return "◀";
            case "KEY_RIGHT": case "GAMEPAD_DPAD_RIGHT": return "▶";
            default: break;
        }
        return b.replaceAll("^(MOUSE_|KEY_|GAMEPAD_)", "").replace("BUTTON_", "").replace("KP_", "NUMPAD ").replace("_", " ");
    }

    // ------------------------------------------------------------ binding -> engine key name
    static String resolve(String b, boolean car) {
        if (b == null || b.equals("NONE")) return null;
        switch (b) {
            case "MOUSE_LEFT_BUTTON": return "Fire";
            case "MOUSE_RIGHT_BUTTON": return "Aim";
            case "MOUSE_MIDDLE_BUTTON": return "MouseMiddle";
            case "MOUSE_MOVE_LEFT": case "GAMEPAD_RIGHT_THUMB_LEFT": return "LookLeft";
            case "MOUSE_MOVE_RIGHT": case "GAMEPAD_RIGHT_THUMB_RIGHT": return "LookRight";
            case "MOUSE_MOVE_UP": case "GAMEPAD_RIGHT_THUMB_UP": return "LookUp";
            case "MOUSE_MOVE_DOWN": case "GAMEPAD_RIGHT_THUMB_DOWN": return "LookDown";
            case "MOUSE_SCROLL_UP": case "MOUSE_SCROLL_DOWN": return null;
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
        if (!b.startsWith("KEY_")) return null;
        String k = b.substring(4);
        if (k.length() == 1 && Character.isLetter(k.charAt(0))) return "Key" + k.toUpperCase(Locale.US);
        if (k.length() == 1 && Character.isDigit(k.charAt(0))) return "Digit" + k;
        if (k.matches("F\\d{1,2}")) return k;
        if (k.matches("KP_\\d")) return "Numpad" + k.substring(3);
        switch (k) {
            case "ALT_L": return "AltLeft";
            case "ALT_R": return "AltRight";
            case "BKSP": return "Backspace";
            case "CTRL_L": return "ControlLeft";
            case "CTRL_R": return "ControlRight";
            case "SHIFT_L": return "ShiftLeft";
            case "SHIFT_R": return "ShiftRight";
            case "DEL": return "Delete";
            case "INSERT": return "Insert";
            case "HOME": return "Home";
            case "END": return "End";
            case "PG_UP": return "PageUp";
            case "PG_DOWN": return "PageDown";
            case "PRTSCN": return "PrintScreen";
            case "CAPS_LOCK": return "CapsLock";
            case "NUM_LOCK": return "NumLock";
            case "ENTER": return "Enter";
            case "ESC": return "Escape";
            case "SPACE": return "Space";
            case "TAB": return "Tab";
            case "UP": return "ArrowUp";
            case "DOWN": return "ArrowDown";
            case "LEFT": return "ArrowLeft";
            case "RIGHT": return "ArrowRight";
            case "BRACKET_LEFT": return "BracketLeft";
            case "BRACKET_RIGHT": return "BracketRight";
            case "BACKSLASH": return "Backslash";
            case "SLASH": return "Slash";
            case "SEMICOLON": return "Semicolon";
            case "COMMA": return "Comma";
            case "PERIOD": return "Period";
            case "APOSTROPHE": return "Quote";
            case "MINUS": return "Minus";
            case "KP_ADD": return "NumpadAdd";
            default: return null;
        }
    }

    // ------------------------------------------------------------ finding the game anywhere
    /** Turns a folder picked with the system picker (tree URI) into a real path, or null. */
    static File treeToFile(android.net.Uri tree) {
        try {
            String id = android.provider.DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            String vol = colon >= 0 ? id.substring(0, colon) : id, rest = colon >= 0 ? id.substring(colon + 1) : "";
            File base = vol.equalsIgnoreCase("primary") ? Environment.getExternalStorageDirectory() : new File("/storage/" + vol);
            return rest.isEmpty() ? base : new File(base, rest);
        } catch (Exception e) { return null; }
    }

    /** A valid game root has data/manifest.json and b/<build>/{game.wasm, shaders/index.json}. */
    static boolean validRoot(File dir) {
        if (!new File(dir, "data/manifest.json").isFile()) return false;
        File[] builds = new File(dir, "b").listFiles();
        if (builds != null) for (File build : builds) {
            if (new File(build, "game.wasm").isFile() && new File(build, "shaders/index.json").isFile()) return true;
        }
        return false;
    }

    interface Progress { boolean tick(int foldersScanned, String where); }

    /** Breadth-first search for a game root under start. Return false from the callback to cancel. */
    static File findGame(File start, int maxDepth, Progress progress) {
        if (start == null || !start.isDirectory()) return null;
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depth = new ArrayDeque<>();
        queue.add(start); depth.add(0);
        int scanned = 0;
        while (!queue.isEmpty()) {
            File dir = queue.removeFirst(); int d = depth.removeFirst();
            scanned++;
            if (progress != null && scanned % 40 == 0 && !progress.tick(scanned, dir.getAbsolutePath())) return null;
            if (validRoot(dir)) return dir;
            if (d >= maxDepth) continue;
            File[] kids = dir.listFiles(File::isDirectory);
            if (kids == null) continue;
            Arrays.sort(kids);
            for (File kid : kids) {
                String name = kid.getName();
                if (name.startsWith(".") || name.equals("data") || name.equals("b") || name.equals("runtime") || (d == 0 && name.equals("Android"))) continue;
                queue.add(kid); depth.add(d + 1);
            }
        }
        return null;
    }

    /** Every storage root on the phone: internal storage first, then SD cards / USB drives. */
    static List<File> storageRoots() {
        List<File> roots = new ArrayList<>();
        roots.add(Environment.getExternalStorageDirectory());
        File[] vols = new File("/storage").listFiles();
        if (vols != null) for (File v : vols) {
            String n = v.getName();
            if (!n.equals("emulated") && !n.equals("self") && v.isDirectory() && v.canRead()) roots.add(v);
        }
        return roots;
    }

    // ------------------------------------------------------------ device line
    /** GPU name via a tiny offscreen OpenGL ES context (cached). Call off the main thread. */
    static String gpuName(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("shell", Context.MODE_PRIVATE);
        String cached = p.getString("gpu", null);
        if (cached != null) return cached;
        String name = "";
        try {
            android.opengl.EGLDisplay dpy = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
            int[] ver = new int[2];
            android.opengl.EGL14.eglInitialize(dpy, ver, 0, ver, 1);
            int[] attr = {android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT, android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT, android.opengl.EGL14.EGL_NONE};
            android.opengl.EGLConfig[] cfg = new android.opengl.EGLConfig[1]; int[] n = new int[1];
            android.opengl.EGL14.eglChooseConfig(dpy, attr, 0, cfg, 0, 1, n, 0);
            android.opengl.EGLContext ctx = android.opengl.EGL14.eglCreateContext(dpy, cfg[0], android.opengl.EGL14.EGL_NO_CONTEXT, new int[]{android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE}, 0);
            android.opengl.EGLSurface surf = android.opengl.EGL14.eglCreatePbufferSurface(dpy, cfg[0], new int[]{android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1, android.opengl.EGL14.EGL_NONE}, 0);
            android.opengl.EGL14.eglMakeCurrent(dpy, surf, surf, ctx);
            String r = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER);
            if (r != null) name = r.replace("(TM)", "").replaceAll("\\s+", " ").trim();
            android.opengl.EGL14.eglMakeCurrent(dpy, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
            android.opengl.EGL14.eglDestroySurface(dpy, surf);
            android.opengl.EGL14.eglDestroyContext(dpy, ctx);
            android.opengl.EGL14.eglTerminate(dpy);
        } catch (Throwable ignored) { }
        if (!name.isEmpty()) p.edit().putString("gpu", name).apply();
        return name;
    }

    static String deviceLine(Context c, String version, String gpu) {
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : Build.HARDWARE;
        StringBuilder sb = new StringBuilder("v" + version + "  ·  " + Build.MODEL);
        if (soc != null && !soc.isEmpty() && !soc.equalsIgnoreCase("unknown")) sb.append("  ·  ").append(soc);
        if (gpu != null && !gpu.isEmpty()) sb.append("  ·  ").append(gpu);
        return sb.toString();
    }
}
