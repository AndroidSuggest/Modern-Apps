package com.vayunmathur.communicate.data.whatsapp

import com.vayunmathur.communicate.BuildConfig

/**
 * Single on/off gate for the experimental **WhatsApp primary client** feature.
 *
 * Backed by [BuildConfig.DEV_BUILD], which is `true` for `assembleDev`/`assembleDebug` and `false`
 * for `assembleRelease`. Because it's a compile-time constant, every `if (WhatsAppFeature.enabled)`
 * branch (and everything it reaches) is stripped from release builds by R8, so the release variant
 * never registers, connects, or exposes WhatsApp UI/lines.
 *
 * WhatsApp is an unofficial primary-client reimplementation and carries real ToS/ban risk (a live
 * test number was banned with `violation_type:15`), so it must not ship in the release variant.
 */
object WhatsAppFeature {
    val enabled: Boolean get() = BuildConfig.DEV_BUILD
}
