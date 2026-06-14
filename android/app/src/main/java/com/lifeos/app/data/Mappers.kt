package com.lifeos.app.data

import com.lifeos.app.data.local.InsightEntity
import com.lifeos.app.data.local.MoodEntity
import com.lifeos.app.data.local.ScoreEntity
import com.lifeos.app.data.local.StateEntity
import com.lifeos.app.data.remote.dto.ChatMessageDto
import com.lifeos.app.data.remote.dto.DailyScoreDto
import com.lifeos.app.data.remote.dto.DailyStateDto
import com.lifeos.app.data.remote.dto.GithubDto
import com.lifeos.app.data.remote.dto.HealthRecordDto
import com.lifeos.app.data.remote.dto.InsightDto
import com.lifeos.app.data.remote.dto.MoodDto
import com.lifeos.app.data.remote.dto.NotificationDto
import com.lifeos.app.domain.model.AppNotification
import com.lifeos.app.domain.model.ChatTurn
import com.lifeos.app.domain.model.DailyScore
import com.lifeos.app.domain.model.DailyState
import com.lifeos.app.domain.model.GithubActivity
import com.lifeos.app.domain.model.HealthSample
import com.lifeos.app.domain.model.Insight
import com.lifeos.app.domain.model.MoodCheckin
import java.time.LocalDate

// --- Health ---
fun HealthSample.toDto() = HealthRecordDto(
    sourceId = sourceId,
    type = type.wire,
    value = value,
    unit = unit,
    startTime = start.toString(),
    endTime = end.toString(),
)

// --- Mood ---
fun MoodCheckin.toDto() = MoodDto(day.toString(), mood, energy, focus, stress, note)
fun MoodCheckin.toEntity() = MoodEntity(day.toString(), mood, energy, focus, stress, note)
fun MoodEntity.toDomain() = MoodCheckin(LocalDate.parse(day), mood, energy, focus, stress, note)

// --- GitHub ---
fun GithubActivity.toDto() = GithubDto(
    day = day.toString(),
    commits = commits,
    additions = additions,
    deletions = deletions,
    activeRepos = activeRepos,
    codingMinutes = codingMinutes,
)

// --- Scores ---
fun DailyScoreDto.toEntity() = ScoreEntity(
    day, sleepScore, recoveryScore, executionScore, consistencyScore, burnoutRisk,
)
fun ScoreEntity.toDomain() = DailyScore(
    LocalDate.parse(day), sleepScore, recoveryScore, executionScore, consistencyScore, burnoutRisk,
)

// --- States ---
fun DailyStateDto.toEntity() = StateEntity(day, state, confidence, rationale)
fun StateEntity.toDomain() = DailyState(LocalDate.parse(day), state, confidence, rationale)

// --- Insights ---
fun InsightDto.toEntity() = InsightEntity(
    pair = "$driver|$outcome",
    driver = driver,
    outcome = outcome,
    correlation = correlation,
    sampleSize = sampleSize,
    lagDays = lagDays,
    summary = summary,
)
fun InsightEntity.toDomain() = Insight(driver, outcome, correlation, sampleSize, lagDays, summary)

// --- Notifications ---
fun NotificationDto.toDomain() = AppNotification(severity, title, detail)

// --- Chat ---
fun ChatTurn.toDto() = ChatMessageDto(role, content)
