# Changelog

All notable changes to Game Shell Native. Versions follow `MAJOR.MINOR.PATCH`; each `v*` tag publishes a signed release.

## [0.5.3] - 2026-10-28
### Changed
- Project renamed to **game-shell-native**. The Java package is now `app.webgameshell.nativeshell`, the Rust crate `game-shell-native`, and the native library `libgame_shell_native.so`. The app ID is unchanged.
- Releases are signed with one permanent key, so updates install over the previous version.
- Repository cleanup: removed the upstream iOS/macOS projects, the PC and iPhone installers, the game downloader script and the installer tests (Android only).
- New README, building / installing / controls docs, issue templates.

## [0.5.2] - 2026-10-28
### Fixed
- Pages no longer draw under the status bar, camera cutout or gesture bar (Android 15+ edge-to-edge).
- Gaps between button rows; icons sit next to their labels; the portrait home is vertically centred.

## [0.5.1] - 2026-10-27
### Fixed
- Playing a second time without force-stopping the app gave a dead black screen. The game process now always ends when you leave it, and every Play starts a fresh one.
### Changed
- Responsive UI: sizes scale with the real screen size, the system font scale is capped, pages have a max width on big screens; slimmer buttons, a 2×2 home grid, a segmented preset picker and compact info rows.

## [0.5.0] - 2026-10-27
### Added
- Bold two-pane home: logo, device line (version · model · SoC · GPU), a giant Play button and a game panel.
- **Choose folder** (Android folder picker) and **Scan phone**: the game is detected by its contents, not its folder name.
- **Controls** page: use, edit, copy, create and import `.icp` layouts.
- Layout editor rebuilt to match Winlator: floating toolbox, element sheet (type, shape, keyboard / mouse / gamepad bindings, scale, opacity, toggle, mouse-move, text, icons, range keys, radial slots), grid snap, autosave.
- Bundled fonts (Barlow Condensed, Inter) and Material vector icons; Winlator control icons and a GTA 5 layout.
- New original app icon.

## [0.4.0] - 2026-10-27
### Added
- OLED-black design with accent colours, animations and vibration.
- Game library with cover images and a 4-step setup wizard with presets.
- Freeze watchdog with one-tap restart and freeze reports; overheating warning; FPS graph HUD.
- On-screen keyboard that types into the game, plus hardware keyboard text.
- Automatic save backups after every session; wrong-build warning on Play.

## [0.3.0] - 2026-10-25
### Added
- First-run wizard, auto-configuration from RAM / chip, reset to defaults.
- Winlator-style control editor and shared `.icp` renderer.
- Gamepad remapping, deadzone and look speed.
- In-game slide menu: resume, restart, exit, HUD, live layout switch, mute, graphics, clear cache.
- Clear graphics and temp data; save and layout backups in `Documents/GTA5NativeData`; crash screen; About page.

## [0.2.0] - 2026-10-24
### Added
- Saved games list with per-game settings (render scale, FPS, textures, touch speed, mute, mode, multiplayer).
- Import of Winlator input-control profiles (`.icp`) and an in-game renderer for them.
- Quick and full file verification against the pinned manifest; log export.

## [0.1.0] - 2026-10-24
### Added
- Fork of GTAV Mobile: the downloader was removed; the runtime reads a game folder you already have (all-files access), with render-scale and FPS presets and its own app ID.
