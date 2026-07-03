package com.lifeos.app.data.remote

import com.lifeos.app.data.remote.dto.CardStatementDto
import com.lifeos.app.data.remote.dto.ChatRequestDto
import com.lifeos.app.data.remote.dto.ChatResponseDto
import com.lifeos.app.data.remote.dto.DailyScoreDto
import com.lifeos.app.data.remote.dto.DailyStateDto
import com.lifeos.app.data.remote.dto.DeviceTokenDto
import com.lifeos.app.data.remote.dto.DeviceTokenResponseDto
import com.lifeos.app.data.remote.dto.ExecutionSyncResultDto
import com.lifeos.app.data.remote.dto.ExpenseSyncResultDto
import com.lifeos.app.data.remote.dto.GithubDto
import com.lifeos.app.data.remote.dto.HealthBatchDto
import com.lifeos.app.data.remote.dto.IngestResultDto
import com.lifeos.app.data.remote.dto.InsightDto
import com.lifeos.app.data.remote.dto.MoodDto
import com.lifeos.app.data.remote.dto.MoodOutDto
import com.lifeos.app.data.remote.dto.NotificationDto
import com.lifeos.app.data.remote.dto.RecomputeResultDto
import com.lifeos.app.data.remote.dto.SpendAnalysisDto
import com.lifeos.app.data.remote.dto.SpendTrendsDto
import com.lifeos.app.data.remote.dto.StatementSyncResultDto
import com.lifeos.app.data.remote.dto.SuggestionsResponseDto
import com.lifeos.app.data.remote.dto.TransactionDto
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

    @POST("collectors/execution/sync")
    suspend fun syncExecution(@Query("days") days: Int): ExecutionSyncResultDto

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

    @GET("chat/suggestions")
    suspend fun chatSuggestions(@Query("days") days: Int): SuggestionsResponseDto

    @POST("device/token")
    suspend fun registerDeviceToken(@Body body: DeviceTokenDto): DeviceTokenResponseDto

    @GET("expenses")
    suspend fun listTransactions(@Query("days") days: Int): List<TransactionDto>

    @POST("expenses/sync")
    suspend fun syncExpenses(): ExpenseSyncResultDto

    @GET("statements")
    suspend fun listStatements(@Query("months") months: Int): List<CardStatementDto>

    @POST("statements/sync")
    suspend fun syncStatements(): StatementSyncResultDto

    @GET("statements/analysis")
    suspend fun spendAnalysis(@Query("days") days: Int): SpendAnalysisDto

    @GET("statements/trends")
    suspend fun spendTrends(@Query("months") months: Int): SpendTrendsDto
}
