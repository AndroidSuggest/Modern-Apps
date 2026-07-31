package org.schabi.newpipe.extractor.utils

import java.util.Locale

/**
 * This class contains a simple implementation of [Locale.forLanguageTag] for Android
 * API levels below 21 (Lollipop). This is needed as core library desugaring does not backport that
 * method as of this writing.
 *
 * Relevant issue: https://issuetracker.google.com/issues/171182330
 */
object LocaleCompat {

    // Source: The AndroidX LocaleListCompat class's private forLanguageTagCompat() method.
    // Use Locale.forLanguageTag() on Android API level >= 21 / Java instead.
    @JvmStatic
    fun forLanguageTag(str: String): Locale? {
        if (str.contains("-")) {
            // Kotlin's split keeps trailing empty strings and rejects a negative limit,
            // so the upstream Java `split("-", -1)` maps to an unlimited split here.
            val args = str.split("-")
            return when {
                args.size > 2 -> Locale(args[0], args[1], args[2])
                args.size > 1 -> Locale(args[0], args[1])
                args.size == 1 -> Locale(args[0])
                else -> null
            }
        } else if (str.contains("_")) {
            val args = str.split("_")
            return when {
                args.size > 2 -> Locale(args[0], args[1], args[2])
                args.size > 1 -> Locale(args[0], args[1])
                args.size == 1 -> Locale(args[0])
                else -> null
            }
        } else {
            return Locale(str)
        }
    }
}
