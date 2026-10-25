package com.alihammoud21.gtavmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Game Shell Native launcher: first-run wizard, saved games with per-game settings (Winlator style),
 * control profiles, gamepad remap, tools and About. No downloader.
 * Modified from GTAV Mobile (GPL-3.0-or-later); see NOTICE.md.
 */
public final class LauncherActivity extends Activity {
    private static final int REQ_IMPORT = 7, REQ_EDIT = 8;
    private static final String[] PAD_INPUTS = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "START", "SELECT", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"};
    private static final String[] HUD_NAMES = {"Off", "FPS", "Detailed"};
    private static final String SOURCE_URL = "https://github.com/beworkall/gtaappweb";

    private TextView status;
    private int editing = -1;
    private JSONObject draft;
    private String pendingMessage = "";
    private int wizardStep = -1;
    private JSONObject wizardGame;

    // settings widgets
    private EditText etName, etPath, etServer, etPlayer;
    private Spinner spMode, spQuality, spFps, spTex, spRes, spHud, spProfile;
    private SeekBar sbTouch, sbOpacity, sbDead, sbLook;
    private Switch swMute, swMp, swHost, swHidePad;
    private List<String> profileNames = new ArrayList<>();

    // ================================================================ lifecycle
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Shell.installDefaultProfiles(this);
        applyOverrides();
        if (!prefs().getBoolean("wizard_done", false)) showWizard(0); else showHome();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyOverrides();
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("autoplay", false)) {
            intent.removeExtra("autoplay");
            final JSONArray games = loadGames();
            if (games.length() > 0) {
                toast("Restarting the game...");
                // wait for the old game process to exit so the new one starts clean
                new android.os.Handler(getMainLooper()).postDelayed(() -> play(Math.min(prefs().getInt("sel", 0), games.length() - 1)), 1600);
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (wizardStep == 1) showWizard(1);
        else if (editing >= 0) refreshStatus();
    }

    @Override public void onBackPressed() {
        if (wizardStep > 0) { showWizard(wizardStep - 1); return; }
        if (editing >= 0) { saveDraft(); showHome(); return; }
        super.onBackPressed();
    }

    // ================================================================ data
    private android.content.SharedPreferences prefs() { return getSharedPreferences("shell", MODE_PRIVATE); }

    private JSONArray loadGames() {
        try {
            JSONArray a = new JSONArray(prefs().getString("games", "[]"));
            return a;
        } catch (Exception e) { return new JSONArray(); }
    }

    private void saveGames(JSONArray a) { prefs().edit().putString("games", a.toString()).apply(); }

    /** Pulls changes made in the in-game menu back into the saved game. */
    private void applyOverrides() {
        File f = new File(getFilesDir(), "overrides.json");
        if (!f.isFile()) return;
        try {
            JSONObject o = new JSONObject(Shell.read(f));
            JSONArray games = loadGames();
            int sel = prefs().getInt("sel", 0);
            if (sel >= 0 && sel < games.length()) {
                JSONObject g = games.getJSONObject(sel);
                for (String k : new String[]{"quality", "fps", "res", "profile", "hud", "opacity", "mute"}) if (o.has(k)) g.put(k, o.get(k));
                games.put(sel, g); saveGames(games);
            }
        } catch (Exception ignored) { }
        f.delete();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ================================================================ UI helpers
    private LinearLayout newPage() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(36), dp(20), dp(24));
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        status = null;
        return layout;
    }

    private TextView text(String s, int size) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); return t; }

    private TextView heading(String s) {
        TextView t = text(s, 14); t.setTextColor(Color.parseColor("#8fb3ff")); t.setPadding(0, dp(16), 0, dp(2)); return t;
    }

    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false);
        b.setOnClickListener(v -> { try { action.run(); } catch (Throwable t) { toast("Error: " + t); } });
        return b;
    }

    private Spinner spinner(String[] items, int selected) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        s.setSelection(Math.max(0, Math.min(items.length - 1, selected)));
        return s;
    }

    private SeekBar seek(int max, int value) { SeekBar s = new SeekBar(this); s.setMax(max); s.setProgress(Math.max(0, Math.min(max, value))); return s; }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    // ================================================================ first-run wizard
    private void showWizard(int step) {
        wizardStep = step; editing = -1;
        if (wizardGame == null) { try { wizardGame = Shell.defaultGame("GTA V", prefs().getString("path", Shell.DEFAULT_PATH)); } catch (Exception ignored) { } }
        LinearLayout p = newPage();
        p.addView(text("Welcome to Game Shell Native", 26));
        p.addView(text("Step " + (step + 1) + " of 3", 13));
        if (step == 0) {
            p.addView(heading("What this app does"));
            p.addView(text("It runs your own copy of the game on your phone's GPU. The app contains no game files and downloads nothing.\n\nThis quick setup takes a minute: allow file access, point to your game folder, and you're ready to play.", 15));
            p.addView(button("Next", () -> showWizard(1)));
        } else if (step == 1) {
            p.addView(heading("Allow file access"));
            p.addView(text("The game is read straight from the folder you already have, so the app needs \"All files access\". It also keeps backups in Documents/GTA5NativeData so your saves and layouts survive an uninstall.", 15));
            final boolean ok = Shell.hasAccess();
            TextView s = text(ok ? "File access: granted \u2713" : "File access: not granted yet", 15);
            s.setTextColor(ok ? Color.parseColor("#6EE7A8") : Color.parseColor("#FFD54A")); s.setPadding(0, dp(10), 0, dp(10)); p.addView(s);
            if (!ok) p.addView(button("Allow file access", this::grantAccess));
            Button next = button("Next", () -> showWizard(2)); next.setEnabled(ok); p.addView(next);
            p.addView(button("Back", () -> showWizard(0)));
        } else {
            p.addView(heading("Pick your game's main folder"));
            p.addView(text("Choose the folder that contains your game (for example Download/1GTA). The app searches inside it and selects the game automatically.", 15));
            final EditText path = new EditText(this); path.setSingleLine(); path.setText(wizardGame.optString("path", Shell.DEFAULT_PATH)); p.addView(path);
            final TextView result = text(wizardGame.optString("root").isEmpty() ? "No game selected yet." : "Game found: " + wizardGame.optString("root"), 14);
            result.setPadding(0, dp(8), 0, dp(8));
            final Button finish = button("Finish setup", this::finishWizard);
            finish.setEnabled(!wizardGame.optString("root").isEmpty());
            p.addView(button("Find my game", () -> {
                try {
                    wizardGame.put("path", path.getText().toString().trim());
                    File root = search(new File(wizardGame.optString("path")));
                    if (root == null) { wizardGame.put("root", ""); result.setText("No game found under:\n" + wizardGame.optString("path") + "\nLooking for data/manifest.json and b/<build>/game.wasm."); finish.setEnabled(false); }
                    else { wizardGame.put("root", root.getAbsolutePath()); result.setText("Game found: " + root.getAbsolutePath()); finish.setEnabled(true); }
                } catch (Exception e) { toast("Error: " + e.getMessage()); }
            }));
            p.addView(result);
            p.addView(finish);
            p.addView(button("Back", () -> showWizard(1)));
        }
    }

    private void finishWizard() {
        try {
            String summary = Shell.autoConfigure(this, wizardGame);
            JSONArray games = new JSONArray(); games.put(wizardGame); saveGames(games);
            prefs().edit().putBoolean("wizard_done", true).putInt("sel", 0).putString("path", wizardGame.optString("path")).apply();
            Shell.backupProfiles(this);
            wizardStep = -1; pendingMessage = "Setup complete. " + summary;
            showHome();
        } catch (Exception e) { toast("Setup failed: " + e.getMessage()); }
    }

    // ================================================================ home
    private void showHome() {
        editing = -1; draft = null; wizardStep = -1;
        LinearLayout p = newPage();
        p.addView(text("Game Shell Native", 28));
        p.addView(text("by beworkall", 13));
        showCrashIfAny(p);
        if (!pendingMessage.isEmpty()) { TextView m = text(pendingMessage, 14); m.setTextColor(Color.parseColor("#6EE7A8")); m.setPadding(0, dp(8), 0, 0); p.addView(m); pendingMessage = ""; }
        JSONArray games = loadGames();
        for (int i = 0; i < games.length(); i++) {
            final int index = i;
            JSONObject g = games.optJSONObject(i);
            if (g == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(0, dp(12), 0, 0);
            Button open = button(g.optString("name", "Game") + "\n" + (g.optString("root").isEmpty() ? "folder not set" : g.optString("root")), () -> showSettings(index));
            open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.addView(open, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(button("Play", () -> play(index)), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            p.addView(row);
        }
        p.addView(button("+ Add game", () -> {
            try { JSONArray a = loadGames(); JSONObject g = Shell.defaultGame(a.length() == 0 ? "GTA V" : "GTA V #" + (a.length() + 1), prefs().getString("path", Shell.DEFAULT_PATH)); a.put(g); saveGames(a); showSettings(a.length() - 1); }
            catch (Exception e) { toast("Could not add: " + e.getMessage()); }
        }));
        p.addView(button("Create / import controls", () -> { if (loadGames().length() > 0) showSettings(Math.min(prefs().getInt("sel", 0), loadGames().length() - 1)); }));
        p.addView(button("About", this::showAbout));
        status = text("", 13); status.setPadding(0, dp(12), 0, 0); p.addView(status);
        File error = new File(getFilesDir(), "last-error.txt");
        if (error.isFile()) { status.setText("Game could not start: " + Shell.read(error)); error.delete(); }
    }

    private void showCrashIfAny(LinearLayout p) {
        final File crash = new File(getFilesDir(), "crash.txt");
        if (!crash.isFile()) return;
        final String text = Shell.read(crash);
        TextView t = text("Last crash (copy it and send it to the developer):\n" + text, 10);
        t.setTextColor(Color.parseColor("#ff8f8f")); t.setTextIsSelectable(true); t.setPadding(0, dp(10), 0, dp(4)); p.addView(t);
        LinearLayout row = new LinearLayout(this);
        row.addView(button("Copy", () -> { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("crash", text)); toast("Copied"); }));
        row.addView(button("Dismiss", () -> { crash.delete(); showHome(); }));
        p.addView(row);
    }

    // ================================================================ per-game settings page
    private void showSettings(int index) {
        JSONArray games = loadGames();
        draft = games.optJSONObject(index);
        if (draft == null) { showHome(); return; }
        editing = index;
        prefs().edit().putInt("sel", index).apply();
        LinearLayout p = newPage();
        p.addView(text("Game settings", 24));

        p.addView(heading("Name"));
        etName = new EditText(this); etName.setSingleLine(); etName.setText(draft.optString("name")); p.addView(etName);

        p.addView(heading("Game folder"));
        etPath = new EditText(this); etPath.setSingleLine(); etPath.setText(draft.optString("path")); p.addView(etPath);
        LinearLayout folderRow = new LinearLayout(this);
        folderRow.addView(button("Allow file access", this::grantAccess));
        folderRow.addView(button("Find game", () -> { collect(); findGame(); }));
        p.addView(folderRow);
        status = text("", 13); status.setPadding(0, dp(6), 0, dp(6)); p.addView(status);

        p.addView(heading("Mode"));
        spMode = spinner(Shell.MODE_NAMES, draft.optInt("mode", 1)); p.addView(spMode);

        p.addView(heading("Graphics"));
        p.addView(text("Render scale (lower = faster)", 12));
        spQuality = spinner(Shell.QUALITY_NAMES, draft.optInt("quality", 1)); p.addView(spQuality);
        p.addView(text("Frame rate", 12));
        spFps = spinner(Shell.FPS_NAMES, draft.optInt("fps", 0)); p.addView(spFps);
        p.addView(text("Texture quality", 12));
        spTex = spinner(Shell.TEX_NAMES, draft.optInt("texture", 0)); p.addView(spTex);
        p.addView(text("Fixed internal resolution (advanced)", 12));
        spRes = spinner(Shell.RES_NAMES, draft.optInt("res", 0)); p.addView(spRes);
        LinearLayout autoRow = new LinearLayout(this);
        autoRow.addView(button("Auto-configure for this phone", () -> {
            collect(); pendingMessage = Shell.autoConfigure(this, draft); saveDraft(); showSettings(editing);
        }));
        autoRow.addView(button("Reset settings", () -> new AlertDialog.Builder(this).setMessage("Reset this game's settings to the defaults? The game folder is kept.")
            .setNegativeButton("Cancel", null).setPositiveButton("Reset", (d, w) -> {
                try {
                    JSONObject fresh = Shell.defaultGame(draft.optString("name", "GTA V"), draft.optString("path"));
                    fresh.put("root", draft.optString("root"));
                    draft = fresh; JSONArray a = loadGames(); a.put(editing, draft); saveGames(a);
                    pendingMessage = "Settings reset to defaults."; showSettings(editing);
                } catch (Exception e) { toast("Reset failed: " + e.getMessage()); }
            }).show()));
        p.addView(autoRow);

        p.addView(heading("On-screen controls (Winlator profiles)"));
        profileNames = Shell.listProfiles(this);
        String[] shown = new String[profileNames.size()];
        for (int i = 0; i < shown.length; i++) shown[i] = profileNames.get(i).equals(Shell.BUILTIN) ? "Built-in (original)" : profileNames.get(i);
        spProfile = spinner(shown, Math.max(0, profileNames.indexOf(draft.optString("profile", "FPS")))); p.addView(spProfile);
        LinearLayout profRow = new LinearLayout(this);
        profRow.addView(button("Edit layout", () -> openEditor(false)));
        profRow.addView(button("Duplicate & edit", () -> openEditor(true)));
        p.addView(profRow);
        LinearLayout profRow2 = new LinearLayout(this);
        profRow2.addView(button("New layout", () -> { collect(); saveDraft(); startActivityForResult(new Intent(this, EditorActivity.class), REQ_EDIT); }));
        profRow2.addView(button("Import .icp", () -> {
            collect(); saveDraft();
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_IMPORT);
        }));
        p.addView(profRow2);
        p.addView(text("Controls opacity", 12));
        sbOpacity = seek(85, (int) Math.round(draft.optDouble("opacity", 0.9) * 100) - 15); p.addView(sbOpacity);
        p.addView(text("Touch look speed", 12));
        sbTouch = seek(40, (int) Math.round(draft.optDouble("touch", 1.0) * 10) - 1); p.addView(sbTouch);

        p.addView(heading("Gamepad (wired or Bluetooth)"));
        p.addView(text("Connect a controller, then just play. Remap any button below.", 12));
        p.addView(text("Stick deadzone", 12));
        sbDead = seek(50, draft.optInt("padDead", 20)); p.addView(sbDead);
        p.addView(text("Right-stick look speed", 12));
        sbLook = seek(30, draft.optInt("padLook", 10)); p.addView(sbLook);
        p.addView(button("Remap gamepad buttons", this::remapDialog));

        p.addView(heading("HUD and sound"));
        p.addView(text("In-game HUD", 12));
        spHud = spinner(HUD_NAMES, draft.optInt("hud", 0)); p.addView(spHud);
        swMute = new Switch(this); swMute.setText("Mute audio"); swMute.setChecked(draft.optBoolean("mute")); p.addView(swMute);

        p.addView(heading("Advanced: multiplayer"));
        swMp = new Switch(this); swMp.setText("Multiplayer"); swMp.setChecked(draft.optBoolean("mp")); p.addView(swMp);
        swHost = new Switch(this); swHost.setText("Host a session"); swHost.setChecked(draft.optBoolean("host")); p.addView(swHost);
        etServer = new EditText(this); etServer.setHint("Server address (host:47520)"); etServer.setSingleLine(); etServer.setText(draft.optString("server")); p.addView(etServer);
        etPlayer = new EditText(this); etPlayer.setHint("Player name"); etPlayer.setSingleLine(); etPlayer.setText(draft.optString("player")); p.addView(etPlayer);

        p.addView(heading("Tools and data"));
        p.addView(text("Data folder: Documents/GTA5NativeData (backups of saves and layouts)", 12));
        p.addView(button("Quick check (file sizes)", () -> { collect(); saveDraft(); verify(false); }));
        p.addView(button("Full verify (hashes, slow)", () -> { collect(); saveDraft(); verify(true); }));
        p.addView(button("Clear graphics and temp data", () -> new AlertDialog.Builder(this)
            .setMessage("Delete the compile cache and temp files? This can fix crashes, blank screens and lag. Your saves are kept. The next launch takes several minutes.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> pendingMessageToStatus(Shell.clearGraphicsAndTemp(this))).show()));
        LinearLayout bk = new LinearLayout(this);
        bk.addView(button("Back up saves", () -> pendingMessageToStatus(Shell.backupSaves(this))));
        bk.addView(button("Restore saves", () -> new AlertDialog.Builder(this).setMessage("Replace current saves with the backup?")
            .setNegativeButton("Cancel", null).setPositiveButton("Restore", (d, w) -> pendingMessageToStatus(Shell.restoreSaves(this))).show()));
        p.addView(bk);
        p.addView(button("Export log to Downloads", this::exportLog));

        p.addView(heading(""));
        p.addView(button("Save and Play", () -> { collect(); saveDraft(); play(editing); }));
        p.addView(button("Save and go back", () -> { collect(); saveDraft(); showHome(); }));
        p.addView(button("Delete this game", () -> {
            JSONArray a = loadGames(); JSONArray b = new JSONArray();
            for (int i = 0; i < a.length(); i++) if (i != editing) b.put(a.opt(i));
            saveGames(b); showHome();
        }));
        refreshStatus();
    }

    private void pendingMessageToStatus(String s) { if (status != null) status.setText(s); else toast(s); }

    private void collect() {
        if (draft == null || etName == null) return;
        try {
            String name = etName.getText().toString().trim();
            draft.put("name", name.isEmpty() ? "GTA V" : name);
            draft.put("path", etPath.getText().toString().trim());
            draft.put("mode", spMode.getSelectedItemPosition());
            draft.put("quality", spQuality.getSelectedItemPosition());
            draft.put("fps", spFps.getSelectedItemPosition());
            draft.put("texture", spTex.getSelectedItemPosition());
            draft.put("res", spRes.getSelectedItemPosition());
            draft.put("touch", (sbTouch.getProgress() + 1) / 10.0);
            draft.put("opacity", (sbOpacity.getProgress() + 15) / 100.0);
            draft.put("mute", swMute.isChecked());
            draft.put("hud", spHud.getSelectedItemPosition());
            draft.put("padDead", sbDead.getProgress()); draft.put("padLook", sbLook.getProgress());
            if (spProfile.getSelectedItemPosition() < profileNames.size()) draft.put("profile", profileNames.get(spProfile.getSelectedItemPosition()));
            draft.put("mp", swMp.isChecked()); draft.put("host", swHost.isChecked());
            draft.put("server", etServer.getText().toString().trim()); draft.put("player", etPlayer.getText().toString().trim());
        } catch (Exception ignored) { }
    }

    private void saveDraft() {
        if (editing < 0 || draft == null) return;
        try {
            JSONArray a = loadGames(); a.put(editing, draft); saveGames(a);
            prefs().edit().putString("path", draft.optString("path")).apply();
        } catch (Exception ignored) { }
    }

    private void refreshStatus() {
        if (status == null || draft == null) return;
        String root = draft.optString("root");
        boolean ok = !root.isEmpty() && Shell.hasAccess() && valid(new File(root));
        String s = "File access: " + (Shell.hasAccess() ? "yes" : "NO (tap Allow file access)") + "\n"
            + (ok ? "Game found: " + root : "Game not found yet (tap Find game)");
        if (!pendingMessage.isEmpty()) { s += "\n" + pendingMessage; pendingMessage = ""; }
        status.setText(s);
    }

    // ================================================================ folder detection
    private void grantAccess() {
        if (Shell.hasAccess()) { toast("File access already granted."); return; }
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
        if (!Shell.hasAccess()) { status.setText("Allow file access first."); return; }
        File root = search(new File(draft.optString("path")));
        try {
            if (root == null) { draft.put("root", ""); status.setText("No game found under:\n" + draft.optString("path")); }
            else { draft.put("root", root.getAbsolutePath()); status.setText("Game found: " + root.getAbsolutePath()); }
        } catch (Exception ignored) { }
        saveDraft();
    }

    // ================================================================ profiles
    private void openEditor(boolean copy) {
        collect(); saveDraft();
        String name = profileNames.get(Math.min(spProfile.getSelectedItemPosition(), profileNames.size() - 1));
        if (name.equals(Shell.BUILTIN)) { toast("Pick a profile first, or use New layout."); return; }
        startActivityForResult(new Intent(this, EditorActivity.class).putExtra("profile", name).putExtra("copy", copy), REQ_EDIT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_EDIT) {
            if (result == RESULT_OK && data != null && data.getStringExtra("profile") != null && draft != null) {
                try { draft.put("profile", data.getStringExtra("profile")); } catch (Exception ignored) { }
                saveDraft();
            }
            if (editing >= 0) showSettings(editing);
            return;
        }
        if (request != REQ_IMPORT || result != RESULT_OK || data == null) { if (editing >= 0) showSettings(editing); return; }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        int ok = 0; String last = null;
        for (Uri uri : uris) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(); Shell.copy(in, buf);
                JSONObject json = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                if (json.optJSONArray("elements") == null) throw new IOException("not a controls profile");
                String base = displayName(uri).replaceAll("(?i)\\.icp$", "").replaceAll("[^A-Za-z0-9 ._()\\-]", "_");
                if (base.isEmpty()) base = json.optString("name", "profile");
                try (FileOutputStream out = new FileOutputStream(new File(Shell.profileDir(this), base + ".icp"))) { out.write(buf.toByteArray()); }
                ok++; last = base;
            } catch (Exception e) { toast("Skipped a file: " + e.getMessage()); }
        }
        toast("Imported " + ok + " profile(s)");
        Shell.backupProfiles(this);
        if (ok > 0 && draft != null) { try { draft.put("profile", last); } catch (Exception ignored) { } saveDraft(); }
        if (editing >= 0) showSettings(editing);
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) { }
        return "profile";
    }

    // ================================================================ gamepad remap
    private void remapDialog() {
        final String[] choices = Shell.bindingNames();
        JSONObject map = draft.optJSONObject("padmap");
        if (map == null) map = new JSONObject();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(16), dp(8), dp(16), dp(8));
        final Spinner[] spinners = new Spinner[PAD_INPUTS.length];
        for (int i = 0; i < PAD_INPUTS.length; i++) {
            String input = PAD_INPUTS[i];
            String def = input.startsWith("DPAD_") ? "GAMEPAD_" + input : "GAMEPAD_BUTTON_" + input;
            TextView t = text(input.replace('_', ' '), 13); col.addView(t);
            Spinner s = new Spinner(this);
            s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
            String cur = map.optString(input, def);
            for (int k = 0; k < choices.length; k++) if (choices[k].equals(cur)) s.setSelection(k);
            spinners[i] = s; col.addView(s);
        }
        ScrollView sv = new ScrollView(this); sv.addView(col);
        new AlertDialog.Builder(this).setTitle("Gamepad buttons").setView(sv)
            .setPositiveButton("Save", (d, w) -> {
                try {
                    JSONObject out = new JSONObject();
                    for (int i = 0; i < PAD_INPUTS.length; i++) out.put(PAD_INPUTS[i], choices[spinners[i].getSelectedItemPosition()]);
                    draft.put("padmap", out); saveDraft(); toast("Gamepad mapping saved");
                } catch (Exception e) { toast("Error: " + e.getMessage()); }
            })
            .setNeutralButton("Defaults", (d, w) -> { draft.remove("padmap"); saveDraft(); toast("Gamepad mapping reset"); })
            .setNegativeButton("Cancel", null).show();
    }

    // ================================================================ tools
    private void exportLog() {
        if (!Shell.hasAccess()) { toast("Allow file access first."); return; }
        try {
            File out = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GameShell-runtime.log");
            try (FileOutputStream os = new FileOutputStream(out)) {
                for (String n : new String[]{"runtime.log", "last-error.txt", "crash.txt", "config.json", "ui.json", "game_dir.txt"}) {
                    File f = new File(getFilesDir(), n);
                    os.write(("\n===== " + n + (f.isFile() ? " (" + f.length() + " bytes)" : " (missing)") + " =====\n").getBytes(StandardCharsets.UTF_8));
                    if (f.isFile()) try (InputStream in = new FileInputStream(f)) { Shell.copy(in, os); }
                }
            }
            pendingMessageToStatus("Log saved to Downloads/GameShell-runtime.log");
        } catch (Exception e) { toast("Export failed: " + e.getMessage()); }
    }

    private void verify(final boolean hashes) {
        if (!Shell.hasAccess()) { status.setText("Allow file access first."); return; }
        final File root = draft.optString("root").isEmpty() ? null : new File(draft.optString("root"));
        if (root == null || !valid(root)) { status.setText("Find the game folder first."); return; }
        status.setText("Checking files...");
        final TextView s = status;
        new Thread(() -> {
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = getAssets().open("game-manifest.json")) { Shell.copy(in, buf); }
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

    // ================================================================ about
    private void showAbout() {
        LinearLayout p = newPage();
        p.addView(text("About", 26));
        p.addView(heading("Game Shell Native 0.3"));
        p.addView(text("Developer: beworkall", 16));
        p.addView(text("A native Android launcher and runtime for game files you already own. It contains no game files and downloads nothing.", 14));
        p.addView(heading("Credits"));
        p.addView(text("\u2022 Runtime: GTAV Mobile by alihammoud21, which is based on Muguet by Constantin Clerc (c22dev). All original authors' notices are kept.\n"
            + "\u2022 The browser port of the game and its data format: the PlayGTA5 project's authors.\n"
            + "\u2022 Control profile format and editor ideas: Winlator by brunodev85 (LGPL-2.1).\n"
            + "\u2022 Grand Theft Auto V and its content belong to Rockstar Games / Take-Two Interactive. This app is not affiliated with or endorsed by them.", 14));
        p.addView(heading("License"));
        p.addView(text("Free software under the GNU GPL v3 or later. You may study, change and share it under the same license.", 14));
        p.addView(text("Source: " + SOURCE_URL, 13));
        p.addView(button("Show license text", () -> {
            String lic = "";
            try (InputStream in = getAssets().open("LICENSE.txt")) { ByteArrayOutputStream b = new ByteArrayOutputStream(); Shell.copy(in, b); lic = b.toString("UTF-8"); } catch (IOException e) { lic = "License text not bundled."; }
            TextView t = text(lic, 10); t.setPadding(dp(12), dp(12), dp(12), dp(12)); t.setTextIsSelectable(true);
            ScrollView sv = new ScrollView(this); sv.addView(t);
            new AlertDialog.Builder(this).setTitle("GNU GPL v3").setView(sv).setPositiveButton("Close", null).show();
        }));
        p.addView(button("Open source page", () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL)))));
        p.addView(button("Back", this::showHome));
    }

    // ================================================================ play
    private void play(int index) {
        JSONArray games = loadGames();
        JSONObject g = games.optJSONObject(index);
        if (g == null) return;
        try {
            if (!Shell.hasAccess()) { toast("Allow file access first (open the game settings)."); return; }
            File root = g.optString("root").isEmpty() ? null : new File(g.optString("root"));
            if (root == null || !valid(root)) root = search(new File(g.optString("path")));
            if (root == null) { toast("Game folder not found. Open the game settings and tap Find game."); return; }
            g.put("root", root.getAbsolutePath()); games.put(index, g); saveGames(games);
            prefs().edit().putInt("sel", index).apply();
            Shell.write(new File(getFilesDir(), "game_dir.txt"), root.getAbsolutePath());
            if (g.optBoolean("mp") && !g.optBoolean("host") && g.optString("server").trim().isEmpty()) { toast("Enter a server address, or turn on Host a session."); return; }

            JSONObject config = new JSONObject();
            config.put("scale", Shell.QUALITY[Math.max(0, Math.min(Shell.QUALITY.length - 1, g.optInt("quality", 1)))]);
            config.put("fullscreen", true);
            config.put("fps_limit", Shell.FPS[Math.max(0, Math.min(Shell.FPS.length - 1, g.optInt("fps", 0)))]);
            config.put("start_mode", g.optInt("mode", 1) + 1);
            config.put("touch_sensitivity", g.optDouble("touch", 1.0));
            config.put("mute", g.optBoolean("mute"));
            int tex = g.optInt("texture", 0);
            if (tex > 0) config.put("texture_quality", tex - 1);
            int res = Math.max(0, Math.min(Shell.RES_VALUES.length - 1, g.optInt("res", 0)));
            if (res > 0) config.put("resolution", Shell.RES_VALUES[res]);
            config.put("multiplayer", g.optBoolean("mp")); config.put("host", g.optBoolean("host"));
            config.put("server", g.optString("server")); config.put("player_name", g.optString("player"));
            Shell.write(new File(getFilesDir(), "config.json"), config.toString());

            JSONObject ui = new JSONObject();
            ui.put("hud", g.optInt("hud", 0)); ui.put("opacity", g.optDouble("opacity", 0.9));
            ui.put("padDead", g.optInt("padDead", 20)); ui.put("padLook", g.optInt("padLook", 10));
            ui.put("mute", g.optBoolean("mute"));
            ui.put("quality", g.optInt("quality", 1)); ui.put("fps", g.optInt("fps", 0)); ui.put("res", g.optInt("res", 0));
            if (g.optJSONObject("padmap") != null) ui.put("padmap", g.optJSONObject("padmap"));
            Shell.write(new File(getFilesDir(), "ui.json"), ui.toString());
            Shell.write(new File(getFilesDir(), "active_profile.txt"), g.optString("profile", Shell.BUILTIN));
            Shell.backupProfiles(this);
            startActivity(new Intent(this, GameActivity.class));
        } catch (Exception error) { toast("Could not start: " + error.getMessage()); }
    }
}
