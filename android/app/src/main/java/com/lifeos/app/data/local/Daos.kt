package com.lifeos.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MoodDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MoodEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MoodEntity>)

    @Query("SELECT * FROM mood_checkins WHERE day >= :since ORDER BY day DESC")
    fun observeSince(since: String): Flow<List<MoodEntity>>

    @Query("SELECT * FROM mood_checkins WHERE day = :day LIMIT 1")
    suspend fun get(day: String): MoodEntity?
}

@Dao
interface ScoreDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ScoreEntity>)

    @Query("SELECT * FROM daily_scores WHERE day >= :since ORDER BY day DESC")
    fun observeSince(since: String): Flow<List<ScoreEntity>>
}

@Dao
interface StateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<StateEntity>)

    @Query("SELECT * FROM daily_states WHERE day >= :since ORDER BY day DESC")
    fun observeSince(since: String): Flow<List<StateEntity>>
}

@Dao
interface InsightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<InsightEntity>)

    @Query("DELETE FROM insights")
    suspend fun clear()

    @Query("SELECT * FROM insights ORDER BY ABS(correlation) DESC")
    fun observeAll(): Flow<List<InsightEntity>>
}

@Dao
interface SynthesizedInsightDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SynthesizedInsightEntity>)

    @Query("DELETE FROM synthesized_insights")
    suspend fun clear()

    @Query("SELECT * FROM synthesized_insights ORDER BY rank")
    fun observeAll(): Flow<List<SynthesizedInsightEntity>>
}

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<TransactionEntity>)

    // The server can delete-and-reinsert a transaction under a new id (e.g. a
    // backend rebuild reprocessing an email); upsertAll alone never removes
    // the old id, so it lingers forever as a phantom duplicate. Call this
    // with every id the server just returned for the same window so the
    // cache mirrors the server instead of only ever growing.
    @Query("DELETE FROM transactions_cache WHERE txnDate >= :since AND id NOT IN (:keepIds)")
    suspend fun deleteStaleSince(since: String, keepIds: List<Int>)

    @Query("DELETE FROM transactions_cache WHERE txnDate >= :since")
    suspend fun deleteSince(since: String)

    @Query("SELECT * FROM transactions_cache WHERE txnDate >= :since ORDER BY txnDate DESC, id DESC")
    fun observeSince(since: String): Flow<List<TransactionEntity>>
}

@Dao
interface CardStatementDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<CardStatementEntity>)

    @Query("DELETE FROM card_statements_cache")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(entities: List<CardStatementEntity>) {
        clear()
        upsertAll(entities)
    }

    @Query("SELECT * FROM card_statements_cache ORDER BY statementDate DESC")
    fun observeAll(): Flow<List<CardStatementEntity>>
}
