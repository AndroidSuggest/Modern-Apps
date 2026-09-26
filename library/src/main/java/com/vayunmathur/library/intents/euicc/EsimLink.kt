package com.vayunmathur.library.intents.euicc

/**
 * The standard eSIM universal link both ends of the provisioning handoff share:
 * `https://esimsetup.android.com/esim_qrcode_provisioning?carddata=<activation-code>`.
 *
 * Scanners build this link from the QR payload and fire `ACTION_VIEW`; the LPA
 * whose manifest declares the host+path receives it instead of a browser. This
 * is the same URL the platform's own eSIM flow uses — no app-specific actions
 * or extras.
 */
object EsimLink {
    const val SCHEME = "https"
    const val HOST = "esimsetup.android.com"
    const val PATH = "/esim_qrcode_provisioning"
    const val CARDDATA_PARAM = "carddata"

    /**
     * Package of our euicc app. Scanners pin the VIEW intent here with
     * `setPackage` so it reaches the LPA directly: an unpinned link would
     * otherwise resolve to the browser (which cannot provision anything), and
     * verified App Links are unobtainable for a domain we don't own. Must match
     * the euicc module's `applicationId`.
     */
    const val LPA_PACKAGE = "com.vayunmathur.euicc"

    /** Builds the provisioning link for a scanned activation code. */
    fun provisioningUri(activationCode: String): android.net.Uri =
        android.net.Uri.Builder()
            .scheme(SCHEME)
            .authority(HOST)
            .path(PATH)
            .appendQueryParameter(CARDDATA_PARAM, activationCode)
            .build()
}
