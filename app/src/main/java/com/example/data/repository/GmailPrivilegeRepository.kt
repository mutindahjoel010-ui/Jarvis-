package com.example.data.repository

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.example.data.model.EmailSummary
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GmailMessageListResponse(
    val messages: List<GmailMessageRef>? = null
)

@JsonClass(generateAdapter = true)
data class GmailMessageRef(
    val id: String,
    val threadId: String? = null
)

@JsonClass(generateAdapter = true)
data class GmailMessageFullResponse(
    val id: String,
    val snippet: String? = null,
    val labelIds: List<String>? = null,
    val payload: GmailPayload? = null
)

@JsonClass(generateAdapter = true)
data class GmailPayload(
    val mimeType: String? = null,
    val headers: List<GmailHeader>? = null,
    val body: GmailBody? = null,
    val parts: List<GmailPayload>? = null
)

@JsonClass(generateAdapter = true)
data class GmailHeader(
    val name: String,
    val value: String
)

@JsonClass(generateAdapter = true)
data class GmailBody(
    val data: String? = null
)

interface GmailApiService {
    @GET("gmail/v1/users/me/messages")
    suspend fun listMessages(
        @Header("Authorization") bearerToken: String,
        @Query("q") query: String? = null,
        @Query("maxResults") maxResults: Int = 10
    ): GmailMessageListResponse

    @GET("gmail/v1/users/me/messages/{id}")
    suspend fun getMessage(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: String,
        @Query("format") format: String = "full"
    ): GmailMessageFullResponse
}

sealed interface GmailAuthCheckResult {
    data class Authorized(val accessToken: String) : GmailAuthCheckResult
    data class NeedsResolution(val pendingIntent: PendingIntent) : GmailAuthCheckResult
    data class Unavailable(val reason: String) : GmailAuthCheckResult
}

data class EmailQueryResult(
    val emails: List<EmailSummary>,
    val needsGmailAuth: Boolean = false,
    val errorMessage: String? = null
)

