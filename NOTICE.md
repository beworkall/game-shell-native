# Notices and credits

GTAV Mobile includes and modifies Muguet by Constantin Clerc (c22dev),
from https://github.com/c22dev/muguet, imported at commit
`a2f781683160993ad15a8a04fbf09e7027a831a6`.

The original code and this derivative are licensed under GPL-3.0-or-later.
The full license is in LICENSE. Original author notices are retained in source.

Modifications dated 2026-10-11 include application branding, an Android launcher
and native runtime integration, platform-specific dependencies and graphics,
Android input and surface lifecycle handling, build scripts, import and memory
validation fixes, checksum-verified game setup, release packaging, regression
tests, a precompiled Xcode USB signing project, and documentation.

Upstream contributor credits:
- Kagander: minimap fixes.
- inj-src: parallel BC texture decoding.
- Dbhardwaj99: free disk-space reporting.
- dre4moff: controller release when focus is lost.

The vendored wgpu-core component retains its own MIT/Apache-2.0 notices.
The Gradle wrapper is from Gradle 8.13 (Apache-2.0); its license is in
android/gradle/LICENSE. Other dependencies retain their respective licenses.

The mobile release integrates game code and downloads matching data from
alihammoud21/playgta5. These assets are separate from the GPL-licensed runtime;
upstream's GAME-DATA.md is not part of this fork. GTA V and associated marks belong to their respective owners.

## Game Shell Native modifications (2026-10-24)

This fork of GTAV Mobile (itself a derivative of Muguet) is licensed GPL-3.0-or-later like its parents.
Changes: the in-app game downloader was removed from the launcher; the launcher now lets you point the
runtime at a game folder you already have (src/config.rs reads game_dir.txt); added all-files access for
that folder; render-scale and frame-rate presets in the launcher; new application id; new CI workflow.
No game files are bundled or hosted by this fork. Original author notices are retained in source.

### v0.2 (2026-10-24)
Launcher rewritten as a saved-games list with a per-game settings page (render scale, frame rate, texture quality,
touch speed, mute, mode, multiplayer, control profile). Added import of Winlator input-control profiles (.icp) and a
renderer for them in GameActivity (buttons, d-pad, stick, trackpad, radial menus, range buttons, toggles, mouse-move
buttons). Added file verification against the bundled manifest and a log exporter. Default profiles FPS, RTS,
Virtual Gamepad and Template (12 buttons) were supplied by the user.

### v0.3 (2026-10-25)
First-run setup wizard; saved games with per-game settings; auto-configuration from device RAM/chip and reset;
a Winlator-style control-layout editor (EditorActivity) and shared profile renderer (ControlView) for .icp files;
remappable gamepad support with deadzones; an in-game slide menu (resume, restart, exit, HUD, live profile switch,
mute, graphics apply/restart, clear cache); clear-graphics-and-temp tool; backups of saves and layouts to
Documents/GTA5NativeData; crash screen; About page; new app icon (original artwork); download links removed from the
bundled file manifest (hashes and sizes are kept for verification). Rust: two small JNI hooks
(nativePresents for the FPS counter, nativeMute for live mute; src/android.rs, src/audio.rs).
Control-profile format and editor ideas were studied from Winlator (LGPL-2.1) by brunodev85; no Winlator code was copied.

### v0.4 (2026-10-27)
New look (OLED black theme with selectable accent colour, rounded cards, press animation and vibration) built
with plain Android views (Ui.java); redesigned launcher with a game library (optional cover images, long-press
quick actions, last played), a 4-step setup wizard with performance presets, per-game settings in sections and an
App settings page. In-game: freeze watchdog (no presented frames while not loading -> restart banner and a freeze
report in Documents/GTA5NativeData/reports), thermal warning, FPS graph in the HUD, on-screen keyboard
(KeyboardView.java), redesigned slide menu, vibration on touch-control presses. Saves are backed up automatically
(incrementally) after playing. Play checks that the folder holds the expected game build. Rust: new JNI hook
nativeKeyText (src/android.rs) so on-screen and hardware keyboards type text into the game (cheat console, text boxes).

## Game Shell Native v0.5 (2026-10-27)
- Launcher, Ui, ControlView and EditorActivity rewritten (two-pane home, folder picker and phone scan, Controls page, Winlator-style editor).
- Bundled fonts: Barlow Condensed and Inter, SIL Open Font License 1.1 (assets/fonts/OFL.txt).
- Interface icons: Material Icons by Google, Apache License 2.0 (assets/icons/MaterialIcons-Apache-2.0.txt), converted to Android vector drawables.
- Control icons (assets/icons/0-17.png) and the "GTA 5 (Winlator)" profile come from Winlator by brunodev85, LGPL-2.1 (assets/icons/LGPL-2.1.txt). The editor's design follows Winlator's InputControlsView / ControlsEditorActivity.
- App icon: original artwork; contains no Rockstar logos or game art.

## Game Shell Native v0.5.1 - v0.5.3 (2026-10-27 / 2026-10-28)
- v0.5.1: the :game process is always ended when the game screen closes, and the launcher clears any leftover
  game process before Play; src/android.rs now exits instead of returning when the runtime was already started
  in the process. Responsive sizing in Ui.java.
- v0.5.2: window-inset handling (edge-to-edge), button-row spacing, centred button icons.
- v0.5.3: renamed the project to game-shell-native (Java package app.webgameshell.nativeshell, crate
  game-shell-native, library libgame_shell_native.so; JNI symbol names updated in src/android.rs). build.rs is
  Android-only. Removed the upstream iOS/macOS projects (ios/, launcher/, make-ios.sh, make-app.sh, build.sh,
  build.command), the PC/iPhone installers (distribution/), the game downloader and release scripts (scripts/), the
  installer tests (tests/) and GAME-DATA.md / docs/VALIDATION.md. The game manifest now lives only in
  android/app/src/main/assets/game-manifest.json (hashes and sizes only, no source location).
  CI builds signed releases and publishes GitHub Releases for v* tags.
