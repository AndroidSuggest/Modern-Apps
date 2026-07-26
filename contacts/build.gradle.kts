plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "person"
}

android {
    defaultConfig {
        versionCode = 20260725
        versionName = "v2.6.2"
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

    // External Libraries
    implementation(libs.okio) // isolated: VCF export/import, FileSystem – not networking
    implementation(libs.libphonenumber)
    implementation(libs.androidx.work.runtime.ktx)
}
