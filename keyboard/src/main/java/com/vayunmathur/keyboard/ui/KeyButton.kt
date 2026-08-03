@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.vayunmathur.keyboard.util.ShiftState
import com.vayunmathur.library.ui.IconShift
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

/**
 * Key composables shared by every page. Keys are rounded, weight-sized tiles (so a row
 * always fills the width). On press the *whole* key fills with the brightest surface
 * colour ([pressedColor]) — a clear, gboard-style highlight rather than a ripple splash.
 * Colours are all Material tokens so light/dark + dynamic colour just work.
 */

private val KeyPadding = 3.dp
private val KeyShape = RoundedCornerShape(11.dp)

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
 * the main one (the ABC/DEF letters on the phone dial-pad, matching FUTO's phone layout).
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
    alternates: String = "",
    onClick: () -> Unit,
    onAlternate: ((String) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // -1 while the popup is closed, otherwise the alternate under the finger.
    var selected by remember { mutableIntStateOf(-1) }
    var keyLeft by remember { mutableFloatStateOf(0f) }
    var keyWidth by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val screenWidth = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val itemWidth = with(density) { AlternateWidth.toPx() }

    // Where the popup's left edge sits relative to the key's: centred on the key, then
    // nudged back inside the screen. The drag-to-select maths below uses the same number,
    // so what the finger is over is always what is highlighted.
    val popupOffset = remember(alternates, keyLeft, keyWidth) {
        val total = itemWidth * alternates.length
        val centred = keyLeft + (keyWidth - total) / 2f
        val clamped = centred.coerceIn(0f, (screenWidth - total).coerceAtLeast(0f))
        clamped - keyLeft
    }

    // The gesture below reads all of these through snapshots rather than capturing them, so it
    // never has to be restarted to pick up a new value — including popupOffset, which is still
    // 0 on the first composition and only gets its real value once the key has been positioned.
    // See the note on `pointerInput(Unit)`.
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnAlternate by rememberUpdatedState(onAlternate)
    val currentAlternates by rememberUpdatedState(alternates)
    val currentPopupOffset by rememberUpdatedState(popupOffset)

    Box(
        modifier = Modifier
            .weight(weight)
            .padding(KeyPadding)
            .height(height)
            .onGloballyPositioned {
                keyLeft = it.positionInWindow().x
                keyWidth = it.size.width.toFloat()
            }
            .clip(KeyShape)
            .background(if (pressed) pressedKeyColor() else charKeyColor())
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
                        val alts = currentAlternates
                        val onAlt = currentOnAlternate
                        if (alts.isEmpty() || onAlt == null) {
                            awaitPointerEventScope { waitForUpOrCancellation() }
                            continue
                        }
                        val picked = awaitPointerEventScope {
                            selected = indexAt(down.position.x, currentPopupOffset, itemWidth, alts.length)
                            var change = down
                            while (change.pressed) {
                                change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == down.id } ?: break
                                selected = indexAt(change.position.x, currentPopupOffset, itemWidth, alts.length)
                            }
                            selected
                        }
                        onAlt(alts[picked].toString())
                    } finally {
                        // tryEmit, not emit: this also has to run on the cancellation path,
                        // where a suspending emit would itself be cancelled and leave the
                        // key stuck. `continue` runs it too, so every exit clears the press.
                        selected = -1
                        interaction.tryEmit(PressInteraction.Release(press))
                    }
                }
            },
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
        // A small dot marks the keys that have something under a long press, so the
        // accents are discoverable instead of folklore.
        if (alternates.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 5.dp)
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        when {
            selected >= 0 -> AlternatesPopup(alternates, selected, popupOffset.toInt())
            // FUTO/AOSP-style preview: while held, balloon the character above the key so
            // the finger doesn't hide it. clippingEnabled=false lets it float above the
            // top row (outside the keyboard bounds).
            pressed -> KeyPreview(label)
        }
    }
}

/** True while the finger is still over the key it went down on. */
private fun Offset.isInside(size: IntSize): Boolean =
    x >= 0f && y >= 0f && x <= size.width && y <= size.height

private fun indexAt(x: Float, popupOffset: Float, itemWidth: Float, count: Int): Int =
    (((x - popupOffset) / itemWidth).toInt()).coerceIn(0, count - 1)

