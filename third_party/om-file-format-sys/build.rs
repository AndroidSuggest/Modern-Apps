use std::env;
use std::path::Path;

fn main() {
    const LIB_NAME: &str = "omfileformatc";
    let submodule_path = "c";
    if !Path::new(submodule_path).exists() {
        panic!("Submodule not found at path: {}", submodule_path);
    }
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=src/lib.rs");
    println!("cargo:rerun-if-changed=src/bindings_android.rs");
    println!("cargo:rerun-if-changed=src/bindings_host.rs");
    println!("cargo:rerun-if-changed={}", submodule_path);
    println!("cargo:rerun-if-env-changed=CC");
    println!("cargo:rerun-if-env-changed=SYSROOT");
    println!("cargo:rerun-if-env-changed=TARGET");

    let target = env::var("TARGET").unwrap_or_default();
    let is_android = target.contains("android");
    let sysroot = env::var("SYSROOT").ok();
    let cc_env = env::var("CC").unwrap_or_default();
    let has_ndk = sysroot.is_some() || cc_env.contains("android");

    // When checking aarch64-linux-android without NDK (host `cargo check --target ...`),
    // skip compiling C – host clang lacks Android headers. Gradle RustNative.kt sets
    // CC to NDK clang + SYSROOT so real Android builds still compile C.
    if is_android && !has_ndk {
        println!("cargo:rustc-link-lib=static={}", LIB_NAME);
        return;
    }

    let mut build = cc::Build::new();
    if !cc_env.is_empty() {
        build.compiler(cc_env);
    }
    build.include(format!("{}/include", submodule_path));
    let src_path = format!("{}/src", submodule_path);
    let mut c_files: Vec<_> = std::fs::read_dir(&src_path)
        .unwrap()
        .filter_map(|e| e.ok())
        .map(|e| e.path())
        .filter(|p| p.extension().and_then(|e| e.to_str()) == Some("c"))
        .collect();
    c_files.sort();
    for path in c_files {
        build.file(path);
    }
    // armv8-only flags
    if env::var("CFLAGS").is_err() {
        build.flag("-O3");
        let arch = env::var("CARGO_CFG_TARGET_ARCH").unwrap_or_else(|_| "aarch64".into());
        if arch == "aarch64" {
            build.flag("-march=armv8-a");
        }
        build.flag_if_supported("-Wdate-time");
        build.flag_if_supported("-Werror=date-time");
    }
    if let Some(sysroot_path) = sysroot {
        build.flag(format!("--sysroot={}", sysroot_path));
    }
    build.warnings(false);
    build.compile(LIB_NAME);
    println!("cargo:rustc-link-lib=static={}", LIB_NAME);
}
