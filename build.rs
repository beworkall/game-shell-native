// muguet - build.rs
// Constantin Clerc (c22dev) - 0.1
// Modified for GTAV Mobile on 2026-10-11 and Game Shell Native on 2026-10-28 (Android only); see NOTICE.md.

fn main() {
    println!("cargo::rustc-check-cfg=cfg(game_code)");
    println!("cargo:rerun-if-env-changed=MUGUET_GAME_CODE");
    if let Ok(path) = std::env::var("MUGUET_GAME_CODE") {
        assert!(std::path::Path::new(&path).is_file(), "MUGUET_GAME_CODE does not exist: {path}");
        println!("cargo:rerun-if-changed={path}");
        println!("cargo:rustc-cfg=game_code");
    }
}
