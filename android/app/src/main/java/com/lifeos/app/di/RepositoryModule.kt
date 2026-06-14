package com.lifeos.app.di

import com.lifeos.app.data.repository.AnalyticsRepositoryImpl
import com.lifeos.app.data.repository.ChatRepositoryImpl
import com.lifeos.app.data.repository.GithubRepositoryImpl
import com.lifeos.app.data.repository.HealthRepositoryImpl
import com.lifeos.app.data.repository.MoodRepositoryImpl
import com.lifeos.app.data.repository.SettingsRepositoryImpl
import com.lifeos.app.domain.repository.AnalyticsRepository
import com.lifeos.app.domain.repository.ChatRepository
import com.lifeos.app.domain.repository.GithubRepository
import com.lifeos.app.domain.repository.HealthRepository
import com.lifeos.app.domain.repository.MoodRepository
import com.lifeos.app.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun healthRepository(impl: HealthRepositoryImpl): HealthRepository

    @Binds
    @Singleton
    abstract fun moodRepository(impl: MoodRepositoryImpl): MoodRepository

    @Binds
    @Singleton
    abstract fun githubRepository(impl: GithubRepositoryImpl): GithubRepository

    @Binds
    @Singleton
    abstract fun analyticsRepository(impl: AnalyticsRepositoryImpl): AnalyticsRepository

    @Binds
    @Singleton
    abstract fun chatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun settingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
}
