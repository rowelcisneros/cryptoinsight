package com.cryptoinsight.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.random.Random

object NotificationSupport {
    private const val CHANNEL_ID = "market_signals"
    private const val CHANNEL_NAME = "Market Signals"
    const val EXTRA_SYMBOL = "extra_symbol"
    const val EXTRA_TAB = "extra_tab"
    const val EXTRA_TIMEFRAME = "extra_timeframe"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Crypto Insight market signal updates"
        }
        manager.createNotificationChannel(channel)
    }

    fun notify(
        context: Context,
        title: String,
        body: String,
        symbol: String? = null,
        tab: String = "detail",
        timeframe: String? = null
    ) {
        ensureChannel(context)
        if (!canPostNotifications(context)) return

        val pendingIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            symbol?.let { putExtra(EXTRA_SYMBOL, it) }
            putExtra(EXTRA_TAB, tab)
            timeframe?.let { putExtra(EXTRA_TIMEFRAME, it) }
        }.let { intent ->
            PendingIntent.getActivity(
                context,
                symbol?.hashCode() ?: 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(Random.nextInt(), notification)
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasNotificationPermission(context: Context): Boolean = canPostNotifications(context)
}
