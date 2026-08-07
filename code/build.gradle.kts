plugins {
    id("common-conventions-app")
    // Listing screenshots come from Compose previews (src/screenshotTest), not from an
    // instrumented test on a device. Same `:code:metadata` task name either way.
    id("common-conventions-preview-metadata")
}

launcherIcon {
    symbol = "code"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.code"
    }
}

// Native tree-sitter highlighter (Rust → libcode_ts.so). Opt-in, because building it needs the
// Android NDK + `rustup target add aarch64-linux-android` and a generated
// code/src/main/rust/Cargo.lock. Enable in CI/on a build machine with `-PenableTreeSitter=true`.
// When disabled (the default), no .so is produced, TreeSitterNative.isAvailable is false at runtime,
// and the editor transparently falls back to the regex highlighter. Kept opt-in so ordinary builds
// (and other agents' native modules, which share the repo-root Cargo workspace) are unaffected.
if (providers.gradleProperty("enableTreeSitter").orNull == "true") {
    androidComponents {
        onVariants { variant ->
            val rustDir = layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
            variant.sources.jniLibs?.addStaticSourceDirectory(rustDir)
        }
    }
    rustNativeLib("code_ts", "code")
}

dependencies {
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.jgit)
    implementation(libs.androidx.webkit)
}
