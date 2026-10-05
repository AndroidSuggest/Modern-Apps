package com.vayunmathur.astronomy.platform

import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Snapshot of every persisted astronomy preference. */
internal data class AstronomyPrefsSnapshot(
    val constellationMode: ConstellationMode,
    val showGrid: Boolean,
    val showDeepSky: Boolean,
    val showPlanets: Boolean,
    val magLimit: Float,
    val nightMode: Boolean,
    val showBelowHorizon: Boolean,
    val fovDeg: Float,
    val latDeg: Double?,
    val lonDeg: Double?,
)

/**
 * Reads/writes the persisted astronomy preferences. Extracted from [AstronomyViewModel]
 * so the ViewModel stays under the TooManyFunctions limit; behavior is unchanged.
 */
internal class AstronomyPrefs(
    private val ds: DataStoreUtils,
    private val scope: CoroutineScope,
) {
    fun load(): AstronomyPrefsSnapshot {
        val latDeg = ds.getDouble("astro_lat")
        val lonDeg = ds.getDouble("astro_lon")
        return AstronomyPrefsSnapshot(
            constellationMode = runCatching {
                ConstellationMode.valueOf(ds.getString("astro_const_mode") ?: "LINES")
            }.getOrDefault(ConstellationMode.LINES),
            showGrid = ds.getBoolean("astro_show_grid", true),
            showDeepSky = ds.getBoolean("astro_show_deep", true),
            showPlanets = ds.getBoolean("astro_show_planets", true),
            magLimit = ds.getDouble("astro_mag_limit")?.toFloat() ?: DEFAULT_MAG_LIMIT,
            nightMode = ds.getBoolean("astro_night_mode", false),
            showBelowHorizon = ds.getBoolean("astro_show_below", true),
            fovDeg = ds.getDouble("astro_fov")?.toFloat() ?: DEFAULT_FOV_DEG,
            latDeg = latDeg,
            lonDeg = lonDeg,
        )
    }

    fun saveBool(key: String, v: Boolean) {
        scope.launch { ds.setBoolean(key, v) }
    }

    fun saveDouble(key: String, v: Double) {
        scope.launch { ds.setDouble(key, v) }
    }

    fun saveString(key: String, v: String) {
        scope.launch { ds.setString(key, v) }
    }

    private companion object {
        const val DEFAULT_MAG_LIMIT = 6.0f
        const val DEFAULT_FOV_DEG = 70f
    }
}
