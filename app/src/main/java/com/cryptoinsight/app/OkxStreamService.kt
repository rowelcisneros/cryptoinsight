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
import java.util.concurrent.TimeUnit

class OkxStreamService {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rotateRunnable = Runnable {
        if (!manuallyClosed) {
            activeStatusCallback?.invoke("OKX WebSocket rotating before 24h limit")
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

    private fun openSocket(
        symbols: List<String>,
        selectedSymbol: String,
        onStatus: (String) -> Unit,
        onUpdate: (StreamUpdate) -> Unit
    ) {
        val request = Request.Builder()
            .url("wss://ws.okx.com:8443/ws/v5/public")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                subscribe(symbols, selectedSymbol)
                onStatus("OKX WebSocket live")
                scheduleRotation()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"event\":\"subscribe\"")) return
                parseMessage(text)?.let(onUpdate)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("OKX WebSocket failed, retrying with backoff")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!manuallyClosed) {
                    onStatus("OKX WebSocket closed, retrying")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun subscribe(symbols: List<String>, selectedSymbol: String) {
        val args = JSONArray()
        symbols.forEach { symbol ->
            args.put(JSONObject().put("channel", "tickers").put("instId", symbol.toOkxInstId()))
        }
        args.put(JSONObject().put("channel", "candle1m").put("instId", selectedSymbol.toOkxInstId()))
        args.put(JSONObject().put("channel", "books").put("instId", selectedSymbol.toOkxInstId()))
        val payload = JSONObject()
            .put("op", "subscribe")
            .put("args", args)
        webSocket?.send(payload.toString())
    }

    private fun parseMessage(text: String): StreamUpdate? {
        val root = JSONObject(text)
        val arg = root.optJSONObject("arg") ?: return null
        val channel = arg.optString("channel")
        val data = root.optJSONArray("data") ?: return null
        if (data.length() == 0) return null

        return when {
            channel == "tickers" -> {
                val payload = data.optJSONObject(0) ?: return null
                val last = payload.optString("last").toDoubleOrNull() ?: return null
                val open = payload.optString("sodUtc0").toDoubleOrNull() ?: return null
                val change = if (open == 0.0) 0.0 else ((last - open) / open) * 100.0
                StreamUpdate(
                    tickers = mapOf(
                        payload.optString("instId").fromOkxInstId() to StreamTicker(
                            symbol = payload.optString("instId").fromOkxInstId(),
                            price = last,
                            changePercent = change,
                            quoteVolume = payload.optString("volCcy24h").toDoubleOrNull() ?: 0.0
                        )
                    )
                )
            }

            channel == "candle1m" -> {
                val row = data.optJSONArray(0) ?: return null
                StreamUpdate(
                    selectedKline = row.toOkxArrayKline()
                )
            }

            channel == "books" -> {
                val payload = data.optJSONObject(0) ?: return null
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
        onStatus("OKX WebSocket reconnect in ${delayMs / 1000}s")
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ reconnectNow() }, delayMs)
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

private fun JSONArray.toOkxArrayKline(): Kline {
    val open = optString(1).toDoubleOrNull() ?: 0.0
    val high = optString(2).toDoubleOrNull() ?: open
    val low = optString(3).toDoubleOrNull() ?: open
    val close = optString(4).toDoubleOrNull() ?: open
    val volume = optString(7).toDoubleOrNull() ?: optString(5).toDoubleOrNull() ?: 0.0
    return Kline(open, high, low, close, volume)
}

private fun String.fromOkxInstId(): String = replace("-", "")
