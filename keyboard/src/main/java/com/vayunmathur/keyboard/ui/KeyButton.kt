@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.util.Layouts
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Key composables shared by every page. Keys are rounded, weight-sized tiles (so a row
 * always fills the width). On press the *whole* key fills with the brightest surface
 * colour ([pressedColor]) — a clear, gboard-style highlight rather than a ripple splash.
 * Colours are all Material tokens so light/dark + dynamic colour just work.
 */

internal val KeyPadding = 3.dp
internal val KeyShape = RoundedCornerShape(11.dp)

/** Resting colour for letter/symbol keys (slightly raised above the keyboard surface). */
@Composable
fun charKeyColor(): Color = MaterialTheme.colorScheme.surfaceContainerHigh

/** Resting colour for function keys (dimmer, so they read as secondary). */
@Composable
fun specialKeyColor(): Color = MaterialTheme.colorScheme.surfaceContainer

/** The whole-key highlight shown while a key is held down. */
@Composable
fun pressedKeyColor(): Color = MaterialTheme.colorScheme.surfaceBright

/**
 * A letter/symbol key: tap commits [label]; holding it opens the [alternates] — the accented
 * or related characters that key can also produce — and sliding onto one picks it, the way
 * every phone keyboard does accents. An optional [hint] draws a small secondary label beneath
 * the main one (the ABC/DEF letters on the phone dial-pad, matching FUTO's phone layout), and
 * [cornerHint] draws one in the top-left corner instead (the digit a letter key doubles as
 * when the persistent number row is off).
 *
 * The popup always opens with [label] itself as its leftmost entry, so a long press that
 * turns out to have been a mistake can still be finished with the plain letter instead of
 * forcing the user to release onto an accent and delete it.
 *
 * The whole thing is one gesture: press, hold, slide, release. Nothing has to dismiss the
 * popup, which matters in an IME — a focusable popup would take focus away from the very
 * field being typed into, and a non-focusable one has no way to learn about a tap outside it.
 */
