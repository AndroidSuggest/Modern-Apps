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
 * Flags any use of `android.widget.Toast`.
 *
 * Toasts are banned here. They render outside the app's Material surface and
 * ignore its theme, they cannot carry an action so a failure message leaves
 * the user with nowhere to go, they are invisible to accessibility services in
 * the way a snackbar is not, and since Android 12 the system silently drops
 * them when the app is not in the foreground - so the one case people reach
 * for a Toast in, reporting from background work, is exactly the case where it
 * may never appear.
 *
 * Use instead:
 *  - `rememberMessenger().show(...)` from a composable
 *  - `AppMessages.show(...)` from a ViewModel, worker, or plain Activity
 *  - a notification, if the message must survive the user leaving the screen
 */
class ToastDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> = listOf("makeText")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (!context.evaluator.isMemberInClass(method, TOAST_CLASS)) return
        context.report(
            ISSUE,
            node,
            context.getLocation(node),
            "Toast is not allowed. Use `AppMessages.show(...)`, or " +
                "`rememberMessenger().show(...)` in a composable.",
        )
    }

    companion object {
        private const val TOAST_CLASS = "android.widget.Toast"

        val ISSUE: Issue = Issue.create(
            id = "ToastUsage",
            briefDescription = "Toast is not allowed",
            explanation = """
                Toasts render outside the app's Material surface and ignore the app theme,                 cannot carry an action, and are silently suppressed by the system when the                 app is backgrounded on Android 12 and above - so the case people reach for                 a Toast in, reporting from background work, is the case where it may never                 appear.

                Use AppMessages.show(...) from anywhere without a Context,                 rememberMessenger().show(...) inside a composable, or a notification when                 the message has to outlive the current screen.
                """.trimIndent(),
            category = Category.USABILITY,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(ToastDetector::class.java, Scope.JAVA_FILE_SCOPE),
        )
    }
}
