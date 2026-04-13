package com.cryptoinsight.app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.concurrent.TimeUnit

class CryptoInsightMessagingService : FirebaseMessagingService() {
    override fun onCreate() {
        super.onCreate()
        NotificationSupport.ensureChannel(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "Crypto Insight"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: "New market signal received."
        val symbol = message.data["symbol"]
        val tab = message.data["tab"] ?: "detail"
        val timeframe = message.data["timeframe"]
        NotificationSupport.notify(this, title, body, symbol, tab, timeframe)
    }

    override fun onNewToken(token: String) {
        getSharedPreferences("crypto_insight_prefs", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .putBoolean("fcm_token_pending_sync", true)
            .apply()
        FirebaseTokenSyncPlaceholder.enqueueSync(this)
    }
}

object FirebaseRegistration {
    fun isConfigured(context: Context): Boolean {
        return runCatching {
            FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
        }.getOrDefault(false)
    }

    fun fetchToken(
        context: Context,
        onSuccess: (String) -> Unit,
        onFailure: () -> Unit
    ) {
        if (!isConfigured(context)) {
            onFailure()
            return
        }
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { onSuccess(it) }
                .addOnFailureListener { onFailure() }
        }.onFailure {
            onFailure()
        }
    }
}

object FirebaseTokenSyncPlaceholder {
    private const val UNIQUE_WORK = "fcm_token_sync"

    fun markSynced(context: Context) {
        context.getSharedPreferences("crypto_insight_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("fcm_token_pending_sync", false)
            .apply()
    }

    fun isPending(context: Context): Boolean =
        context.getSharedPreferences("crypto_insight_prefs", Context.MODE_PRIVATE)
            .getBoolean("fcm_token_pending_sync", false)

    fun enqueueSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<FirebaseTokenSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}

class FirebaseTokenSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val backend: MessagingBackendRepository = HttpMessagingBackendRepository()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("crypto_insight_prefs", Context.MODE_PRIVATE)
        val token = prefs.getString("fcm_token", null) ?: return Result.success()
        if (!FirebaseTokenSyncPlaceholder.isPending(applicationContext)) return Result.success()
        if (!backend.isConfigured()) return Result.retry()

        val result = backend.syncDeviceToken(token, "fcm")
        return if (result.success) {
            FirebaseTokenSyncPlaceholder.markSynced(applicationContext)
            Result.success()
        } else {
            Result.retry()
        }
    }
}
