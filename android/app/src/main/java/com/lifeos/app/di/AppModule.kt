package com.lifeos.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.lifeos.app.data.local.CardStatementDao
import com.lifeos.app.data.local.CurrentCardCycleDao
import com.lifeos.app.data.local.InsightDao
import com.lifeos.app.data.local.LifeOsDatabase
import com.lifeos.app.data.local.MIGRATION_12_13
import com.lifeos.app.data.local.PriorityDao
import com.lifeos.app.data.local.SynthesizedInsightDao
import com.lifeos.app.data.local.MoodDao
import com.lifeos.app.data.local.ScoreDao
import com.lifeos.app.data.local.StateDao
import com.lifeos.app.data.local.TransactionDao
import com.lifeos.app.trips.data.TripDao
import com.lifeos.app.trips.data.TripDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lifeos_settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): LifeOsDatabase =
        Room.databaseBuilder(context, LifeOsDatabase::class.java, "lifeos.db")
            .addMigrations(MIGRATION_12_13)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun tripDatabase(@ApplicationContext context: Context): TripDatabase =
        Room.databaseBuilder(context, TripDatabase::class.java, "lifeos-trips.db").build()

    @Provides
    fun tripDao(db: TripDatabase): TripDao = db.tripDao()

    @Provides
    fun moodDao(db: LifeOsDatabase): MoodDao = db.moodDao()

    @Provides
    fun scoreDao(db: LifeOsDatabase): ScoreDao = db.scoreDao()

    @Provides
    fun stateDao(db: LifeOsDatabase): StateDao = db.stateDao()

    @Provides
    fun insightDao(db: LifeOsDatabase): InsightDao = db.insightDao()

    @Provides
    fun synthesizedInsightDao(db: LifeOsDatabase): SynthesizedInsightDao = db.synthesizedInsightDao()

    @Provides
    fun transactionDao(db: LifeOsDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun cardStatementDao(db: LifeOsDatabase): CardStatementDao = db.cardStatementDao()

    @Provides
    fun currentCardCycleDao(db: LifeOsDatabase): CurrentCardCycleDao = db.currentCardCycleDao()

    @Provides
    fun priorityDao(db: LifeOsDatabase): PriorityDao = db.priorityDao()
}
