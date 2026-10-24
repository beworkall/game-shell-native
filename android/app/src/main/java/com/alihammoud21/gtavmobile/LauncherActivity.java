package com.alihammoud21.gtavmobile;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.*;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import org.json.JSONObject;

/**
 * Game Shell Native launcher: you point it at a game folder you already have.
 * No downloader. Modified from GTAV Mobile (GPL-3.0-or-later); see NOTICE.md.
 */
public final class LauncherActivity extends Activity {
    private static final String DEFAULT_PATH = "/storage/emulated/0/Download/1GTA";
    private static final String[] QUALITY_NAMES = {"Render scale 0.40 (fastest)", "Render scale 0.50 (default)", "Render scale 0.65", "Render scale 0.80", "Render scale 1.00 (sharpest)"};
    private static final double[] QUALITY = {0.40, 0.50, 0.65, 0.80, 1.00};
    private static final String[] FPS_NAMES = {"30 FPS cap (cooler)", "60 FPS cap", "No cap"};
    private static final int[] FPS = {30, 60, 0};

    private TextView status;
    private Button play;
    private EditText path, server, player;
    private Switch multiplayer, host;
    private Spinner mode, quality, fps;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad * 2, pad, pad);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);

        TextView title = new TextView(this); title.setText("Game Shell Native"); title.setTextSize(30); layout.addView(title);
        TextView intro = new TextView(this);
        intro.setText("Bring your own game files. Pick the folder that contains data/ and b/, then Play.\nThe first launch compiles the game and can take several minutes; later launches reuse the cache.");
        intro.setPadding(0, pad / 2, 0, pad / 2); layout.addView(intro);

        SharedPreferences prefs = getSharedPreferences("shell", MODE_PRIVATE);
        path = new EditText(this); path.setSingleLine(); path.setText(prefs.getString("path", DEFAULT_PATH)); layout.addView(path);

        Button access = new Button(this); access.setText("1. Allow file access"); layout.addView(access);
        access.setOnClickListener(v -> grantAccess());
        Button find = new Button(this); find.setText("2. Find my game folder"); layout.addView(find);
        find.setOnClickListener(v -> findGame());

        status = new TextView(this); status.setPadding(0, pad / 2, 0, pad / 2); layout.addView(status);

        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"Story", "Sandbox"}));
        mode.setSelection(1); layout.addView(mode);
        quality = new Spinner(this);
        quality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, QUALITY_NAMES));
        quality.setSelection(prefs.getInt("quality", 1)); layout.addView(quality);
        fps = new Spinner(this);
        fps.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, FPS_NAMES));
        fps.setSelection(prefs.getInt("fps", 0)); layout.addView(fps);

        multiplayer = new Switch(this); multiplayer.setText("Multiplayer"); layout.addView(multiplayer);
        host = new Switch(this); host.setText("Host a session"); layout.addView(host);
        server = new EditText(this); server.setHint("Server address (host:47520)"); server.setSingleLine(); layout.addView(server);
        player = new EditText(this); player.setHint("Player name"); player.setSingleLine(); layout.addView(player);
        loadSettings();

        play = new Button(this); play.setText("Play"); layout.addView(play);
        play.setOnClickListener(v -> startGame());
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        File error = new File(getFilesDir(), "last-error.txt");
        if (error.isFile()) {
            try {
                status.setText("Game could not start: " + new String(java.nio.file.Files.readAllBytes(error.toPath()), java.nio.charset.StandardCharsets.UTF_8));
                error.delete();
            } catch (IOException ignored) { status.setText("Game could not start. Check runtime.log for details."); }
        } else {
            refresh();
        }
    }

    private boolean hasAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void grantAccess() {
        if (hasAccess()) { refresh(); return; }
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
                if (name.equals("data") || name.equals("b") || name.equals("runtime")) continue; // inside a game root, not a root itself
                queue.add(kid); depth.add(d + 1);
            }
        }
        return null;
    }

    private void findGame() {
        if (!hasAccess()) { status.setText("Allow file access first (button 1)."); return; }
        String p = path.getText().toString().trim();
        getSharedPreferences("shell", MODE_PRIVATE).edit().putString("path", p).apply();
        File root = search(new File(p));
        if (root == null) {
            new File(getFilesDir(), "game_dir.txt").delete();
            status.setText("No game found under:\n" + p + "\nLooking for a folder with data/manifest.json and b/<build>/game.wasm + shaders/index.json.");
        } else {
            try (FileWriter writer = new FileWriter(new File(getFilesDir(), "game_dir.txt"))) { writer.write(root.getAbsolutePath()); }
            catch (IOException e) { status.setText("Could not save the folder: " + e.getMessage()); return; }
            status.setText("Using: " + root.getAbsolutePath());
        }
        refresh();
    }

    private File chosen() {
        try {
            String p = new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(), "game_dir.txt").toPath()), java.nio.charset.StandardCharsets.UTF_8).trim();
            return p.isEmpty() ? null : new File(p);
        } catch (IOException e) { return null; }
    }

    private boolean ready() {
        File g = chosen();
        return g != null && hasAccess() && valid(g);
    }

    private void refresh() {
        File g = chosen();
        if (ready()) {
            status.setText("Ready: " + g.getAbsolutePath());
        } else if (!hasAccess()) {
            status.setText("File access: NO. Tap button 1, allow it, then come back.");
        } else if (g == null) {
            status.setText("Tap button 2 to find your game folder.");
        } else {
            status.setText("Saved folder is no longer valid. Tap button 2 again.");
        }
        play.setEnabled(ready());
    }

    private void loadSettings() {
        try {
            JSONObject config = new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(getFilesDir(), "config.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
            mode.setSelection(config.optInt("start_mode", 2) == 1 ? 0 : 1);
            multiplayer.setChecked(config.optBoolean("multiplayer")); host.setChecked(config.optBoolean("host"));
            server.setText(config.optString("server")); player.setText(config.optString("player_name"));
        } catch (Exception ignored) { /* First launch uses defaults. */ }
    }

    private void startGame() {
        try {
            if (multiplayer.isChecked() && !host.isChecked() && server.getText().toString().trim().isEmpty()) {
                status.setText("Enter a server address, or turn on Host a session."); return;
            }
            getSharedPreferences("shell", MODE_PRIVATE).edit()
                .putInt("quality", quality.getSelectedItemPosition()).putInt("fps", fps.getSelectedItemPosition()).apply();
            JSONObject config = new JSONObject();
            config.put("scale", QUALITY[quality.getSelectedItemPosition()]);
            config.put("fullscreen", true);
            config.put("fps_limit", FPS[fps.getSelectedItemPosition()]);
            config.put("start_mode", mode.getSelectedItemPosition() + 1);
            config.put("multiplayer", multiplayer.isChecked()); config.put("host", host.isChecked());
            config.put("server", server.getText().toString().trim()); config.put("player_name", player.getText().toString().trim());
            try (FileWriter writer = new FileWriter(new File(getFilesDir(), "config.json"))) { writer.write(config.toString()); }
            startActivity(new Intent(this, GameActivity.class));
        } catch (Exception error) { status.setText("Could not start: " + error.getMessage()); }
    }
}
