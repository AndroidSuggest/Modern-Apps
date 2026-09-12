package com.vayunmathur.health.ui

import androidx.annotation.StringRes
import com.vayunmathur.health.R
import com.vayunmathur.health.data.RecordType

/**
 * Configuration for health metrics.
 */
enum class HealthMetricConfig(
    @StringRes val titleRes: Int,
    val recordType: RecordType,
    val unit: String,
    val dailyGoal: Double,
    val secondaryGoal: Double? = null,
    val isLineChart: Boolean = false,
    val useDecimals: Boolean = false,
    val isDualSeries: Boolean = false
) {
    ENERGY(R.string.metric_energy_burned, RecordType.CaloriesTotal, "cal", 2470.0), ACTIVE_CALORIES(
        R.string.metric_active_calories,
        RecordType.CaloriesActive,
        "cal",
        500.0
    ),
    BASAL_METABOLIC_RATE(
        R.string.metric_basal_metabolic_rate, RecordType.CaloriesBasal, "cal", 1800.0
    ),
    STEPS(
        R.string.metric_steps,
        RecordType.Steps,
        "steps",
        10000.0
    ),
    WHEELCHAIR_PUSHES(
        R.string.metric_wheelchair_pushes,
        RecordType.Wheelchair,
        "pushes",
        3000.0
    ),
    DISTANCE(
        R.string.metric_distance,
        RecordType.Distance,
        "km",
        5.0,
        isLineChart = false,
        useDecimals = true
    ),
    ELEVATION(
        R.string.metric_elevation_gained,
        RecordType.Elevation,
        "m",
        50.0,
        isLineChart = false,
        useDecimals = true
    ),
    FLOORS(
        R.string.metric_floors_climbed,
        RecordType.Floors,
        "floors",
        10.0,
        isLineChart = false,
        useDecimals = true
    ),
    HYDRATION(
        R.string.metric_hydration,
        RecordType.Hydration,
        "L",
        2.5,
        isLineChart = false,
        useDecimals = true
    ),

    // Biological & Medical (Line Charts)
    BLOOD_PRESSURE(
        R.string.metric_blood_pressure,
        RecordType.BloodPressure,
        "mmHg",
        120.0,
        secondaryGoal = 80.0,
        isLineChart = true,
        isDualSeries = true
    ),
    GLUCOSE(
        R.string.metric_blood_glucose,
        RecordType.BloodGlucose,
        "mg/dL",
        100.0,
        isLineChart = true,
        useDecimals = true
    ),
    VO2_MAX(
        R.string.metric_vo2_max,
        RecordType.Vo2Max,
        "ml/kg/min",
        45.0,
        isLineChart = true,
        useDecimals = true
    ),
    SKIN_TEMP(
        R.string.metric_skin_temp_variation,
        RecordType.SkinTemperature,
        "°C",
        0.0,
        isLineChart = true,
        useDecimals = true
    ),
    BREATHING_RATE(
        R.string.metric_breathing_rate,
        RecordType.RespiratoryRate,
        "brpm",
        16.0,
        isLineChart = true,
        useDecimals = true
    ),
    RESTING_HEART_RATE(
        R.string.metric_resting_heart_rate,
        RecordType.RestingHeartRate,
        "bpm",
        60.0,
        isLineChart = true
    ),
    OXYGEN_SATURATION(
        R.string.metric_oxygen_saturation,
        RecordType.OxygenSaturation,
        "%",
        95.0,
        isLineChart = true,
        useDecimals = true
    ),
    HRV(
        R.string.metric_heart_rate_variability,
        RecordType.HeartRateVariabilityRmssd,
        "ms",
        50.0,
        isLineChart = true,
        useDecimals = true
    ),
    HEART_RATE(R.string.metric_heart_rate, RecordType.HeartRate, "bpm", 100.0, isLineChart = true),

    // Physical Measurements
    HEIGHT(
        R.string.metric_height,
        RecordType.Height,
        "cm",
        175.0,
        isLineChart = true,
        useDecimals = true
    ),
    WEIGHT(
        R.string.metric_weight,
        RecordType.Weight,
        "kg",
        75.0,
        isLineChart = true,
        useDecimals = true
    ),
    BODY_FAT(
        R.string.metric_body_fat,
        RecordType.BodyFat,
        "%",
        20.0,
        isLineChart = true,
        useDecimals = true
    ),
    LEAN_BODY_MASS(
        R.string.metric_lean_body_mass,
        RecordType.LeanBodyMass,
        "kg",
        60.0,
        isLineChart = true,
        useDecimals = true
    ),
    BONE_MASS(
        R.string.metric_bone_mass,
        RecordType.BoneMass,
        "kg",
        3.0,
        isLineChart = true,
        useDecimals = true
    ),
    BODY_WATER_MASS(
        R.string.metric_body_water_mass,
        RecordType.BodyWaterMass,
        "kg",
        45.0,
        isLineChart = true,
        useDecimals = true
    ),
    SLEEP(
        R.string.metric_sleep, RecordType.Sleep, "hr", 8.0, isLineChart = false, useDecimals = true
    ),
    EXERCISE_DURATION(
        R.string.metric_exercise_duration, RecordType.Exercise, "min", 30.0, isLineChart = false, useDecimals = false
    )
}
