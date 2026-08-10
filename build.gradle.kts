// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Repairs corrupted translations contributed via Weblate where the <xliff:g> markup
// was escaped (e.g. "&lt;xliff:g ...&gt;%1$s&lt;/xliff:g&gt;"). When escaped, Android
// renders the markup literally in the UI instead of only the placeholder. This task
// unwraps such occurrences back to their inner placeholder (e.g. "%1$s"). See issue #477.
tasks.register("fixStrings") {
    group = "translation"
    description = "Fixes translated strings where <xliff:g> markup was escaped and shows up literally in the UI (#477)."
    val projectRoot = rootDir
    doLast {
        val escapedXliff = Regex(
            "&lt;\\s*xliff:g\\b.*?&gt;(.*?)&lt;\\s*/\\s*xliff:g\\s*&gt;",
            RegexOption.DOT_MATCHES_ALL,
        )
        var filesChanged = 0
        var replacements = 0
        projectRoot.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" }
            .filter { it.isFile && it.name == "strings.xml" }
            .forEach { file ->
                val original = file.readText()
                var count = 0
                val fixed = escapedXliff.replace(original) { match ->
                    count++
                    match.groupValues[1]
                }
                if (count > 0) {
                    file.writeText(fixed)
                    filesChanged++
                    replacements += count
                    println("fixStrings: fixed $count occurrence(s) in ${file.relativeTo(projectRoot)}")
                }
            }
        println("fixStrings: done. Fixed $replacements occurrence(s) across $filesChanged file(s).")
    }
}