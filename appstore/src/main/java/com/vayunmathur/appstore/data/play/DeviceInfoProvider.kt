package com.vayunmathur.appstore.data.play

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale
import java.util.Properties

/**
 * Port of Aurora's NativeDeviceInfoProvider.
 * Builds device Properties required by Aurora dispenser anonymous auth.
 * Also wraps gplayapi's DeviceInfoProvider by converting props.
 */
object DeviceInfoProvider {

    /**
     * The exact device profile Aurora ships and its dispenser is tuned to:
     * gplayapi's bundled `gplayapi_px_9a.properties` (Pixel 9a, Finsky 45.8.21-31,
     * Vending 84582130, sdk 35). The AAR merges its res/raw into this module, so we
     * load it by name.
     *
     * Prefer this over [buildDeviceProperties]: the shared anonymous accounts the
     * dispenser hands out are minted against this standard profile, and gplayapi's
     * hardcoded `X-DFE-Encoded-Targets`/`X-DFE-Phenotype` headers are tuned for this
     * Finsky version. A runtime-built native profile (esp. on de-Googled ROMs, where
     * [GsfVersionProvider] falls back to a 2020-era Finsky 21.5.17) is inconsistent
     * with those headers and gets auth/purchase rejected — i.e. "keys not working".
     * Falls back to the native profile only if the bundled resource is missing.
     */
    fun auroraProfile(context: Context): Properties = try {
        val id = context.resources.getIdentifier("gplayapi_px_9a", "raw", context.packageName)
        if (id != 0) {
            context.resources.openRawResource(id).use { input -> Properties().apply { load(input) } }
        } else {
            buildDeviceProperties(context)
        }
    } catch (_: Exception) {
        buildDeviceProperties(context)
    }

    fun buildDeviceProperties(context: Context): Properties {
        val props = Properties()
        val pm = context.packageManager
        val gsf = GsfVersionProvider.get(context)

        // Build.*
        props.setProperty("Ro.product.brand", Build.BRAND)
        props.setProperty("Ro.product.name", Build.PRODUCT)
        props.setProperty("Ro.product.device", Build.DEVICE)
        props.setProperty("Ro.product.model", Build.MODEL)
        props.setProperty("Ro.product.manufacturer", Build.MANUFACTURER)
        props.setProperty("Ro.product.id", Build.ID)
        props.setProperty("Ro.build.fingerprint", Build.FINGERPRINT)
        props.setProperty("Ro.build.bootloader", Build.BOOTLOADER)
        props.setProperty("Ro.build.hardware", Build.HARDWARE)
        props.setProperty("Ro.product.board", Build.BOARD)
        props.setProperty("Build.HARDWARE", Build.HARDWARE)
        props.setProperty("Build.BRAND", Build.BRAND)
        props.setProperty("Build.DEVICE", Build.DEVICE)
        props.setProperty("Build.FINGERPRINT", Build.FINGERPRINT)
        props.setProperty("Build.MANUFACTURER", Build.MANUFACTURER)
        props.setProperty("Build.MODEL", Build.MODEL)
        props.setProperty("Build.PRODUCT", Build.PRODUCT)
        props.setProperty("Build.ID", Build.ID)
        props.setProperty("Build.BOOTLOADER", Build.BOOTLOADER)
        props.setProperty("Build.RADIO", Build.getRadioVersion() ?: "unknown")
        props.setProperty("Build.SDK_INT", Build.VERSION.SDK_INT.toString())
        props.setProperty("Build.RELEASE", Build.VERSION.RELEASE ?: "13")

        // Config
        val config = context.resources.configuration
        props.setProperty("TouchScreen", config.touchscreen.toString())
        props.setProperty("Keyboard", config.keyboard.toString())
        props.setProperty("Navigation", config.navigation.toString())
        props.setProperty("ScreenLayout", (config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK).toString())
        props.setProperty("HasHardKeyboard", (config.keyboard == Configuration.KEYBOARD_QWERTY).toString())
        props.setProperty("HasFiveWayNavigation", (config.navigation == Configuration.NAVIGATION_DPAD).toString())

        // Display
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getMetrics(metrics)
            props.setProperty("Screen.Density", metrics.densityDpi.toString())
            props.setProperty("Screen.Width", metrics.widthPixels.toString())
            props.setProperty("Screen.Height", metrics.heightPixels.toString())
        } catch (_: Exception) {
            props.setProperty("Screen.Density", "420")
            props.setProperty("Screen.Width", "1080")
            props.setProperty("Screen.Height", "1920")
        }

        // ABIs
        props.setProperty("Platforms", Build.SUPPORTED_ABIS.joinToString(","))

        // Features
        try {
            val features = pm.systemAvailableFeatures.mapNotNull { it.name }.joinToString(",")
            props.setProperty("Features", features)
        } catch (_: Exception) {
            props.setProperty("Features", "")
        }

        // Locales
        try {
            val locales = context.assets.locales?.joinToString(",") ?: Locale.getDefault().toString()
            props.setProperty("Locales", locales)
        } catch (_: Exception) {
            props.setProperty("Locales", "en_US")
        }

        // Shared libs
        try {
            val libs = pm.systemSharedLibraryNames?.joinToString(",") ?: ""
            props.setProperty("SharedLibraries", libs)
        } catch (_: Exception) {
            props.setProperty("SharedLibraries", "")
        }

        // GL - best effort
        props.setProperty("GL.Version", "OpenGL ES 3.0")
        props.setProperty("GL.Extensions", "")
        props.setProperty("GL.EGL.Extensions", "")

        // GSF / Vending
        props.setProperty("GSF.version", gsf.gsfVersionCode.toString())
        props.setProperty("Vending.version", gsf.vendingVersionCode.toString())
        props.setProperty("Vending.versionString", gsf.vendingVersionString)

        props.setProperty("Client", "android-google")
        props.setProperty("Roaming", "mobile-notroaming")
        props.setProperty("TimeZone", "UTC-10")
        props.setProperty("CellOperator", "310")
        props.setProperty("SimOperator", "38")

        return props
    }

    /**
     * Convert our Properties to gplayapi's DeviceInfoProvider
     */
    fun toGplayDeviceInfoProvider(props: Properties, locale: String = Locale.getDefault().toString()): com.aurora.gplayapi.data.providers.DeviceInfoProvider {
        return com.aurora.gplayapi.data.providers.DeviceInfoProvider(props, locale)
    }
}
