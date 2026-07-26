plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
    alias(libs.plugins.ksp)
}

launcherIcon {
    symbol = "key_vertical"
}

android {
    defaultConfig {
        minSdk = 35
        applicationId = "com.vayunmathur.passwords"
    }
    packaging {
        resources.excludes += "META-INF/INDEX.LIST"
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(
            layout.buildDirectory.dir("rustJniLibs").get().asFile.absolutePath
        )
    }
}

// Native KDBX (KeePass) read/write (Rust `keepass` crate). See passwords/src/main/rust/.
// Replaces keepassjava2 + Bouncy Castle; existing .kdbx vaults stay interoperable.
rustNativeLib("passwords_kdbx", "passwords-kdbx")

dependencies {
    implementation(project(":library:biometric"))
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.credentials.lib)
    implementation(libs.androidx.autofill)
    // Own WebSocketClient via :library:network – no Ktor
    implementation(project(":library:network"))

    // Test-only crypto reference for the caBLE parity test (CryptoParityTest).
    // Not shipped in the APK; unrelated to the KDBX/keepass path.
    testImplementation(libs.bouncycastle)
}