/** The row of alternates above a held key, with the one under the finger highlighted. */
@Composable
private fun AlternatesPopup(alternates: String, selected: Int, offsetX: Int) {
    Popup(
        popupPositionProvider = remember(offsetX) { AlternatesPositionProvider(offsetX) },
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Row(
            modifier = Modifier
                .padding(bottom = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceBright),
        ) {
            alternates.forEachIndexed { index, c ->
                Box(
                    modifier = Modifier
                        .width(AlternateWidth)
                        .height(52.dp)
                        .background(
                            if (index == selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                Color.Transparent
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = c.toString(),
                        color = if (index == selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 22.sp,
                    )
                }
            }
        }
    }
}

private val AlternateWidth = 44.dp

/** Places the alternates row directly above the key, shifted by the clamped [offsetX]. */
private class AlternatesPositionProvider(private val offsetX: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(anchorBounds.left + offsetX, anchorBounds.top - popupContentSize.height)
}

/** The pop-up character preview shown above a held key. */
@Composable
private fun KeyPreview(label: String) {
    Popup(
        popupPositionProvider = KeyPreviewPositionProvider,
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp, vertical = 4.dp)
                .defaultMinSize(minWidth = 46.dp, minHeight = 50.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 30.sp,
            )
        }
    }
}

/** Positions the preview centred horizontally over the key and directly above it. */
private object KeyPreviewPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val y = anchorBounds.top - popupContentSize.height
        return IntOffset(x, y)
    }
}

/** A functional key that only needs a tap (page toggles, comma/period, emoji, enter). */
@Composable
fun RowScope.SpecialKey(
    height: Dp,
    weight: Float = 1f,
    containerColor: Color = specialKeyColor(),
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    pressedContainerColor: Color = pressedKeyColor(),
    pressedContentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(KeyPadding)
            .height(height)
            .clip(KeyShape)
            .background(if (pressed) pressedContainerColor else containerColor)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (pressed) pressedContentColor else contentColor,
        ) { content() }
    }
}

/**
 * Space bar: tap inserts a space, long-press switches to the next IME. [label] names the
 * active layout, which is how the user can tell at a glance which language they are typing.
 */
@Composable
fun RowScope.SpaceKey(
    height: Dp,
    weight: Float,
    label: String,
    onSpace: () -> Unit,
    onLongPress: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(KeyPadding)
            .height(height)
            .clip(KeyShape)
            .background(if (pressed) pressedKeyColor() else charKeyColor())
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onSpace,
                onLongClick = onLongPress,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Backspace with press-and-hold repeat delete. */
@Composable
fun RowScope.RepeatKey(
    height: Dp,
    weight: Float,
    onRepeat: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(KeyPadding)
            .height(height)
            .clip(KeyShape)
            .background(if (pressed) pressedKeyColor() else specialKeyColor())
            .pointerInput(Unit) {
                detectTapGestures(onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    interaction.emit(press)
                    onRepeat()
                    val job = scope.launch {
                        delay(350)
                        while (true) {
                            onRepeat()
                            delay(45)
                        }
                    }
                    val released = tryAwaitRelease()
                    job.cancel()
                    interaction.emit(
                        if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                    )
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

/** Shift key: single tap toggles shift, double tap latches caps-lock. */
@Composable
fun RowScope.ShiftKey(
    height: Dp,
    weight: Float,
    shift: ShiftState,
    onShift: () -> Unit,
) {
    val active = shift != ShiftState.OFF
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container = when {
        pressed -> pressedKeyColor()
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> specialKeyColor()
    }
    val content = when {
        pressed -> MaterialTheme.colorScheme.onSurface
        active -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .weight(weight)
            .padding(KeyPadding)
            .height(height)
            .clip(KeyShape)
            .background(container)
            .pointerInput(Unit) {
                // Fire on press-up immediately (no double-tap wait). Caps-lock is detected
                // from tap timing in the service, so shift responds instantly.
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interaction.emit(press)
                        val released = tryAwaitRelease()
                        interaction.emit(
                            if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press),
                        )
                        if (released) onShift()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        IconShift(tint = content)
        if (shift == ShiftState.CAPS_LOCK) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp)
                    .size(width = 14.dp, height = 2.dp)
                    .background(content),
            )
        }
    }
}
