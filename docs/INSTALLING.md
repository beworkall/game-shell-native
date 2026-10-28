# Installing and setting up

## 1. Install the app
1. Open the repository's **Releases** page and download `GameShellNative.apk` from the latest release.
2. Open the file on your phone. If Android asks, allow "Install unknown apps" for your browser or file manager.
3. Updates: install the new APK over the old one. Your settings, layouts and saves are kept.

> Upgrading from a debug build (before v0.5.3)? Uninstall the old app once. Saves and layouts come back
> automatically from `Documents/GTA5NativeData`.

## 2. Prepare your game folder
You need your own extracted copy of the browser build `8b0b5899ed`. The app doesn't download or link to game files.
The folder must contain:

```
data/manifest.json
b/8b0b5899ed/game.wasm
shaders/index.json
```

It can have any name and be anywhere on the phone (internal storage, Download, SD card).
You need about 17 GB free.

## 3. Setup wizard
1. **Allow file access** (Android's "All files access" screen opens; switch it on and come back).
2. **Find your game:** **Choose folder** opens the Android folder picker, and **Scan phone** searches all storage.
3. **Tune it:** pick a preset (the recommended one is marked for your phone) and a touch layout.
4. Press **PLAY**. The first launch compiles shaders for your GPU and takes a few minutes.

## 4. Check your files (optional)
**Settings → Quick check** compares sizes; **Full verify** checks every SHA-256 hash.
Results go to `Downloads/GameShell-verify.txt`, including which original archive part a bad file came from.

## Where things are stored
| What | Where |
|---|---|
| Game files | Your folder, read in place |
| Settings, compile cache | App storage (removed on uninstall) |
| Save backups, layout backups, freeze reports | `Documents/GTA5NativeData` (kept after uninstall) |
| Exported log | `Downloads/GameShell-runtime.log` |
