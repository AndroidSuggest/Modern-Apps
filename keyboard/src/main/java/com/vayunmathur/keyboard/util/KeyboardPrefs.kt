package com.vayunmathur.keyboard.util

import com.vayunmathur.library.util.DataStoreUtils

/**
 * All user-tunable keyboard settings, snapshot into one immutable value so both the
 * setup Activity and the IME service read the same shape. Persisted via [DataStoreUtils]
 * (shared, offline preferences) under the [Keys] names.
 */
data class KeyboardSettings(
    val haptic: Boolean = true,
    val sound: Boolean = false,
    val autoCapitalize: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    val showSuggestions: Boolean = true,
    val autoCorrect: Boolean = false,
    /** Show a persistent 1–0 number row above the letters. */
    val numberRow: Boolean = true,
    /** Multiplier on the base key height (0.8..1.4). */
    val keyHeightScale: Float = 1f,
    /** Ids of the layouts the user enabled, in the order the language key cycles them. */
    val layoutIds: List<String> = listOf(KeyboardLayouts.DEFAULT.id),
    /** Id of the layout currently in use; one of [layoutIds]. */
    val activeLayoutId: String = KeyboardLayouts.DEFAULT.id,
) {
    /** The enabled layouts, unknown/removed ids dropped and never empty. */
    val layouts: List<KeyboardLayout>
        get() = layoutIds.mapNotNull { KeyboardLayouts.byId(it) }
            .ifEmpty { listOf(KeyboardLayouts.DEFAULT) }

    /** The layout the letter page draws, falling back to the first enabled one. */
    val activeLayout: KeyboardLayout
        get() = layouts.firstOrNull { it.id == activeLayoutId } ?: layouts.first()

    object Keys {
        const val HAPTIC = "kb_haptic"
        const val SOUND = "kb_sound"
        const val AUTO_CAP = "kb_auto_cap"
        const val DOUBLE_SPACE_PERIOD = "kb_double_space_period"
        const val SHOW_SUGGESTIONS = "kb_show_suggestions"
        const val AUTO_CORRECT = "kb_auto_correct"
        const val NUMBER_ROW = "kb_number_row"
        const val KEY_HEIGHT = "kb_key_height"
        const val LAYOUTS = "kb_layouts"
        const val ACTIVE_LAYOUT = "kb_active_layout"
    }

    companion object {
        /**
         * Enabled layouts are stored as one comma-separated string rather than a string set,
         * because the order is the order the language key cycles through them.
         */
        fun encodeLayouts(ids: List<String>): String = ids.joinToString(",")

        fun decodeLayouts(stored: String?): List<String> =
            stored?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
                ?: listOf(KeyboardLayouts.DEFAULT.id)

        /** Read the current persisted snapshot (defaults applied for unset keys). */
        fun load(ds: DataStoreUtils): KeyboardSettings = KeyboardSettings(
            haptic = ds.getBoolean(Keys.HAPTIC, true),
            sound = ds.getBoolean(Keys.SOUND, false),
            autoCapitalize = ds.getBoolean(Keys.AUTO_CAP, true),
            doubleSpacePeriod = ds.getBoolean(Keys.DOUBLE_SPACE_PERIOD, true),
            showSuggestions = ds.getBoolean(Keys.SHOW_SUGGESTIONS, true),
            autoCorrect = ds.getBoolean(Keys.AUTO_CORRECT, false),
            numberRow = ds.getBoolean(Keys.NUMBER_ROW, true),
            keyHeightScale = (ds.getDouble(Keys.KEY_HEIGHT) ?: 1.0).toFloat(),
            layoutIds = decodeLayouts(ds.getString(Keys.LAYOUTS)),
            activeLayoutId = ds.getString(Keys.ACTIVE_LAYOUT) ?: KeyboardLayouts.DEFAULT.id,
        )
    }
}
