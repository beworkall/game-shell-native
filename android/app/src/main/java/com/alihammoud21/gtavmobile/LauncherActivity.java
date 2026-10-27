package com.alihammoud21.gtavmobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Game Shell Native launcher: first-run wizard, bold two-pane home (in the spirit of Liberty Recompiled),
 * per-game settings, Winlator-style controls page, app settings, tools and About.
 * No downloader: you bring your own game folder. Modified from GTAV Mobile (GPL-3.0-or-later); see NOTICE.md.
 */
public final class LauncherActivity extends Activity {
    private static final int REQ_IMPORT = 7, REQ_EDIT = 8, REQ_COVER = 9, REQ_TREE = 10;
    private static final String[] PAD_INPUTS = {"A", "B", "X", "Y", "L1", "R1", "L2", "R2", "L3", "R3", "START", "SELECT", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"};
    private static final String[] HUD_NAMES = {"Off", "FPS", "Detailed + FPS graph"};
    private static final String SOURCE_URL = "https://github.com/beworkall/gtaappweb";
    private static final String VERSION = "0.5.1";
    private static final int P_HOME = 0, P_SETTINGS = 1, P_APP = 2, P_ABOUT = 3, P_WIZARD = 4, P_CONTROLS = 5;

    private Ui u;
    private int page = P_HOME;
    private int editing = -1, coverTarget = -1, treeTarget = -1;   // treeTarget: -2 = wizard, >= 0 = game index
    private JSONObject draft;
    private String pendingMessage = "";
    private int wizardStep = 0, wizardPreset = -1, wizardProfile = 0;
    private JSONObject wizardGame;
    private LinearLayout statusRow;
    private TextView deviceLine;

    // settings widgets
    private EditText etName, etPath, etServer, etPlayer;
    private Spinner spMode, spQuality, spFps, spTex, spRes, spHud, spProfile;
    private SeekBar sbTouch, sbOpacity, sbDead, sbLook;
    private Switch swMute, swMp, swHost;
    private int presetChoice = -1;
    private List<String> profileNames = new ArrayList<>();

    // ================================================================ lifecycle
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
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

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        rebuild();
    }

    /** Redraws whatever page is open (rotation, accent change, returning from another screen). */
    private void rebuild() {
        switch (page) {
            case P_SETTINGS: collect(); saveDraft(); showSettings(editing); break;
            case P_APP: showAppSettings(); break;
            case P_ABOUT: showAbout(); break;
            case P_WIZARD: showWizard(wizardStep); break;
            case P_CONTROLS: showControls(); break;
            default: showHome();
        }
    }

