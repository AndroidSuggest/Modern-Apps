package com.vayunmathur.everysync.data.sink

import android.content.Context
import com.vayunmathur.library.log.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.Volume
import com.vayunmathur.everysync.data.MeasurementType
import com.vayunmathur.everysync.data.RemoteMeasurement
import com.vayunmathur.everysync.data.RemoteNutrition
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Writes [RemoteMeasurement]s into Health Connect. Uses a stable `clientRecordId`
 * per measurement so re-running a sync upserts rather than duplicating. Mirrors the
 * record-construction patterns in `health`'s HealthAPI / HealthSyncWorker.
 */
object HealthSink {
    private const val TAG = "HealthSink"
    private const val INSERT_BATCH = 1000
    private const val INSERT_CONCURRENCY = 4
    private const val HR_SAMPLES_PER_RECORD = 1000
    private const val HR_RECORDS_PER_INSERT = 50 // bounds samples-per-insert (~50k)
    private val zone = ZoneId.systemDefault()

    suspend fun upsert(context: Context, measurements: List<RemoteMeasurement>) {
        if (measurements.isEmpty()) return
        val client = HealthConnectClient.getOrCreate(context)
        // Heart rate is a *series* record: collapse the many per-second/minute
        // samples into a handful of multi-sample records instead of one record
        // each. Same resolution, but ~1000x fewer records to insert.
        val (heartRate, other) = measurements.partition { it.type == MeasurementType.HEART_RATE }
        val hrRecords = heartRateSeries(heartRate)
        Log.status(
            TAG,
            "upsert: ${measurements.size} measurements " +
                "(${heartRate.size} HR samples -> ${hrRecords.size} HR series); " +
                measurements.groupingBy { it.type }.eachCount(),
        )
        // Batch sizes: HR series records each carry up to HR_SAMPLES_PER_RECORD
        // samples, so a batch of them is far larger than a batch of scalar records.
        // Keep HR batches small so a single insert doesn't blow past Health
        // Connect's per-transaction limits (which silently fails the whole call).
        // Build scalar records defensively: Health Connect validates value ranges
        // at construction (e.g. HRV RMSSD must be <= 200 ms), and a single bad
        // datapoint must never abort the whole sync — just skip it.
        val scalar = other.mapNotNull { safeRecord(it) }
        val batches = scalar.chunked(INSERT_BATCH) + hrRecords.chunked(HR_RECORDS_PER_INSERT)
        val semaphore = Semaphore(INSERT_CONCURRENCY)
        var inserted = 0
        coroutineScope {
            batches.map { batch ->
                async {
                    semaphore.withPermit {
                        try {
                            client.insertRecords(batch)
                            synchronized(this@HealthSink) { inserted += batch.size }
                        } catch (expected: Exception) {
                            Log.error(TAG, "insertRecords failed (${batch.size} records)", expected)
                        }
                    }
                }
            }.awaitAll()
        }
        Log.status(TAG, "upsert: inserted $inserted / ${scalar.size + hrRecords.size} records")
    }

    /**
     * Group heart-rate samples into series [HeartRateRecord]s (bucketed by civil
     * day, capped at [HR_SAMPLES_PER_RECORD] samples each). Deterministic
     * clientRecordIds keep re-syncing an overlapping window an idempotent upsert.
     */
    private fun heartRateSeries(measurements: List<RemoteMeasurement>): List<Record> {
        if (measurements.isEmpty()) return emptyList()
        val out = mutableListOf<Record>()
        measurements.groupBy { Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() }
            .forEach { (day, dayMeasurements) ->
                dayMeasurements.sortedBy { it.startMillis }
                    .chunked(HR_SAMPLES_PER_RECORD)
                    .forEachIndexed { bucket, chunk ->
                        val samples = chunk.mapNotNull {
                            val bpm = it.value.toLong()
                            if (bpm in 1..300) HeartRateRecord.Sample(Instant.ofEpochMilli(it.startMillis), bpm)
                            else null
                        }
                        if (samples.isEmpty()) return@forEachIndexed
                        val startInst = samples.first().time
                        var endInst = samples.last().time
                        if (!endInst.isAfter(startInst)) endInst = startInst.plusMillis(1)
                        out += HeartRateRecord(
                            startTime = startInst,
                            startZoneOffset = zone.rules.getOffset(startInst),
                            endTime = endInst,
                            endZoneOffset = zone.rules.getOffset(endInst),
                            samples = samples,
                            metadata = Metadata.manualEntry(
                                clientRecordId = "googlehealth:HEART_RATE:$day:$bucket",
                            ),
                        )
                    }
            }
        return out
    }

