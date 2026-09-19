package com.vayunmathur.auto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.auto.platform.CarLauncherState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface

/**
 * The car launcher's Compose entry: body + bottom bar.
 *
 * Body is the home grid (null selection) or one hosted app's screen; the
 * bottom bar holds home | pins | status. The dock shows the pinned apps in
 * stored order (stale ids filtered), falling back to the first apps until
 * the user picks — plus the open app as a transient trailing icon when it
 * isn't pinned, so an unpinned app still reads selected in the bar.
 * Selection writes into the shared state so the session focus follows the
 * tap. One public `@Composable` per file throughout `ui/`.
 *
 * Plain `Surface + Column`: the car `Presentation` has no phone chrome or
 * insets to scaffold around.
 */
@Composable
fun LauncherRoot(modifier: Modifier = Modifier, onCardBounds: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> }) {
    val selected by CarLauncherState.selectedApp.collectAsStateWithLifecycle()
    val discovered by CarLauncherState.discovered.collectAsStateWithLifecycle()
    val pinnedIds by CarLauncherState.pinnedOrder.collectAsStateWithLifecycle()
    val actions by CarLauncherState.actions.collectAsStateWithLifecycle()
    val pinned = remember(pinnedIds, discovered) {
        val byId = discovered.associateBy { it.id }
        val ordered = pinnedIds.mapNotNull { byId[it] }
        if (ordered.isNotEmpty()) ordered else discovered
    }
    val dock = remember(pinned, selected, discovered) {
        if (selected != null && pinned.none { it.id == selected }) {
            val open = discovered.firstOrNull { it.id == selected }
            if (open != null) pinned + open else pinned
        } else {
            pinned
        }
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            if (selected == null) {
                LauncherHomeGrid(
                    apps = discovered,
                    onOpen = { CarLauncherState.selectApp(it) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                AppScreen(
                    appId = selected!!,
                    modifier = Modifier.weight(1f),
                )
            }
            LauncherBottomBar(
                pinned = dock,
                selectedId = selected,
                onHome = { CarLauncherState.selectApp(null) },
                // Tap the open app: home-reset it in place. Tap anything
                // else: open it where it was (each host session keeps its
                // own screen stack, so re-select resumes).
                onPin = { id ->
                    if (id == selected) {
                        discovered.firstOrNull { it.id == id }?.let {
                            actions.onAppHomeTap(it.component)
                        }
                    } else {
                        CarLauncherState.selectApp(id)
                    }
                },
            )
        }
    }
}