    private void handleIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("autoplay", false)) {
            intent.removeExtra("autoplay");
            final JSONArray games = loadGames();
            if (games.length() > 0) {
                toast("Restarting the game...");
                // wait for the old game process to exit so the new one starts clean
                new android.os.Handler(getMainLooper()).postDelayed(() -> play(Math.min(sel(), games.length() - 1), true), 1600);
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        // The launcher is on screen, so the game is not: clear out any game process Android kept alive.
        killGameProcess(this);
        if (prefs().getBoolean("inGame", false)) {
            prefs().edit().putBoolean("inGame", false).apply();
            applyOverrides();
            if (prefs().getBoolean("autoBackup", true) && Shell.hasAccess()) {
                final Context app = getApplicationContext();
                new Thread(() -> {
                    final String msg = Shell.backupSaves(app);
                    if (msg.startsWith("Backed up")) runOnUiThread(() -> toast("Saves backed up automatically"));
                }, "auto-backup").start();
            }
            if (page == P_HOME) showHome();
        }
        if (page == P_WIZARD && wizardStep == 1) showWizard(1);
        else if (page == P_SETTINGS) refreshStatus();
    }

    @Override public void onBackPressed() {
        if (page == P_WIZARD && wizardStep > 0) { showWizard(wizardStep - 1); return; }
        if (page == P_SETTINGS) { collect(); saveDraft(); showHome(); return; }
        if (page == P_APP || page == P_ABOUT || page == P_CONTROLS) { showHome(); return; }
        super.onBackPressed();
    }

    // ================================================================ data
    private android.content.SharedPreferences prefs() { return getSharedPreferences("shell", MODE_PRIVATE); }

    private int sel() { return prefs().getInt("sel", 0); }

    private JSONArray loadGames() {
        try {
            JSONArray a = new JSONArray(prefs().getString("games", "[]"));
            boolean changed = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject g = a.optJSONObject(i);
                if (g != null && g.optString("id").isEmpty()) { g.put("id", UUID.randomUUID().toString()); changed = true; }
            }
            if (changed) saveGames(a);
            return a;
        } catch (Exception e) { return new JSONArray(); }
    }

    private void saveGames(JSONArray a) { prefs().edit().putString("games", a.toString()).apply(); }

    private JSONObject game(int index) { return loadGames().optJSONObject(index); }

    private void putGame(int index, JSONObject g) {
        JSONArray a = loadGames();
        try { a.put(index, g); } catch (Exception ignored) { }
        saveGames(a);
    }

    /** Pulls changes made in the in-game menu back into the saved game and app settings. */
    private void applyOverrides() {
        File f = new File(getFilesDir(), "overrides.json");
        if (!f.isFile()) return;
        try {
            JSONObject o = new JSONObject(Shell.read(f));
            if (o.has("haptics")) prefs().edit().putBoolean("haptics", o.optBoolean("haptics", true)).apply();
            JSONArray games = loadGames();
            int s = sel();
            if (s >= 0 && s < games.length()) {
                JSONObject g = games.getJSONObject(s);
                for (String k : new String[]{"quality", "fps", "res", "profile", "hud", "opacity", "mute"}) if (o.has(k)) g.put(k, o.get(k));
                if (o.has("quality") || o.has("fps")) g.put("preset", -1);
                games.put(s, g); saveGames(games);
            }
        } catch (Exception ignored) { }
        f.delete();
    }

    private File coverFile(JSONObject g) {
        File d = new File(getFilesDir(), "covers"); d.mkdirs();
        return new File(d, g.optString("id", "x") + ".jpg");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private String ago(long t) {
        if (t <= 0) return "never";
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 60 * 24) return (m / 60) + " h ago";
        return (m / 1440) + " days ago";
    }

    private String presetName(JSONObject g) {
        int pr = g.optInt("preset", -1);
        return pr >= 0 && pr < Shell.PRESET_NAMES.length ? Shell.PRESET_NAMES[pr] : "Custom";
    }

    private String profileLabel(String p) { return p == null || p.equals(Shell.BUILTIN) ? "Original built-in" : p; }

    private boolean ready(JSONObject g) {
        String root = g == null ? "" : g.optString("root");
        return !root.isEmpty() && Shell.hasAccess() && Shell.validRoot(new File(root));
    }

    // ================================================================ finding the game
    private void chooseFolder(int target) {
        if (!Shell.hasAccess()) { grantAccess(); return; }
        treeTarget = target;
        try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE); }
        catch (Exception e) { toast("No folder picker on this phone. Type the path instead."); }
    }

    private interface Found { void on(File root, File searchedFrom); }

    /** Searches start (or every storage root when start is null) in the background with a cancellable progress dialog. */
    private void locate(final File start, final Found found) {
        if (!Shell.hasAccess()) { grantAccess(); return; }
        final boolean[] cancel = {false};
        final TextView msg = u.body(start == null ? "Scanning your phone..." : "Searching " + start.getAbsolutePath());
        msg.setPadding(u.dp(22), u.dp(16), u.dp(22), u.dp(8));
        final AlertDialog dlg = new AlertDialog.Builder(this).setTitle(start == null ? "Scanning phone" : "Searching folder").setView(msg)
            .setNegativeButton("Cancel", (d, w) -> cancel[0] = true).setCancelable(false).show();
        new Thread(() -> {
            File result = null;
            List<File> roots = start == null ? Shell.storageRoots() : Collections.singletonList(start);
            for (File r : roots) {
                result = Shell.findGame(r, start == null ? 8 : 6, (n, where) -> {
                    runOnUiThread(() -> msg.setText("Looked in " + n + " folders\n" + where));
                    return !cancel[0];
                });
                if (result != null || cancel[0]) break;
            }
            final File res = result;
            runOnUiThread(() -> {
                try { dlg.dismiss(); } catch (Exception ignored) { }
                if (cancel[0]) return;
                if (res == null) u.buzz(2); else u.buzz(1);
                found.on(res, start);
            });
        }, "find-game").start();
    }

    /** Stores a found root on a game (or the wizard draft) and refreshes the page. */
    private void applyFound(int target, File root, File from) {
        try {
            JSONObject g = target == -2 ? wizardGame : game(target);
            if (g == null) return;
            if (root == null) {
                pendingMessage = "";
                toast(from == null ? "No game found anywhere on this phone." : "No game inside " + from.getAbsolutePath());
            } else {
                g.put("root", root.getAbsolutePath());
                g.put("path", (from != null ? from : root).getAbsolutePath());
                prefs().edit().putString("path", g.optString("path")).apply();
                String problem = Shell.buildProblem(this, root);
                pendingMessage = problem == null ? "Game found: " + root.getAbsolutePath() : problem;
            }
            if (target == -2) { wizardGame = g; showWizard(2); }
            else {
                putGame(target, g);
                if (page == P_SETTINGS && draft != null) { draft.put("root", g.optString("root")); draft.put("path", g.optString("path")); showSettings(editing); }
                else showHome();
            }
        } catch (Exception e) { toast("Error: " + e.getMessage()); }
    }

    // ================================================================ first-run wizard
    private void showWizard(int step) {
        page = P_WIZARD; wizardStep = step; editing = -1;
        u = Ui.of(this);
        if (wizardGame == null) { try { wizardGame = Shell.defaultGame("GTA V", prefs().getString("path", Shell.DEFAULT_PATH)); } catch (Exception ignored) { } }
        if (wizardPreset < 0) wizardPreset = Shell.recommendedPreset(this);
        LinearLayout p = u.page(this);
        p.addView(u.progress(step, 4));
        if (step == 0) {
            p.addView(u.logo(u.landscape() ? 1f : 0.72f));
            p.addView(u.spacer(14));
            LinearLayout c = u.card();
            c.addView(u.heading("Welcome"));
            c.addView(u.status("Contains no game files and downloads nothing", 0));
            c.addView(u.status("Reads the game folder you already have, wherever it is", 0));
            c.addView(u.status("Winlator-style touch layouts and full gamepad support", 0));
            c.addView(u.status("Setup takes about a minute", 3));
            p.addView(c);
            p.addView(u.primary("Get started", R.drawable.ic_play_arrow, () -> showWizard(1)));
        } else if (step == 1) {
            p.addView(u.title("File access"));
            p.addView(u.subtitle("Step 2 of 4"));
            LinearLayout c = u.card();
            c.addView(u.body("The game is read straight from its folder, so the app needs \"All files access\". It also keeps backups of your saves and layouts in Documents/GTA5NativeData, so they survive an uninstall."));
            final boolean ok = Shell.hasAccess();
            c.addView(u.spacer(8));
            c.addView(u.status(ok ? "File access granted" : "File access not granted yet", ok ? 0 : 1));
            if (!ok) c.addView(u.primary("Allow file access", R.drawable.ic_folder_open, this::grantAccess));
            p.addView(c);
            Button next = u.primary("Next", () -> showWizard(2));
            next.setEnabled(ok); next.setAlpha(ok ? 1f : 0.4f);
            p.addView(u.split(u.secondary("Back", R.drawable.ic_arrow_back, () -> showWizard(0)), next));
        } else if (step == 2) {
            p.addView(u.title("Find your game"));
            p.addView(u.subtitle("Step 3 of 4"));
            LinearLayout c = u.card();
            c.addView(u.body("Pick the folder where you extracted the game, or let the app scan the whole phone. Folder names don't matter: the app recognises the game by its files."));
            final boolean found = !wizardGame.optString("root").isEmpty();
            c.addView(u.field("Game folder", found ? wizardGame.optString("root") : "Not selected"));
            c.addView(u.split(
                u.secondary("Choose folder", R.drawable.ic_folder_open, () -> chooseFolder(-2)),
                u.secondary("Scan phone", R.drawable.ic_search, () -> locate(null, (r, f) -> applyFound(-2, r, f)))));
            if (!pendingMessage.isEmpty()) { c.addView(u.spacer(6)); c.addView(u.status(pendingMessage, found ? 0 : 2)); pendingMessage = ""; }
            p.addView(c);
            Button next = u.primary("Next", () -> showWizard(3));
            next.setEnabled(found); next.setAlpha(found ? 1f : 0.4f);
            p.addView(u.split(u.secondary("Back", R.drawable.ic_arrow_back, () -> showWizard(1)), next));
        } else {
            p.addView(u.title("Tune it"));
            p.addView(u.subtitle(String.format(Locale.US, "Step 4 of 4  ·  %.1f GB RAM, %s", Shell.ramGb(this), Shell.socName())));
            p.addView(u.section("Performance preset"));
            p.addView(u.choices(Shell.PRESET_NAMES, Shell.PRESET_DESCS, wizardPreset, Shell.recommendedPreset(this), i -> wizardPreset = i));
            p.addView(u.section("Touch controls"));
            p.addView(u.choices(new String[]{"FPS layout", "Virtual gamepad", "Original built-in"},
                new String[]{"Winlator FPS style: d-pad, fire, aim, radial menus, F-keys.", "Xbox-style stick and buttons on screen.", "The runtime's own simple layout."},
                wizardProfile, 0, i -> wizardProfile = i));
            p.addView(u.body("You can change all of this later."));
            p.addView(u.split(u.secondary("Back", R.drawable.ic_arrow_back, () -> showWizard(2)), u.primary("Finish", R.drawable.ic_check_circle, this::finishWizard)));
        }
    }

    private void finishWizard() {
        try {
            Shell.applyPreset(wizardGame, wizardPreset);
            wizardGame.put("profile", wizardProfile == 0 ? "FPS" : wizardProfile == 1 ? "Virtual Gamepad" : Shell.BUILTIN);
            JSONArray games = new JSONArray(); games.put(wizardGame); saveGames(games);
            prefs().edit().putBoolean("wizard_done", true).putInt("sel", 0).putString("path", wizardGame.optString("path")).apply();
            Shell.backupProfiles(this);
            u.buzz(1);
            pendingMessage = "You're all set. Press Play.";
            showHome();
        } catch (Exception e) { toast("Setup failed: " + e.getMessage()); }
    }

    // ================================================================ home (two panes)
    private void showHome() {
        page = P_HOME; editing = -1;
        u = Ui.of(this);
        JSONArray games = loadGames();
        int s = Math.max(0, Math.min(sel(), games.length() - 1));
        final JSONObject g = games.optJSONObject(s);
        draft = g;
        final int index = s;
        LinearLayout[] panes = u.panes(this);
        LinearLayout left = panes[0], right = panes[1];
        boolean land = u.landscape();

        // ---- left: logo, device line, status, Play, 2x2 actions
        boolean compact = u.shortScreen();
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        left.addView(u.logo(land ? (compact ? 0.6f : 0.8f) : 0.66f));
        deviceLine = u.text(Shell.deviceLine(this, VERSION, prefs().getString("gpu", "")), 12, Ui.MUTED, false);
        deviceLine.setGravity(Gravity.CENTER);
        deviceLine.setSingleLine(); deviceLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        deviceLine.setPadding(0, u.dp(compact ? 6 : 10), 0, u.dp(compact ? 6 : 10));
        left.addView(deviceLine, new LinearLayout.LayoutParams(-1, -2));
        if (prefs().getString("gpu", "").isEmpty()) {
            new Thread(() -> { final String gpu = Shell.gpuName(getApplicationContext()); runOnUiThread(() -> { if (deviceLine != null) deviceLine.setText(Shell.deviceLine(this, VERSION, gpu)); }); }, "gpu").start();
        }
        String msg; int kind;
        if (g == null) { msg = "No game yet. Add your game folder."; kind = 1; }
        else if (!Shell.hasAccess()) { msg = "File access is off. Allow it to play."; kind = 1; }
        else if (!ready(g)) { msg = "Game folder not found. Choose it or scan your phone."; kind = 1; }
        else { msg = !pendingMessage.isEmpty() ? pendingMessage : "Ready  \u00B7  " + g.optString("name", "GTA V") + "  \u00B7  " + presetName(g) + " preset"; kind = 0; }
        pendingMessage = "";
        LinearLayout st = u.status(msg, kind);
        st.setGravity(Gravity.CENTER);
        ((LinearLayout.LayoutParams) st.getChildAt(1).getLayoutParams()).weight = 0;
        st.getChildAt(1).getLayoutParams().width = -2;
        left.addView(st, new LinearLayout.LayoutParams(-1, -2));
        showCrashIfAny(left);
        File error = new File(getFilesDir(), "last-error.txt");
        if (error.isFile()) { left.addView(u.status("Game could not start: " + Shell.read(error), 2)); error.delete(); }

        LinearLayout buttons = u.column();
        int side = land ? u.dp(8) : 0;
        buttons.setPadding(side, 0, side, 0);
        buttons.addView(u.hero("Play", () -> { if (g != null) play(index, false); else addGame(); }));
        buttons.addView(u.split(
            u.secondary("Settings", R.drawable.ic_tune, () -> { if (g != null) showSettings(index); else addGame(); }),
            u.secondary("Controls", R.drawable.ic_sports_esports, this::showControls)));
        buttons.addView(u.split(
            u.secondary("App", R.drawable.ic_settings, this::showAppSettings),
            u.secondary("About", R.drawable.ic_info, this::showAbout)));
        left.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        // ---- right: the game panel
        if (g != null && coverFile(g).isFile()) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(BitmapFactory.decodeFile(coverFile(g).getAbsolutePath()));
            iv.setBackground(u.round(Ui.SURFACE2, 14, 0));
            iv.setClipToOutline(true);
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, u.dp(compact ? 96 : 140));
            cl.bottomMargin = u.dp(10);
            right.addView(iv, cl);
            iv.setOnLongClickListener(v -> { quickActions(index); return true; });
        }
        LinearLayout head = u.row();
        head.addView(u.heading(g == null ? "Add your game" : g.optString("name", "Your game")), new LinearLayout.LayoutParams(0, -2, 1f));
        if (g != null) head.addView(u.pill(ready(g) ? "Ready" : "Setup", ready(g) ? Ui.OK : Ui.WARN));
        right.addView(head);
        if (games.length() > 1) right.addView(gameSwitcher(games, s));
        if (g == null) {
            right.addView(u.body("Use your own extracted copy. The folder name doesn't matter: the app recognises the game by its files."));
            right.addView(u.primary("Add game", R.drawable.ic_add, this::addGame));
            return;
        }
        boolean ok = ready(g);
        right.addView(u.info("Folder", g.optString("root").isEmpty() ? "Not selected" : g.optString("root")));
        statusRow = u.status(ok ? "Game files look right" : "Not ready yet", ok ? 0 : 1);
        if (ok) {
            String problem = Shell.buildProblem(this, new File(g.optString("root")));
            if (problem != null) Ui.setStatus(statusRow, problem, 1);
        }
        right.addView(statusRow);
        if (!Shell.hasAccess()) right.addView(u.primary("Allow file access", R.drawable.ic_folder_open, this::grantAccess));
        right.addView(u.split(
            u.secondary("Choose", R.drawable.ic_folder_open, () -> chooseFolder(index)),
            u.secondary("Scan phone", R.drawable.ic_search, () -> locate(null, (r, f) -> applyFound(index, r, f)))));
        right.addView(u.divider());
        right.addView(u.info("Performance", presetName(g)));
        right.addView(u.segmented(Shell.PRESET_NAMES, g.optInt("preset", -1), pi -> { Shell.applyPreset(g, pi); putGame(index, g); u.buzz(1); showHome(); }));
        right.addView(u.divider());
        right.addView(u.info("Controls", profileLabel(g.optString("profile", Shell.BUILTIN))));
        right.addView(u.info("Last played", ago(g.optLong("lastPlayed", 0))));
        right.addView(u.info("Last backup", ago(prefs().getLong("lastBackup", 0))));
        right.addView(u.split(
            u.secondary("Check", R.drawable.ic_fact_check, () -> verify(false)),
            u.secondary("Back up", R.drawable.ic_backup, () -> setStatus(Shell.backupSaves(this), 0)),
            u.secondary("More", R.drawable.ic_layers, () -> quickActions(index))));
    }

    private View gameSwitcher(JSONArray games, int current) {
        LinearLayout row = u.row();
        row.setPadding(0, u.dp(6), 0, u.dp(4));
        for (int i = 0; i < games.length(); i++) {
            final int idx = i;
            JSONObject g = games.optJSONObject(i);
            TextView chip = u.text(g == null ? "Game" : g.optString("name", "Game"), 14, i == current ? u.onAccent() : Ui.TEXT, true);
            chip.setBackground(u.round(i == current ? u.accent : Ui.SURFACE2, 14, 0));
            chip.setPadding(u.dp(14), u.dp(8), u.dp(14), u.dp(8));
            chip.setOnClickListener(v -> { prefs().edit().putInt("sel", idx).apply(); u.buzz(0); showHome(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = u.dp(8);
            row.addView(chip, lp);
        }
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.addView(row);
        return hs;
    }

    private void addGame() {
        try {
            JSONArray a = loadGames();
            JSONObject g = Shell.defaultGame(a.length() == 0 ? "GTA V" : "GTA V #" + (a.length() + 1), prefs().getString("path", Shell.DEFAULT_PATH));
            Shell.autoConfigure(this, g);
            a.put(g); saveGames(a);
            prefs().edit().putInt("sel", a.length() - 1).apply();
            showHome();
        } catch (Exception e) { toast("Could not add: " + e.getMessage()); }
    }

    private void quickActions(final int index) {
        final JSONObject g = game(index);
        if (g == null) return;
        final boolean hasCover = coverFile(g).isFile();
        List<String> items = new ArrayList<>(Arrays.asList("Rename", hasCover ? "Change cover image" : "Set cover image"));
        if (hasCover) items.add("Remove cover image");
        items.add("Add another game");
        items.add("Remove from library");
        final String[] arr = items.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(g.optString("name")).setItems(arr, (d, which) -> {
            switch (arr[which]) {
                case "Rename": {
                    final EditText in = u.input(g.optString("name"), "Name");
                    new AlertDialog.Builder(this).setTitle("Rename").setView(in).setNegativeButton("Cancel", null)
                        .setPositiveButton("Save", (x, y) -> { try { g.put("name", in.getText().toString().trim()); putGame(index, g); showHome(); } catch (Exception ignored) { } }).show();
                    break;
                }
                case "Set cover image": case "Change cover image":
                    coverTarget = index;
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_COVER);
                    break;
                case "Remove cover image": coverFile(g).delete(); showHome(); break;
                case "Add another game": addGame(); break;
                case "Remove from library": confirmDelete(index); break;
                default: break;
            }
        }).show();
    }

    private void confirmDelete(final int index) {
        new AlertDialog.Builder(this).setMessage("Remove this game from the library? Your game files and saves are not touched.")
            .setNegativeButton("Cancel", null).setPositiveButton("Remove", (d, w) -> {
                JSONArray a = loadGames(); JSONArray b = new JSONArray();
                for (int i = 0; i < a.length(); i++) if (i != index) b.put(a.opt(i));
                saveGames(b);
                prefs().edit().putInt("sel", 0).apply();
                u.buzz(1); showHome();
            }).show();
    }

    private void showCrashIfAny(LinearLayout p) {
        final File crash = new File(getFilesDir(), "crash.txt");
        if (!crash.isFile()) return;
        final String text = Shell.read(crash);
        LinearLayout c = u.card();
        c.addView(u.status("The app crashed last time", 2));
        TextView t = u.body(text); u.ts(t, 10); t.setTextIsSelectable(true); t.setMaxLines(8);
        c.addView(t);
        c.addView(u.split(
            u.secondary("Copy details", R.drawable.ic_content_copy, () -> { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("crash", text)); toast("Copied"); }),
            u.secondary("Dismiss", R.drawable.ic_close, () -> { crash.delete(); showHome(); })));
        p.addView(c);
    }

    // ================================================================ controls page (Winlator-style profiles)
    private void showControls() {
        page = P_CONTROLS; editing = -1;
        u = Ui.of(this);
        final int s = sel();
        final JSONObject g = game(s);
        final String inUse = g == null ? Shell.BUILTIN : g.optString("profile", Shell.BUILTIN);
        LinearLayout p = u.page(this);
        p.addView(u.title("Controls"));
        p.addView(u.subtitle("Touch layouts in Winlator's .icp format. Tap Use to pick one" + (g == null ? "." : " for " + g.optString("name") + ".")));
        p.addView(u.split(
            u.primary("New layout", R.drawable.ic_add, () -> startActivityForResult(new Intent(this, EditorActivity.class), REQ_EDIT)),
            u.secondary("Import .icp", R.drawable.ic_folder_open, () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_IMPORT))));
        LinearLayout list = u.column();
        list.setPadding(0, u.dp(10), 0, 0);
        for (final String name : Shell.listProfiles(this)) {
            LinearLayout c = u.card();
            LinearLayout head = u.row();
            TextView t = u.text(profileLabel(name), 19, Ui.TEXT, true);
            t.setTypeface(u.heavy());
            head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
            if (name.equals(inUse)) head.addView(u.pill("In use", Ui.OK));
            c.addView(head);
            int count = -1;
            if (!name.equals(Shell.BUILTIN)) {
                try { count = new JSONObject(Shell.read(new File(Shell.profileDir(this), name + ".icp"))).getJSONArray("elements").length(); } catch (Exception ignored) { }
            }
            c.addView(u.body(name.equals(Shell.BUILTIN) ? "The runtime's own simple layout." : (count >= 0 ? count + " elements" : "Could not read this file")));
            Button use = u.secondary("Use", R.drawable.ic_check_circle, () -> {
                if (g == null) { toast("Add a game first."); return; }
                try { g.put("profile", name); putGame(s, g); u.buzz(1); showControls(); } catch (Exception ignored) { }
            });
            if (name.equals(Shell.BUILTIN)) c.addView(use);
            else c.addView(u.split(use,
                u.secondary("Edit", R.drawable.ic_edit, () -> startActivityForResult(new Intent(this, EditorActivity.class).putExtra("profile", name), REQ_EDIT)),
                u.secondary("Copy", R.drawable.ic_content_copy, () -> startActivityForResult(new Intent(this, EditorActivity.class).putExtra("profile", name).putExtra("copy", true), REQ_EDIT))));
            list.addView(c);
        }
        p.addView(list);
        Ui.stagger(list, this);
        p.addView(u.secondary("Back", R.drawable.ic_arrow_back, this::showHome));
    }

    // ================================================================ per-game settings
    private void showSettings(int index) {
        JSONArray games = loadGames();
        draft = games.optJSONObject(index);
        if (draft == null) { showHome(); return; }
        page = P_SETTINGS; editing = index;
        prefs().edit().putInt("sel", index).apply();
        u = Ui.of(this);
        LinearLayout p = u.page(this);
        LinearLayout top = u.row();
        LinearLayout titles = u.column();
        titles.addView(u.title(draft.optString("name", "Game")));
        titles.addView(u.subtitle("Game settings"));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        Button playTop = u.primary("Play", R.drawable.ic_play_arrow, () -> { collect(); saveDraft(); play(editing, false); });
        top.addView(playTop, new LinearLayout.LayoutParams(u.dp(120), -2));
        p.addView(top);

        // ---- game
        p.addView(u.section("Game"));
        LinearLayout gc = u.card();
        gc.addView(u.label("Name"));
        etName = u.input(draft.optString("name"), "Game name"); gc.addView(etName);
        gc.addView(u.label("Game folder"));
        etPath = u.input(draft.optString("path"), "/storage/emulated/0/Download/..."); gc.addView(etPath);
        gc.addView(u.split(
            u.secondary("Choose folder", R.drawable.ic_folder_open, () -> { collect(); saveDraft(); chooseFolder(editing); }),
            u.secondary("Scan phone", R.drawable.ic_search, () -> { collect(); saveDraft(); locate(null, (r, f) -> applyFound(editing, r, f)); })));
        gc.addView(u.secondary("Search the typed folder", R.drawable.ic_search, () -> { collect(); saveDraft(); locate(new File(draft.optString("path")), (r, f) -> applyFound(editing, r, f)); }));
        statusRow = u.status("", 3);
        gc.addView(u.spacer(4));
        gc.addView(statusRow);
        gc.addView(u.label("Start in"));
        spMode = u.spinner(Shell.MODE_NAMES, draft.optInt("mode", 1)); gc.addView(spMode);
        p.addView(gc);

        // ---- performance
        p.addView(u.section("Performance"));
        LinearLayout pc = u.card();
        presetChoice = draft.optInt("preset", -1);
        pc.addView(u.choices(Shell.PRESET_NAMES, Shell.PRESET_DESCS, presetChoice, Shell.recommendedPreset(this), i -> {
            presetChoice = i;
            int[] pr = Shell.PRESETS[i];
            spQuality.setSelection(pr[0]); spFps.setSelection(pr[1]); spTex.setSelection(pr[2]); spRes.setSelection(0);
        }));
        pc.addView(u.label("Render scale (lower = faster)"));
        spQuality = u.spinner(Shell.QUALITY_NAMES, draft.optInt("quality", 1)); pc.addView(spQuality);
        pc.addView(u.label("Frame rate"));
        spFps = u.spinner(Shell.FPS_NAMES, draft.optInt("fps", 0)); pc.addView(spFps);
        pc.addView(u.label("Texture quality"));
        spTex = u.spinner(Shell.TEX_NAMES, draft.optInt("texture", 0)); pc.addView(spTex);
        pc.addView(u.label("Fixed internal resolution (advanced)"));
        spRes = u.spinner(Shell.RES_NAMES, draft.optInt("res", 0)); pc.addView(spRes);
        pc.addView(u.split(
            u.secondary("Auto-configure", R.drawable.ic_auto_fix_high, () -> { collect(); pendingMessage = Shell.autoConfigure(this, draft); saveDraft(); showSettings(editing); }),
            u.secondary("Reset all", R.drawable.ic_restart_alt, () -> new AlertDialog.Builder(this).setMessage("Reset this game's settings to the defaults? The game folder is kept.")
                .setNegativeButton("Cancel", null).setPositiveButton("Reset", (d, w) -> {
                    try {
                        JSONObject fresh = Shell.defaultGame(draft.optString("name", "GTA V"), draft.optString("path"));
                        fresh.put("root", draft.optString("root")); fresh.put("id", draft.optString("id"));
                        Shell.autoConfigure(this, fresh);
                        draft = fresh; putGame(editing, draft);
                        pendingMessage = "Settings reset to defaults."; showSettings(editing);
                    } catch (Exception e) { toast("Reset failed: " + e.getMessage()); }
                }).show())));
        p.addView(pc);

        // ---- controls
        p.addView(u.section("Touch controls"));
        LinearLayout cc = u.card();
        profileNames = Shell.listProfiles(this);
        String[] shown = new String[profileNames.size()];
        for (int i = 0; i < shown.length; i++) shown[i] = profileLabel(profileNames.get(i));
        cc.addView(u.label("Layout"));
        spProfile = u.spinner(shown, Math.max(0, profileNames.indexOf(draft.optString("profile", "FPS")))); cc.addView(spProfile);
        cc.addView(u.split(u.secondary("Edit layout", R.drawable.ic_edit, () -> openEditor(false)), u.secondary("All layouts", R.drawable.ic_sports_esports, () -> { collect(); saveDraft(); showControls(); })));
        SeekBar[] tmp = new SeekBar[1];
        cc.addView(u.slider("Controls opacity", 85, (int) Math.round(draft.optDouble("opacity", 0.9) * 100) - 15, v -> (v + 15) + "%", tmp)); sbOpacity = tmp[0];
        cc.addView(u.slider("Touch look speed", 40, (int) Math.round(draft.optDouble("touch", 1.0) * 10) - 1, v -> String.format(Locale.US, "%.1fx", (v + 1) / 10.0), tmp)); sbTouch = tmp[0];
        p.addView(cc);

        // ---- gamepad
        p.addView(u.section("Gamepad"));
        LinearLayout pad = u.card();
        pad.addView(u.body("USB and Bluetooth controllers work as soon as they're connected. Remap any button below."));
        pad.addView(u.slider("Stick deadzone", 50, draft.optInt("padDead", 20), v -> v + "%", tmp)); sbDead = tmp[0];
        pad.addView(u.slider("Right-stick look speed", 30, draft.optInt("padLook", 10), v -> String.format(Locale.US, "%.1fx", v / 10.0), tmp)); sbLook = tmp[0];
        pad.addView(u.secondary("Remap gamepad buttons", R.drawable.ic_gamepad, this::remapDialog));
        p.addView(pad);

        // ---- hud & sound
        p.addView(u.section("HUD and sound"));
        LinearLayout hs = u.card();
        hs.addView(u.label("In-game HUD"));
        spHud = u.spinner(HUD_NAMES, draft.optInt("hud", 0)); hs.addView(spHud);
        swMute = u.toggle("Mute game audio", draft.optBoolean("mute")); hs.addView(swMute);
        p.addView(hs);

        // ---- multiplayer
        p.addView(u.section("Multiplayer (experimental)"));
        LinearLayout mp = u.card();
        swMp = u.toggle("Multiplayer", draft.optBoolean("mp")); mp.addView(swMp);
        swHost = u.toggle("Host a session", draft.optBoolean("host")); mp.addView(swHost);
        etServer = u.input(draft.optString("server"), "Server address (host:47520)"); mp.addView(etServer);
        mp.addView(u.spacer(8));
        etPlayer = u.input(draft.optString("player"), "Player name"); mp.addView(etPlayer);
        p.addView(mp);

        // ---- tools
        p.addView(u.section("Tools and data"));
        LinearLayout tc = u.card();
        tc.addView(u.body("Backups of saves and layouts live in Documents/GTA5NativeData. Last backup: " + ago(prefs().getLong("lastBackup", 0)) + "."));
        tc.addView(u.split(u.secondary("Quick check", R.drawable.ic_fact_check, () -> { collect(); saveDraft(); verify(false); }), u.secondary("Full verify", R.drawable.ic_verified, () -> { collect(); saveDraft(); verify(true); })));
        tc.addView(u.split(
            u.secondary("Back up saves", R.drawable.ic_backup, () -> { String m = Shell.backupSaves(this); setStatus(m, m.startsWith("Backed") || m.startsWith("Backup is") ? 0 : 2); }),
            u.secondary("Restore saves", R.drawable.ic_restore, () -> new AlertDialog.Builder(this).setMessage("Replace current saves with the backup?")
                .setNegativeButton("Cancel", null).setPositiveButton("Restore", (d, w) -> { String m = Shell.restoreSaves(this); setStatus(m, m.startsWith("Restored") ? 0 : 2); }).show())));
        tc.addView(u.secondary("Clear graphics and temp data", R.drawable.ic_cleaning_services, () -> new AlertDialog.Builder(this)
            .setMessage("Delete the compile cache and temp files? This can fix crashes, blank screens and lag. Saves are kept. The next launch takes several minutes.")
            .setNegativeButton("Cancel", null).setPositiveButton("Clear", (d, w) -> setStatus(Shell.clearGraphicsAndTemp(this), 0)).show()));
        tc.addView(u.secondary("Export log to Downloads", R.drawable.ic_description, this::exportLog));
        p.addView(tc);

        p.addView(u.primary("Save and play", R.drawable.ic_play_arrow, () -> { collect(); saveDraft(); play(editing, false); }));
        p.addView(u.split(u.secondary("Save and go back", R.drawable.ic_arrow_back, () -> { collect(); saveDraft(); showHome(); }), u.danger("Remove game", R.drawable.ic_delete, () -> confirmDelete(editing))));
        refreshStatus();
    }

    private void setStatus(String s, int kind) {
        if (statusRow != null && statusRow.isAttachedToWindow()) { Ui.setStatus(statusRow, s, kind); u.buzz(kind == 2 ? 2 : kind == 0 ? 1 : 0); }
        else toast(s);
    }

    private void collect() {
        if (draft == null || etName == null || page != P_SETTINGS) return;
        try {
            String name = etName.getText().toString().trim();
            draft.put("name", name.isEmpty() ? "GTA V" : name);
            draft.put("path", etPath.getText().toString().trim());
            draft.put("mode", spMode.getSelectedItemPosition());
            draft.put("quality", spQuality.getSelectedItemPosition());
            draft.put("fps", spFps.getSelectedItemPosition());
            draft.put("texture", spTex.getSelectedItemPosition());
            draft.put("res", spRes.getSelectedItemPosition());
            int preset = -1;
            for (int i = 0; i < Shell.PRESETS.length; i++) {
                int[] pr = Shell.PRESETS[i];
                if (pr[0] == spQuality.getSelectedItemPosition() && pr[1] == spFps.getSelectedItemPosition() && pr[2] == spTex.getSelectedItemPosition() && spRes.getSelectedItemPosition() == 0) preset = i;
            }
            draft.put("preset", preset);
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
            putGame(editing, draft);
            prefs().edit().putString("path", draft.optString("path")).apply();
        } catch (Exception ignored) { }
    }

    private void refreshStatus() {
        if (statusRow == null || draft == null || page != P_SETTINGS) return;
        String root = draft.optString("root");
        String msg; int kind;
        if (!Shell.hasAccess()) { msg = "File access is off. Tap Choose folder to allow it."; kind = 1; }
        else if (!root.isEmpty() && Shell.validRoot(new File(root))) {
            String problem = Shell.buildProblem(this, new File(root));
            msg = problem == null ? "Game found: " + root : problem; kind = problem == null ? 0 : 1;
        } else { msg = "Game not found yet. Choose the folder or scan your phone."; kind = 1; }
        if (!pendingMessage.isEmpty()) { msg = pendingMessage + "\n" + msg; pendingMessage = ""; }
        Ui.setStatus(statusRow, msg, kind);
    }

    // ================================================================ app settings
    private void showAppSettings() {
        page = P_APP;
        u = Ui.of(this);
        LinearLayout p = u.page(this);
        p.addView(u.title("App settings"));
        p.addView(u.subtitle("Look, feel and safety nets"));

        p.addView(u.section("Accent colour"));
        LinearLayout ac = u.card();
        LinearLayout swatches = u.row();
        int cur = prefs().getInt("accent", 0);
        for (int i = 0; i < Ui.ACCENTS.length; i++) {
            View dot = new View(this);
            dot.setBackground(u.round(Ui.ACCENTS[i], 16, 0));
            if (i == cur) ((android.graphics.drawable.GradientDrawable) dot.getBackground()).setStroke(u.dp(3), Ui.TEXT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(u.dp(46), u.dp(46));
            lp.rightMargin = u.dp(12);
            final int idx = i;
            dot.setOnClickListener(v -> { prefs().edit().putInt("accent", idx).apply(); Ui.buzz(this, 1); showAppSettings(); });
            swatches.addView(dot, lp);
        }
        ac.addView(swatches);
        ac.addView(u.spacer(8));
        ac.addView(u.body(Ui.ACCENT_NAMES[Math.max(0, Math.min(Ui.ACCENT_NAMES.length - 1, cur))]));
        p.addView(ac);

        p.addView(u.section("Feel"));
        LinearLayout fc = u.card();
        Switch hap = u.toggle("Vibration (buttons and touch controls)", prefs().getBoolean("haptics", true));
        hap.setOnCheckedChangeListener((b, on) -> { prefs().edit().putBoolean("haptics", on).apply(); if (on) Ui.buzz(this, 1); });
        fc.addView(hap);
        p.addView(fc);

        p.addView(u.section("Safety nets"));
        LinearLayout sc = u.card();
        Switch auto = u.toggle("Back up saves automatically after playing", prefs().getBoolean("autoBackup", true));
        auto.setOnCheckedChangeListener((b, on) -> prefs().edit().putBoolean("autoBackup", on).apply());
        sc.addView(auto);
        SeekBar[] tmp = new SeekBar[1];
        sc.addView(u.slider("Freeze detector", 30, prefs().getInt("freezeSec", 12), v -> v == 0 ? "off" : v + " s without frames", tmp));
        final SeekBar fs = tmp[0];
        fs.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int v, boolean user) { prefs().edit().putInt("freezeSec", v == 0 ? 0 : Math.max(5, v)).apply(); }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { u.buzz(0); }
        });
        TextView fsHelp = u.body("When the picture stops but sound keeps playing, the app offers a one-tap restart and saves a report.");
        u.ts(fsHelp, 12);
        sc.addView(fsHelp);
        p.addView(sc);

        p.addView(u.section("Setup"));
        LinearLayout su = u.card();
        su.addView(u.secondary("Run the setup wizard again", R.drawable.ic_auto_fix_high, () -> { wizardGame = null; wizardPreset = -1; showWizard(0); }));
        su.addView(u.secondary("About this app", R.drawable.ic_info, this::showAbout));
        p.addView(su);
        p.addView(u.primary("Done", R.drawable.ic_check_circle, this::showHome));
    }

    // ================================================================ access
    private void grantAccess() {
        if (Shell.hasAccess()) { toast("File access is already on."); return; }
        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    // ================================================================ profiles, covers, folders
    private void openEditor(boolean copy) {
        collect(); saveDraft();
        String name = profileNames.get(Math.min(spProfile.getSelectedItemPosition(), profileNames.size() - 1));
        if (name.equals(Shell.BUILTIN)) { toast("Pick a layout first, or open All layouts."); return; }
        startActivityForResult(new Intent(this, EditorActivity.class).putExtra("profile", name).putExtra("copy", copy), REQ_EDIT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQ_TREE) {
            final int target = treeTarget;
            treeTarget = -1;
            if (result != RESULT_OK || data == null || data.getData() == null) return;
            File picked = Shell.treeToFile(data.getData());
            if (picked == null || !picked.isDirectory()) { toast("Couldn't open that folder. Try Scan phone."); return; }
            locate(picked, (r, f) -> applyFound(target, r, f));
            return;
        }
        if (request == REQ_COVER) {
            if (result == RESULT_OK && data != null && data.getData() != null && coverTarget >= 0) saveCover(coverTarget, data.getData());
            coverTarget = -1;
            showHome();
            return;
        }
        if (request == REQ_EDIT) {
            if (page == P_SETTINGS && result == RESULT_OK && data != null && data.getStringExtra("profile") != null && draft != null) {
                try { draft.put("profile", data.getStringExtra("profile")); } catch (Exception ignored) { }
                saveDraft();
            }
            Shell.backupProfiles(this);
            rebuild();
            return;
        }
        if (request != REQ_IMPORT || result != RESULT_OK || data == null) { rebuild(); return; }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        int ok = 0;
        for (Uri uri : uris) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream(); Shell.copy(in, buf);
                JSONObject json = new JSONObject(new String(buf.toByteArray(), StandardCharsets.UTF_8));
                if (json.optJSONArray("elements") == null) throw new IOException("not a controls profile");
                String base = displayName(uri).replaceAll("(?i)\\.icp$", "").replaceAll("[^A-Za-z0-9 ._()\\-]", "_");
                if (base.isEmpty()) base = json.optString("name", "profile");
                try (FileOutputStream out = new FileOutputStream(new File(Shell.profileDir(this), base + ".icp"))) { out.write(buf.toByteArray()); }
                ok++;
            } catch (Exception e) { toast("Skipped a file: " + e.getMessage()); }
        }
        toast("Imported " + ok + " layout(s)");
        Shell.backupProfiles(this);
        rebuild();
    }

    private void saveCover(int index, Uri uri) {
        JSONObject g = game(index);
        if (g == null) return;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
            int sample = 1;
            while (o.outWidth / sample > 1600) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
            if (bmp == null) throw new IOException("not an image");
            try (FileOutputStream out = new FileOutputStream(coverFile(g))) { bmp.compress(Bitmap.CompressFormat.JPEG, 85, out); }
            u.buzz(1);
        } catch (Exception e) { toast("Could not use that image: " + e.getMessage()); }
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
        final String[] labels = Shell.bindingLabels(choices);
        JSONObject map = draft.optJSONObject("padmap");
        if (map == null) map = new JSONObject();
        LinearLayout col = u.column();
        col.setPadding(u.dp(18), u.dp(8), u.dp(18), u.dp(8));
        final Spinner[] spinners = new Spinner[PAD_INPUTS.length];
        for (int i = 0; i < PAD_INPUTS.length; i++) {
            String input = PAD_INPUTS[i];
            String def = input.startsWith("DPAD_") ? "GAMEPAD_" + input : "GAMEPAD_BUTTON_" + input;
            col.addView(u.label(input.replace('_', ' ')));
            String cur = map.optString(input, def);
            Spinner s = u.spinner(labels, Math.max(0, Arrays.asList(choices).indexOf(cur)));
            spinners[i] = s; col.addView(s);
        }
        ScrollView sv = new ScrollView(this); sv.addView(col);
        new AlertDialog.Builder(this).setTitle("Gamepad buttons").setView(sv)
            .setPositiveButton("Save", (d, w) -> {
                try {
                    JSONObject out = new JSONObject();
                    for (int i = 0; i < PAD_INPUTS.length; i++) out.put(PAD_INPUTS[i], choices[spinners[i].getSelectedItemPosition()]);
                    draft.put("padmap", out); saveDraft(); toast("Gamepad mapping saved"); u.buzz(1);
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
                os.write(("Game Shell Native " + VERSION + " on " + Shell.socName() + ", Android " + android.os.Build.VERSION.RELEASE + "\n").getBytes(StandardCharsets.UTF_8));
                for (String n : new String[]{"runtime.log", "last-error.txt", "crash.txt", "config.json", "ui.json", "game_dir.txt"}) {
                    File f = new File(getFilesDir(), n);
                    os.write(("\n===== " + n + (f.isFile() ? " (" + f.length() + " bytes)" : " (missing)") + " =====\n").getBytes(StandardCharsets.UTF_8));
                    if (f.isFile()) try (InputStream in = new FileInputStream(f)) { Shell.copy(in, os); }
                }
            }
            setStatus("Log saved to Downloads/GameShell-runtime.log", 0);
        } catch (Exception e) { toast("Export failed: " + e.getMessage()); }
    }

    private void verify(final boolean hashes) {
        if (!Shell.hasAccess()) { setStatus("Allow file access first.", 1); return; }
        final File root = draft == null || draft.optString("root").isEmpty() ? null : new File(draft.optString("root"));
        if (root == null || !Shell.validRoot(root)) { setStatus("Find the game folder first.", 1); return; }
        setStatus(hashes ? "Hashing every file (several minutes)..." : "Checking files...", 3);
        final LinearLayout row = statusRow;
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
                    if (i % 150 == 0) {
                        final int done = i;
                        runOnUiThread(() -> Ui.setStatus(row, (hashes ? "Hashing " : "Checking ") + done + " / " + files.length(), 3));
                    }
                }
                final boolean ok = badParts.isEmpty();
                final String summary = (hashes ? "Full verify" : "Quick check") + ": " + files.length() + " files. Missing " + missing + ", wrong size " + badSize + (hashes ? ", bad hash " + badHash : "")
                    + (ok ? ". All good." : ".\nAffected zip parts: " + badParts + "\nList: Downloads/GameShell-verify.txt");
                try (FileWriter w = new FileWriter(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "GameShell-verify.txt"))) { w.write(summary + "\n\n" + report); } catch (IOException ignored) { }
                runOnUiThread(() -> { Ui.setStatus(row, summary, ok ? 0 : 2); u.buzz(ok ? 1 : 2); });
            } catch (Exception e) { runOnUiThread(() -> Ui.setStatus(row, "Verify failed: " + e, 2)); }
        }, "verify").start();
    }

    // ================================================================ about
    private void showAbout() {
        page = P_ABOUT;
        u = Ui.of(this);
        LinearLayout p = u.page(this);
        p.addView(u.logo(u.landscape() ? 0.8f : 0.6f));
        p.addView(u.spacer(10));
        LinearLayout dev = u.card();
        dev.addView(u.text("DEVELOPER", 13, Ui.MUTED, true));
        TextView name = u.text("beworkall", 30, Ui.TEXT, true);
        name.setTypeface(u.display());
        dev.addView(name);
        dev.addView(u.body("Game Shell Native " + VERSION + ". A native Android launcher and runtime for game files you already own. It contains no game files and downloads nothing."));
        p.addView(dev);
        p.addView(u.section("Credits"));
        LinearLayout cr = u.card();
        cr.addView(u.body("• Runtime: GTAV Mobile by alihammoud21, based on Muguet by Constantin Clerc (c22dev). Original notices are kept in the source.\n\n"
            + "• The browser port of the game and its data format: the PlayGTA5 project's authors.\n\n"
            + "• Controls: profile format, control icons, the GTA 5 layout and the editor design come from Winlator by brunodev85 (LGPL-2.1).\n\n"
            + "• Fonts: Barlow Condensed and Inter (SIL Open Font License). Interface icons: Material Icons by Google (Apache 2.0).\n\n"
            + "• Grand Theft Auto V and its content belong to Rockstar Games / Take-Two Interactive. This app is not affiliated with or endorsed by them."));
        p.addView(cr);
        p.addView(u.section("License"));
        LinearLayout li = u.card();
        li.addView(u.body("Free software under the GNU GPL v3 or later: you may study, change and share it under the same license.\nSource: " + SOURCE_URL));
        li.addView(u.split(
            u.secondary("Licenses", R.drawable.ic_description, this::showLicenses),
            u.secondary("Source page", R.drawable.ic_help, () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))))));
        p.addView(li);
        p.addView(u.primary("Back", R.drawable.ic_arrow_back, this::showHome));
    }

    private void showLicenses() {
        StringBuilder all = new StringBuilder();
        String[][] files = {{"GNU GPL v3 (this app)", "LICENSE.txt"}, {"SIL Open Font License (fonts)", "fonts/OFL.txt"}, {"Apache 2.0 (Material Icons)", "icons/MaterialIcons-Apache-2.0.txt"}, {"GNU LGPL 2.1 (Winlator icons and layout)", "icons/LGPL-2.1.txt"}};
        for (String[] f : files) {
            all.append("==================== ").append(f[0]).append(" ====================\n\n");
            try (InputStream in = getAssets().open(f[1])) { ByteArrayOutputStream b = new ByteArrayOutputStream(); Shell.copy(in, b); all.append(b.toString("UTF-8")); }
            catch (IOException e) { all.append("(not bundled)"); }
            all.append("\n\n");
        }
        TextView t = u.body(all.toString()); u.ts(t, 10); t.setPadding(u.dp(14), u.dp(14), u.dp(14), u.dp(14)); t.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this); sv.addView(t);
        new AlertDialog.Builder(this).setTitle("Licenses").setView(sv).setPositiveButton("Close", null).show();
    }

    // ================================================================ play
    /** Kills a leftover :game process (same app, so this is allowed). Returns true if one was running. */
    static boolean killGameProcess(Context c) {
        boolean killed = false;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> list = am == null ? null : am.getRunningAppProcesses();
            if (list != null) for (android.app.ActivityManager.RunningAppProcessInfo p : list) {
                if (p.processName != null && p.processName.endsWith(":game") && p.pid != android.os.Process.myPid()) {
                    android.os.Process.killProcess(p.pid);
                    killed = true;
                }
            }
        } catch (Exception ignored) { }
        return killed;
    }

    private void play(final int index, boolean skipChecks) {
        final JSONObject g = game(index);
        if (g == null) return;
        if (!Shell.hasAccess()) { toast("Allow file access first."); grantAccess(); return; }
        File root = g.optString("root").isEmpty() ? null : new File(g.optString("root"));
        if (root == null || !Shell.validRoot(root)) root = Shell.findGame(new File(g.optString("path")), 6, null);
        if (root == null) { toast("Game folder not found. Choose it or scan your phone."); u.buzz(2); return; }
        if (g.optBoolean("mp") && !g.optBoolean("host") && g.optString("server").trim().isEmpty()) { toast("Enter a server address, or turn on Host a session."); return; }
        final File gameRoot = root;
        String problem = skipChecks ? null : Shell.buildProblem(this, root);
        if (problem != null) {
            u.buzz(2);
            new AlertDialog.Builder(this).setTitle("Different game build").setMessage(problem + "\n\nPlay anyway?")
                .setNegativeButton("Cancel", null).setPositiveButton("Play anyway", (d, w) -> launch(index, g, gameRoot)).show();
            return;
        }
        launch(index, g, gameRoot);
    }

    private void launch(int index, JSONObject g, File root) {
        try {
            g.put("root", root.getAbsolutePath());
            g.put("lastPlayed", System.currentTimeMillis());
            putGame(index, g);
            prefs().edit().putInt("sel", index).putBoolean("inGame", true).apply();
            Shell.write(new File(getFilesDir(), "game_dir.txt"), root.getAbsolutePath());

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
            ui.put("accent", prefs().getInt("accent", 0));
            ui.put("haptics", prefs().getBoolean("haptics", true));
            ui.put("freezeSec", prefs().getInt("freezeSec", 12));
            if (g.optJSONObject("padmap") != null) ui.put("padmap", g.optJSONObject("padmap"));
            Shell.write(new File(getFilesDir(), "ui.json"), ui.toString());
            Shell.write(new File(getFilesDir(), "active_profile.txt"), g.optString("profile", Shell.BUILTIN));
            Shell.backupProfiles(this);
            u.buzz(1);
            // Always start the game in a fresh process; wait a moment if an old one had to be killed.
            if (killGameProcess(this)) new android.os.Handler(getMainLooper()).postDelayed(() -> startActivity(new Intent(this, GameActivity.class)), 700);
            else startActivity(new Intent(this, GameActivity.class));
        } catch (Exception error) { toast("Could not start: " + error.getMessage()); u.buzz(2); }
    }
}