    /** Construct a record, skipping (not crashing) on Health Connect range violations. */
    private fun safeRecord(m: RemoteMeasurement): Record? = try {
        toRecord(m)
    } catch (expected: Exception) {
        Log.status(TAG, "skipping ${m.type} (${m.value}) @ ${m.startMillis}: ${expected.message}")
        null
    }

    private fun toRecord(m: RemoteMeasurement): Record? {
        val start = Instant.ofEpochMilli(m.startMillis)
        val end = Instant.ofEpochMilli(m.endMillis)
        val startOffset = zone.rules.getOffset(start)
        val endOffset = zone.rules.getOffset(end)
        val meta = Metadata.manualEntry(clientRecordId = m.clientRecordId)
        val ctx = RecordContext(m, start, startOffset, end, endOffset, meta)
        return when (m.type) {
            MeasurementType.WEIGHT,
            MeasurementType.HEIGHT,
            MeasurementType.BODY_FAT,
            -> bodyCompositionRecord(ctx)
            MeasurementType.OXYGEN_SATURATION,
            MeasurementType.RESTING_HEART_RATE,
            MeasurementType.HEART_RATE_VARIABILITY,
            MeasurementType.RESPIRATORY_RATE,
            MeasurementType.BLOOD_GLUCOSE,
            MeasurementType.BODY_TEMPERATURE,
            MeasurementType.VO2_MAX,
            MeasurementType.HEART_RATE,
            -> vitalsRecord(ctx)
            MeasurementType.STEPS,
            MeasurementType.DISTANCE,
            MeasurementType.FLOORS,
            MeasurementType.ELEVATION,
            MeasurementType.ACTIVE_CALORIES,
            MeasurementType.TOTAL_CALORIES,
            MeasurementType.HYDRATION,
            -> activityRecord(ctx)
            MeasurementType.SLEEP,
            MeasurementType.EXERCISE,
            MeasurementType.NUTRITION,
            -> sessionRecord(ctx)
        }
    }

    private data class RecordContext(
        val m: RemoteMeasurement,
        val start: Instant,
        val startOffset: java.time.ZoneOffset,
        val end: Instant,
        val endOffset: java.time.ZoneOffset,
        val meta: Metadata,
    )

    private fun bodyCompositionRecord(ctx: RecordContext): Record {
        val m = ctx.m
        return when (m.type) {
            MeasurementType.WEIGHT ->
                WeightRecord(
                    time = ctx.start,
                    zoneOffset = ctx.startOffset,
                    weight = Mass.kilograms(m.value),
                    metadata = ctx.meta,
                )
            MeasurementType.HEIGHT ->
                HeightRecord(
                    time = ctx.start,
                    zoneOffset = ctx.startOffset,
                    height = Length.meters(m.value),
                    metadata = ctx.meta,
                )
            else ->
                BodyFatRecord(
                    time = ctx.start,
                    zoneOffset = ctx.startOffset,
                    percentage = Percentage(m.value),
                    metadata = ctx.meta,
                )
        }
    }

    private fun vitalsRecord(ctx: RecordContext): Record {
        val m = ctx.m
        return when (m.type) {
            MeasurementType.OXYGEN_SATURATION -> oxygenSaturation(ctx)
            MeasurementType.RESTING_HEART_RATE -> restingHeartRate(ctx)
            MeasurementType.HEART_RATE_VARIABILITY -> heartRateVariability(ctx)
            MeasurementType.RESPIRATORY_RATE -> respiratoryRate(ctx)
            MeasurementType.BLOOD_GLUCOSE -> bloodGlucose(ctx)
            MeasurementType.BODY_TEMPERATURE -> bodyTemperature(ctx)
            MeasurementType.VO2_MAX -> vo2Max(ctx)
            else -> heartRateSeries(ctx)
        }
    }

    private fun oxygenSaturation(ctx: RecordContext) = OxygenSaturationRecord(
        time = ctx.start,
        zoneOffset = ctx.startOffset,
        percentage = Percentage(ctx.m.value),
        metadata = ctx.meta,
    )

