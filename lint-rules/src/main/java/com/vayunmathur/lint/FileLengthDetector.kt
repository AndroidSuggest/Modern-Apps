package com.vayunmathur.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile

/**
 * Caps Kotlin file length: 350 lines for files in a `ui` package, 800
 * everywhere else.
 *
 * Oversized screen files are where brace-imbalance bugs hide (a stray `}` in
 * a 900-line file breaks the whole module and the compiler points at the
 * tail, not the cause) and where "one public composable per file" silently
 * rots into grab-bags of private helpers. Splitting a screen into its pane
 * components keeps each file reviewable and keeps diffs scoped.
 *
 * Test and screenshot sources are exempt — fixtures and literal-state
 * previews are long by nature — as are generated files and the shared
 * non-app modules in [LintPackageExclusions].
 *
 * Two further carve-outs live in [LENGTH_EXEMPT_FILES]: files that are long
 * *by design* rather than by accretion — registries, shims and vendored
 * third-party code that must stay whole to stay reviewable.
 */
class FileLengthDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> = listOf(UFile::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitFile(node: UFile) {
                val packageName = node.packageName
                if (packageName.isEmpty()) return
                if (!packageName.startsWith("com.vayunmathur.")) return
                if (LintPackageExclusions.isExcluded(packageName)) return

                val fileName = context.file?.name
                    ?: (node.sourcePsi as? KtFile)?.name
                    ?: return
                if (!fileName.endsWith(".kt")) return
                if (fileName == "BuildConfig.kt" || fileName == "R.kt") return
                if (fileName in LENGTH_EXEMPT_FILES) return

                // Test, on-device-test and screenshot-preview sources are
                // fixtures by nature; capping them would punish literal state.
                // Vendored third-party code stays whole to stay diffable upstream.
                val path = context.file?.path.orEmpty()
                if (isVendored(path)) return
                if (path.contains("/src/test/") || path.contains("/src/androidTest/") ||
                    path.contains("/src/screenshotTest/") ||
                    path.contains("\\src\\test\\") || path.contains("\\src\\androidTest\\") ||
                    path.contains("\\src\\screenshotTest\\")
                ) {
                    return
                }

                val ktFile = node.sourcePsi as? KtFile ?: return
                val lineCount = ktFile.text.lineSequence().count()
                val isUi = packageName.split('.').contains("ui")
                val limit = if (isUi) UI_LIMIT else OTHER_LIMIT
                if (lineCount <= limit) return

                context.report(
                    ISSUE,
                    node,
                    context.getLocation(node),
                    "File `$fileName` has $lineCount lines (limit $limit for " +
                        (if (isUi) "`ui` package files" else "non-`ui` files") + "). " +
                        "Split it — extract pane/section components into their own " +
                        "files under ui/, ui/components/, or ui/dialogs/ — so each " +
                        "file stays reviewable and brace errors stay local.",
                )
            }
        }

    companion object {
        const val UI_LIMIT = 350
        const val OTHER_LIMIT = 800

        /**
         * Files exempt by name: long by design, not by accretion.
         *
         * - `Icons.kt` / `MaterialComponents.kt` — the `library/ui` shim is
         *   one-function-per-symbol by design (same reason it is excluded from
         *   `OneComposablePerFile`); splitting it would scatter the registry.
         * - `OdfSerializer.kt` / `OoxmlExporter.kt` — serializer registries;
         *   the format logic they enumerate must stay beside its dispatch.
         * - `KeyboardService.kt` — the IME service; input-method lifecycle
         *   plus candidates plus gestures in one place is the platform norm.
         * - `extractor/` (`youpipe/extractor`) — vendored NewPipe code, kept
         *   whole to stay diffable against upstream.
         */
        val LENGTH_EXEMPT_FILES: Set<String> = setOf(
            "Icons.kt",
            "MaterialComponents.kt",
            "OdfSerializer.kt",
            "OoxmlExporter.kt",
            "KeyboardService.kt",
        )

        /** True if the file lives under a vendored third-party tree. */
        fun isVendored(path: String): Boolean =
            path.contains("/youpipe/extractor/") || path.contains("\\youpipe\\extractor\\")

        val ISSUE: Issue = Issue.create(
            id = "FileLength",
            briefDescription = "Kotlin file exceeds the line limit",
            explanation = """
                Kotlin files in a `ui` package are limited to $UI_LIMIT lines; all \
                other Kotlin sources to $OTHER_LIMIT lines. Oversized screen files \
                hide brace-imbalance breakage (the compiler points at the tail, not \
                the cause) and rot into grab-bags of helpers. Extract pane and \
                section components into their own files under ui/, ui/components/, \
                or ui/dialogs/. Test, androidTest and screenshotTest sources are \
                exempt.
                """.trimIndent(),
            category = Category.PRODUCTIVITY,
            priority = 5,
            severity = Severity.ERROR,
            implementation = Implementation(
                FileLengthDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
