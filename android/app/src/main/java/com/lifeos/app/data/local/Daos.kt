package com.lifeos.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
