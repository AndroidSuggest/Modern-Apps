plugins {
    id("common-conventions-app")
}

launcherIcon {
    symbol = "restaurant"
}

android {
    defaultConfig {
        versionCode = 20260731
        versionName = "v2.6.4"
        applicationId = "com.vayunmathur.fooddelivery"
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":library:image"))
    implementation(project(":library:network"))
    implementation(libs.stripe.android)
    // stripe-android applies the kotlin-parcelize compiler plugin but never publishes
    // kotlin-parcelize-runtime in its POM, so kotlinx.parcelize.Parceler is absent from
    // the classpath. Four payments-core companions (PaymentFlowResult.Unvalidated,
    // PaymentRelayStarter.Args.ErrorArgs, GooglePayLauncherResult.Error and
    // SourceParams.ApiParams) *implement* that interface, so this is a real missing
    // superinterface, not just a stray annotation reference: R8 fails the release build,
    // and -dontwarn would only trade that for a NoClassDefFoundError the first time a
    // payment result is parcelled. Supply the runtime instead.
    implementation(libs.kotlin.parcelize.runtime)
}
