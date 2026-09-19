import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.detekt")
}

// Static analysis (./check runs `:module:detekt`). Rules live in the committed
// root config/detekt/detekt.yml; findings fail the task (no ignoreFailures).
// Config-cache compatible: the config file is resolved here, never a Project.
detekt {
    config.setFrom(files(rootDir.resolve("config/detekt/detekt.yml")))
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

configure<com.android.build.api.dsl.LibraryExtension> {
    buildFeatures {
        compose = true
    }

    namespace = "com.vayunmathur${path.replace(":", ".").replace("-", "")}"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 31
    }

    lint {
        // Toast is banned repo-wide. Apps also catch this transitively via
        // checkDependencies, but failing here gives faster feedback when
        // working inside a library module.
        // FileLength caps Kotlin file size, same as apps: split first.
        fatal += listOf("ToastUsage", "FileLength")
    }
}

dependencies {
    // Repo-specific lint checks (currently: no Toast).
    lintChecks(project(":lint-rules"))

    // AndroidX Core & Lifecycle
    implementation(libs.kotlinx.datetime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)

    // Compose UI (BOM Managed)
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.optIn.addAll(
        "androidx.compose.material3.ExperimentalMaterial3Api",
        "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
    )
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Reproducible builds: log SOURCE_DATE_EPOCH if present for verification
System.getenv("SOURCE_DATE_EPOCH")?.let {
    logger.lifecycle("Reproducible build: SOURCE_DATE_EPOCH=$it")
}
