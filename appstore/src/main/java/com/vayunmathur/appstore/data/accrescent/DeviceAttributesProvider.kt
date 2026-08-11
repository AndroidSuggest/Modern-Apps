package com.vayunmathur.appstore.data.accrescent

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import app.accrescent.appstore.v1.DeviceAttributes
import com.android.bundle.DeviceSpec

/**
 * Builds the [DeviceAttributes] / bundletool [DeviceSpec] the Accrescent API uses to pick which
 * split (config) APKs this device needs.
 *
 * Starts minimal — the long-stable DeviceSpec fields that actually drive split selection
 * (ABIs, locales, features, density, SDK) plus best-effort build/SoC identification — and
 * deliberately leaves OpenGL extension enumeration empty to avoid dragging in an EGL context
 * just to browse. If the API rejects requests without more (`INVALID_ARGUMENT` /
 * `FAILED_PRECONDITION`), fill in the remaining fields here.
 */
class DeviceAttributesProvider(private val context: Context) {

    fun deviceAttributes(): DeviceAttributes =
        DeviceAttributes.newBuilder().setSpec(deviceSpec()).build()

    fun deviceSpec(): DeviceSpec {
        val builder = DeviceSpec.newBuilder()
            .addAllSupportedAbis(Build.SUPPORTED_ABIS?.toList().orEmpty())
            .addAllSupportedLocales(supportedLocales())
            .addAllDeviceFeatures(deviceFeatures())
            .setScreenDensity(screenDensity())
            .setSdkVersion(Build.VERSION.SDK_INT)
            .setBuildBrand(Build.BRAND)
            .setBuildDevice(Build.DEVICE)
            .setCodename(Build.VERSION.CODENAME)

        // SoC identification is API 31+, which is this store's minSdk. These are non-null
        // (they read "unknown" when unavailable), which the server can simply ignore.
        builder.socManufacturer = Build.SOC_MANUFACTURER
        builder.socModel = Build.SOC_MODEL

        ramBytes()?.let { builder.ramBytes = it }

        return builder.build()
    }

    private fun supportedLocales(): List<String> {
        val locales = context.resources.configuration.locales
        return (0 until locales.size()).map { locales[it].toLanguageTag() }
    }

    private fun deviceFeatures(): List<String> = try {
        context.packageManager.systemAvailableFeatures.mapNotNull { it.name }
    } catch (_: Exception) {
        emptyList()
    }

    private fun screenDensity(): Int = try {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)
        metrics.densityDpi
    } catch (_: Exception) {
        context.resources.displayMetrics.densityDpi
    }

    private fun ramBytes(): Long? = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    } catch (_: Exception) {
        null
    }
}
