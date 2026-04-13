package com.cryptoinsight.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

data class StreamUpdate(
    val tickers: Map<String, StreamTicker> = emptyMap(),
    val selectedKline: Kline? = null,
    val selectedDepth: OrderBook? = null
)

data class StreamTicker(
    val symbol: String,
    val price: Double,
    val changePercent: Double,
    val quoteVolume: Double
)

class BinanceStreamService {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rotateRunnable = Runnable {
        if (!manuallyClosed) {
            activeStatusCallback?.invoke("WebSocket rotating before 24h limit")
            reconnectAttempt = 0
            reconnectNow()
        }
    }

    private var webSocket: WebSocket? = null
    private var activeSymbols: List<String> = emptyList()
    private var activeSelectedSymbol: String? = null
    private var activeStatusCallback: ((String) -> Unit)? = null
    private var activeUpdateCallback: ((StreamUpdate) -> Unit)? = null
    private var reconnectAttempt = 0
    private var manuallyClosed = false

    fun connect(
        symbols: List<String>,
        selectedSymbol: String,
        onStatus: (String) -> Unit,
        onUpdate: (StreamUpdate) -> Unit
    ) {
        manuallyClosed = false
        mainHandler.removeCallbacksAndMessages(null)
        closeSocket()
        activeSymbols = symbols
        activeSelectedSymbol = selectedSymbol
        activeStatusCallback = onStatus
        activeUpdateCallback = onUpdate
        openSocket(symbols, selectedSymbol, onStatus, onUpdate)
    }

    private fun openSocket(
        symbols: List<String>,
        selectedSymbol: String,
        onStatus: (String) -> Unit,
        onUpdate: (StreamUpdate) -> Unit
    ) {
        val miniTickerStreams = symbols.map { "${it.lowercase(Locale.US)}@miniTicker" }
        val selectedStreams = listOf(
            "${selectedSymbol.lowercase(Locale.US)}@kline_1m",
            "${selectedSymbol.lowercase(Locale.US)}@depth20@1000ms"
        )
        val streamPath = (miniTickerStreams + selectedStreams).joinToString("/")
        val request = Request.Builder()
            .url("wss://stream.binance.com:9443/stream?streams=$streamPath")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                onStatus("WebSocket live")
                scheduleRotation()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseCombinedMessage(text)?.let(onUpdate)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("WebSocket failed, retrying with backoff")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!manuallyClosed) {
                    onStatus("WebSocket closed, retrying")
                    scheduleReconnect()
                }
            }
        })
    }

    fun close() {
        manuallyClosed = true
        mainHandler.removeCallbacksAndMessages(null)
        closeSocket()
        activeSymbols = emptyList()
        activeSelectedSymbol = null
        activeStatusCallback = null
        activeUpdateCallback = null
        reconnectAttempt = 0
    }

    private fun parseCombinedMessage(text: String): StreamUpdate? {
        val root = JSONObject(text)
        val stream = root.optString("stream")
        val data = root.opt("data") ?: return null

        return when {
            stream.endsWith("@miniTicker") -> {
                val payload = data as JSONObject
                val close = payload.optString("c").toDoubleOrNull() ?: return null
                val open = payload.optString("o").toDoubleOrNull() ?: return null
                val quoteVolume = payload.optString("q").toDoubleOrNull() ?: 0.0
                val change = if (open == 0.0) 0.0 else ((close - open) / open) * 100.0
                StreamUpdate(
                    tickers = mapOf(
                        payload.optString("s") to StreamTicker(
                            symbol = payload.optString("s"),
                            price = close,
                            changePercent = change,
                            quoteVolume = quoteVolume
                        )
                    )
                )
            }

            stream.contains("@kline_") -> {
                val payload = (data as JSONObject).getJSONObject("k")
                StreamUpdate(
                    selectedKline = Kline(
                        open = payload.optString("o").toDoubleOrNull() ?: 0.0,
                        high = payload.optString("h").toDoubleOrNull() ?: 0.0,
                        low = payload.optString("l").toDoubleOrNull() ?: 0.0,
                        close = payload.optString("c").toDoubleOrNull() ?: 0.0,
                        volume = payload.optString("v").toDoubleOrNull() ?: 0.0
                    )
                )
            }

            stream.contains("@depth20") -> {
                val payload = data as JSONObject
                StreamUpdate(
                    selectedDepth = OrderBook(
                        bids = payload.optJSONArray("bids").toLevels(),
                        asks = payload.optJSONArray("asks").toLevels()
                    )
                )
            }

            else -> null
        }
    }

    private fun scheduleReconnect() {
        if (manuallyClosed) return
        val symbols = activeSymbols
        val selectedSymbol = activeSelectedSymbol
        val onStatus = activeStatusCallback
        val onUpdate = activeUpdateCallback
        if (symbols.isEmpty() || selectedSymbol == null || onStatus == null || onUpdate == null) return

        reconnectAttempt += 1
        val delayMs = minOf(30_000L, 1_000L shl (reconnectAttempt - 1).coerceAtMost(4))
        onStatus("WebSocket reconnect in ${delayMs / 1000}s")
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed(
            { reconnectNow() },
            delayMs
        )
    }

    private fun reconnectNow() {
        val symbols = activeSymbols
        val selectedSymbol = activeSelectedSymbol
        val onStatus = activeStatusCallback
        val onUpdate = activeUpdateCallback
        if (symbols.isEmpty() || selectedSymbol == null || onStatus == null || onUpdate == null) return
        closeSocket()
        openSocket(symbols, selectedSymbol, onStatus, onUpdate)
    }

    private fun scheduleRotation() {
        mainHandler.removeCallbacks(rotateRunnable)
        mainHandler.postDelayed(rotateRunnable, ROTATE_BEFORE_24H_MS)
    }

    private fun closeSocket() {
        webSocket?.cancel()
        webSocket = null
    }

    private companion object {
        const val ROTATE_BEFORE_24H_MS = 23 * 60 * 60 * 1000L + 50 * 60 * 1000L
    }
}

fun JSONArray?.toLevels(): List<OrderLevel> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            val row = optJSONArray(index) ?: continue
            val price = row.optString(0).toDoubleOrNull() ?: continue
            val quantity = row.optString(1).toDoubleOrNull() ?: continue
            add(OrderLevel(price, quantity))
        }
    }
}
