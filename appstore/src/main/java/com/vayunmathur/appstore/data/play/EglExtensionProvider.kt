package com.vayunmathur.appstore.data.play

/**
 * Minimal stub for EGL provider – real query may require GL thread.
 * For V1 we return empty to avoid crashing; DeviceInfoProvider sets placeholder values.
 */
object EglExtensionProvider {
    val eglExtensions: List<String> = emptyList()
    fun getGlVersion(): String = "OpenGL ES 3.0"
    fun getGlExtensions(): List<String> = emptyList()
}
