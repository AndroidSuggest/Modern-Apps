import com.android.build.api.dsl.ApplicationExtension
import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.tasks.bundling.AbstractArchiveTask

// Store-listing screenshots rendered from Compose @Preview instead of from an instrumented
// test on a device.
//
// Apply this INSTEAD OF `common-conventions-metadata` on an app whose screens have been
// split into stateless composables. It registers the same `metadata` task name, so
// `./gradlew :calculator:metadata` keeps working and `release.sh` needs no change — but it
// needs no emulator, no adb, and no `pm clear` of a real device.
//
//     ./gradlew :calculator:metadata
//
// renders every @Preview in `src/screenshotTest/` and copies the PNGs into
// `metadata_data/photos/<module-key>/`, numbered 1.png, 2.png, ... in filename order.
// Name the preview functions `Preview1Foo`, `Preview2Bar`, ... so that order is the
// listing order — the generated filenames embed the function name.
//
// Trade-off worth knowing: previews render through Layoutlib, which has no wallpaper, so
// `DynamicTheme` falls back to its default palette rather than the user's Material You
// accent. On-device captures (`common-conventions-metadata`) show the real accent.

val libs = the<LibrariesForLibs>()

// The plugin refuses to apply unless android.experimental.enableScreenshotTest is already
// set, and it checks at apply time — so the authoritative switch is the one in the root
// gradle.properties, not this DSL. The per-module property below is kept only so the
// requirement is visible from the module that actually needs it.
apply(plugin = "com.android.compose.screenshot")

configure<ApplicationExtension> {
    @Suppress("UnstableApiUsage")
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

dependencies {
    add("screenshotTestImplementation", platform(libs.androidx.compose.bom))
    add("screenshotTestImplementation", libs.androidx.compose.ui.tooling)
    add("screenshotTestImplementation", libs.androidx.compose.ui.tooling.preview)
    // @PreviewTest. A @Preview without it renders in Studio but is not collected as a
    // screenshot test, which shows up as "did not discover any tests".
    add("screenshotTestImplementation", libs.screenshot.validation.api)
}

val moduleKey = path.removePrefix(":").replace(":", "-")
val screenshotsOut = File(rootDir, "metadata_data/photos/$moduleKey")

// Where the plugin puts its PNGs. Both paths are taken verbatim from the engine config it
// generates at build/tmp/<task>/preview_screenshot_config.properties (referenceImageDir and
// previewImageOutputDir) — worth re-checking there if a plugin upgrade moves them. In
// recording mode the images land in the reference dir; they are an intermediate for us (see
// .gitignore), though keeping them is also what would let `validate…ScreenshotTest` act as
// UI regression testing later.
val referenceDir = File(projectDir, "src/screenshotTestDebug/reference")
val renderedDir = File(layout.buildDirectory.get().asFile, "outputs/screenshotTest-results/preview/debug/rendered")

/**
 * Collects rendered previews into `metadata_data/photos/<module-key>/`.
 *
 * Everything is resolved to plain [File]s at configuration time so the task stays
 * configuration-cache compatible (the root build has it enabled).
 */
abstract class CollectPreviewScreenshots : DefaultTask() {

    @get:Internal abstract val sourceDirs: ListProperty<File>

    @get:Internal abstract val destination: Property<File>

    @get:Internal abstract val label: Property<String>

    @TaskAction
    fun run() {
        val source = sourceDirs.get().firstOrNull { it.isDirectory && it.walkTopDown().any { f -> f.extension == "png" } }
            ?: error(
                "No rendered previews found for ${label.get()}. Looked in:\n" +
                    sourceDirs.get().joinToString("\n") { "  $it" } +
                    "\nDoes the module have @Preview functions under src/screenshotTest/?"
            )

        // Sorted by path so the listing order is the preview function order (Preview1…,
        // Preview2…). Anything else would silently shuffle the store listing.
        val images = source.walkTopDown().filter { it.extension == "png" }.sortedBy { it.invariantSeparatorsPath }.toList()

        val dest = destination.get()
        dest.deleteRecursively()
        dest.mkdirs()
        images.forEachIndexed { index, png ->
            png.copyTo(File(dest, "${index + 1}.png"), overwrite = true)
        }
        logger.lifecycle("Metadata screenshots: ${images.size} image(s) -> $dest")
        images.forEachIndexed { i, f -> logger.lifecycle("  ${i + 1}.png  <-  ${f.name}") }
    }
}

tasks.register<CollectPreviewScreenshots>("metadata") {
    group = "metadata"
    description =
        "Render Compose previews and copy them into metadata_data/photos/$moduleKey (no device required)"
    // `update…` renders the previews and writes them out; `validate…` would additionally
    // diff them against a committed baseline, which we do not keep.
    dependsOn("updateDebugScreenshotTest")
    sourceDirs.set(listOf(referenceDir, renderedDir))
    destination.set(screenshotsOut)
    label.set(path)
    // Cheap, and the whole point is to refresh the images on demand.
    outputs.upToDateWhen { false }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
