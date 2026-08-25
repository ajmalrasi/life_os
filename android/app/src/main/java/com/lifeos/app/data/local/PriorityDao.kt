package com.lifeos.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PriorityDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PriorityItemEntity>)

    @Query("DELETE FROM priority_items_cache")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(items: List<PriorityItemEntity>) {
        clear()
        upsertAll(items)
    }

    @Query("SELECT * FROM priority_items_cache ORDER BY receivedAt DESC")
    fun observeAll(): Flow<List<PriorityItemEntity>>
}