class GmailPrivilegeRepository(private val context: Context) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gmailApi: GmailApiService = Retrofit.Builder()
        .baseUrl("https://gmail.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GmailApiService::class.java)

    private val _isGmailConnected = MutableStateFlow(false)
    val isGmailConnected: StateFlow<Boolean> = _isGmailConnected.asStateFlow()

    @Volatile
    private var cachedAccessToken: String? = null

    suspend fun requestAuthorization(): GmailAuthCheckResult = withContext(Dispatchers.IO) {
        try {
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(GMAIL_READONLY_SCOPE)))
                .build()
            val result = withTimeout(6_000L) {
                Identity.getAuthorizationClient(context)
                    .authorize(request)
                    .await()
            }

            val pendingIntent = result.pendingIntent
            if (result.hasResolution() && pendingIntent != null) {
                _isGmailConnected.value = false
                GmailAuthCheckResult.NeedsResolution(pendingIntent)
            } else {
                val token = result.accessToken
                if (!token.isNullOrBlank()) {
                    cachedAccessToken = token
                    _isGmailConnected.value = true
                    GmailAuthCheckResult.Authorized(token)
                } else {
                    _isGmailConnected.value = false
                    GmailAuthCheckResult.Unavailable("Gmail access token was empty.")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gmail authorization check failed", e)
            _isGmailConnected.value = false
            GmailAuthCheckResult.Unavailable(e.localizedMessage ?: "Gmail authorization unavailable.")
        }
    }

    fun handleAuthorizationActivityResult(data: Intent?): Boolean {
        return try {
            val authResult = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data)
            val token = authResult.accessToken
            if (!token.isNullOrBlank()) {
                cachedAccessToken = token
                _isGmailConnected.value = true
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract Gmail authorization result", e)
            false
        }
    }

    suspend fun listEmails(query: String = "", maxResults: Int = 8): EmailQueryResult =
        withContext(Dispatchers.IO) {
            val token = resolveAccessToken()
            if (token == null) {
                return@withContext EmailQueryResult(
                    emails = emptyList(),
                    needsGmailAuth = true,
                    errorMessage = "Gmail access has not been granted yet."
                )
            }

            try {
                val bearer = "Bearer $token"
                val safeMax = maxResults.coerceIn(1, 15)
                val listResponse = gmailApi.listMessages(
                    bearerToken = bearer,
                    query = query.trim().ifBlank { null },
                    maxResults = safeMax
                )
                val refs = listResponse.messages.orEmpty()
                val emails = coroutineScope {
                    refs.map { ref ->
                        async {
                            try {
                                withTimeoutOrNull(6_000L) {
                                    val msg = gmailApi.getMessage(bearer, ref.id, format = "full")
                                    mapMessageToSummary(msg)
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to fetch Gmail message ${ref.id}", e)
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                EmailQueryResult(emails = emails, needsGmailAuth = false)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read Gmail messages", e)
                if (e.message?.contains("401") == true) {
                    cachedAccessToken = null
                    _isGmailConnected.value = false
                    EmailQueryResult(
                        emails = emptyList(),
                        needsGmailAuth = true,
                        errorMessage = "Gmail session expired. Please reconnect Gmail."
                    )
                } else {
                    EmailQueryResult(
                        emails = emptyList(),
                        needsGmailAuth = false,
                        errorMessage = e.localizedMessage ?: "Could not read emails right now."
                    )
                }
            }
        }

    suspend fun getEmailDetail(messageId: String): EmailSummary? = withContext(Dispatchers.IO) {
        val token = resolveAccessToken() ?: return@withContext null
        try {
            val msg = gmailApi.getMessage("Bearer $token", messageId, format = "full")
            mapMessageToSummary(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Gmail detail for $messageId", e)
            null
        }
    }

    fun clearSession() {
        cachedAccessToken = null
        _isGmailConnected.value = false
    }

    private suspend fun resolveAccessToken(): String? {
        cachedAccessToken?.let { return it }
        return when (val check = requestAuthorization()) {
            is GmailAuthCheckResult.Authorized -> check.accessToken
            else -> null
        }
    }

    private fun mapMessageToSummary(msg: GmailMessageFullResponse): EmailSummary {
        val headers = msg.payload?.headers.orEmpty()
        val from = headers.firstOrNull { it.name.equals("From", ignoreCase = true) }?.value.orEmpty()
        val subject = headers.firstOrNull { it.name.equals("Subject", ignoreCase = true) }?.value.orEmpty()
            .ifBlank { "(No subject)" }
        val date = headers.firstOrNull { it.name.equals("Date", ignoreCase = true) }?.value.orEmpty()
        val isUnread = msg.labelIds?.contains("UNREAD") == true
        val bodyText = extractPlainTextBody(msg.payload).ifBlank { msg.snippet.orEmpty() }
        return EmailSummary(
            id = msg.id,
            from = from,
            subject = subject,
            date = date,
            snippet = msg.snippet.orEmpty(),
            isUnread = isUnread,
            body = bodyText.take(4000)
        )
    }

    private fun extractPlainTextBody(payload: GmailPayload?): String {
        if (payload == null) return ""
        if (payload.mimeType == "text/plain" && !payload.body?.data.isNullOrBlank()) {
            return decodeBase64Url(payload.body!!.data!!)
        }
        payload.parts?.forEach { part ->
            val extracted = extractPlainTextBody(part)
            if (extracted.isNotBlank()) return extracted
        }
        if (!payload.body?.data.isNullOrBlank()) {
            return decodeBase64Url(payload.body!!.data!!)
        }
        return ""
    }

    private fun decodeBase64Url(encoded: String): String {
        return try {
            val bytes = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private companion object {
        const val TAG = "GmailRepo"
        const val GMAIL_READONLY_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    }
}
