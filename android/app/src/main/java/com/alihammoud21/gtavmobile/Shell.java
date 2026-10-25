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
        File marker = new File(c.getFilesDir(), "profiles-installed");
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

    static String backupSaves(Context c) {
        if (!hasAccess()) return "Allow file access first.";
        try {
            long n = copyTree(new File(c.getFilesDir(), "saves"), new File(docs(), "saves"));
            backupProfiles(c);
            return "Backed up " + (n >> 20) + " MB to Documents/GTA5NativeData";
        } catch (IOException e) { return "Backup failed: " + e.getMessage(); }
    }

    static String restoreSaves(Context c) {
        if (!hasAccess()) return "Allow file access first.";
        File src = new File(docs(), "saves");
        if (!src.isDirectory()) return "No backup found in Documents/GTA5NativeData/saves";
        try { long n = copyTree(src, new File(c.getFilesDir(), "saves")); return "Restored " + (n >> 20) + " MB of saves"; }
        catch (IOException e) { return "Restore failed: " + e.getMessage(); }
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
        return g;
    }

    /** Fills quality settings from the phone's RAM and chip. Returns a short explanation. */
    static String autoConfigure(Context c, JSONObject g) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double gb = mi.totalMem / 1e9;
        String soc = socName();
        int quality, tex, fps = 0;
        String tier;
        if (gb >= 10.5) { tier = "high-end"; quality = 2; tex = 2; }
        else if (gb >= 7) { tier = "mid-range"; quality = 1; tex = 1; }
        else { tier = "entry-level"; quality = 0; tex = 1; }
        try { g.put("quality", quality); g.put("fps", fps); g.put("texture", tex); g.put("res", 0); } catch (Exception ignored) { }
        return String.format(Locale.US, "Detected %.1f GB RAM, %s (%s): render scale %s, 30 FPS cap, %s textures.",
            gb, soc, tier, QUALITY_NAMES[quality], TEX_NAMES[tex].toLowerCase(Locale.US));
    }

    static String socName() {
        String s = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "";
        if (s == null || s.isEmpty() || s.equals("unknown")) s = Build.HARDWARE;
        return (Build.MANUFACTURER + " " + Build.MODEL + ", " + s).trim();
    }

    // ------------------------------------------------------------ bindings
    static String[] bindingNames() {
        List<String> n = new ArrayList<>();
        n.add("NONE");
        for (char c = 'A'; c <= 'Z'; c++) n.add("KEY_" + c);
        for (int i = 0; i <= 9; i++) n.add("KEY_" + i);
        for (int i = 1; i <= 12; i++) n.add("KEY_F" + i);
        n.addAll(Arrays.asList("KEY_SPACE", "KEY_ENTER", "KEY_ESC", "KEY_TAB", "KEY_BKSP", "KEY_DEL", "KEY_SHIFT_L", "KEY_CTRL_L", "KEY_ALT_L",
            "KEY_UP", "KEY_DOWN", "KEY_LEFT", "KEY_RIGHT", "MOUSE_LEFT_BUTTON", "MOUSE_RIGHT_BUTTON",
            "GAMEPAD_BUTTON_A", "GAMEPAD_BUTTON_B", "GAMEPAD_BUTTON_X", "GAMEPAD_BUTTON_Y", "GAMEPAD_BUTTON_L1", "GAMEPAD_BUTTON_R1",
            "GAMEPAD_BUTTON_L2", "GAMEPAD_BUTTON_R2", "GAMEPAD_BUTTON_L3", "GAMEPAD_BUTTON_R3", "GAMEPAD_BUTTON_START", "GAMEPAD_BUTTON_SELECT",
            "GAMEPAD_DPAD_UP", "GAMEPAD_DPAD_DOWN", "GAMEPAD_DPAD_LEFT", "GAMEPAD_DPAD_RIGHT",
            "GAMEPAD_LEFT_THUMB_UP", "GAMEPAD_LEFT_THUMB_DOWN", "GAMEPAD_LEFT_THUMB_LEFT", "GAMEPAD_LEFT_THUMB_RIGHT"));
        return n.toArray(new String[0]);
    }

    // ------------------------------------------------------------ binding -> engine key name
    static String resolve(String b, boolean car) {
        if (b == null || b.equals("NONE")) return null;
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
            if (k.matches("F\\d{1,2}")) return k;
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

}