    private fun restingHeartRate(ctx: RecordContext) = RestingHeartRateRecord(
        time = ctx.start,
        zoneOffset = ctx.startOffset,
        beatsPerMinute = ctx.m.value.toLong(),
        metadata = ctx.meta,
    )

    private fun heartRateVariability(ctx: RecordContext) = HeartRateVariabilityRmssdRecord(
        time = ctx.start,
        zoneOffset = ctx.startOffset,
        heartRateVariabilityMillis = ctx.m.value,
        metadata = ctx.meta,
    )

    private fun respiratoryRate(ctx: RecordContext) = RespiratoryRateRecord(
        time = ctx.start,
        zoneOffset = ctx.startOffset,
        rate = ctx.m.value,
        metadata = ctx.meta,
    )

    private fun bloodGlucose(ctx: RecordContext) = BloodGlucoseRecord(
        time = ctx.start,
        zoneOffset = ctx.startOffset,
        level = BloodGlucose.milligramsPerDeciliter(ctx.m.value),
        metadata = ctx.meta,
    )

    private fun bodyTemperature(ctx: RecordContext) = BodyTemperatureRecord(
        time = ctx.start,
        zoneOffset = ctx.startOffset,
        temperature = Temperature.celsius(ctx.m.value),
        metadata = ctx.meta,
    )

    private fun vo2Max(ctx: RecordContext) = Vo2MaxRecord(
        time = ctx.start,
        zoneOffset = ctx.startOffset,
        vo2MillilitersPerMinuteKilogram = ctx.m.value,
        metadata = ctx.meta,
    )

    private fun heartRateSeries(ctx: RecordContext) = HeartRateRecord(
        startTime = ctx.start,
        startZoneOffset = ctx.startOffset,
        endTime = ctx.end,
        endZoneOffset = ctx.endOffset,
        samples = listOf(HeartRateRecord.Sample(ctx.start, ctx.m.value.toLong())),
        metadata = ctx.meta,
    )

    private fun activityRecord(ctx: RecordContext): Record {
        val m = ctx.m
        return when (m.type) {
            MeasurementType.STEPS -> steps(ctx)
            MeasurementType.DISTANCE -> distance(ctx)
            MeasurementType.FLOORS -> floors(ctx)
            MeasurementType.ELEVATION -> elevation(ctx)
            MeasurementType.ACTIVE_CALORIES -> activeCalories(ctx)
            MeasurementType.TOTAL_CALORIES -> totalCalories(ctx)
            else -> hydration(ctx)
        }
    }

    private fun steps(ctx: RecordContext) = StepsRecord(
        startTime = ctx.start,
        startZoneOffset = ctx.startOffset,
        endTime = ctx.end,
        endZoneOffset = ctx.endOffset,
        count = ctx.m.value.toLong().coerceAtLeast(1),
        metadata = ctx.meta,
    )

    private fun distance(ctx: RecordContext) = DistanceRecord(
        startTime = ctx.start,
        startZoneOffset = ctx.startOffset,
        endTime = ctx.end,
        endZoneOffset = ctx.endOffset,
        distance = Length.meters(ctx.m.value),
        metadata = ctx.meta,
    )

    private fun floors(ctx: RecordContext) = FloorsClimbedRecord(
        startTime = ctx.start,
        startZoneOffset = ctx.startOffset,
        endTime = ctx.end,
        endZoneOffset = ctx.endOffset,
        floors = ctx.m.value,
        metadata = ctx.meta,
    )

    private fun elevation(ctx: RecordContext) = ElevationGainedRecord(
        startTime = ctx.start,
        startZoneOffset = ctx.startOffset,
        endTime = ctx.end,
        endZoneOffset = ctx.endOffset,
        elevation = Length.meters(ctx.m.value),
        metadata = ctx.meta,
    )

    private fun activeCalories(ctx: RecordContext) = ActiveCaloriesBurnedRecord(
        startTime = ctx.start,
        startZoneOffset = ctx.startOffset,
        endTime = ctx.end,
        endZoneOffset = ctx.endOffset,
        energy = Energy.kilocalories(ctx.m.value),
        metadata = ctx.meta,
    )

