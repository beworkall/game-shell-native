# Game Shell Native (v0.4)

Native Android runtime (Rust + Wasmtime + wgpu), forked from GTAV Mobile / Muguet (GPL-3.0-or-later).

Differences from upstream:
- Bring your own game files: no downloader. Pick the folder that holds `data/` and `b/` (the app finds it).
- Reads the folder in place (all-files access), so there is no second 16 GB copy.
- Render-scale and FPS-cap presets in the launcher.
- Own application id (installs next to upstream).
- Saved games list + per-game settings page (render scale, FPS, texture quality, touch speed, mute, mode, multiplayer).
- Winlator control profiles: import `.icp` files and pick one per game (FPS, RTS, Virtual Gamepad, Template included).
- Tools: quick/full file verification against the pinned manifest, and "Export log to Downloads".

## Build
Upload this folder to a PRIVATE GitHub repo (keep `.github`) -> Actions -> android-apk -> Run workflow
-> download the `game-shell-native-apk` artifact. First build is slow (Rust + NDK).

## Use
1. Allow file access. 2. Find my game folder. 3. Play. First launch compiles the game (minutes); the compile cache is reused afterwards.

Experimental: upstream says gameplay on physical phones is not yet verified.
See NOTICE.md for credits and the list of changes.

## v0.3 highlights
- First-run wizard: allow file access, pick the main game folder, game is auto-selected, settings auto-configured for your phone.
- Winlator-style control editor: add/move/resize elements, set any key, mouse button or gamepad binding, save/duplicate/import `.icp`.
- Gamepad (USB/Bluetooth): remap every button, stick deadzone, look speed.
- In-game slide menu (Back button or the top-centre button): Resume, Restart, Exit, HUD (FPS / detailed), switch controls live, edit layout, mute, apply graphics, clear cache.
- Tools: quick/full file verification, clear graphics and temp data, back up / restore saves, export log.
- Backups live in `Documents/GTA5NativeData` (saves, layouts) so they survive an uninstall.

## v0.4 highlights
- New OLED-black design with your choice of accent colour, smooth animations and vibration feedback.
- Game library with cover images (long-press a game for quick actions) and a 4-step setup wizard with presets.
- Freeze watchdog: if the picture stops but sound continues, a banner offers a one-tap restart and saves a report.
- On-screen keyboard (in-game menu -> Keyboard) that really types into the game, plus hardware keyboard typing.
- FPS graph HUD, overheating warning, automatic save backups after every session, wrong-build warning on Play.

## v0.5 highlights
- Bold two-pane home: big logo, device line (version · model · SoC · GPU), giant Play button, game panel on the right.
- Real fonts (Barlow Condensed + Inter) and Material vector icons instead of emoji.
- Find the game anywhere: **Choose folder** (Android folder picker) or **Scan phone**. Detection is by contents, not folder name.
- New **Controls** page: Use / Edit / Copy layouts, New, Import .icp.
- Layout editor rebuilt to match Winlator: draggable floating toolbox, element sheet (type, shape, keyboard/mouse/gamepad bindings, scale, opacity, toggle, mouse-move, text, icons, range keys, radial slots), grid snap, autosave.
- New original app icon.
