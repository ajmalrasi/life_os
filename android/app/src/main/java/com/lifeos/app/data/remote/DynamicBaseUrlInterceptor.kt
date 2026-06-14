package com.lifeos.app.data.remote

import com.lifeos.app.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rewrites each request's scheme/host/port to the user-configured backend
 * address. Retrofit is built once with a placeholder base URL; this keeps the
 * Jetson address fully configurable at runtime (settings screen / DataStore)
 * without rebuilding the Retrofit instance.
 */
@Singleton
class DynamicBaseUrlInterceptor @Inject constructor() : Interceptor {

    private val baseUrl = AtomicReference<String>(normalize(BuildConfig.DEFAULT_API_BASE_URL))

    fun setBaseUrl(url: String) {
        baseUrl.set(normalize(url))
    }

    private fun normalize(url: String): String =
        if (url.endsWith("/")) url else "$url/"

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val target = baseUrl.get().toHttpUrlOrNull() ?: return chain.proceed(request)

        val newUrl = request.url.newBuilder()
            .scheme(target.scheme)
            .host(target.host)
            .port(target.port)
            .build()

        return chain.proceed(request.newBuilder().url(newUrl).build())
    }
}
