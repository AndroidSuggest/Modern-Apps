package com.vayunmathur.code.util

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** [CodeSettingsActions] implementation, persisting through [EditorPrefs]. */
internal class EditorSettingsActions(private val vm: EditorViewModel) : CodeSettingsActions {

    override fun setFontSize(size: Int) {
        vm.fontSizeState.value = size
        vm.scope.launch { vm.prefs.setFontSize(size) }
    }

    override fun setTabWidth(width: Int) {
        vm.tabWidthState.value = width
        vm.scope.launch { vm.prefs.setTabWidth(width) }
    }

    override fun setThemeMode(mode: String) {
        vm.themeModeState.value = mode
        vm.scope.launch { vm.prefs.setThemeMode(mode) }
    }

    override fun setAutoIndent(enabled: Boolean) {
        vm.autoIndentState.value = enabled
        vm.scope.launch { vm.prefs.setAutoIndent(enabled) }
    }

    override fun setAutoCloseBrackets(enabled: Boolean) {
        vm.autoCloseBracketsState.value = enabled
        vm.scope.launch { vm.prefs.setAutoCloseBrackets(enabled) }
    }
}
