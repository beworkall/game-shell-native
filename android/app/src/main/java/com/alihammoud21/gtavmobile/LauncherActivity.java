package com.alihammoud21.gtavmobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Game Shell Native launcher (Winlator-style): a list of saved games, each with its own
 * settings page (folder, quality, controls profile) that is kept between launches.
 * No downloader. Modified from GTAV Mobile (GPL-3.0-or-later); see NOTICE.md.
 */
public final class LauncherActivity extends Activity {
    private static final String DEFAULT_PATH = "/storage/emulated/0/Download/1GTA";
    private static final String BUILTIN = "builtin";
    private static final String[] QUALITY_NAMES = {"0.40 (fastest)", "0.50 (default)", "0.65", "0.80", "1.00 (sharpest)"};
    private static final double[] QUALITY = {0.40, 0.50, 0.65, 0.80, 1.00};
    private static final String[] FPS_NAMES = {"30 FPS cap (cooler)", "60 FPS cap", "No cap"};
    private static final int[] FPS = {30, 60, 0};
    private static final String[] TEX_NAMES = {"Leave as is", "Low", "Medium", "High"};
    private static final String[] MODE_NAMES = {"Story", "Sandbox"};
    private static final int REQ_IMPORT = 7;

    private LinearLayout page;
    private TextView status;
    private int editing = -1;
    private JSONObject draft;
    private Spinner profileSpinner;
    private List<String> profileNames = new ArrayList<>();