    private fun totalCalories(ctx: RecordContext) = TotalCaloriesBurnedRecord(
        startTime = ctx.start,
        startZoneOffset = ctx.startOffset,
        endTime = ctx.end,
        endZoneOffset = ctx.endOffset,
        energy = Energy.kilocalories(ctx.m.value),
        metadata = ctx.meta,
    )

    private fun hydration(ctx: RecordContext) = HydrationRecord(
        startTime = ctx.start,
        startZoneOffset = ctx.startOffset,
        endTime = ctx.end,
        endZoneOffset = ctx.endOffset,
        volume = Volume.liters(ctx.m.value),
        metadata = ctx.meta,
    )

    private fun sessionRecord(ctx: RecordContext): Record {
        val m = ctx.m
        return when (m.type) {
            MeasurementType.SLEEP -> SleepSessionRecord(
                startTime = ctx.start,
                startZoneOffset = ctx.startOffset,
                endTime = ctx.end,
                endZoneOffset = ctx.endOffset,
                stages = m.sleepStages.map {
                    SleepSessionRecord.Stage(
                        startTime = Instant.ofEpochMilli(it.startMillis),
                        endTime = Instant.ofEpochMilli(it.endMillis),
                        stage = it.stage,
                    )
                },
                metadata = ctx.meta,
            )
            MeasurementType.EXERCISE -> ExerciseSessionRecord(
                startTime = ctx.start,
                startZoneOffset = ctx.startOffset,
                endTime = ctx.end,
                endZoneOffset = ctx.endOffset,
                exerciseType = m.exerciseType,
                title = m.title,
                notes = m.notes,
                metadata = ctx.meta,
            )
            else -> nutritionRecord(
                m,
                ctx.start,
                ctx.startOffset,
                ctx.end,
                ctx.endOffset,
                ctx.meta,
            )
        }
    }

    private fun nutritionRecord(
        m: RemoteMeasurement,
        start: Instant,
        startOffset: java.time.ZoneOffset,
        end: Instant,
        endOffset: java.time.ZoneOffset,
        meta: Metadata,
    ): NutritionRecord {
        val n: RemoteNutrition = m.nutrition ?: RemoteNutrition()
        fun g(key: String) = n.nutrientGrams[key]?.let { Mass.grams(it) }
        return NutritionRecord(
            startTime = start, startZoneOffset = startOffset,
            endTime = end, endZoneOffset = endOffset,
            name = m.title,
            energy = n.energyKcal?.let { Energy.kilocalories(it) },
            totalFat = g("TOTAL_FAT"),
            totalCarbohydrate = g("TOTAL_CARBOHYDRATE"),
            protein = g("PROTEIN"),
            dietaryFiber = g("DIETARY_FIBER"),
            sugar = g("SUGAR"),
            sodium = g("SODIUM"),
            biotin = g("BIOTIN"),
            caffeine = g("CAFFEINE"),
            calcium = g("CALCIUM"),
            chloride = g("CHLORIDE"),
            cholesterol = g("CHOLESTEROL"),
            chromium = g("CHROMIUM"),
            copper = g("COPPER"),
            folate = g("FOLATE"),
            folicAcid = g("FOLIC_ACID"),
            iodine = g("IODINE"),
            iron = g("IRON"),
            magnesium = g("MAGNESIUM"),
            manganese = g("MANGANESE"),
            molybdenum = g("MOLYBDENUM"),
            monounsaturatedFat = g("MONOUNSATURATED_FAT"),
            niacin = g("NIACIN"),
            pantothenicAcid = g("PANTOTHENIC_ACID"),
            phosphorus = g("PHOSPHORUS"),
            polyunsaturatedFat = g("POLYUNSATURATED_FAT"),
            potassium = g("POTASSIUM"),
            riboflavin = g("RIBOFLAVIN"),
            saturatedFat = g("SATURATED_FAT"),
            selenium = g("SELENIUM"),
            thiamin = g("THIAMIN"),
            transFat = g("TRANS_FAT"),
            unsaturatedFat = g("UNSATURATED_FAT"),
            vitaminA = g("VITAMIN_A"),
            vitaminB12 = g("VITAMIN_B12"),
            vitaminB6 = g("VITAMIN_B6"),
            vitaminC = g("VITAMIN_C"),
            vitaminD = g("VITAMIN_D"),
            vitaminE = g("VITAMIN_E"),
            vitaminK = g("VITAMIN_K"),
            zinc = g("ZINC"),
            metadata = meta,
        )
    }
}
