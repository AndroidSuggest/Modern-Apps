//! Build script for the `ml_vulkan` crate.
//!
//! Currently a no-op placeholder: there is nothing to code-generate, no C
//! to compile, and no shaders to bake yet. The hook is reserved so later
//! steps have a place to live without touching the crate layout.

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
}
