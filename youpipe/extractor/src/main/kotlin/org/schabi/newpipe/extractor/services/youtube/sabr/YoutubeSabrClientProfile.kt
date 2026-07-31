package org.schabi.newpipe.extractor.services.youtube.sabr

enum class YoutubeSabrClientProfile(
    private val clientName: String,
    private val clientId: String,
    private val clientVersion: String,
    private val osName: String?,
    private val osVersion: String?,
    private val webLike: Boolean,
    private val userAgent: String?
) {
    WEB("WEB", "1", "2.20250122.04.00", null, null, false, null),
    MWEB(
        "MWEB", "2", "2.20250122.04.00", null, null, true,
        "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
    ),
    WEB_EMBEDDED("WEB_EMBEDDED_PLAYER", "56", "1.20250121.00.00", null, null, true, null),
    ANDROID(
        "ANDROID", "3", "21.03.36", "Android", "16", false,
        "com.google.android.youtube/21.03.36 (Linux; U; Android 15; US) gzip"
    ),
    ANDROID_VR(
        "ANDROID_VR", "28", "1.65.10", "Android", "12L", false,
        "com.google.android.apps.youtube.vr.oculus/1.65.10 " +
            "(Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    ),
    IOS(
        "IOS", "5", "19.45.4", "iOS", "18.1.0.22B83", false,
        "com.google.ios.youtube/19.45.4(iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X; US)"
    ),
    TVHTML5(
        "TVHTML5", "7", "7.20250923.13.00", null, null, true,
        "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/15.4 Safari/605.1.15"
    );

    fun getClientName(): String = clientName
    fun getClientId(): String = clientId
    fun getClientVersion(): String = clientVersion
    fun getOsName(): String? = osName
    fun getOsVersion(): String? = osVersion
    fun isWebLike(): Boolean = webLike
    fun getUserAgent(): String? = userAgent

    // Kotlin property aliases for idiomatic access
    val clientNameProp: String get() = clientName
    val clientIdProp: String get() = clientId
    val clientVersionProp: String get() = clientVersion
    val osNameProp: String? get() = osName
    val osVersionProp: String? get() = osVersion
    val isWebLikeProp: Boolean get() = webLike
    val userAgentProp: String? get() = userAgent
}
