package com.lifeos.app

import com.lifeos.app.data.remote.dto.DailyScoreDto
import com.lifeos.app.data.remote.dto.InsightDto
import com.lifeos.app.data.toDomain
import com.lifeos.app.data.toDto
import com.lifeos.app.data.toEntity
import com.lifeos.app.domain.model.HealthMetricType
import com.lifeos.app.domain.model.HealthSample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MappersTest {

    @Test
    fun `health sample maps to dto with wire type`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val sample = HealthSample("id-1", HealthMetricType.SLEEP_DURATION, 7.5, "h", now, now)
        val dto = sample.toDto()
        assertEquals("sleep_duration", dto.type)
        assertEquals(7.5, dto.value, 0.0)
        assertEquals("id-1", dto.sourceId)
    }

    @Test
    fun `score dto round-trips through entity to domain`() {
        val dto = DailyScoreDto(day = "2026-01-02", sleepScore = 80.0, executionScore = 60.0)
        val domain = dto.toEntity().toDomain()
        assertEquals(LocalDate.parse("2026-01-02"), domain.day)
        assertEquals(80.0, domain.sleepScore)
        assertEquals(60.0, domain.executionScore)
        assertEquals(null, domain.recoveryScore)
    }

    @Test
    fun `insight entity uses composite key`() {
        val dto = InsightDto("sleep_score", "execution_score", 0.6, 14, 0, "summary")
        val entity = dto.toEntity()
        assertEquals("sleep_score|execution_score", entity.pair)
    }
}
