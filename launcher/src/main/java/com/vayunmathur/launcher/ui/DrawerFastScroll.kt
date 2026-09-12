package com.vayunmathur.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.launcher.domain.FastScroll
import com.vayunmathur.launcher.platform.DrawerApp
import com.vayunmathur.launcher.ui.components.FastScrollStrip
import com.vayunmathur.launcher.ui.components.onAppWindowBounds
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text

/**
 * The initials down the right edge.
 *
 * Not clickable, and not draggable either: the whole strip is driven by [FastScrollStrip] from the
 * home screen's gesture owner, because a `pointerInput` here would be a second one in a hierarchy
 * that permits exactly one. What this composable does is publish where it is and what sections it
 * is showing, and draw the thumb.
 *
 * A **thumb**, and no letters. Launcher3's fast scroller is a track with a
 * `fastscroll_thumb_height` handle on it; the alphabet only ever appears as the big
 * `fastscroll_popup` letter beside the finger while scrubbing. A permanent column of letters down
 * the edge is a different control from a different launcher.
 *
 * The thumb's position is read inside the draw block, not at composition scope: the list reports a
 * new scroll offset every frame, and reading that in composition would recompose the drawer sixty
 * times a second while it scrolls.
 */
@Composable
internal fun FastScrollThumb(
    apps: List<DrawerApp>,
    strip: FastScrollStrip?,
    gridState: LazyGridState,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sections = remember(apps) {
        buildMap {
            apps.forEachIndexed { index, app ->
                val initial = app.label.firstOrNull()?.uppercaseChar() ?: return@forEachIndexed
                val letter = if (initial.isLetter()) initial else '#'
                putIfAbsent(letter, index)
            }
        }.toList()
    }

    if (strip != null) {
        // In a SideEffect, because these are writes to state the gesture owner reads: doing them
        // straight from composition is the "backwards write" that leaves one frame disagreeing with
        // the next about how many sections there are.
        SideEffect {
            strip.sections = sections.size
            var jumped = -1
            strip.onFraction = { fraction ->
                val section = FastScroll.sectionAt(fraction, sections.size)
                // Only when the section actually changes. The finger reports a position every few
                // milliseconds, and each jump is a suspend scroll that cancels the one before it -
                // so re-issuing the same jump per event is a fight with the list's scroll mutex for
                // no movement at all.
                if (section != null && section != jumped) {
                    jumped = section
                    onJump(sections[section].second)
                }
            }
        }
        // The drawer closing leaves this composition, and stale bounds would keep swallowing every
        // touch down the right edge of the workspace.
        DisposableEffect(strip) {
            onDispose {
                strip.bounds = Rect.Zero
                strip.sections = 0
                strip.onFraction = {}
            }
        }
    }

    val track = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(INDEX_WIDTH)
            .padding(vertical = Spacing.sm)
            .onAppWindowBounds { strip?.bounds = it }
            .drawBehind {
                val width = THUMB_WIDTH.toPx()
                val height = THUMB_HEIGHT.toPx()
                val travel = (size.height - height).coerceAtLeast(0f)
                // The finger owns the thumb while it is scrubbing; otherwise the list does.
                val fraction = strip?.active ?: gridState.scrollFraction()
                drawRoundRect(
                    color = track,
                    topLeft = Offset(size.width - width, fraction * travel),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(width / 2f),
                    alpha = THUMB_ALPHA,
                )
            },
    )
}

/**
 * Roughly how far down the list is, for the thumb.
 *
 * By item index rather than by pixel: a lazy grid does not know the height of what it has not
 * measured, so an exact proportion is not available, and the thumb only has to be believable.
 */
internal fun LazyGridState.scrollFraction(): Float {
    val info = layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    if (total <= visible || visible == 0) return 0f
    return (firstVisibleItemIndex.toFloat() / (total - visible)).coerceIn(0f, 1f)
}

/**
 * The letter the finger is currently over, shown beside the strip.
 *
 * A fast scroller with no bubble is a scrollbar: the list flies past too quickly to read, so the
 * letter is the only thing telling the user where they are. Sized as Launcher3's
 * `fastscroll_popup_*`: 75x62dp with 32dp text, held clear of the strip by `fastscroll_popup_margin`.
 */
@Composable
internal fun LetterBubble(apps: List<DrawerApp>, fraction: Float) {
    val letters = remember(apps) {
        apps.mapNotNull { app ->
            val initial = app.label.firstOrNull()?.uppercaseChar() ?: return@mapNotNull null
            if (initial.isLetter()) initial else '#'
        }.distinct()
    }
    val letter = FastScroll.sectionAt(fraction, letters.size)?.let { letters[it] } ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = INDEX_WIDTH + BUBBLE_MARGIN),
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier
                // Beside the finger rather than under it, and following the same fraction the
                // scroller is using, so bubble and list cannot disagree.
                .layoutOffsetY { height -> (fraction * height).toInt() }
                .size(width = BUBBLE_WIDTH, height = BUBBLE_HEIGHT)
                .clip(RoundedCornerShape(BUBBLE_CORNER))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                letter.toString(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** Offsets by a fraction of the parent's height, which `offset` alone cannot express. */
internal fun Modifier.layoutOffsetY(y: (Int) -> Int): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.place(IntOffset(0, y(constraints.maxHeight) - placeable.height / 2))
        }
    },
)

/** Launcher3's `fastscroll_width`, which is the strip's touch region rather than its ink. */
internal val INDEX_WIDTH = 58.dp

/** Launcher3's `fastscroll_thumb_height` and `fastscroll_track_min_width`. */
internal val THUMB_HEIGHT = 52.dp
internal val THUMB_WIDTH = 6.dp

/** Present without competing with the icons it sits beside. */
internal const val THUMB_ALPHA = 0.5f

/** Launcher3's `fastscroll_popup_*`. */
internal val BUBBLE_WIDTH = 75.dp
internal val BUBBLE_HEIGHT = 62.dp
internal val BUBBLE_MARGIN = 19.dp
internal val BUBBLE_CORNER = 16.dp
