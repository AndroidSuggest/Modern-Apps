package com.vayunmathur.keyboard.ime

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.KeyboardSettings
import com.vayunmathur.keyboard.util.ShiftState

/** What the Enter key should do, derived from the target field's [android.view.inputmethod.EditorInfo]. */
enum class EnterAction { RETURN, GO, SEARCH, SEND, NEXT, DONE, PREVIOUS }

/**
 * Text-field flavour that tweaks the letters layout: EMAIL surfaces `@`, URL surfaces
 * `/` (both in place of the comma key), NORMAL keeps punctuation. Number/phone fields
 * use the dedicated numeric page instead (see [KeyboardState.basePage]).
 */
enum class TextVariation { NORMAL, EMAIL, URL }

/**
 * Compose-observable UI state owned by the IME service. The service mutates these fields in
 * response to key actions and editor changes; [com.vayunmathur.keyboard.ui.KeyboardScreen]
 * reads them to render. Kept as a plain holder (not a ViewModel) since the service owns its
 * whole lifetime.
 */
class KeyboardState {
    /** Currently visible page of keys. */
    var page by mutableStateOf(KeyboardPage.LETTERS)

    /** The non-emoji page to return to (letters normally, numeric for number fields). */
    var basePage by mutableStateOf(KeyboardPage.LETTERS)

    var shift by mutableStateOf(ShiftState.OFF)

    /** Up to three completion/correction candidates for the word being typed. */
    var suggestions by mutableStateOf<List<String>>(emptyList())

    var settings by mutableStateOf(KeyboardSettings())

    var enterAction by mutableStateOf(EnterAction.RETURN)

    /**
     * App-supplied label for a custom IME action ([android.view.inputmethod.EditorInfo.actionLabel]),
     * or null when the action is one of the standard [EnterAction] values. Takes precedence over
     * [enterAction] when labelling the enter key.
     */
    var enterActionLabel by mutableStateOf<String?>(null)

    /** True when the target field is a password field (disables suggestions/composing). */
    var passwordField by mutableStateOf(false)

    /** Field flavour that adapts the letters layout (email/url punctuation). */
    var textVariation by mutableStateOf(TextVariation.NORMAL)

    /**
     * Navigation-bar height in pixels, read from the window by the service. In an IME
     * the window insets are not reliably dispatched to Compose, so we pad the keyboard
     * bottom by this value ourselves to keep the last row clear of the system bar.
     */
    var bottomInsetPx by mutableStateOf(0)
}

/**
 * Callbacks the keyboard UI invokes; implemented by the service, which owns the
 * [android.view.inputmethod.InputConnection] and applies the edits.
 */
interface ImeActions {
    fun onChar(text: String)
    fun onBackspace()
    fun onEnter()
    fun onSpace()
    fun onShift()
    fun onCapsLock()
    fun setPage(page: KeyboardPage)
    fun commitSuggestion(word: String)

    /** Switch to the next layout the user enabled in settings (the globe key). */
    fun nextLayout()
    fun switchToNextIme()

    companion object {
        /**
         * Does nothing. Lets `src/screenshotTest` render [
         * com.vayunmathur.keyboard.ui.KeyboardScreen] without an IME service behind it,
         * which is where the store listing images come from.
         */
        val Noop: ImeActions = object : ImeActions {
            override fun onChar(text: String) {}
            override fun onBackspace() {}
            override fun onEnter() {}
            override fun onSpace() {}
            override fun onShift() {}
            override fun onCapsLock() {}
            override fun setPage(page: KeyboardPage) {}
            override fun commitSuggestion(word: String) {}
            override fun nextLayout() {}
            override fun switchToNextIme() {}
        }
    }
}
