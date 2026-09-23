package com.vayunmathur.communicate.data.rcs

import com.vayunmathur.communicate.BuildConfig

/**
 * Single on/off gate for the experimental **RCS single-registration** line.
 *
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature]: backed by
 * [BuildConfig.DEV_BUILD] (`true` for `assembleDev`/`assembleDebug`, `false` for release).
 * All `if (RcsFeature.enabled)` branches are stripped from release by R8.
 */
object RcsFeature {
    val enabled: Boolean get() = BuildConfig.DEV_BUILD
}
