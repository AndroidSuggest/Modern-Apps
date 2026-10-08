package com.vayunmathur.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * Bans direct `android.util.Log` use everywhere but the facade itself.
 *
 * All logging goes through `com.vayunmathur.library.log.Log`
 * (`Log.dev` / `Log.debug` / `Log.status` / `Log.error`), so every log carries
 * an explicit tag and dev-only logs honour the build's dev gate
 * (`Log.init(BuildConfig.DEV_BUILD)` in each app's `Application`). A raw
 * `android.util.Log` call bypasses both, so it is an error.
 *
 * Matches on the resolved method owner, not the import text, so aliased
 * imports (`import android.util.Log as AndroidLog`) and fully-qualified calls
 * (`android.util.Log.d(...)`) are caught too.
 *
 * No per-file opt-out: the only legitimate `android.util.Log` reference is the
 * facade's own delegation, which is exempt by package
 * (`com.vayunmathur.library.log`). The untracked AOSP reference stub at the
 * repo root is not part of any module source set, so lint never sees it.
 */
class DirectAndroidLogDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> =
        listOf("d", "e", "i", "w", "v", "wtf", "println", "isLoggable", "getStackTraceString")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!context.evaluator.isMemberInClass(method, ANDROID_LOG)) return
        if (context.uastFile?.packageName == LOG_PACKAGE) return

        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "Direct android.util.Log use is not allowed. Use " +
                "`com.vayunmathur.library.log.Log` instead: `Log.debug(tag, content)`, " +
                "`Log.status(tag, content)`, `Log.error(tag, content)`, or the dev-gated " +
                "`Log.dev(tag, content)` (each with a `Throwable` overload).",
        )
    }

    companion object {
        private const val ANDROID_LOG = "android.util.Log"
        private const val LOG_PACKAGE = "com.vayunmathur.library.log"

        val ISSUE: Issue = Issue.create(
            id = "DirectAndroidLog",
            briefDescription = "Direct android.util.Log use is not allowed",
            explanation = """
                All logging must go through the repo facade \
                `com.vayunmathur.library.log.Log` (Log.dev / Log.debug / Log.status / \
                Log.error), so every log carries an explicit tag and dev-only logs honour \
                the build's dev gate. A raw android.util.Log call bypasses both. Replace \
                `Log.d` with `Log.debug`, `Log.i`/`Log.w` with `Log.status`, `Log.e` with \
                `Log.error`, keeping the existing tag as the first argument.
                """.trimIndent(),
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(DirectAndroidLogDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
