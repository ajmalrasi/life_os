package com.lifeos.app.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
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
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(Vo2MaxRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    suspend fun hasAllPermissions(): Boolean =
        client().permissionController.getGrantedPermissions().containsAll(permissions)

    /** Read all supported metrics for the last [days] and normalize to samples. */
    suspend fun readRecentSamples(days: Int): List<HealthSample> {
        val end = Instant.now()
        val start = end.minus(days.toLong(), ChronoUnit.DAYS)
        val filter = TimeRangeFilter.between(start, end)
        val out = mutableListOf<HealthSample>()

        out += readSleep(filter)
        out += readHeartRate(filter)
        out += readResting(filter)
        out += readHrv(filter)
        out += readSpo2(filter)
        out += readSteps(filter)
        out += readExercise(filter)
        out += readCalories(filter)
        out += readDistance(filter)
        out += readVo2Max(filter)
        out += readWeight(filter)
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
}
