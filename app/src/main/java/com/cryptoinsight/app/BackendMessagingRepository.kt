package com.cryptoinsight.app

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class DeviceRegistrationRequest(
    val token: String,
    val platform: String = "android",
    val packageName: String,
    val pushMode: String
)

data class TestPushRequest(
    val token: String,
    val symbol: String,
    val tab: String,
    val timeframe: String,
    val title: String,
    val body: String
)

data class BackendResult(
    val success: Boolean,
    val message: String
)

interface MessagingBackendRepository {
    suspend fun syncDeviceToken(token: String, pushMode: String): BackendResult
    suspend fun sendTestPush(token: String, symbol: String, timeframe: String): BackendResult
    fun isConfigured(): Boolean
}

class HttpMessagingBackendRepository : MessagingBackendRepository {
    private val baseUrl = BuildConfig.MESSAGING_BASE_URL.trim()
    private val apiKey = BuildConfig.MESSAGING_API_KEY.trim()

    private val api: MessagingBackendApi? = if (baseUrl.isNotBlank()) {
        Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MessagingBackendApi::class.java)
    } else {
        null
    }

    override fun isConfigured(): Boolean = api != null

    override suspend fun syncDeviceToken(token: String, pushMode: String): BackendResult {
        val backend = api ?: return BackendResult(false, "Backend messaging not configured")
        return runCatching {
            backend.registerDevice(
                apiKey = apiKey.ifBlank { null },
                body = DeviceRegistrationRequest(
                    token = token,
                    packageName = "com.cryptoinsight.app",
                    pushMode = pushMode
                )
            )
        }.getOrElse {
            BackendResult(false, "Token sync failed")
        }
    }

    override suspend fun sendTestPush(token: String, symbol: String, timeframe: String): BackendResult {
        val backend = api ?: return BackendResult(false, "Backend messaging not configured")
        return runCatching {
            backend.sendTestPush(
                apiKey = apiKey.ifBlank { null },
                body = TestPushRequest(
                    token = token,
                    symbol = symbol,
                    tab = "detail",
                    timeframe = timeframe,
                    title = "$symbol test signal",
                    body = "Backend-triggered FCM test for $symbol on $timeframe"
                )
            )
        }.getOrElse {
            BackendResult(false, "Test push failed")
        }
    }
}

private interface MessagingBackendApi {
    @POST("api/device/register")
    suspend fun registerDevice(
        @Header("X-API-Key") apiKey: String?,
        @Body body: DeviceRegistrationRequest
    ): BackendResult

    @POST("api/notifications/test")
    suspend fun sendTestPush(
        @Header("X-API-Key") apiKey: String?,
        @Body body: TestPushRequest
    ): BackendResult
}
