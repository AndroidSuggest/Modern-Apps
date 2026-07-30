package com.vayunmathur.keyboard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.keyboard.ime.EnterAction
import com.vayunmathur.keyboard.ime.ImeActions
import com.vayunmathur.keyboard.ime.KeyboardState
import com.vayunmathur.keyboard.ime.TextVariation
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.Layouts
import com.vayunmathur.keyboard.util.ShiftState
import com.vayunmathur.library.ui.IconBackspace
import com.vayunmathur.library.ui.IconEmoji
import com.vayunmathur.library.ui.IconReturn
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text

/**
 * Root of the keyboard view: a suggestion strip (when enabled) above whichever page of keys
 * is active. All sizing is weight-based so it fills the width; key height scales with the
 * user's height-scale setting.
 */
@Composable
fun KeyboardScreen(state: KeyboardState, actions: ImeActions) {
    val scale = state.settings.keyHeightScale.coerceIn(0.8f, 1.4f)
    val keyHeight = (52 * scale).dp
    // The IME window doesn't reliably deliver insets to Compose, so pad the bottom by
    // the navigation-bar height the service measured from the window.
    // Clear the system nav bar / gesture area: measured inset plus a comfortable margin,
    // with a floor so there's breathing room even when the inset reads small (gesture nav).
    val bottomInset = with(LocalDensity.current) { state.bottomInsetPx.toDp() }
    val bottomPad = (bottomInset + 18.dp).coerceAtLeast(30.dp)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPad),
        ) {
            if (state.settings.showSuggestions && state.page != KeyboardPage.EMOJI) {
                SuggestionStrip(
                    height = 44.dp,
                    suggestions = state.suggestions,
                    onPick = actions::commitSuggestion,
                )
            }
            when (state.page) {
                KeyboardPage.LETTERS -> LettersPage(state, actions, keyHeight)
                KeyboardPage.SYMBOLS ->
                    SymbolPage(state, actions, keyHeight, Layouts.SYMBOL_ROWS, KeyboardPage.MORE_SYMBOLS, "=\\<")
                KeyboardPage.MORE_SYMBOLS ->
                    SymbolPage(state, actions, keyHeight, Layouts.MORE_SYMBOL_ROWS, KeyboardPage.SYMBOLS, "?123")
                KeyboardPage.NUMERIC -> NumericPage(state, actions, keyHeight)
                KeyboardPage.EMOJI -> EmojiPage(
                    keyHeight = keyHeight,
                    rows = 4,
                    onEmoji = { actions.onChar(it) },
                    onBackspace = actions::onBackspace,
                    onBack = { actions.setPage(state.basePage) },
                )
            }
        }
    }
}

@Composable
private fun LettersPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    val rows = Layouts.LETTER_ROWS
    val shift = state.shift
    if (state.settings.numberRow) {
        Row(Modifier.fillMaxWidth()) {
            "1234567890".forEach { SymbolKey(it, keyHeight, actions) }
        }
    }
    Row(Modifier.fillMaxWidth()) {
        rows[0].forEach { LetterKey(it, shift, keyHeight, actions) }
    }
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(0.5f))
        rows[1].forEach { LetterKey(it, shift, keyHeight, actions) }
        Spacer(Modifier.weight(0.5f))
    }
    Row(Modifier.fillMaxWidth()) {
        ShiftKey(keyHeight, 1.5f, shift, actions::onShift)
        rows[2].forEach { LetterKey(it, shift, keyHeight, actions) }
        RepeatKey(keyHeight, 1.5f, actions::onBackspace) { IconBackspace() }
    }
    // Email/URL fields surface @ or / where the comma usually sits.
    val commaChar = when (state.textVariation) {
        TextVariation.EMAIL -> "@"
        TextVariation.URL -> "/"
        TextVariation.NORMAL -> ","
    }
    BottomRow(
        state, actions, keyHeight,
        leftLabel = "?123", leftTarget = KeyboardPage.SYMBOLS, commaChar = commaChar,
    )
}

@Composable
private fun RowScope.LetterKey(c: Char, shift: ShiftState, keyHeight: Dp, actions: ImeActions) {
    val display = if (shift != ShiftState.OFF) c.uppercaseChar().toString() else c.toString()
    CharKey(
        label = display,
        height = keyHeight,
        onClick = { actions.onChar(display) },
        onLongClick = { actions.onCharLongPress(c) },
    )
}

