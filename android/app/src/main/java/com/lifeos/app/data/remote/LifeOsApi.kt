package com.lifeos.app.data.remote

import com.lifeos.app.data.remote.dto.CardStatementDto
import com.lifeos.app.data.remote.dto.CardStatementDetailsDto
import com.lifeos.app.data.remote.dto.CurrentCardCyclesDto
import com.lifeos.app.data.remote.dto.CurrentCardCycleDetailsDto
import com.lifeos.app.data.remote.dto.ChatRequestDto
import com.lifeos.app.data.remote.dto.ChatResponseDto
import com.lifeos.app.data.remote.dto.DailyScoreDto
import com.lifeos.app.data.remote.dto.DailyStateDto
import com.lifeos.app.data.remote.dto.DeviceTokenDto
import com.lifeos.app.data.remote.dto.DeviceTokenResponseDto
import com.lifeos.app.data.remote.dto.ExecutionSyncResultDto
import com.lifeos.app.data.remote.dto.ExpenseSyncResultDto
import com.lifeos.app.data.remote.dto.GithubDto
import com.lifeos.app.data.remote.dto.InsightDto
import com.lifeos.app.data.remote.dto.MoodDto
import com.lifeos.app.data.remote.dto.MoodOutDto
import com.lifeos.app.data.remote.dto.NotificationDto
import com.lifeos.app.data.remote.dto.SyncStatusDto
import com.lifeos.app.data.remote.dto.PriorityDetailsDto
import com.lifeos.app.data.remote.dto.PriorityFeedbackDto
import com.lifeos.app.data.remote.dto.PriorityItemDto
import com.lifeos.app.data.remote.dto.PrioritySyncResultDto
import com.lifeos.app.data.remote.dto.PriorityUpdateDto
import com.lifeos.app.data.remote.dto.MailAccountDto
import com.lifeos.app.data.remote.dto.MailConnectionCompleteDto
import com.lifeos.app.data.remote.dto.MailConnectionStartDto
import com.lifeos.app.data.remote.dto.MailConnectionStartResultDto
import com.lifeos.app.data.remote.dto.RecomputeResultDto
import com.lifeos.app.data.remote.dto.RepaymentCandidateDto
import com.lifeos.app.data.remote.dto.RepaymentCreateDto
import com.lifeos.app.data.remote.dto.RepaymentDto
import com.lifeos.app.data.remote.dto.RefundCreateDto
import com.lifeos.app.data.remote.dto.RefundDto
import com.lifeos.app.data.remote.dto.SpendAnalysisDto
import com.lifeos.app.data.remote.dto.SpendTrendsDto
import com.lifeos.app.data.remote.dto.StatementSyncResultDto
import com.lifeos.app.data.remote.dto.SuggestionsResponseDto
import com.lifeos.app.data.remote.dto.SynthesizedInsightDto
import com.lifeos.app.data.remote.dto.TransactionDto
import com.lifeos.app.data.remote.dto.TransactionCategoryUpdateDto
import com.lifeos.app.trips.data.TripBackupRequestDto
import com.lifeos.app.trips.data.TripBackupResponseDto
import com.lifeos.app.data.remote.dto.StatementPaidUpdateDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Header
import okhttp3.RequestBody
import retrofit2.Response
import okhttp3.ResponseBody

interface LifeOsApi {

    @PUT("trips/{tripId}")
    suspend fun backupTrip(
        @Path("tripId") tripId: String,
        @Body body: TripBackupRequestDto,
    ): TripBackupResponseDto

    @GET("trips/{tripId}")
    suspend fun getTripBackup(@Path("tripId") tripId: String): TripBackupResponseDto

    @PUT("trips/{tripId}/attachments/{attachmentId}")
    suspend fun backupTripAttachment(
        @Path("tripId") tripId: String,
        @Path("attachmentId") attachmentId: String,
        @Header("X-Filename") filename: String,
        @Body body: RequestBody,
    ): Response<Unit>

    @DELETE("trips/{tripId}")
    suspend fun deleteTripBackup(@Path("tripId") tripId: String): Response<Unit>

    @GET("priority")
    suspend fun listPriority(
        @Query("include_done") includeDone: Boolean = false,
    ): List<PriorityItemDto>

    @GET("priority/{itemId}")
    suspend fun priorityDetails(@Path("itemId") itemId: Int): PriorityDetailsDto

    @POST("priority/sync/check")
    suspend fun syncPriority(): PrioritySyncResultDto

    @GET("mail/accounts")
    suspend fun listMailAccounts(): List<MailAccountDto>

    @POST("mail/accounts/connect/start")
    suspend fun startMailConnection(
        @Body payload: MailConnectionStartDto,
    ): MailConnectionStartResultDto

    @POST("mail/accounts/connect/complete")
    suspend fun completeMailConnection(
        @Body payload: MailConnectionCompleteDto,
    ): MailAccountDto

    @DELETE("mail/accounts/{accountId}/purposes/{purpose}")
    suspend fun stopUsingMailAccount(
        @Path("accountId") accountId: Int,
        @Path("purpose") purpose: String,
    )

    @PATCH("priority/{itemId}")
    suspend fun updatePriority(
        @Path("itemId") itemId: Int,
        @Body body: PriorityUpdateDto,
    ): PriorityItemDto

    @POST("priority/{itemId}/feedback")
    suspend fun priorityFeedback(
        @Path("itemId") itemId: Int,
        @Body body: PriorityFeedbackDto,
    ): PriorityItemDto

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

    @GET("status/sync")
    suspend fun syncStatus(): SyncStatusDto

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

    @POST("expenses/refunds")
    suspend fun markAsRefunded(@Body body: RefundCreateDto): RefundDto

    @DELETE("expenses/refunds/{refundId}")
    suspend fun undoRefund(@Path("refundId") refundId: Int): RefundDto

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

    @GET("statements/cycles/current")
    suspend fun currentCardCycles(): CurrentCardCyclesDto

    @GET("statements/cycles/current/{bank}/{cardLast4}")
    suspend fun currentCardCycleDetails(
        @Path("bank") bank: String,
        @Path("cardLast4") cardLast4: String,
    ): CurrentCardCycleDetailsDto

    @GET("statements/trends")
    suspend fun spendTrends(@Query("months") months: Int): SpendTrendsDto
}