    // ---------------------------------------------------------------- lifecycle
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        installDefaultProfiles();
        showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        File error = new File(getFilesDir(), "last-error.txt");
        if (error.isFile() && status != null) {
            try {
                status.setText("Game could not start: " + new String(java.nio.file.Files.readAllBytes(error.toPath()), StandardCharsets.UTF_8));
                error.delete();
            } catch (IOException ignored) { status.setText("Game could not start. Use Export log."); }
        }
    }

    @Override public void onBackPressed() {
        if (editing >= 0) { saveDraft(); showHome(); } else super.onBackPressed();
    }

    // ---------------------------------------------------------------- storage helpers
    private SharedPreferences prefs() { return getSharedPreferences("shell", MODE_PRIVATE); }

    private JSONArray loadGames() {
        try {
            JSONArray a = new JSONArray(prefs().getString("games", "[]"));
            if (a.length() > 0) return a;
        } catch (Exception ignored) { }
        JSONArray a = new JSONArray();
        try { a.put(newGame(0)); } catch (Exception ignored) { }
        saveGames(a);
        return a;
    }

    private void saveGames(JSONArray a) { prefs().edit().putString("games", a.toString()).apply(); }

    private JSONObject newGame(int n) throws Exception {
        JSONObject g = new JSONObject();
        g.put("name", n == 0 ? "GTA V" : "GTA V #" + (n + 1));
        g.put("path", prefs().getString("path", DEFAULT_PATH));
        g.put("root", "");
        g.put("mode", 1); g.put("quality", 1); g.put("fps", 0); g.put("texture", 0);
        g.put("touch", 1.0); g.put("mute", false);
        g.put("profile", "FPS");
        g.put("mp", false); g.put("host", false); g.put("server", ""); g.put("player", "");
        return g;
    }

    private File profileDir() { File d = new File(getFilesDir(), "profiles"); d.mkdirs(); return d; }

    private void installDefaultProfiles() {
        File marker = new File(getFilesDir(), "profiles-installed");
        if (marker.exists()) return;
        try {
            String[] names = getAssets().list("profiles");
            if (names != null) for (String n : names) {
                try (InputStream in = getAssets().open("profiles/" + n); OutputStream out = new FileOutputStream(new File(profileDir(), n))) {
                    copy(in, out);
                }
            }
            marker.createNewFile();
        } catch (IOException ignored) { }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private List<String> listProfiles() {
        List<String> names = new ArrayList<>();
        File[] files = profileDir().listFiles((d, n) -> n.endsWith(".icp"));
        if (files != null) { Arrays.sort(files); for (File f : files) names.add(f.getName().substring(0, f.getName().length() - 4)); }
        names.add(BUILTIN);
        return names;
    }

    // ---------------------------------------------------------------- UI helpers
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private LinearLayout newPage() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(36), dp(20), dp(20));
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        page = layout;
        status = null;
        return layout;
    }

    private TextView text(String s, int size) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); return t; }

    private TextView heading(String s) { TextView t = text(s, 14); t.setTextColor(Color.parseColor("#8fb3ff")); t.setPadding(0, dp(14), 0, dp(2)); return t; }

    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v -> action.run()); return b;
    }

    private Spinner spinner(String[] items, int selected) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        s.setSelection(Math.max(0, Math.min(items.length - 1, selected)));
        return s;
    }

    // ---------------------------------------------------------------- pages
    private void showHome() {
        editing = -1; draft = null;
        LinearLayout p = newPage();
        p.addView(text("Game Shell Native", 28));
        p.addView(text("Your games. Tap a game to edit its settings, or Play to start it.", 14));
        JSONArray games = loadGames();
        for (int i = 0; i < games.length(); i++) {
            final int index = i;
            JSONObject g = games.optJSONObject(i);
            if (g == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(12), 0, 0);
            Button open = button(g.optString("name", "Game") + "\n" + g.optString("path"), () -> showSettings(index));
            open.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            row.addView(open, new LinearLayout.LayoutParams(0, ViewGroupLayout.WRAP, 1f));
            Button play = button("Play", () -> play(index));
            row.addView(play, new LinearLayout.LayoutParams(ViewGroupLayout.WRAP, ViewGroupLayout.WRAP));
            p.addView(row);
        }
        p.addView(button("+ Add game", () -> {
            try { JSONArray a = loadGames(); a.put(newGame(a.length())); saveGames(a); showSettings(a.length() - 1); }
            catch (Exception e) { toast("Could not add: " + e.getMessage()); }
        }));
        status = text("", 13); status.setPadding(0, dp(12), 0, 0); p.addView(status);
        File error = new File(getFilesDir(), "last-error.txt");
        if (error.isFile()) onResume();
    }

    /** Minimal constants so layouts read cleanly. */
    private static final class ViewGroupLayout { static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT; }

    private void showSettings(int index) {
        JSONArray games = loadGames();
        draft = games.optJSONObject(index);
        if (draft == null) { showHome(); return; }
        editing = index;
        LinearLayout p = newPage();
        p.addView(text("Game settings", 24));

        p.addView(heading("Name"));
        EditText name = new EditText(this); name.setSingleLine(); name.setText(draft.optString("name")); p.addView(name);

        p.addView(heading("Game folder"));
        EditText path = new EditText(this); path.setSingleLine(); path.setText(draft.optString("path")); p.addView(path);
        LinearLayout folderRow = new LinearLayout(this);
        folderRow.addView(button("1. Allow file access", this::grantAccess));
        folderRow.addView(button("2. Find game", () -> {
            draft.remove("root");
            putStr("name", name); putStr("path", path);
            findGame();
        }));
        p.addView(folderRow);

        p.addView(heading("Mode"));
        Spinner mode = spinner(MODE_NAMES, draft.optInt("mode", 1)); p.addView(mode);

        p.addView(heading("Render scale (lower = faster)"));
        Spinner quality = spinner(QUALITY_NAMES, draft.optInt("quality", 1)); p.addView(quality);

        p.addView(heading("Frame rate"));
        Spinner fps = spinner(FPS_NAMES, draft.optInt("fps", 0)); p.addView(fps);

        p.addView(heading("Texture quality"));
        Spinner tex = spinner(TEX_NAMES, draft.optInt("texture", 0)); p.addView(tex);

        p.addView(heading("Touch look speed"));
        SeekBar touch = new SeekBar(this); touch.setMax(40);
        touch.setProgress((int) Math.round(draft.optDouble("touch", 1.0) * 10) - 1 < 0 ? 0 : (int) Math.round(draft.optDouble("touch", 1.0) * 10) - 1);
        p.addView(touch);

        Switch mute = new Switch(this); mute.setText("Mute audio"); mute.setChecked(draft.optBoolean("mute")); p.addView(mute);

        p.addView(heading("Controls profile (Winlator .icp)"));
        profileNames = listProfiles();
        String[] shown = new String[profileNames.size()];
        for (int i = 0; i < shown.length; i++) shown[i] = profileNames.get(i).equals(BUILTIN) ? "Built-in (original)" : profileNames.get(i);
        profileSpinner = spinner(shown, Math.max(0, profileNames.indexOf(draft.optString("profile", "FPS"))));
        p.addView(profileSpinner);
        p.addView(button("Import .icp profiles", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(intent, REQ_IMPORT);
        }));

        p.addView(heading("Multiplayer"));
        Switch mp = new Switch(this); mp.setText("Multiplayer"); mp.setChecked(draft.optBoolean("mp")); p.addView(mp);
        Switch host = new Switch(this); host.setText("Host a session"); host.setChecked(draft.optBoolean("host")); p.addView(host);
        EditText server = new EditText(this); server.setHint("Server address (host:47520)"); server.setSingleLine(); server.setText(draft.optString("server")); p.addView(server);
        EditText player = new EditText(this); player.setHint("Player name"); player.setSingleLine(); player.setText(draft.optString("player")); p.addView(player);

        p.addView(heading("Tools"));
        p.addView(button("Quick check (file sizes)", () -> { collect(name, path, mode, quality, fps, tex, touch, mute, mp, host, server, player); verify(false); }));
        p.addView(button("Full verify (hashes, slow)", () -> { collect(name, path, mode, quality, fps, tex, touch, mute, mp, host, server, player); verify(true); }));
        p.addView(button("Export log to Downloads", this::exportLog));

        status = text("", 13); status.setPadding(0, dp(10), 0, dp(6)); p.addView(status);
        refreshStatus();

        p.addView(button("Save and Play", () -> {
            collect(name, path, mode, quality, fps, tex, touch, mute, mp, host, server, player);
            saveDraft(); play(editing);
        }));
        p.addView(button("Save and go back", () -> {
            collect(name, path, mode, quality, fps, tex, touch, mute, mp, host, server, player);
            saveDraft(); showHome();
        }));
        p.addView(button("Delete this game", () -> {
            JSONArray a = loadGames(); JSONArray b = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (i != editing) b.put(a.opt(i));
            saveGames(b); showHome();
        }));
        // keep the field values when the page is rebuilt after Find/Import
        draftFields = new Object[]{name, path, mode, quality, fps, tex, touch, mute, mp, host, server, player};
    }

    private Object[] draftFields;

    private void putStr(String key, EditText e) { try { draft.put(key, e.getText().toString().trim()); } catch (Exception ignored) { } }

    private void collect(EditText name, EditText path, Spinner mode, Spinner quality, Spinner fps, Spinner tex, SeekBar touch,
                         Switch mute, Switch mp, Switch host, EditText server, EditText player) {
        try {
            draft.put("name", name.getText().toString().trim().isEmpty() ? "GTA V" : name.getText().toString().trim());
            draft.put("path", path.getText().toString().trim());
            draft.put("mode", mode.getSelectedItemPosition());
            draft.put("quality", quality.getSelectedItemPosition());
            draft.put("fps", fps.getSelectedItemPosition());
            draft.put("texture", tex.getSelectedItemPosition());
            draft.put("touch", (touch.getProgress() + 1) / 10.0);
            draft.put("mute", mute.isChecked());
            if (profileSpinner != null && profileSpinner.getSelectedItemPosition() < profileNames.size())
                draft.put("profile", profileNames.get(profileSpinner.getSelectedItemPosition()));
            draft.put("mp", mp.isChecked()); draft.put("host", host.isChecked());
            draft.put("server", server.getText().toString().trim()); draft.put("player", player.getText().toString().trim());
        } catch (Exception ignored) { }
    }

    private void saveDraft() {
        if (editing < 0 || draft == null) return;
        try {
            if (draftFields != null) {
                collect((EditText) draftFields[0], (EditText) draftFields[1], (Spinner) draftFields[2], (Spinner) draftFields[3],
                    (Spinner) draftFields[4], (Spinner) draftFields[5], (SeekBar) draftFields[6], (Switch) draftFields[7],
                    (Switch) draftFields[8], (Switch) draftFields[9], (EditText) draftFields[10], (EditText) draftFields[11]);
            }
            JSONArray a = loadGames(); a.put(editing, draft); saveGames(a);
            prefs().edit().putString("path", draft.optString("path")).apply();
        } catch (Exception ignored) { }
    }

    private void refreshStatus() {
        if (status == null || draft == null) return;
        String root = draft.optString("root");
        boolean ok = !root.isEmpty() && hasAccess() && valid(new File(root));
        status.setText("File access: " + (hasAccess() ? "yes" : "NO (tap button 1)") + "\n"
            + (ok ? "Game found: " + root : "Game not found yet (tap button 2)"));
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ---------------------------------------------------------------- folder detection
    private boolean hasAccess() { return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager(); }

    private void grantAccess() {
        if (hasAccess()) { refreshStatus(); return; }
        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    /** A valid game root has data/manifest.json and b/<build>/{game.wasm, shaders/index.json}. */
    private static boolean valid(File dir) {
        if (!new File(dir, "data/manifest.json").isFile()) return false;
        File[] builds = new File(dir, "b").listFiles();
        if (builds != null) for (File build : builds) {
            if (new File(build, "game.wasm").isFile() && new File(build, "shaders/index.json").isFile()) return true;
        }
        return false;
    }

    private File search(File start) {
        if (!start.isDirectory()) return null;
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depth = new ArrayDeque<>();
        queue.add(start); depth.add(0);
        while (!queue.isEmpty()) {
            File dir = queue.removeFirst(); int d = depth.removeFirst();
            if (valid(dir)) return dir;
            if (d >= 6) continue;
            File[] kids = dir.listFiles(File::isDirectory);
            if (kids == null) continue;
            Arrays.sort(kids);
            for (File kid : kids) {
                String name = kid.getName();
                if (name.equals("data") || name.equals("b") || name.equals("runtime")) continue;
                queue.add(kid); depth.add(d + 1);
            }
        }
        return null;
    }

    private void findGame() {
        if (!hasAccess()) { status.setText("Allow file access first (button 1)."); return; }
        File root = search(new File(draft.optString("path")));
        try {
            if (root == null) { draft.put("root", ""); status.setText("No game found under:\n" + draft.optString("path")); }
            else { draft.put("root", root.getAbsolutePath()); status.setText("Game found: " + root.getAbsolutePath()); }
        } catch (Exception ignored) { }
        saveDraft();
    }

    // ---------------------------------------------------------------- profiles import
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_IMPORT || result != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        int ok = 0; String last = null;
        for (Uri uri : uris) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(); copy(in, buf);
                JSONObject json = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                if (json.optJSONArray("elements") == null) throw new IOException("not a controls profile");
                String base = displayName(uri).replaceAll("(?i)\\.icp$", "").replaceAll("[^A-Za-z0-9 ._()\\-]", "_");
                if (base.isEmpty()) base = json.optString("name", "profile");
                try (FileOutputStream out = new FileOutputStream(new File(profileDir(), base + ".icp"))) { out.write(buf.toByteArray()); }
                ok++; last = base;
            } catch (Exception e) { toast("Skipped a file: " + e.getMessage()); }
        }
        toast("Imported " + ok + " profile(s)");
        if (ok > 0 && draft != null) { try { draft.put("profile", last); } catch (Exception ignored) { } saveDraft(); showSettings(editing); }
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) { }
        return "profile";
    }

    // ---------------------------------------------------------------- tools
    private void exportLog() {
        if (!hasAccess()) { toast("Allow file access first."); return; }
        try {
            File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GameShell-runtime.log");
            try (FileOutputStream os = new FileOutputStream(out)) {
                for (String n : new String[]{"runtime.log", "last-error.txt", "config.json", "game_dir.txt"}) {
                    File f = new File(getFilesDir(), n);
                    os.write(("\n===== " + n + (f.isFile() ? " (" + f.length() + " bytes)" : " (missing)") + " =====\n").getBytes(StandardCharsets.UTF_8));
                    if (f.isFile()) try (InputStream in = new FileInputStream(f)) { copy(in, os); }
                }
            }
            toast("Saved: " + out.getAbsolutePath());
            if (status != null) status.setText("Log saved to Downloads/GameShell-runtime.log");
        } catch (Exception e) { toast("Export failed: " + e.getMessage()); }
    }

    private void verify(boolean hashes) {
        if (!hasAccess()) { status.setText("Allow file access first (button 1)."); return; }
        final File root = draft.optString("root").isEmpty() ? null : new File(draft.optString("root"));
        if (root == null || !valid(root)) { status.setText("Find the game folder first (button 2)."); return; }
        status.setText("Checking files...");
        final TextView s = status;
        new Thread(() -> {
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = getAssets().open("game-manifest.json")) { copy(in, buf); }
                JSONArray files = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("files");
                int missing = 0, badSize = 0, badHash = 0;
                TreeSet<String> badParts = new TreeSet<>();
                StringBuilder report = new StringBuilder();
                byte[] block = new byte[1 << 20];
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    File file = new File(root, f.getString("relative"));
                    String problem = null;
                    if (!file.isFile()) { problem = "MISSING"; missing++; }
                    else if (file.length() != f.getLong("bytes")) { problem = "BAD SIZE (" + file.length() + " vs " + f.getLong("bytes") + ")"; badSize++; }
                    else if (hashes) {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        try (InputStream in = new FileInputStream(file)) { int n; while ((n = in.read(block)) > 0) md.update(block, 0, n); }
                        StringBuilder hex = new StringBuilder(); for (byte b : md.digest()) hex.append(String.format("%02x", b));
                        if (!hex.toString().equals(f.getString("sha256"))) { problem = "BAD HASH"; badHash++; }
                    }
                    if (problem != null) { badParts.add(f.optString("part")); report.append(problem).append("  ").append(f.getString("relative")).append("  [").append(f.optString("part")).append("]\n"); }
                    if (i % 150 == 0) { final int done = i; runOnUiThread(() -> s.setText((hashes ? "Hashing " : "Checking ") + done + " / " + files.length())); }
                }
                final String summary = (hashes ? "Full verify" : "Quick check") + ": " + files.length() + " files\nmissing: " + missing + ", wrong size: " + badSize + (hashes ? ", bad hash: " + badHash : "")
                    + (badParts.isEmpty() ? "\nAll good." : "\nAffected zip parts: " + badParts + "\nFull list: Downloads/GameShell-verify.txt");
                try (FileWriter w = new FileWriter(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GameShell-verify.txt"))) { w.write(summary + "\n\n" + report); } catch (IOException ignored) { }
                runOnUiThread(() -> s.setText(summary));
            } catch (Exception e) { runOnUiThread(() -> s.setText("Verify failed: " + e)); }
        }, "verify").start();
    }

    // ---------------------------------------------------------------- play
    private void play(int index) {
        JSONArray games = loadGames();
        JSONObject g = games.optJSONObject(index);
        if (g == null) return;
        try {
            if (!hasAccess()) { toast("Allow file access first."); return; }
            File root = g.optString("root").isEmpty() ? null : new File(g.optString("root"));
            if (root == null || !valid(root)) root = search(new File(g.optString("path")));
            if (root == null) { toast("Game folder not found. Open the game settings and tap Find game."); return; }
            g.put("root", root.getAbsolutePath()); games.put(index, g); saveGames(games);
            try (FileWriter w = new FileWriter(new File(getFilesDir(), "game_dir.txt"))) { w.write(root.getAbsolutePath()); }
            if (g.optBoolean("mp") && !g.optBoolean("host") && g.optString("server").trim().isEmpty()) { toast("Enter a server address, or turn on Host a session."); return; }
            JSONObject config = new JSONObject();
            config.put("scale", QUALITY[Math.max(0, Math.min(QUALITY.length - 1, g.optInt("quality", 1)))]);
            config.put("fullscreen", true);
            config.put("fps_limit", FPS[Math.max(0, Math.min(FPS.length - 1, g.optInt("fps", 0)))]);
            config.put("start_mode", g.optInt("mode", 1) + 1);
            config.put("touch_sensitivity", g.optDouble("touch", 1.0));
            config.put("mute", g.optBoolean("mute"));
            int tex = g.optInt("texture", 0);
            if (tex > 0) config.put("texture_quality", tex - 1);
            config.put("multiplayer", g.optBoolean("mp")); config.put("host", g.optBoolean("host"));
            config.put("server", g.optString("server")); config.put("player_name", g.optString("player"));
            try (FileWriter w = new FileWriter(new File(getFilesDir(), "config.json"))) { w.write(config.toString()); }
            try (FileWriter w = new FileWriter(new File(getFilesDir(), "active_profile.txt"))) { w.write(g.optString("profile", BUILTIN)); }
            startActivity(new Intent(this, GameActivity.class));
        } catch (Exception error) { toast("Could not start: " + error.getMessage()); }
    }
}
