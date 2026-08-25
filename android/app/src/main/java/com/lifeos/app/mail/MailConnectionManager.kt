package com.lifeos.app.mail

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.lifeos.app.data.remote.LifeOsApi
import com.lifeos.app.data.remote.dto.MailConnectionCompleteDto
import com.lifeos.app.data.remote.dto.MailConnectionStartDto
import com.lifeos.app.data.toDomain
import com.lifeos.app.domain.model.ConnectedMailAccount
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the short phone portion of Gmail OAuth. Google redirects to a temporary
 * loopback listener on this phone; only the one-time callback URL is returned
 * to the Jetson. The refresh token is exchanged and stored by the Jetson.
 */
@Singleton
class MailConnectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: LifeOsApi,
) {
    suspend fun accounts(): List<ConnectedMailAccount> = try {
        api.listMailAccounts().map { it.toDomain() }
    } catch (error: HttpException) {
        throw MailConnectionException(error.detailOr("Mail connections could not be loaded."), error)
    }

    suspend fun connect(
        purpose: String,
        email: String,
        forceReauthorize: Boolean,
    ): ConnectedMailAccount = try {
        withContext(Dispatchers.IO) {
            ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST)).use { server ->
                server.soTimeout = AUTH_TIMEOUT_MILLIS
                val label = if (purpose == "finance") "Money inbox" else "Priority inbox"
                val start = api.startMailConnection(
                    MailConnectionStartDto(
                        purpose = purpose,
                        email = email.trim(),
                        displayLabel = label,
                        callbackPort = server.localPort,
                        forceReauthorize = forceReauthorize,
                    ),
                )
                if (!start.requiresAuthorization) {
                    return@withContext requireNotNull(start.account).toDomain()
                }

                val authorizationUrl = requireNotNull(start.authorizationUrl)
                val sessionId = requireNotNull(start.sessionId)
                val sessionSecret = requireNotNull(start.sessionSecret)
                openBrowser(authorizationUrl)
                val callbackUrl = receiveCallback(server)
                api.completeMailConnection(
                    MailConnectionCompleteDto(sessionId, sessionSecret, callbackUrl),
                ).toDomain()
            }
        }
    } catch (error: HttpException) {
        throw MailConnectionException(error.detailOr("Gmail authorization could not be completed."), error)
    } catch (error: SocketTimeoutException) {
        throw MailConnectionException("Google authorization timed out. Start the connection again.", error)
    }

    suspend fun stopUsing(accountId: Int, purpose: String) {
        try {
            api.stopUsingMailAccount(accountId, purpose)
        } catch (error: HttpException) {
            throw MailConnectionException(error.detailOr("The mailbox could not be disconnected."), error)
        }
    }

    private suspend fun openBrowser(url: String) = withContext(Dispatchers.Main) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (exc: ActivityNotFoundException) {
            throw MailConnectionException("No browser is available for Google authorization.", exc)
        }
    }

    private fun receiveCallback(server: ServerSocket): String {
        server.accept().use { socket ->
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine().orEmpty()
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            if (!target.startsWith(CALLBACK_PATH)) {
                throw MailConnectionException("Google returned an invalid authorization callback.")
            }
            while (reader.readLine()?.isNotEmpty() == true) Unit

            val body = """
                <!doctype html><html><head><meta name="viewport" content="width=device-width">
                <title>LifeOS connected</title></head><body style="font-family:sans-serif;padding:32px">
                <h2>Authorization received</h2><p>Return to LifeOS to finish connecting this inbox.</p>
                <p><a href="lifeos://mail-auth-complete">Return to LifeOS</a></p>
                <script>setTimeout(function(){ location.href='lifeos://mail-auth-complete'; }, 300);</script>
                </body></html>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/html; charset=utf-8\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            socket.getOutputStream().apply {
                write(headers)
                write(body)
                flush()
            }
            return "http://$LOOPBACK_HOST:${server.localPort}$target"
        }
    }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val CALLBACK_PATH = "/oauth2/callback"
        const val AUTH_TIMEOUT_MILLIS = 5 * 60 * 1_000
    }
}

class MailConnectionException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

private fun HttpException.detailOr(fallback: String): String = runCatching {
    JSONObject(response()?.errorBody()?.string().orEmpty()).optString("detail")
}.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback
