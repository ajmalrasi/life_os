package com.lifeos.app.data.remote

import com.lifeos.app.data.remote.dto.CardStatementDto
import com.lifeos.app.data.remote.dto.CardStatementDetailsDto
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
import com.lifeos.app.data.remote.dto.RepaymentCandidateDto
import com.lifeos.app.data.remote.dto.RepaymentCreateDto
import com.lifeos.app.data.remote.dto.RepaymentDto
import com.lifeos.app.data.remote.dto.SpendAnalysisDto
import com.lifeos.app.data.remote.dto.SpendTrendsDto
import com.lifeos.app.data.remote.dto.StatementSyncResultDto
import com.lifeos.app.data.remote.dto.SuggestionsResponseDto
import com.lifeos.app.data.remote.dto.SynthesizedInsightDto
import com.lifeos.app.data.remote.dto.TransactionDto
import com.lifeos.app.data.remote.dto.TransactionCategoryUpdateDto
import com.lifeos.app.data.remote.dto.StatementPaidUpdateDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.Query
import retrofit2.http.Streaming
import okhttp3.ResponseBody

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

    @GET("analytics/synthesis")
    suspend fun synthesis(): List<SynthesizedInsightDto>

    @GET("notifications")
    suspend fun notifications(): List<NotificationDto>

    @POST("chat")
    suspend fun chat(@Body request: ChatRequestDto): ChatResponseDto

    @Streaming
    @POST("chat/stream")
    suspend fun streamChat(@Body request: ChatRequestDto): ResponseBody

    @GET("chat/suggestions")
    suspend fun chatSuggestions(@Query("days") days: Int): SuggestionsResponseDto

    @POST("device/token")
    suspend fun registerDeviceToken(@Body body: DeviceTokenDto): DeviceTokenResponseDto

    @GET("expenses")
    suspend fun listTransactions(@Query("days") days: Int): List<TransactionDto>

    @POST("expenses/sync")
    suspend fun syncExpenses(): ExpenseSyncResultDto

    @GET("expenses/{transactionId}/repayment-candidates")
    suspend fun repaymentCandidates(
        @Path("transactionId") transactionId: Int,
    ): List<RepaymentCandidateDto>

    @POST("expenses/repayments")
    suspend fun markAsRepaid(@Body body: RepaymentCreateDto): RepaymentDto

    @DELETE("expenses/repayments/{repaymentId}")
    suspend fun undoRepayment(@Path("repaymentId") repaymentId: Int): RepaymentDto

    @PATCH("expenses/{transactionId}/category")
    suspend fun updateTransactionCategory(
        @Path("transactionId") transactionId: Int,
        @Body body: TransactionCategoryUpdateDto,
    ): TransactionDto

    @GET("statements")
    suspend fun listStatements(@Query("months") months: Int): List<CardStatementDto>

    @POST("statements/sync")
    suspend fun syncStatements(): StatementSyncResultDto

    @PATCH("statements/{statementId}/paid")
    suspend fun updateStatementPaid(
        @Path("statementId") statementId: Int,
        @Body body: StatementPaidUpdateDto,
    ): CardStatementDto

    @GET("statements/{statementId}/details")
    suspend fun statementDetails(
        @Path("statementId") statementId: Int,
    ): CardStatementDetailsDto

    @GET("statements/analysis")
    suspend fun spendAnalysis(@Query("days") days: Int): SpendAnalysisDto

    @GET("statements/trends")
    suspend fun spendTrends(@Query("months") months: Int): SpendTrendsDto
}
