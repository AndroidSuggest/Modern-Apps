package com.vayunmathur.keyboard.ime

import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.vayunmathur.keyboard.ui.KeyboardScreen
import com.vayunmathur.keyboard.util.Dictionary
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.KeyboardSettings
import com.vayunmathur.keyboard.util.ShiftState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The input method (IME). Renders the keyboard with Compose and turns key actions into edits
 * on the target field's [InputConnection].
 *
 * Compose views need a [LifecycleOwner], [ViewModelStoreOwner] and [SavedStateRegistryOwner]
 * in their view tree; an [InputMethodService] provides none, so this service implements all
 * three and drives the lifecycle from the IME window callbacks.
 */
/** Max gap between two shift taps to latch caps-lock. */
private const val DOUBLE_TAP_MS = 300L

class KeyboardService : InputMethodService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, ImeActions {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var ds: DataStoreUtils
    private val kbState = KeyboardState()
    private var dictionary: Dictionary = Dictionary.EMPTY

    /** The word currently being composed (underlined) on the letters page. */
    private val composing = StringBuilder()
    private var lastSpaceTime = 0L
    private var lastShiftTime = 0L

    /** Enter behaviour derived from the current field. */
    private var editorActionId = EditorInfo.IME_ACTION_UNSPECIFIED
    private var enterSendsAction = false

    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        ds = DataStoreUtils.getInstance(this)
        kbState.settings = KeyboardSettings.load(ds)
        vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator

