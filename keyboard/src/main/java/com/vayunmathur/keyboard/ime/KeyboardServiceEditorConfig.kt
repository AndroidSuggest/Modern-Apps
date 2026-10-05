package com.vayunmathur.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.vayunmathur.keyboard.util.KeyboardPage
import com.vayunmathur.keyboard.util.ShiftState

/**
 * Derives the keyboard's page, text variation and enter behaviour from the target field.
 *
 * Split from KeyboardService.configureForEditor (CyclomaticComplexMethod 30); behaviour
 * identical, only the decision tree is named per branch.
 */
internal fun KeyboardService.configureForEditor(info: EditorInfo) {
    val cls = info.inputType and InputType.TYPE_MASK_CLASS
    val variation = info.inputType and InputType.TYPE_MASK_VARIATION
    applyFieldKind(cls, variation)
    applyEnterAction(info)
    kbState.shift = ShiftState.OFF
}

/** Page, variation and password flag from the input class/variation. */
private fun KeyboardService.applyFieldKind(cls: Int, variation: Int) {
    kbState.passwordField = isPasswordField(cls, variation)
    // Phone gets its own dial-pad layout (FUTO phone.yaml); number/datetime share the
    // numeric layout (FUTO number.yaml). Everything else uses letters.
    kbState.textVariation = textVariationFor(cls, variation)
    kbState.basePage = basePageFor(cls)
    kbState.page = kbState.basePage
}

private fun isPasswordField(cls: Int, variation: Int): Boolean {
    if (cls == InputType.TYPE_CLASS_NUMBER) {
        return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
    if (cls != InputType.TYPE_CLASS_TEXT) return false
    return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
}

private fun textVariationFor(cls: Int, variation: Int): TextVariation {
    if (cls != InputType.TYPE_CLASS_TEXT) return TextVariation.NORMAL
    return when (variation) {
        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> TextVariation.EMAIL
        InputType.TYPE_TEXT_VARIATION_URI -> TextVariation.URL
        else -> TextVariation.NORMAL
    }
}

private fun basePageFor(cls: Int): KeyboardPage = when (cls) {
    InputType.TYPE_CLASS_PHONE -> KeyboardPage.PHONE
    InputType.TYPE_CLASS_NUMBER,
    InputType.TYPE_CLASS_DATETIME -> KeyboardPage.NUMERIC
    else -> KeyboardPage.LETTERS
}

/**
 * Which action Enter performs, using AOSP LatinIME's precedence:
 *  1. IME_FLAG_NO_ENTER_ACTION  -> Enter is a plain newline, whatever imeOptions says.
 *  2. a custom actionLabel      -> perform info.actionId. This is NOT the imeOptions
 *     action: apps calling setImeActionLabel("Search", id) usually leave imeOptions
 *     at UNSPECIFIED, so reading only imeOptions loses the action entirely and Enter
 *     falls back to a newline.
 *  3. otherwise                 -> perform imeOptions & IME_MASK_ACTION.
 */
private fun KeyboardService.applyEnterAction(info: EditorInfo) {
    val optionsAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
    val noAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
    val customLabel = info.actionLabel?.toString()?.takeIf { it.isNotBlank() }

    editorActionId = if (customLabel != null) info.actionId else optionsAction
    enterSendsAction = shouldSendAction(noAction, customLabel, optionsAction)

    kbState.enterActionLabel = if (enterSendsAction) customLabel else null
    kbState.enterAction = enterActionFor(optionsAction, enterSendsAction)
}

private fun shouldSendAction(noAction: Boolean, customLabel: String?, optionsAction: Int): Boolean {
    if (noAction) return false
    if (customLabel != null) return true
    // UNSPECIFIED means "the app didn't say"; treat it as a newline rather than
    // firing action 0, which most multi-line fields do not expect.
    return optionsAction != EditorInfo.IME_ACTION_NONE &&
        optionsAction != EditorInfo.IME_ACTION_UNSPECIFIED
}

private fun enterActionFor(optionsAction: Int, sendsAction: Boolean): EnterAction {
    if (!sendsAction) return EnterAction.RETURN
    return when (optionsAction) {
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
