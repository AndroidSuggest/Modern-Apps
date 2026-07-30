@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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

/** A letter/symbol key: tap commits, optional long-press commits an alternate. */
@Composable
fun RowScope.CharKey(
    label: String,
    height: Dp,
    weight: Float = 1f,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
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
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp)
        // FUTO/AOSP-style preview: while held, balloon the character above the key so
        // the finger doesn't hide it. clippingEnabled=false lets it float above the
        // top row (outside the keyboard bounds).
        if (pressed) KeyPreview(label)
    }
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

/** Space bar: tap inserts a space, long-press switches to the next IME. */
@Composable
fun RowScope.SpaceKey(
    height: Dp,
    weight: Float,
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
            text = "English",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
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
