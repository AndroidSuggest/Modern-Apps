plugins {
    id("common-conventions-app")
}

launcherIcon {
    symbol = "restaurant"
}

android {
    defaultConfig {
        applicationId = "com.vayunmathur.fooddelivery"
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":library:image"))
    implementation(project(":library:network"))
    implementation(libs.stripe.android)
}
