plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.compose.gradle)
    implementation(libs.kotlin.serialization.gradle)
    implementation(libs.symbol.processing.gradle.plugin)
    implementation(libs.gradle)
    implementation(libs.compose.screenshot.gradle)
    implementation(libs.kotlin.gradle.plugin)
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