        // Load the dictionary off the main thread; suggestions stay empty until it is ready.
        scope.launch { dictionary = Dictionary.load(this@KeyboardService) }
        observeSettings()
    }

    /** Keep [KeyboardState.settings] in sync with DataStore so changes apply live. */
    private fun observeSettings() {
        val keys = KeyboardSettings.Keys
        scope.launch { ds.booleanFlow(keys.HAPTIC).collectLatest { update { copy(haptic = it) } } }
        scope.launch { ds.booleanFlow(keys.SOUND).collectLatest { update { copy(sound = it) } } }
        scope.launch { ds.booleanFlow(keys.AUTO_CAP).collectLatest { update { copy(autoCapitalize = it) } } }
        scope.launch { ds.booleanFlow(keys.DOUBLE_SPACE_PERIOD).collectLatest { update { copy(doubleSpacePeriod = it) } } }
        scope.launch { ds.booleanFlow(keys.SHOW_SUGGESTIONS).collectLatest { update { copy(showSuggestions = it) } } }
        scope.launch { ds.booleanFlow(keys.AUTO_CORRECT).collectLatest { update { copy(autoCorrect = it) } } }
        scope.launch { ds.booleanFlow(keys.NUMBER_ROW).collectLatest { update { copy(numberRow = it) } } }
        scope.launch { ds.doubleFlow(keys.KEY_HEIGHT).collectLatest { update { copy(keyHeightScale = it.toFloat()) } } }
        scope.launch {
            ds.stringFlow(keys.LAYOUTS).collectLatest {
                update { copy(layoutIds = KeyboardSettings.decodeLayouts(it)) }
            }
        }
        scope.launch { ds.stringFlow(keys.ACTIVE_LAYOUT).collectLatest { update { copy(activeLayoutId = it) } } }
    }

    private inline fun update(transform: KeyboardSettings.() -> KeyboardSettings) {
        kbState.settings = kbState.settings.transform()
    }

    override fun onCreateInputView(): View {
        // Compose resolves its per-window recomposer from the IME window's decor view
        // (the ancestor of the framework's `parentPanel`), NOT from our ComposeView.
        // Setting the ViewTree owners only on the ComposeView therefore crashes with
        // "ViewTreeLifecycleOwner not found"; they must live on the window decor view.
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@KeyboardService)
            setViewTreeViewModelStoreOwner(this@KeyboardService)
            setViewTreeSavedStateRegistryOwner(this@KeyboardService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Some devices dispatch window insets to the input view; capture them when
            // they do (complements the decor-view read in updateBottomInset()).
            setOnApplyWindowInsetsListener { _, insets ->
                kbState.bottomInsetPx = insets.getInsets(
                    android.view.WindowInsets.Type.navigationBars(),
                ).bottom
                insets
            }
            setContent {
                DynamicTheme {
                    KeyboardScreen(kbState, this@KeyboardService)
                }
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        configureForEditor(info)
        kbState.suggestions = emptyList()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        updateAutoCapShift()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        updateBottomInset()
    }

    /**
     * Read the navigation-bar height from the window so the keyboard can pad clear of
     * it. Reads immediately and again after the next layout pass (rootWindowInsets is
     * often not populated yet when onWindowShown/onStartInputView first run).
     */
    private fun updateBottomInset() {
        val decor = window?.window?.decorView ?: return
        val read = {
            decor.rootWindowInsets?.let {
                kbState.bottomInsetPx = it.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
            }
        }
        read()
        decor.post { read() }
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        // Keep the composition alive (STARTED, not DESTROYED) so re-showing is instant.
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        composing.setLength(0)
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        scope.cancel()
        super.onDestroy()
    }

    // --- Editor configuration ---

    private fun configureForEditor(info: EditorInfo) {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = (cls == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        // Phone gets its own dial-pad layout (FUTO phone.yaml); number/datetime share the
        // numeric layout (FUTO number.yaml). Everything else uses letters.
        val isPhone = cls == InputType.TYPE_CLASS_PHONE
        val isNumeric = cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_DATETIME

        kbState.passwordField = isPassword
        kbState.textVariation = when {
            cls == InputType.TYPE_CLASS_TEXT &&
                (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) -> TextVariation.EMAIL
            cls == InputType.TYPE_CLASS_TEXT &&
                variation == InputType.TYPE_TEXT_VARIATION_URI -> TextVariation.URL
            else -> TextVariation.NORMAL
        }
        kbState.basePage = when {
            isPhone -> KeyboardPage.PHONE
            isNumeric -> KeyboardPage.NUMERIC
            else -> KeyboardPage.LETTERS
        }
        kbState.page = kbState.basePage
        kbState.shift = ShiftState.OFF

        // Which action Enter performs, using AOSP LatinIME's precedence:
        //  1. IME_FLAG_NO_ENTER_ACTION  -> Enter is a plain newline, whatever imeOptions says.
        //  2. a custom actionLabel      -> perform info.actionId. This is NOT the imeOptions
        //     action: apps calling setImeActionLabel("Search", id) usually leave imeOptions
        //     at UNSPECIFIED, so reading only imeOptions loses the action entirely and Enter
        //     falls back to a newline.
        //  3. otherwise                 -> perform imeOptions & IME_MASK_ACTION.
        val optionsAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val customLabel = info.actionLabel?.toString()?.takeIf { it.isNotBlank() }

        editorActionId = if (customLabel != null) info.actionId else optionsAction
        enterSendsAction = !noAction && when {
            customLabel != null -> true
            // UNSPECIFIED means "the app didn't say"; treat it as a newline rather than
            // firing action 0, which most multi-line fields do not expect.
            else -> optionsAction != EditorInfo.IME_ACTION_NONE &&
                optionsAction != EditorInfo.IME_ACTION_UNSPECIFIED
        }

        kbState.enterActionLabel = if (enterSendsAction) customLabel else null
        kbState.enterAction = if (!enterSendsAction) EnterAction.RETURN else when (optionsAction) {
            EditorInfo.IME_ACTION_GO -> EnterAction.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
            EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
            EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
            // A custom action with no recognisable imeOptions action still sends; the key
            // shows the app's own label (enterActionLabel) rather than the return glyph.
            else -> EnterAction.RETURN
        }
    }

    /**
     * Composing (word tracking) is needed for either suggestions or autocorrect, and only
     * makes sense for plain text fields (never passwords or the numeric layout). The only
     * dictionary we ship is English, so it also stays off for every other layout rather than
     * offering English words to someone writing Greek.
     */
    private fun useComposing(): Boolean =
        (kbState.settings.showSuggestions || kbState.settings.autoCorrect) &&
            !kbState.passwordField && kbState.basePage == KeyboardPage.LETTERS &&
            kbState.settings.activeLayout.englishDictionary

    // --- ImeActions ---

    override fun onChar(text: String) {
        feedback()
        val ic = currentInputConnection ?: return
        if (useComposing() && text.length == 1 && text[0].isLetter()) {
            composing.append(text)
            ic.setComposingText(composing, 1)
            updateSuggestions()
        } else {
            commitCurrentWord(ic, autoCorrect = false)
            ic.commitText(text, 1)
            kbState.suggestions = emptyList()
        }
        consumeShift()
        updateAutoCapShift()
    }

    override fun onCharLongPress(base: Char) {
        val alts = kbState.settings.activeLayout.alternates[base] ?: return
        if (alts.isEmpty()) return
        feedback()
        val ic = currentInputConnection ?: return
        commitCurrentWord(ic, autoCorrect = false)
        ic.commitText(alts[0].toString(), 1)
        kbState.suggestions = emptyList()
        consumeShift()
        updateAutoCapShift()
    }

    override fun onBackspace() {
        feedback()
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                kbState.suggestions = emptyList()
            } else {
                ic.setComposingText(composing, 1)
                updateSuggestions()
            }
        } else {
            // Delete a selection if there is one, else one char before the cursor.
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        }
        updateAutoCapShift()
    }

    override fun onEnter() {
        feedback()
        val ic = currentInputConnection ?: return
        // Finish the composing word first: performEditorAction hands control to the app,
        // which would otherwise read the field without the last (still-composing) word.
        commitCurrentWord(ic, autoCorrect = false)
        // performEditorAction returns false when the target can't handle the action (a
        // dead connection, or an actionId the app declines). Fall back to a real Enter so
        // the key never silently does nothing.
        val handled = enterSendsAction && ic.performEditorAction(editorActionId)
        if (!handled) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        kbState.suggestions = emptyList()
        updateAutoCapShift()
    }

    override fun onSpace() {
        feedback()
        val ic = currentInputConnection ?: return
        commitCurrentWord(ic, autoCorrect = kbState.settings.autoCorrect)
        val before = ic.getTextBeforeCursor(2, 0)
        val now = SystemClock.uptimeMillis()
        val doubleSpace = kbState.settings.doubleSpacePeriod && before != null && before.length == 2 &&
            before[1] == ' ' && before[0].isLetterOrDigit() && now - lastSpaceTime < 1000
        if (doubleSpace) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
        } else {
            ic.commitText(" ", 1)
        }
        lastSpaceTime = now
        kbState.suggestions = emptyList()
        updateAutoCapShift()
    }

    override fun onShift() {
        feedback()
        // Apply shift immediately on every tap; a quick second tap (while already shifted)
        // latches caps-lock. No waiting for a double-tap, so shift feels instant.
        val now = SystemClock.uptimeMillis()
        kbState.shift = when {
            now - lastShiftTime < DOUBLE_TAP_MS && kbState.shift != ShiftState.OFF -> ShiftState.CAPS_LOCK
            kbState.shift == ShiftState.OFF -> ShiftState.SHIFTED
            else -> ShiftState.OFF
        }
        lastShiftTime = now
    }

    override fun onCapsLock() {
        feedback()
        kbState.shift = ShiftState.CAPS_LOCK
    }

    override fun setPage(page: KeyboardPage) {
        feedback()
        kbState.page = page
    }

    override fun commitSuggestion(word: String) {
        feedback()
        val ic = currentInputConnection ?: return
        ic.setComposingText(word, 1)
        ic.finishComposingText()
        ic.commitText(" ", 1)
        composing.setLength(0)
        lastSpaceTime = SystemClock.uptimeMillis()
        kbState.suggestions = emptyList()
        updateAutoCapShift()
    }

    /**
     * Cycle to the next layout the user enabled (the globe key, which only appears when
     * there is more than one). The layouts may not even share a script, so anything still
     * composing is committed first rather than carried across.
     */
    override fun nextLayout() {
        feedback()
        val ids = kbState.settings.layouts.map { it.id }
        if (ids.size < 2) return
        val next = ids[(ids.indexOf(kbState.settings.activeLayout.id) + 1) % ids.size]
        currentInputConnection?.let { commitCurrentWord(it, autoCorrect = false) }
        kbState.settings = kbState.settings.copy(activeLayoutId = next)
        kbState.shift = ShiftState.OFF
        kbState.suggestions = emptyList()
        scope.launch { ds.setString(KeyboardSettings.Keys.ACTIVE_LAYOUT, next) }
        updateAutoCapShift()
    }

    override fun switchToNextIme() {
        feedback()
        val switched = switchToNextInputMethod(false)
        if (!switched) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    // --- Editing helpers ---

    /** Finish the composing word, optionally replacing it with an autocorrect suggestion. */
    private fun commitCurrentWord(ic: InputConnection, autoCorrect: Boolean) {
        if (composing.isEmpty()) return
        if (autoCorrect) {
            val typed = composing.toString()
            if (!dictionary.contains(typed)) {
                val fix = dictionary.autocorrect(typed)
                if (fix != null && !fix.equals(typed, ignoreCase = true)) {
                    ic.setComposingText(fix, 1)
                }
            }
        }
        ic.finishComposingText()
        composing.setLength(0)
    }

    private fun updateSuggestions() {
        if (!kbState.settings.showSuggestions || !useComposing()) {
            kbState.suggestions = emptyList()
            return
        }
        val prefix = composing.toString()
        kbState.suggestions = if (prefix.isBlank()) emptyList() else dictionary.suggestions(prefix, 3)
    }

    private fun consumeShift() {
        if (kbState.shift == ShiftState.SHIFTED) kbState.shift = ShiftState.OFF
    }

    /** Auto-capitalize the shift key when the cursor sits at the start of a sentence. */
    private fun updateAutoCapShift() {
        if (kbState.basePage != KeyboardPage.LETTERS) return
        // Shift is a second character layer, not upper case, in scripts like Devanagari or
        // Thai — auto-capitalizing there would silently swap the whole layout.
        if (!kbState.settings.activeLayout.cased) return
        if (kbState.passwordField || kbState.textVariation != TextVariation.NORMAL) return
        if (kbState.shift == ShiftState.CAPS_LOCK) return
        if (!kbState.settings.autoCapitalize) return
        if (composing.isNotEmpty()) return
        kbState.shift = if (isAtSentenceStart()) ShiftState.SHIFTED else ShiftState.OFF
    }

    private fun isAtSentenceStart(): Boolean {
        val ic = currentInputConnection ?: return true
        val before = ic.getTextBeforeCursor(2, 0) ?: return true
        if (before.isEmpty()) return true
        val last = before[before.length - 1]
        if (last == '\n') return true
        if (before.length < 2) return false
        val prev = before[before.length - 2]
        return last == ' ' && (prev == '.' || prev == '?' || prev == '!')
    }

    // --- Feedback ---

    private fun feedback() {
        if (kbState.settings.haptic) {
            // A light key "tick" (like the stock keyboard), not a full-strength buzz.
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
        if (kbState.settings.sound) {
            getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
        }
    }
}
