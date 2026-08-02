package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey

/**
 * Title alignment for [AppScaffold].
 *
 * A real design choice rather than an inconsistency, so it stays a per-screen
 * decision: [Start] is the workhorse for content screens, [Center] suits
 * single-purpose or top-level screens. What [AppScaffold] fixes is that
 * choosing between them used to mean writing the whole scaffold out again.
 */
enum class AppBarAlignment { Start, Center }

/**
 * Scaffold for an ordinary screen: a top app bar with an optional back button,
 * optional actions, and content.
 *
 * Fifty-five files had written out the same `Scaffold` + `TopAppBar` +
 * `IconNavigation(backStack)` by hand. [ListPage] already covers list screens;
 * this is for everything else.
 *
 * The content lambda receives the scaffold's [PaddingValues] and must apply
 * them - it is not applied here so that a screen can let a list scroll under
 * the bars while still insetting its own items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val navigationIcon: @Composable () -> Unit = {
        if (onNavigateBack != null) IconNavigation(onNavigateBack)
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            when (alignment) {
                AppBarAlignment.Start -> TopAppBar(
                    title = { Text(title) },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
                AppBarAlignment.Center -> CenterAlignedTopAppBar(
                    title = { Text(title) },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/**
 * [AppScaffold] taking a title slot rather than a string.
 *
 * For the handful of screens whose title needs more than text - a truncated
 * document name, a styled or two-line heading. Prefer the string overload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val navigationIcon: @Composable () -> Unit = {
        if (onNavigateBack != null) IconNavigation(onNavigateBack)
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            when (alignment) {
                AppBarAlignment.Start -> TopAppBar(
                    title = title, navigationIcon = navigationIcon,
                    actions = actions, scrollBehavior = scrollBehavior,
                )
                AppBarAlignment.Center -> CenterAlignedTopAppBar(
                    title = title, navigationIcon = navigationIcon,
                    actions = actions, scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
        content = content,
    )
}

/** [AppScaffold] for a screen that owns a back stack, wiring the back button to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T : NavKey> AppScaffold(
    title: String,
    backStack: NavBackStack<T>,
    modifier: Modifier = Modifier,
    alignment: AppBarAlignment = AppBarAlignment.Start,
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) = AppScaffold(
    title = title,
    modifier = modifier,
    onNavigateBack = { backStack.pop() },
    alignment = alignment,
    actions = actions,
    scrollBehavior = scrollBehavior,
    floatingActionButton = floatingActionButton,
    bottomBar = bottomBar,
    snackbarHost = snackbarHost,
    content = content,
)
