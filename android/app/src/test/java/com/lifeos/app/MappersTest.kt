package com.lifeos.app

import com.lifeos.app.data.remote.dto.DailyScoreDto
import com.lifeos.app.data.remote.dto.CardStatementDetailsDto
import com.lifeos.app.data.remote.dto.CardStatementDto
import com.lifeos.app.data.remote.dto.CardStatementItemDto
import com.lifeos.app.data.remote.dto.CurrentCardCycleDto
import com.lifeos.app.data.remote.dto.InsightDto
import com.lifeos.app.data.remote.dto.StatementBreakdownItemDto
import com.lifeos.app.data.toDomain
import com.lifeos.app.data.toDto
import com.lifeos.app.data.toEntity
import com.lifeos.app.domain.model.HealthMetricType
import com.lifeos.app.domain.model.HealthSample
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.math.BigDecimal

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

    @Test
    fun `statement details preserve status credits and accounting separation metadata`() {
        val details = CardStatementDetailsDto(
            statement = CardStatementDto(
                id = 42,
                bank = "hdfc",
                cardLabel = "HDFC Diners",
                dueDate = "2026-07-28",
                totalDue = "52100.50",
                status = "upcoming",
                detailsStatus = "ready",
                detailsParserVersion = 1,
            ),
            items = listOf(
                CardStatementItemDto(
                    id = 7,
                    sequence = 0,
                    txnDate = "2026-07-02",
                    txnTime = "12:29:00",
                    description = "PAYMENT RECEIVED",
                    amount = "8000.00",
                    direction = "credit",
                    itemType = "payment",
                )
            ),
            breakdown = listOf(
                StatementBreakdownItemDto("payments_credits", "Payments & credits", "-8000.00", 1)
            ),
        ).toDomain()

        assertEquals("upcoming", details.statement.status)
        assertEquals("ready", details.statement.detailsStatus)
        assertEquals(BigDecimal("8000.00"), details.items.single().amount)
        assertEquals("credit", details.items.single().direction)
        assertEquals(java.time.LocalTime.of(12, 29), details.items.single().txnTime)
        assertEquals(BigDecimal("-8000.00"), details.breakdown.single().total)
    }

    @Test
    fun `current card cycle survives dto cache and domain mapping`() {
        val dto = CurrentCardCycleDto(
            bank = "axis",
            cardLabel = "Axis Flipkart VISA",
            cardLast4 = "3068",
            cycleStart = "2026-07-16",
            cycleEnd = "2026-08-15",
            currentSpend = "1000.00",
            refunds = "100.00",
            netCycleAmount = "900.00",
            transactionCount = 2,
            lastTransactionDate = "2026-07-21",
        )

        val domain = dto.toEntity().toDomain()

        assertEquals("axis:3068", domain.key)
        assertEquals(LocalDate.parse("2026-07-16"), domain.cycleStart)
        assertEquals(LocalDate.parse("2026-08-15"), domain.cycleEnd)
        assertEquals(BigDecimal("1000.00"), domain.currentSpend)
        assertEquals(BigDecimal("100.00"), domain.refunds)
        assertEquals(BigDecimal("900.00"), domain.netCycleAmount)
    }
}
