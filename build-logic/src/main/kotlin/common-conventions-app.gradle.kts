plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val libs = the<org.gradle.accessors.dm.LibrariesForLibs>()

fun readVersionInfo(): Pair<Int, String> {
    val versionFile = File(rootDir, "version.txt")

    return if (versionFile.exists()) {
        val lines = versionFile.readLines()
        if (lines.size >= 2) {
            val code = lines[0].trim().toIntOrNull() ?: 1
            val name = lines[1].trim()
            code to name
        } else throw IllegalStateException("Invalid version.txt format")
    } else throw IllegalStateException("version.txt not found")
}

val proguardFile
    get() = File(rootDir, "proguard-rules.pro")

val (appVersionCode, appVersionName) = readVersionInfo()

// Adaptive launcher icons are generated at build time from Material Symbols
// instead of committing an ic_launcher_foreground.xml per app. Each app declares
// its symbol via `launcherIcon { symbol = "..." }` (see LauncherIconExtension).
// The symbol path is downloaded from google/material-design-icons pinned at this
// commit for reproducible builds, wrapped in the standard foreground vector, and
// wired into every variant's generated res.
val launcherIcon = extensions.create("launcherIcon", LauncherIconExtension::class.java).apply {
    scale.convention(0.58)
}
val materialSymbolsRef = "819d78680a849ceef4c78f863d8753e3160b7c89"
val materialSymbolsCache = File(gradle.gradleUserHomeDir, "material-symbols-cache")

extensions.configure<com.android.build.api.variant.ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val gen = tasks.register(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}LauncherIcon",
            GenerateLauncherIconTask::class.java,
        ) {
            symbol.set(launcherIcon.symbol)
            scale.set(launcherIcon.scale)
            ref.set(materialSymbolsRef)
            cacheDir.set(materialSymbolsCache)
        }
        variant.sources.res?.addGeneratedSourceDirectory(gen, GenerateLauncherIconTask::outputDir)
    }
}

configure<com.android.build.api.dsl.ApplicationExtension> {
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    buildFeatures {
        compose = true
    }

    namespace = "com.vayunmathur${path.replace(":", ".")}"
    compileSdk {
        version = release(37)
    }
    //compileSdkExtension = 19

    androidResources {
        generateLocaleConfig = true
    }

    lint {
        checkDependencies = true
        warning += listOf("HardcodedText")
        abortOnError = false
        // Don't fail on missing translations - empty skeletons exist for Weblate
        disable += listOf("MissingTranslation")
    }

    // Every app declares the same res/resources.properties (unqualifiedResLocale) for
    // per-app locale config. Share a single committed copy instead of one file per app.
    // (Generated res dirs are NOT scanned by extractSupportedLocales, so this must be a
    // real res source directory.)
    sourceSets.getByName("main").res.srcDir(File(rootDir, "build-logic/shared-res"))

    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 31
        versionCode = appVersionCode
        versionName = appVersionName
        targetSdk = 37
    }

    signingConfigs {
        val isSigningConfigAvailable = project.hasProperty("RELEASE_STORE_FILE")

        if (isSigningConfigAvailable) {
            create("release") {
                storeFile = file(project.property("RELEASE_STORE_FILE") as String)
                storePassword = project.property("RELEASE_STORE_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), proguardFile.absolutePath,
            )

            // This applies ONLY to your release APK/Bundle
            ndk {
                abiFilters.add("arm64-v8a")
            }
        }
        create("dev") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }

    packaging {
        resources {
            // bouncycastle 1.85 (bcprov/bcpkix/bcutil) each ship these license files,
            // which collide during Java-resource merge.
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
    }
}

dependencies {
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
    // Material 3 is intentionally NOT declared here. Apps must consume Material only
    // through `:library:ui` (which exposes it via `api`), so all Material usage goes
    // through the curated wrappers in `com.vayunmathur.library.ui`.

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    implementation(project(":library"))
    implementation(project(":library:ui"))
}

fun DependencyHandlerScope.justSoItShowsAsUsedSomewhere() {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
}

// Apps consume Material only through `:library:ui` wrappers, but some re-exported
// Material 3 types (sheets, tabs, adaptive, etc.) are still marked @RequiresOptIn.
// Opt in globally so app code doesn't need to import Material's markers.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.optIn.addAll(
        "androidx.compose.material3.ExperimentalMaterial3Api",
        "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        "androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi",
    )
}
