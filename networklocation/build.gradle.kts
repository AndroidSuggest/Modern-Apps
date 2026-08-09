plugins {
    id("common-conventions-app")
}

launcherIcon {
    symbol = "my_location"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.networklocation"
    }
}

dependencies {
    // Geocoder DB is a self-contained mmap'd binary (see geocoder/). Block compression uses
    // java.util.zip (Deflate) — no external dependency needed for the core.
}