@Composable
private fun SymbolPage(
    state: KeyboardState,
    actions: ImeActions,
    keyHeight: Dp,
    rows: List<String>,
    otherPage: KeyboardPage,
    toggleLabel: String,
) {
    Row(Modifier.fillMaxWidth()) {
        rows[0].forEach { SymbolKey(it, keyHeight, actions) }
    }
    Row(Modifier.fillMaxWidth()) {
        rows[1].forEach { SymbolKey(it, keyHeight, actions) }
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1.5f, onClick = { actions.setPage(otherPage) }) {
            Text(toggleLabel, fontSize = 14.sp)
        }
        rows[2].forEach { SymbolKey(it, keyHeight, actions) }
        RepeatKey(keyHeight, 1.5f, actions::onBackspace) { IconBackspace() }
    }
    BottomRow(state, actions, keyHeight, leftLabel = "ABC", leftTarget = state.basePage)
}

@Composable
private fun RowScope.SymbolKey(c: Char, keyHeight: Dp, actions: ImeActions) {
    CharKey(label = c.toString(), height = keyHeight, onClick = { actions.onChar(c.toString()) })
}

@Composable
private fun NumericPage(state: KeyboardState, actions: ImeActions, keyHeight: Dp) {
    Row(Modifier.fillMaxWidth()) {
        SymbolKey('1', keyHeight, actions)
        SymbolKey('2', keyHeight, actions)
        SymbolKey('3', keyHeight, actions)
        RepeatKey(keyHeight, 1f, actions::onBackspace) { IconBackspace() }
    }
    Row(Modifier.fillMaxWidth()) {
        SymbolKey('4', keyHeight, actions)
        SymbolKey('5', keyHeight, actions)
        SymbolKey('6', keyHeight, actions)
        SymbolKey('+', keyHeight, actions)
    }
    Row(Modifier.fillMaxWidth()) {
        SymbolKey('7', keyHeight, actions)
        SymbolKey('8', keyHeight, actions)
        SymbolKey('9', keyHeight, actions)
        SymbolKey('-', keyHeight, actions)
    }
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1f, onClick = { actions.setPage(KeyboardPage.LETTERS) }) {
            Text("ABC", fontSize = 14.sp)
        }
        SymbolKey('*', keyHeight, actions)
        SymbolKey('0', keyHeight, actions)
        SymbolKey('#', keyHeight, actions)
        EnterKey(state, actions, keyHeight, 1f)
    }
}

@Composable
private fun BottomRow(
    state: KeyboardState,
    actions: ImeActions,
    keyHeight: Dp,
    leftLabel: String,
    leftTarget: KeyboardPage,
    commaChar: String = ",",
    periodChar: String = ".",
) {
    Row(Modifier.fillMaxWidth()) {
        SpecialKey(keyHeight, 1.5f, onClick = { actions.setPage(leftTarget) }) {
            Text(leftLabel, fontSize = 14.sp)
        }
        SpecialKey(keyHeight, 1f, onClick = { actions.setPage(KeyboardPage.EMOJI) }) { IconEmoji() }
        CharKey(commaChar, keyHeight, 1f, onClick = { actions.onChar(commaChar) })
        SpaceKey(keyHeight, 4f, actions::onSpace, actions::switchToNextIme)
        CharKey(periodChar, keyHeight, 1f, onClick = { actions.onChar(periodChar) })
        EnterKey(state, actions, keyHeight, 1.5f)
    }
}

@Composable
private fun RowScope.EnterKey(state: KeyboardState, actions: ImeActions, keyHeight: Dp, weight: Float) {
    SpecialKey(
        height = keyHeight,
        weight = weight,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = actions::onEnter,
    ) {
        val label = enterLabel(state.enterAction)
        if (label != null) {
            Text(label, fontSize = 14.sp)
        } else {
            IconReturn()
        }
    }
}

private fun enterLabel(action: EnterAction): String? = when (action) {
    EnterAction.GO -> "Go"
    EnterAction.SEARCH -> "Search"
    EnterAction.SEND -> "Send"
    EnterAction.NEXT -> "Next"
    EnterAction.DONE -> "Done"
    EnterAction.PREVIOUS -> "Prev"
    EnterAction.RETURN -> null
}
