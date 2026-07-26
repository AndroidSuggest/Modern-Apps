plugins {
    id("common-conventions-library")
}

android {
    // The convention plugin derives the namespace from the module name, which contains a hyphen
    // ("e2ee-p2p") and is not a valid package segment — so set it explicitly.
    namespace = "com.vayunmathur.e2ee"
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native post-quantum crypto (Rust: fips203 ML-KEM-768 + fips204 ML-DSA-65).
// See library/e2ee-p2p/src/main/rust/. Replaces Bouncy Castle; keys cross as DER,
// byte-compatible with the previously-deployed BC encoding.
rustNativeLib("e2ee_pqc", "e2ee-pqc")

dependencies {
    implementation(libs.cryptography.core)
    implementation(libs.cryptography.provider.jdk)

}
