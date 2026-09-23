package com.lifeos.app.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.lifeos.app.data.remote.AuthInterceptor
import com.lifeos.app.data.remote.DynamicBaseUrlInterceptor
import com.lifeos.app.data.remote.LifeOsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun okHttp(dynamicBaseUrl: DynamicBaseUrlInterceptor, auth: AuthInterceptor): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        return OkHttpClient.Builder()
            .addInterceptor(dynamicBaseUrl)
            .addInterceptor(auth)
            .addInterceptor(logging)
            // OpenClaw inference on a Jetson can be slow; be generous.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            // Placeholder; DynamicBaseUrlInterceptor rewrites host at runtime.
            .baseUrl("http://localhost:8000/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun api(retrofit: Retrofit): LifeOsApi = retrofit.create(LifeOsApi::class.java)
}
