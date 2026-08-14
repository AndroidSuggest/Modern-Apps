package com.vayunmathur.communicate.data.signal

import com.vayunmathur.communicate.BuildConfig

/**
 * Single on/off gate for the experimental **Signal** primary client feature.
 *
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.WhatsAppFeature]: backed by
 * [BuildConfig.DEV_BUILD] (`true` for `assembleDev`/`assembleDebug`, `false` for release).
 * All `if (SignalFeature.enabled)` branches are stripped from release by R8.
 */
object SignalFeature {
    val enabled: Boolean get() = BuildConfig.DEV_BUILD
}
