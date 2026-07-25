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

dependencies {
    implementation(project(":library:biometric"))
    implementRoom(libs)
    implementation(project(":library:room"))
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.credentials.lib)
    implementation(libs.androidx.autofill)
    implementation(libs.keepassjava2.dom)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)

    testImplementation(libs.junit)
    testImplementation(libs.bouncycastle)
}