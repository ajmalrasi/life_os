package com.lifeos.app.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BasalBodyTemperatureRecord
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.lifeos.app.domain.model.HealthMetricType
import com.lifeos.app.domain.model.HealthSample
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/**
 * Thin wrapper over the Health Connect SDK. Knows how to:
 *  - report availability,
 *  - declare the read permissions LifeOS needs (Phase 1 subset),
 *  - read recent records and normalize them into [HealthSample]s for upload.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val permissions: Set<String> = setOf(
        // Sleep & vitals
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(BloodPressureRecord::class),
        HealthPermission.getReadPermission(BloodGlucoseRecord::class),
        HealthPermission.getReadPermission(BodyTemperatureRecord::class),
        HealthPermission.getReadPermission(BasalBodyTemperatureRecord::class),
        // Activity & energy
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(BasalMetabolicRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ElevationGainedRecord::class),
        HealthPermission.getReadPermission(FloorsClimbedRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        // Body measurements
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(LeanBodyMassRecord::class),
        HealthPermission.getReadPermission(BoneMassRecord::class),
        HealthPermission.getReadPermission(BodyWaterMassRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        // Read older history and sync while backgrounded
        HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY,
        HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    private suspend fun granted(): Set<String> =
        client().permissionController.getGrantedPermissions()

    suspend fun hasAllPermissions(): Boolean = granted().containsAll(permissions)

    /**
     * True if the user granted at least one health read permission. Sync is
     * gated on this (not [hasAllPermissions]) so a partial grant still pulls in
     * whatever the user did allow instead of silently reading nothing.
     */
    suspend fun hasAnyPermissions(): Boolean =
        granted().any { it in permissions }

    /**
     * Read every supported metric the user has granted for the last [days] and
     * normalize to samples. Each reader runs only if its read permission was
     * granted, so a partial grant never raises a SecurityException — it just
     * skips the types the user withheld.
     */
    suspend fun readRecentSamples(days: Int): List<HealthSample> {
        val end = Instant.now()
        val start = end.minus(days.toLong(), ChronoUnit.DAYS)
        val filter = TimeRangeFilter.between(start, end)
        val grantedPerms = granted()
        val out = mutableListOf<HealthSample>()

        suspend fun ifGranted(type: KClass<out Record>, block: suspend () -> List<HealthSample>) {
            if (HealthPermission.getReadPermission(type) in grantedPerms) out += block()
        }

        ifGranted(SleepSessionRecord::class) { readSleep(filter) }
        ifGranted(HeartRateRecord::class) { readHeartRate(filter) }
        ifGranted(RestingHeartRateRecord::class) { readResting(filter) }
        ifGranted(HeartRateVariabilityRmssdRecord::class) { readHrv(filter) }
        ifGranted(OxygenSaturationRecord::class) { readSpo2(filter) }
        ifGranted(RespiratoryRateRecord::class) { readRespiratoryRate(filter) }
        ifGranted(BloodPressureRecord::class) { readBloodPressure(filter) }
        ifGranted(BloodGlucoseRecord::class) { readBloodGlucose(filter) }
        ifGranted(BodyTemperatureRecord::class) { readBodyTemperature(filter) }
        ifGranted(BasalBodyTemperatureRecord::class) { readBasalBodyTemperature(filter) }
        ifGranted(StepsRecord::class) { readSteps(filter) }
        ifGranted(ExerciseSessionRecord::class) { readExercise(filter) }
        ifGranted(TotalCaloriesBurnedRecord::class) { readCalories(filter) }
        ifGranted(ActiveCaloriesBurnedRecord::class) { readActiveCalories(filter) }
        ifGranted(BasalMetabolicRateRecord::class) { readBmr(filter) }
        ifGranted(DistanceRecord::class) { readDistance(filter) }
        ifGranted(ElevationGainedRecord::class) { readElevation(filter) }
        ifGranted(FloorsClimbedRecord::class) { readFloors(filter) }
        ifGranted(Vo2MaxRecord::class) { readVo2Max(filter) }
        ifGranted(WeightRecord::class) { readWeight(filter) }
        ifGranted(HeightRecord::class) { readHeight(filter) }
        ifGranted(BodyFatRecord::class) { readBodyFat(filter) }
        ifGranted(LeanBodyMassRecord::class) { readLeanBodyMass(filter) }
        ifGranted(BoneMassRecord::class) { readBoneMass(filter) }
        ifGranted(BodyWaterMassRecord::class) { readBodyWaterMass(filter) }
        ifGranted(HydrationRecord::class) { readHydration(filter) }
        return out
    }

    private suspend fun <T : Record> read(type: KClass<T>, filter: TimeRangeFilter): List<T> =
        client().readRecords(ReadRecordsRequest(type, timeRangeFilter = filter)).records

    private fun hours(start: Instant, end: Instant): Double =
        Duration.between(start, end).toMinutes() / 60.0

    private suspend fun readSleep(filter: TimeRangeFilter): List<HealthSample> {
        val samples = mutableListOf<HealthSample>()
        read(SleepSessionRecord::class, filter).forEach { session ->
            samples += HealthSample(
                sourceId = session.metadata.id,
                type = HealthMetricType.SLEEP_DURATION,
                value = hours(session.startTime, session.endTime),
                unit = "h",
                start = session.startTime,
                end = session.endTime,
            )
            session.stages.forEachIndexed { i, stage ->
                val type = when (stage.stage) {
                    SleepSessionRecord.STAGE_TYPE_DEEP -> HealthMetricType.SLEEP_DEEP
                    SleepSessionRecord.STAGE_TYPE_REM -> HealthMetricType.SLEEP_REM
                    SleepSessionRecord.STAGE_TYPE_LIGHT -> HealthMetricType.SLEEP_LIGHT
                    SleepSessionRecord.STAGE_TYPE_AWAKE,
                    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> HealthMetricType.SLEEP_AWAKE
                    else -> null
                } ?: return@forEachIndexed
                samples += HealthSample(
                    sourceId = "${session.metadata.id}-stage-$i",
                    type = type,
                    value = hours(stage.startTime, stage.endTime),
                    unit = "h",
                    start = stage.startTime,
                    end = stage.endTime,
                )
            }
        }
        return samples
    }

    private suspend fun readHeartRate(filter: TimeRangeFilter): List<HealthSample> =
        read(HeartRateRecord::class, filter).flatMap { record ->
            record.samples.map { s ->
                HealthSample(
                    sourceId = "${record.metadata.id}-${s.time.toEpochMilli()}",
                    type = HealthMetricType.HEART_RATE,
                    value = s.beatsPerMinute.toDouble(),
                    unit = "bpm",
                    start = s.time,
                    end = s.time,
                )
            }
        }

    private suspend fun readResting(filter: TimeRangeFilter): List<HealthSample> =
        read(RestingHeartRateRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.RESTING_HEART_RATE,
                it.beatsPerMinute.toDouble(), "bpm", it.time, it.time,
            )
        }

    private suspend fun readHrv(filter: TimeRangeFilter): List<HealthSample> =
        read(HeartRateVariabilityRmssdRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.HRV,
                it.heartRateVariabilityMillis, "ms", it.time, it.time,
            )
        }

    private suspend fun readSpo2(filter: TimeRangeFilter): List<HealthSample> =
        read(OxygenSaturationRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.BLOOD_OXYGEN,
                it.percentage.value, "%", it.time, it.time,
            )
        }

    private suspend fun readSteps(filter: TimeRangeFilter): List<HealthSample> =
        read(StepsRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.STEPS,
                it.count.toDouble(), "count", it.startTime, it.endTime,
            )
        }

    private suspend fun readExercise(filter: TimeRangeFilter): List<HealthSample> =
        read(ExerciseSessionRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.WORKOUT,
                Duration.between(it.startTime, it.endTime).toMinutes().toDouble(),
                "min", it.startTime, it.endTime,
            )
        }

    private suspend fun readCalories(filter: TimeRangeFilter): List<HealthSample> =
        read(TotalCaloriesBurnedRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.CALORIES,
                it.energy.inKilocalories, "kcal", it.startTime, it.endTime,
            )
        }

    private suspend fun readDistance(filter: TimeRangeFilter): List<HealthSample> =
        read(DistanceRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.WALKING_DISTANCE,
                it.distance.inMeters, "m", it.startTime, it.endTime,
            )
        }

    private suspend fun readVo2Max(filter: TimeRangeFilter): List<HealthSample> =
        read(Vo2MaxRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.VO2_MAX,
                it.vo2MillilitersPerMinuteKilogram, "ml/kg/min", it.time, it.time,
            )
        }

    private suspend fun readWeight(filter: TimeRangeFilter): List<HealthSample> =
        read(WeightRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.WEIGHT,
                it.weight.inKilograms, "kg", it.time, it.time,
            )
        }

    private suspend fun readRespiratoryRate(filter: TimeRangeFilter): List<HealthSample> =
        read(RespiratoryRateRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.RESPIRATORY_RATE,
                it.rate, "rpm", it.time, it.time,
            )
        }

    private suspend fun readBloodPressure(filter: TimeRangeFilter): List<HealthSample> =
        read(BloodPressureRecord::class, filter).flatMap {
            listOf(
                HealthSample(
                    "${it.metadata.id}-sys", HealthMetricType.BLOOD_PRESSURE_SYSTOLIC,
                    it.systolic.inMillimetersOfMercury, "mmHg", it.time, it.time,
                ),
                HealthSample(
                    "${it.metadata.id}-dia", HealthMetricType.BLOOD_PRESSURE_DIASTOLIC,
                    it.diastolic.inMillimetersOfMercury, "mmHg", it.time, it.time,
                ),
            )
        }

    private suspend fun readBloodGlucose(filter: TimeRangeFilter): List<HealthSample> =
        read(BloodGlucoseRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.BLOOD_GLUCOSE,
                it.level.inMilligramsPerDeciliter, "mg/dL", it.time, it.time,
            )
        }

    private suspend fun readBodyTemperature(filter: TimeRangeFilter): List<HealthSample> =
        read(BodyTemperatureRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.BODY_TEMPERATURE,
                it.temperature.inCelsius, "C", it.time, it.time,
            )
        }

    private suspend fun readBasalBodyTemperature(filter: TimeRangeFilter): List<HealthSample> =
        read(BasalBodyTemperatureRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.BASAL_BODY_TEMPERATURE,
                it.temperature.inCelsius, "C", it.time, it.time,
            )
        }

    private suspend fun readActiveCalories(filter: TimeRangeFilter): List<HealthSample> =
        read(ActiveCaloriesBurnedRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.ACTIVE_CALORIES,
                it.energy.inKilocalories, "kcal", it.startTime, it.endTime,
            )
        }

    private suspend fun readBmr(filter: TimeRangeFilter): List<HealthSample> =
        read(BasalMetabolicRateRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.BASAL_METABOLIC_RATE,
                it.basalMetabolicRate.inKilocaloriesPerDay, "kcal/day", it.time, it.time,
            )
        }

    private suspend fun readElevation(filter: TimeRangeFilter): List<HealthSample> =
        read(ElevationGainedRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.ELEVATION_GAINED,
                it.elevation.inMeters, "m", it.startTime, it.endTime,
            )
        }

    private suspend fun readFloors(filter: TimeRangeFilter): List<HealthSample> =
        read(FloorsClimbedRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.FLOORS_CLIMBED,
                it.floors, "count", it.startTime, it.endTime,
            )
        }

    private suspend fun readHeight(filter: TimeRangeFilter): List<HealthSample> =
        read(HeightRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.HEIGHT,
                it.height.inMeters, "m", it.time, it.time,
            )
        }

    private suspend fun readBodyFat(filter: TimeRangeFilter): List<HealthSample> =
        read(BodyFatRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.BODY_FAT,
                it.percentage.value, "%", it.time, it.time,
            )
        }

    private suspend fun readLeanBodyMass(filter: TimeRangeFilter): List<HealthSample> =
        read(LeanBodyMassRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.LEAN_BODY_MASS,
                it.mass.inKilograms, "kg", it.time, it.time,
            )
        }

    private suspend fun readBoneMass(filter: TimeRangeFilter): List<HealthSample> =
        read(BoneMassRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.BONE_MASS,
                it.mass.inKilograms, "kg", it.time, it.time,
            )
        }

    private suspend fun readBodyWaterMass(filter: TimeRangeFilter): List<HealthSample> =
        read(BodyWaterMassRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.BODY_WATER_MASS,
                it.mass.inKilograms, "kg", it.time, it.time,
            )
        }

    private suspend fun readHydration(filter: TimeRangeFilter): List<HealthSample> =
        read(HydrationRecord::class, filter).map {
            HealthSample(
                it.metadata.id, HealthMetricType.HYDRATION,
                it.volume.inLiters, "L", it.startTime, it.endTime,
            )
        }
}
