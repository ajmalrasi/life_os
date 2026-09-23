package com.lifeos.app.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Attaches the user-configured bearer token to every request, mirroring
 * [DynamicBaseUrlInterceptor]'s runtime-configurable pattern: Retrofit/OkHttp
 * are built once, and [SettingsRepositoryImpl] pushes the current token here
 * whenever it changes (DataStore collector + explicit save).
 *
 * An empty/unset token (the default — matches the backend's `api_token`
 * unset-disables-auth default, see AGENTS.md §2) adds no header at all, so
 * this is a silent no-op until the user opts into auth.
 */
@Singleton
class AuthInterceptor @Inject constructor() : Interceptor {

    private val token = AtomicReference("")

    fun setToken(value: String) {
        token.set(value.trim())
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val current = token.get()
        val request = chain.request()
        if (current.isEmpty()) return chain.proceed(request)

        return chain.proceed(
            request.newBuilder()
                .header("Authorization", "Bearer $current")
                .build(),
        )
    }
}
