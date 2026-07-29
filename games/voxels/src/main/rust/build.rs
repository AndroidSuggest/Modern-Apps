use std::path::PathBuf;
use std::process::Command;

fn main() {
    let shaders_dir = PathBuf::from("shaders");
    let glslc = find_glslc();

    let vert = shaders_dir.join("block.vert");
    let frag = shaders_dir.join("block.frag");
    let vert_spv = shaders_dir.join("block.vert.spv");
    let frag_spv = shaders_dir.join("block.frag.spv");
    let sky_vert = shaders_dir.join("sky.vert");
    let sky_frag = shaders_dir.join("sky.frag");
    let sky_vert_spv = shaders_dir.join("sky.vert.spv");
    let sky_frag_spv = shaders_dir.join("sky.frag.spv");
    let cloud_frag = shaders_dir.join("cloud.frag");
    let cloud_frag_spv = shaders_dir.join("cloud.frag.spv");
    let shadow_vert = shaders_dir.join("shadow.vert");
    let shadow_vert_spv = shaders_dir.join("shadow.vert.spv");
    let water_vert = shaders_dir.join("water.vert");
    let water_frag = shaders_dir.join("water.frag");
    let water_vert_spv = shaders_dir.join("water.vert.spv");
    let water_frag_spv = shaders_dir.join("water.frag.spv");
    let post = [("post.vert","post.vert.spv"), ("bright.frag","bright.frag.spv"), ("blur.frag","blur.frag.spv"), ("composite.frag","composite.frag.spv")];
    let atlas_bin = shaders_dir.join("atlas.bin");

    if !atlas_bin.exists() {
        println!("cargo:warning=atlas.bin missing, creating checker fallback 64x64");
        let mut atlas = vec![0u8; 64*64*4];
        for y in 0..64 { for x in 0..64 {
            let i = (y*64 + x)*4;
            let checker = ((x/8 + y/8) %2)==0;
            if checker { atlas[i]=255; atlas[i+1]=0; atlas[i+2]=255; atlas[i+3]=255; }
            else { atlas[i]=0; atlas[i+1]=0; atlas[i+2]=0; atlas[i+3]=255; }
        }}
        std::fs::write(&atlas_bin, atlas).ok();
    }

    if let Some(glslc_path) = &glslc {
        println!("cargo:warning=Found glslc at {}", glslc_path.display());
        let _ = Command::new(glslc_path).arg("-o").arg(&vert_spv).arg(&vert).status();
        let _ = Command::new(glslc_path).arg("-o").arg(&frag_spv).arg(&frag).status();
        let _ = Command::new(glslc_path).arg("-o").arg(&sky_vert_spv).arg(&sky_vert).status();
        let _ = Command::new(glslc_path).arg("-o").arg(&sky_frag_spv).arg(&sky_frag).status();
        let _ = Command::new(glslc_path).arg("-o").arg(&cloud_frag_spv).arg(&cloud_frag).status();
        let _ = Command::new(glslc_path).arg("-o").arg(&shadow_vert_spv).arg(&shadow_vert).status();
        let _ = Command::new(glslc_path).arg("-o").arg(&water_vert_spv).arg(&water_vert).status();
        let _ = Command::new(glslc_path).arg("-o").arg(&water_frag_spv).arg(&water_frag).status();
        for (src, spv) in &post {
            let _ = Command::new(glslc_path).arg("-o").arg(shaders_dir.join(spv)).arg(shaders_dir.join(src)).status();
        }
    }

    if !vert_spv.exists() { std::fs::write(&vert_spv, minimal_vert_spv()).ok(); }
    if !frag_spv.exists() { std::fs::write(&frag_spv, minimal_frag_spv()).ok(); }
    // include_bytes! requires these to exist even if glslc is unavailable.
    if !sky_vert_spv.exists() { std::fs::write(&sky_vert_spv, minimal_vert_spv()).ok(); }
    if !sky_frag_spv.exists() { std::fs::write(&sky_frag_spv, minimal_frag_spv()).ok(); }
    if !cloud_frag_spv.exists() { std::fs::write(&cloud_frag_spv, minimal_frag_spv()).ok(); }
    if !shadow_vert_spv.exists() { std::fs::write(&shadow_vert_spv, minimal_vert_spv()).ok(); }
    if !water_vert_spv.exists() { std::fs::write(&water_vert_spv, minimal_vert_spv()).ok(); }
    if !water_frag_spv.exists() { std::fs::write(&water_frag_spv, minimal_frag_spv()).ok(); }
    for (src, spv) in &post {
        let p = shaders_dir.join(spv);
        if !p.exists() { std::fs::write(&p, if src.ends_with(".vert") { minimal_vert_spv() } else { minimal_frag_spv() }).ok(); }
    }

    println!("cargo:rerun-if-changed=shaders/block.vert");
    println!("cargo:rerun-if-changed=shaders/block.frag");
    println!("cargo:rerun-if-changed=shaders/sky.vert");
    println!("cargo:rerun-if-changed=shaders/sky.frag");
    println!("cargo:rerun-if-changed=shaders/cloud.frag");
    println!("cargo:rerun-if-changed=shaders/shadow.vert");
    println!("cargo:rerun-if-changed=shaders/water.vert");
    println!("cargo:rerun-if-changed=shaders/water.frag");
    println!("cargo:rerun-if-changed=shaders/post.vert");
    println!("cargo:rerun-if-changed=shaders/bright.frag");
    println!("cargo:rerun-if-changed=shaders/blur.frag");
    println!("cargo:rerun-if-changed=shaders/composite.frag");
    println!("cargo:rerun-if-changed=shaders/atlas.bin");
    println!("cargo:rerun-if-changed=build.rs");
}

fn find_glslc() -> Option<PathBuf> {
    for c in [
        "/Users/vayun/devmate-android-toolchain/android-sdk/ndk/29.0.14206865/shader-tools/darwin-x86_64/glslc",
        "/Users/vayun/devmate-android-toolchain/android-sdk/ndk/29.0.14206865/shader-tools/linux-x86_64/glslc",
    ] {
        let p = PathBuf::from(c);
        if p.exists() { return Some(p); }
    }
    if Command::new("which").arg("glslc").output().map(|o| o.status.success()).unwrap_or(false) {
        return Some(PathBuf::from("glslc"));
    }
    None
}

fn minimal_vert_spv() -> Vec<u8> { vec![0x03,0x02,0x23,0x07, 0x00,0x00,0x01,0x00, 0x0a,0x00,0x08,0x00, 0x28,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x11,0x00,0x02,0x00, 0x01,0x00,0x00,0x00] }
fn minimal_frag_spv() -> Vec<u8> { vec![0x03,0x02,0x23,0x07, 0x00,0x00,0x01,0x00, 0x0a,0x00,0x08,0x00, 0x1c,0x00,0x00,0x00, 0x00,0x00,0x00,0x00, 0x11,0x00,0x02,0x00, 0x01,0x00,0x00,0x00] }
