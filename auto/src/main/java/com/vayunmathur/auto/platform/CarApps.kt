package com.vayunmathur.auto.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

/** One tile on the car launcher: an icon, a label, and how to open it. */
data class CarApp(
    val label: CharSequence,
    val icon: Drawable?,
    val launch: Intent,
)

/**
 * Which apps the car launcher shows.
 *
 * The app-inventory pass (Task 10) found no third-party Android Auto targets on the phone,
 * so the launcher resolves Modern Apps siblings plus the platform's own media surfaces:
 * navigation goes to MA Maps, audio to MA Music's media browser service or any declared
 * `MediaBrowserService`, and telephony to the system dialer. Whatever is not installed
 * is skipped rather than shown dead, and an empty grid gets an explicit empty state.
 */
object CarApps {
    fun query(context: Context): List<CarApp> {
        val pm = context.packageManager
        val found = mutableListOf<CarApp>()
        for (slot in SLOTS) {
            runCatching { slot.resolve(context, pm) }
                .onSuccess { app -> if (app != null) found += app }
                .onFailure { Log.w(TAG, "could not resolve car slot ${slot.label}", it) }
        }
        return found
    }

    /**
     * Resolves the assistant slot for the rail's assistant icon, or null when
     * the phone has no assistant to open.
     *
     * Skip-if-missing like every [SLOTS] entry: the rail keeps its 68dp
     * assistant container (gearhead `assistant_icon_container`) but the icon
     * itself stays GONE, so the launcher never shows what the phone cannot
     * open. `ACTION_ASSIST` first (the platform assistant entry point), then
     * the legacy voice-command intent as a fallback.
     */
    fun assistant(context: Context): CarApp? = runCatching {
        val pm = context.packageManager
        val assist = Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val voice = Intent(Intent.ACTION_VOICE_COMMAND).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launch = if (assist.resolveActivity(pm) != null) assist
        else if (voice.resolveActivity(pm) != null) voice
        else return null
        val target = launch.resolveActivity(pm) ?: return null
        val info = pm.getActivityInfo(target, 0)
        CarApp(
            label = info.loadLabel(pm),
            icon = info.loadIcon(pm),
            launch = launch,
        )
    }.getOrNull()

    private interface Slot {
        val label: String
        fun resolve(context: Context, pm: PackageManager): CarApp?
    }

    private fun launcherApp(
        context: Context,
        pm: PackageManager,
        packageName: String,
    ): CarApp? {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val info = pm.getApplicationInfo(packageName, 0)
        return CarApp(
            label = pm.getApplicationLabel(info),
            icon = pm.getApplicationIcon(info),
            launch = intent,
        )
    }

    private val SLOTS: List<Slot> = listOf(
        object : Slot {
            override val label = "maps"
            override fun resolve(context: Context, pm: PackageManager): CarApp? =
                launcherApp(context, pm, "com.vayunmathur.maps")
        },
        object : Slot {
            override val label = "music"
            override fun resolve(context: Context, pm: PackageManager): CarApp? {
                val direct = runCatching {
                    launcherApp(context, pm, "com.vayunmathur.music")
                }.getOrNull()
                if (direct != null) return direct
                // Fallback: anything declaring a browsable media surface.
                val browse = Intent("android.media.browse.MediaBrowserService")
                val services = pm.queryIntentServices(browse, 0)
                val service = services.firstOrNull() ?: return null
                val info = service.serviceInfo
                return CarApp(
                    label = info.loadLabel(pm),
                    icon = info.loadIcon(pm),
                    launch = pm.getLaunchIntentForPackage(info.packageName)
                        ?: Intent(Intent.ACTION_VIEW).setPackage(info.packageName),
                )
            }
        },
        object : Slot {
            override val label = "phone"
            override fun resolve(context: Context, pm: PackageManager): CarApp? {
                val dial = Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val activity = dial.resolveActivity(pm) ?: return null
                val info = pm.getActivityInfo(activity, 0)
                return CarApp(
                    label = info.loadLabel(pm),
                    icon = info.loadIcon(pm),
                    launch = dial,
                )
            }
        },
    )

    private const val TAG = "MaAuto.CarApps"
}
