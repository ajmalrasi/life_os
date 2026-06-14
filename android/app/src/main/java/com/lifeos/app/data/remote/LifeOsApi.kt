package com.lifeos.app.data.remote

import com.lifeos.app.data.remote.dto.ChatRequestDto
import com.lifeos.app.data.remote.dto.ChatResponseDto
import com.lifeos.app.data.remote.dto.DailyScoreDto
import com.lifeos.app.data.remote.dto.DailyStateDto
import com.lifeos.app.data.remote.dto.GithubDto
import com.lifeos.app.data.remote.dto.HealthBatchDto
import com.lifeos.app.data.remote.dto.IngestResultDto
import com.lifeos.app.data.remote.dto.InsightDto
import com.lifeos.app.data.remote.dto.MoodDto
import com.lifeos.app.data.remote.dto.MoodOutDto
import com.lifeos.app.data.remote.dto.NotificationDto
import com.lifeos.app.data.remote.dto.RecomputeResultDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface LifeOsApi {

    @POST("health/batch")
    suspend fun uploadHealth(@Body batch: HealthBatchDto): IngestResultDto

    @POST("mood")
    suspend fun submitMood(@Body mood: MoodDto): MoodOutDto

    @GET("mood")
    suspend fun listMood(@Query("days") days: Int): List<MoodOutDto>

    @POST("github")
    suspend fun submitGithub(@Body activity: GithubDto): GithubDto

    @POST("analytics/recompute")
    suspend fun recompute(@Query("days") days: Int): RecomputeResultDto

    @GET("analytics/scores")
    suspend fun scores(@Query("days") days: Int): List<DailyScoreDto>

    @GET("analytics/state")
    suspend fun states(@Query("days") days: Int): List<DailyStateDto>

    @GET("analytics/insights")
    suspend fun insights(): List<InsightDto>

    @GET("notifications")
    suspend fun notifications(): List<NotificationDto>

    @POST("chat")
    suspend fun chat(@Body request: ChatRequestDto): ChatResponseDto
}
