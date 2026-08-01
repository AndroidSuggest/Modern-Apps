plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "person"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.contacts"
    }
}

metadataScreenshots {
    permissions.addAll(
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.CALL_PHONE",
        "android.permission.READ_PHONE_STATE",
    )
}

dependencies {
    // VCF export/import now uses java.io BufferedReader/Writer – no okio needed
    implementation(libs.libphonenumber)
    implementation(libs.androidx.work.runtime.ktx)
}
