plugins {
    id("common-conventions-app")
    id("common-conventions-metadata")
}

launcherIcon {
    symbol = "calendar_month"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.calendar"
    }
}

metadataScreenshots {
    permissions.addAll("android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR")
}

dependencies {
    implementation(project(":library:widgets"))

}