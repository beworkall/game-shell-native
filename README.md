# Game Shell Native

Native Android runtime (Rust + Wasmtime + wgpu), forked from GTAV Mobile / Muguet (GPL-3.0-or-later).

Differences from upstream:
- Bring your own game files: no downloader. Pick the folder that holds `data/` and `b/` (the app finds it).
- Reads the folder in place (all-files access), so there is no second 16 GB copy.
- Render-scale and FPS-cap presets in the launcher.
- Own application id (installs next to upstream).

## Build
Upload this folder to a PRIVATE GitHub repo (keep `.github`) -> Actions -> android-apk -> Run workflow
-> download the `game-shell-native-apk` artifact. First build is slow (Rust + NDK).

## Use
1. Allow file access. 2. Find my game folder. 3. Play. First launch compiles the game (minutes); the compile cache is reused afterwards.

Experimental: upstream says gameplay on physical phones is not yet verified.
See NOTICE.md for credits and the list of changes.
