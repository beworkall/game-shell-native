# Building Game Shell Native

## The easy way: GitHub Actions
Everything builds in the cloud. The workflow is `.github/workflows/android.yml`.

| You do | You get |
|---|---|
| Push to `main` | An APK under **Actions → run → Artifacts → `game-shell-native-apk`** (kept for 90 days). |
| Push to `main` with a **new `versionName`** in `android/app/build.gradle` | The same build **plus a GitHub Release** `v<version>` with `GameShellNative.apk` and notes taken from `CHANGELOG.md`. |
| Push a tag `v*` | A release for that tag. |
| **Actions → android-apk → Run workflow** | A manual build. |

To ship a version: bump `versionCode` and `versionName`, add a `## [x.y.z]` section to `CHANGELOG.md`, and push to `main`.

The first build takes about 15 minutes (Rust + NDK). Later builds use the cache and take about 3 minutes.

## Signing
Releases are signed with one permanent key so every update installs over the last one.
The workflow reads two repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_B64` | The keystore file encoded with `base64 -w0` |
| `SIGNING_PASSWORD` | The keystore and key password (key alias `shell`) |

With the secrets set, CI builds `assembleRelease`, aligns it for 16 KB pages (`zipalign -P 16`), signs it with
`apksigner` and verifies it. Without them, CI falls back to a debug build and prints a warning.

Set or replace the secrets from a terminal with the GitHub CLI:

```sh
gh secret set SIGNING_PASSWORD     -R beworkall/game-shell-native < password.txt
gh secret set SIGNING_KEYSTORE_B64 -R beworkall/game-shell-native < keystore-base64.txt
```

**Never commit the keystore or password** (`.gitignore` blocks `*.jks` and `*.keystore`). Keep a private backup:
if the key is lost, future updates can't install over the existing app.

## Building locally
Requirements: Linux or macOS, JDK 17, the Android SDK with NDK `28.2.13676358`, and Rust `1.98.1`
(pinned in `rust-toolchain.toml`).

```sh
rustup target add aarch64-linux-android
sdkmanager "platforms;android-35" "build-tools;35.0.0" "ndk;28.2.13676358" "platform-tools"
export ANDROID_HOME=/path/to/android-sdk

./make-android.sh            # build/GameShellNative-debug.apk
./make-android.sh release    # build/GameShellNative-unsigned.apk (sign it yourself)
./make-android.sh --native-only   # only libgame_shell_native.so into jniLibs
```

Gradle runs the Rust build automatically, so Android Studio can open `android/` directly.

## How the pieces fit
- `src/` is the Rust runtime (`libgame_shell_native.so`): the Wasmtime host, wgpu renderer, audio (cpal),
  input and file system, plus the JNI hooks in `src/android.rs`.
- `android/app/` is the Java launcher (package `app.webgameshell.nativeshell`): `LauncherActivity` (home, wizard,
  settings, controls), `EditorActivity` (layout editor), `GameActivity` (NativeActivity in the `:game` process with
  the touch overlay, menu and watchdog), `ControlView` (`.icp` renderer), `Ui` (design system) and `Shell` (helpers).
- Launch flow: the launcher writes `config.json`, `ui.json`, `active_profile.txt` and `game_dir.txt` into app
  storage, then starts `GameActivity` in a fresh `:game` process.