@Composable
fun RowScope.CharKey(
    label: String,
    height: Dp,
    weight: Float = 1f,
    hint: String? = null,
    cornerHint: String? = null,
    alternates: String = "",
    onClick: () -> Unit,
    onAlternate: ((String) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // -1 while the popup is closed, otherwise the alternate under the finger.
    var selected by remember { mutableIntStateOf(-1) }
    // Where the key sits, recorded on layout. Deliberately *not* snapshot state: writing
    // state from onGloballyPositioned that composition then reads schedules another
    // recomposition and another layout pass, and with every key on the keyboard doing it
    // that doubles the cost of every relayout. Nothing needs these until a long press has
    // opened the popup, by which point the key has long since been positioned.
    val bounds = remember { KeyBounds() }

    val density = LocalDensity.current
    val screenWidth = LocalWindowInfo.current.containerSize.width.toFloat()

    // What the popup actually offers: the key's own character first, then its alternates.
    // Each entry has to be one character for the index maths below to hold, so the handful
    // of layouts whose comma or period is a multi-character string keep the bare list.
    val options = remember(label, alternates) {
        when {
            alternates.isEmpty() -> ""
            label.length == 1 -> label + alternates
            else -> alternates
        }
    }

    // Every entry gets its full width unless the row would run off the screen, in which case
    // they share what there is. Clamping the row's position alone is not enough: a long list
    // on a narrow phone would put the last entries past the edge, where no finger can reach.
    val itemWidth = remember(options, screenWidth, density) {
        val full = with(density) { AlternateWidth.toPx() }
        if (options.isEmpty()) full else minOf(full, screenWidth / options.length)
    }

    // The gesture below reads all of these through snapshots rather than capturing them, so it
    // never has to be restarted to pick up a new value. See the note on `pointerInput(Unit)`.
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnAlternate by rememberUpdatedState(onAlternate)
    val currentOptions by rememberUpdatedState(options)
    val currentItemWidth by rememberUpdatedState(itemWidth)
    val currentScreenWidth by rememberUpdatedState(screenWidth)

    Box(
        modifier = Modifier
            .weight(weight)
            // The whole weighted cell — including the KeyPadding gap — takes the touch, so
            // there are no dead strips between keys. The gap is reinstated as padding further
            // down, but only for the visual (clip + background), never for hit-testing.
            .height(height + KeyPadding * 2)
            .onGloballyPositioned {
                bounds.left = it.positionInWindow().x
                bounds.width = it.size.width.toFloat()
            }
            // Written out rather than assembled from detectTapGestures because tap and
            // long-press are one continuous gesture here: the long press opens the popup
            // and the *same* touch goes on to choose from it.
            //
            // Keyed on Unit, deliberately. Keying it on the callbacks instead restarts the
            // gesture whenever a recomposition reallocates them, and `ImeActions` is an
            // interface so Compose treats it as unstable and reallocates them every time.
            // A restart cancels this coroutine, and if that lands between the down and the
            // up — a suggestion arriving, auto-capitalise flipping shift, a settings flow
            // emitting, all of which happen mid-keypress — the Press interaction is never
            // released and the key stays lit with its preview stuck above it until it
            // leaves composition (i.e. until the user switches to another page).
            .pointerInput(Unit) {
                while (true) {
                    val down = awaitPointerEventScope { awaitFirstDown(requireUnconsumed = false) }
                    val press = PressInteraction.Press(down.position)
                    try {
                        interaction.emit(press)
                        val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            awaitPointerEventScope { waitForUpOrCancellation() }
                        }
                        if (lifted != null) {
                            if (lifted.position.isInside(size)) currentOnClick()
                            continue
                        }
                        val opts = currentOptions
                        val onAlt = currentOnAlternate
                        if (opts.isEmpty() || onAlt == null) {
                            awaitPointerEventScope { waitForUpOrCancellation() }
                            continue
                        }
                        val picked = awaitPointerEventScope {
                            val offset = alternatesOffset(bounds, currentItemWidth, opts.length, currentScreenWidth)
                            selected = indexAt(down.position.x, offset, currentItemWidth, opts.length)
                            var change = down
                            while (change.pressed) {
                                change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == down.id } ?: break
                                selected = indexAt(change.position.x, offset, currentItemWidth, opts.length)
                            }
                            selected
                        }
                        onAlt(opts[picked].toString())
                    } finally {
                        // tryEmit, not emit: this also has to run on the cancellation path,
                        // where a suspending emit would itself be cancelled and leave the
                        // key stuck. `continue` runs it too, so every exit clears the press.
                        selected = -1
                        interaction.tryEmit(PressInteraction.Release(press))
                    }
                }
            }
            .padding(KeyPadding)
            .clip(KeyShape)
            .background(if (pressed) pressedKeyColor() else charKeyColor()),
        contentAlignment = Alignment.Center,
    ) {
        if (hint != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = label, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp)
                Text(text = hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
            }
        } else {
            Text(text = label, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp)
        }
        if (cornerHint != null) {
            Text(
                text = cornerHint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 2.dp, start = 6.dp),
            )
        }
        // What a long press gets you, previewed in the corner so the alternates are
        // discoverable instead of folklore. Accented forms of the letter itself are not
        // worth the clutter — every vowel would wear one and it tells you nothing you
        // couldn't guess — so those fall back to a plain dot.
        val altHint = remember(alternates, label, cornerHint) {
            Layouts.alternatePreview(alternates, label, cornerHint)
        }
        when {
            altHint != null -> Text(
                text = altHint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 2.dp, end = 5.dp),
            )
            alternates.isNotEmpty() -> Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 5.dp, end = 5.dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        when {
            selected >= 0 -> AlternatesPopup(
                options = options,
                selected = selected,
                itemWidth = with(density) { itemWidth.toDp() },
                offsetX = alternatesOffset(bounds, itemWidth, options.length, screenWidth).toInt(),
            )
            // FUTO/AOSP-style preview: while held, balloon the character above the key so
            // the finger doesn't hide it. clippingEnabled=false lets it float above the
            // top row (outside the keyboard bounds).
            pressed -> KeyPreview(label)
        }
    }
}
