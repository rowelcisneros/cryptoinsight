package com.cryptoinsight.app

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface LiveMarketRepository {
    suspend fun refreshMarket(baseCoins: List<CoinMarket>): List<CoinMarket>
    suspend fun refreshCoinDetails(coin: CoinMarket): CoinMarket
}

private val sharedMetadataRepository = CoinGeckoMetadataRepository()

data class TrackedAsset(
    val symbol: String,
    val coinGeckoId: String,
    val fallbackName: String
)

object AssetCatalog {
    val trackedAssets = listOf(
        TrackedAsset("BTCUSDT", "bitcoin", "Bitcoin"),
        TrackedAsset("ETHUSDT", "ethereum", "Ethereum"),
        TrackedAsset("SOLUSDT", "solana", "Solana"),
        TrackedAsset("DOGEUSDT", "dogecoin", "Dogecoin"),
        TrackedAsset("PEPEUSDT", "pepe", "Pepe"),
        TrackedAsset("LINKUSDT", "chainlink", "Chainlink"),
        TrackedAsset("AAVEUSDT", "aave", "Aave"),
        TrackedAsset("UNIUSDT", "uniswap", "Uniswap"),
        TrackedAsset("ONDOUSDT", "ondo-finance", "Ondo"),
        TrackedAsset("ARBUSDT", "arbitrum", "Arbitrum"),
        TrackedAsset("OPUSDT", "optimism", "Optimism"),
        TrackedAsset("FETUSDT", "fetch-ai", "Fetch.ai")
    )

    private val assetsBySymbol = trackedAssets.associateBy { it.symbol }

    fun coinGeckoIds(): String = trackedAssets.joinToString(",") { it.coinGeckoId }

    fun coinGeckoIdFor(symbol: String): String? = assetsBySymbol[symbol]?.coinGeckoId

    fun placeholderCoins(): List<CoinMarket> = trackedAssets.map { asset ->
        CoinMarket(
            symbol = asset.symbol,
            name = asset.fallbackName,
            sector = "Uncategorized",
            price = 0.0,
            change24h = 0.0,
            volume24h = 0.0,
            sparkline = List(16) { 0.0 },
            klinesByFrame = Timeframe.entries.associateWith { blankKlinesFor(it) },
            orderBook = blankOrderBook(),
            marketCap = 0.0
        )
    }

    private fun blankKlinesFor(timeframe: Timeframe): List<Kline> {
        val count = when (timeframe) {
            Timeframe.M1 -> 18
            Timeframe.M5 -> 22
            Timeframe.M15 -> 26
            Timeframe.H1 -> 32
            Timeframe.H4 -> 28
            Timeframe.D1 -> 24
        }
        return List(count) { Kline(0.0, 0.0, 0.0, 0.0, 0.0) }
    }

    private fun blankOrderBook(): OrderBook {
        val levels = List(8) { OrderLevel(0.0, 0.0) }
        return OrderBook(bids = levels, asks = levels)
    }
}

class BinanceMarketRepository : LiveMarketRepository {
    private val metadataRepository = sharedMetadataRepository
    private val api = Retrofit.Builder()
        .baseUrl("https://api.binance.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(BinanceApi::class.java)

    override suspend fun refreshMarket(baseCoins: List<CoinMarket>): List<CoinMarket> {
        val enrichedCoins = metadataRepository.enrich(baseCoins)
        val tickers = runCatching { api.tickers() }
            .getOrElse { return enrichedCoins }
            .associateBy { it.symbol }

        return enrichedCoins.map { coin ->
            val ticker = tickers[coin.symbol] ?: return@map coin
            val klines1h = fetchKlinesSafe(coin.symbol, "1h", 32) ?: coin.klinesByFrame.getValue(Timeframe.H1)
            val sparkline = klines1h.map { it.close }.ifEmpty { coin.sparkline }

            coin.copy(
                price = ticker.lastPrice.toDoubleOrNull() ?: coin.price,
                change24h = ticker.priceChangePercent.toDoubleOrNull() ?: coin.change24h,
                volume24h = ticker.quoteVolume.toDoubleOrNull() ?: coin.volume24h,
                sparkline = sparkline,
                klinesByFrame = coin.klinesByFrame + mapOf(Timeframe.H1 to klines1h)
            )
        }
    }

    override suspend fun refreshCoinDetails(coin: CoinMarket): CoinMarket =
        coin.copy(
            klinesByFrame = mapOf(
                Timeframe.M1 to (fetchKlinesSafe(coin.symbol, "1m", 18) ?: coin.klinesByFrame.getValue(Timeframe.M1)),
                Timeframe.M5 to (fetchKlinesSafe(coin.symbol, "5m", 22) ?: coin.klinesByFrame.getValue(Timeframe.M5)),
                Timeframe.M15 to (fetchKlinesSafe(coin.symbol, "15m", 26) ?: coin.klinesByFrame.getValue(Timeframe.M15)),
                Timeframe.H1 to (fetchKlinesSafe(coin.symbol, "1h", 32) ?: coin.klinesByFrame.getValue(Timeframe.H1)),
                Timeframe.H4 to (fetchKlinesSafe(coin.symbol, "4h", 28) ?: coin.klinesByFrame.getValue(Timeframe.H4)),
                Timeframe.D1 to (fetchKlinesSafe(coin.symbol, "1d", 24) ?: coin.klinesByFrame.getValue(Timeframe.D1))
            ),
            orderBook = fetchDepthSafe(coin.symbol) ?: coin.orderBook
        )

    private suspend fun fetchKlinesSafe(symbol: String, interval: String, limit: Int): List<Kline>? =
        runCatching { api.klines(symbol, interval, limit).map { it.toDomain() } }
            .getOrNull()

    private suspend fun fetchDepthSafe(symbol: String): OrderBook? =
        runCatching { api.depth(symbol, 20).toDomain() }
            .getOrNull()
}

class OkxMarketRepository : LiveMarketRepository {
    private val metadataRepository = sharedMetadataRepository
    private val api = Retrofit.Builder()
        .baseUrl("https://www.okx.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OkxApi::class.java)

    override suspend fun refreshMarket(baseCoins: List<CoinMarket>): List<CoinMarket> {
        val enrichedCoins = metadataRepository.enrich(baseCoins)
        val tickers = runCatching { api.tickers(instType = "SPOT").data }
            .getOrElse { return enrichedCoins }
            .associateBy { it.instId }

        return enrichedCoins.map { coin ->
            val instId = coin.symbol.toOkxInstId()
            val ticker = tickers[instId] ?: return@map coin
            val klines1h = fetchKlinesSafe(instId, "1H", 32, coin.symbol) ?: coin.klinesByFrame.getValue(Timeframe.H1)
            val sparkline = klines1h.map { it.close }.ifEmpty { coin.sparkline }

            coin.copy(
                price = ticker.last.toDoubleOrNull() ?: coin.price,
                change24h = ticker.sodUtc0.toDoubleOrNull()
                    ?.takeIf { it != 0.0 }
                    ?.let { ((ticker.last.toDoubleOrNull() ?: coin.price) - it) / it * 100.0 }
                    ?: coin.change24h,
                volume24h = ticker.volCcy24h.toDoubleOrNull() ?: coin.volume24h,
                sparkline = sparkline,
                klinesByFrame = coin.klinesByFrame + mapOf(Timeframe.H1 to klines1h)
            )
        }
    }

    override suspend fun refreshCoinDetails(coin: CoinMarket): CoinMarket {
        val instId = coin.symbol.toOkxInstId()
        return coin.copy(
            klinesByFrame = mapOf(
                Timeframe.M1 to (fetchKlinesSafe(instId, "1m", 18, coin.symbol) ?: coin.klinesByFrame.getValue(Timeframe.M1)),
                Timeframe.M5 to (fetchKlinesSafe(instId, "5m", 22, coin.symbol) ?: coin.klinesByFrame.getValue(Timeframe.M5)),
                Timeframe.M15 to (fetchKlinesSafe(instId, "15m", 26, coin.symbol) ?: coin.klinesByFrame.getValue(Timeframe.M15)),
                Timeframe.H1 to (fetchKlinesSafe(instId, "1H", 32, coin.symbol) ?: coin.klinesByFrame.getValue(Timeframe.H1)),
                Timeframe.H4 to (fetchKlinesSafe(instId, "4H", 28, coin.symbol) ?: coin.klinesByFrame.getValue(Timeframe.H4)),
                Timeframe.D1 to (fetchKlinesSafe(instId, "1Dutc", 24, coin.symbol) ?: coin.klinesByFrame.getValue(Timeframe.D1))
            ),
            orderBook = fetchDepthSafe(instId, coin.symbol) ?: coin.orderBook
        )
    }

    private suspend fun fetchKlinesSafe(instId: String, bar: String, limit: Int, symbol: String): List<Kline>? =
        runCatching { api.candles(instId = instId, bar = bar, limit = limit.toString()).data.map { it.toOkxKline() }.reversed() }
            .getOrNull()

    private suspend fun fetchDepthSafe(instId: String, symbol: String): OrderBook? =
        runCatching { api.books(instId = instId, sz = "20").data.firstOrNull()?.toDomain() }
            .getOrNull()
}

private interface BinanceApi {
    @GET("api/v3/ticker/24hr")
    suspend fun tickers(): List<BinanceTickerDto>

    @GET("api/v3/klines")
    suspend fun klines(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String,
        @Query("limit") limit: Int
    ): List<List<Any>>

    @GET("api/v3/depth")
    suspend fun depth(
        @Query("symbol") symbol: String,
        @Query("limit") limit: Int
    ): BinanceDepthDto
}

private interface OkxApi {
    @GET("api/v5/market/tickers")
    suspend fun tickers(@Query("instType") instType: String): OkxResponse<List<OkxTickerDto>>

    @GET("api/v5/market/candles")
    suspend fun candles(
        @Query("instId") instId: String,
        @Query("bar") bar: String,
        @Query("limit") limit: String
    ): OkxResponse<List<List<String>>>

    @GET("api/v5/market/books")
    suspend fun books(
        @Query("instId") instId: String,
        @Query("sz") sz: String
    ): OkxResponse<List<OkxBooksDto>>
}

private interface CoinGeckoApi {
    @GET("api/v3/coins/markets")
    suspend fun markets(
        @Query("vs_currency") vsCurrency: String,
        @Query("ids") ids: String,
        @Query("sparkline") sparkline: Boolean,
        @Query("price_change_percentage") priceChangePercentage: String
    ): List<CoinGeckoMarketDto>

    @GET("api/v3/coins/{id}")
    suspend fun coin(
        @Path("id") id: String,
        @Query("localization") localization: Boolean,
        @Query("tickers") tickers: Boolean,
        @Query("market_data") marketData: Boolean,
        @Query("community_data") communityData: Boolean,
        @Query("developer_data") developerData: Boolean,
        @Query("sparkline") sparkline: Boolean
    ): CoinGeckoCoinDto
}

private data class BinanceTickerDto(
    val symbol: String,
    val lastPrice: String,
    val priceChangePercent: String,
    val quoteVolume: String
)

private data class BinanceDepthDto(
    val bids: List<List<String>>,
    val asks: List<List<String>>
)

private data class OkxResponse<T>(
    val code: String,
    val msg: String,
    val data: T
)

private data class OkxTickerDto(
    val instId: String,
    val last: String,
    val sodUtc0: String,
    val volCcy24h: String
)

private data class OkxBooksDto(
    val bids: List<List<String>>,
    val asks: List<List<String>>
)

private data class CoinGeckoMarketDto(
    val id: String,
    val name: String,
    val current_price: Double?,
    val market_cap: Double?,
    val total_volume: Double?,
    val price_change_percentage_24h: Double?,
    val sparkline_in_7d: CoinGeckoSparklineDto?
)

private data class CoinGeckoSparklineDto(
    val price: List<Double> = emptyList()
)

private data class CoinGeckoCoinDto(
    val categories: List<String> = emptyList()
)

private class CoinGeckoMetadataRepository {
    private val api = Retrofit.Builder()
        .baseUrl("https://api.coingecko.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(CoinGeckoApi::class.java)

    private val categoryCache = mutableMapOf<String, String>()

    suspend fun enrich(baseCoins: List<CoinMarket>): List<CoinMarket> {
        if (baseCoins.isEmpty()) return baseCoins

        val markets = runCatching {
            api.markets(
                vsCurrency = "usd",
                ids = AssetCatalog.coinGeckoIds(),
                sparkline = true,
                priceChangePercentage = "24h"
            )
        }.getOrElse { return baseCoins }

        val marketsById = markets.associateBy { it.id }

        return baseCoins.map { coin ->
            val coinGeckoId = AssetCatalog.coinGeckoIdFor(coin.symbol) ?: return@map coin
            val market = marketsById[coinGeckoId] ?: return@map coin
            coin.copy(
                name = market.name.ifBlank { coin.name },
                sector = categoryFor(coinGeckoId).ifBlank { coin.sector },
                price = market.current_price ?: coin.price,
                change24h = market.price_change_percentage_24h ?: coin.change24h,
                volume24h = market.total_volume ?: coin.volume24h,
                sparkline = market.sparkline_in_7d?.price
                    ?.takeLast(16)
                    ?.takeIf { it.isNotEmpty() }
                    ?: coin.sparkline,
                marketCap = market.market_cap ?: coin.marketCap
            )
        }
    }

    private suspend fun categoryFor(coinGeckoId: String): String {
        categoryCache[coinGeckoId]?.let { return it }
        val category = runCatching {
            api.coin(
                id = coinGeckoId,
                localization = false,
                tickers = false,
                marketData = false,
                communityData = false,
                developerData = false,
                sparkline = false
            ).categories.firstOrNull()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Uncategorized"
        categoryCache[coinGeckoId] = category
        return category
    }
}

private fun List<Any>.toDomain(): Kline {
    val open = getOrNull(1).toString().toDoubleOrNull() ?: 0.0
    val high = getOrNull(2).toString().toDoubleOrNull() ?: open
    val low = getOrNull(3).toString().toDoubleOrNull() ?: open
    val close = getOrNull(4).toString().toDoubleOrNull() ?: open
    val volume = getOrNull(5).toString().toDoubleOrNull() ?: 0.0
    return Kline(open = open, high = high, low = low, close = close, volume = volume)
}

private fun BinanceDepthDto.toDomain(): OrderBook = OrderBook(
    bids = bids.mapNotNull { level ->
        val price = level.getOrNull(0)?.toDoubleOrNull()
        val quantity = level.getOrNull(1)?.toDoubleOrNull()
        if (price == null || quantity == null) null else OrderLevel(price, quantity)
    },
    asks = asks.mapNotNull { level ->
        val price = level.getOrNull(0)?.toDoubleOrNull()
        val quantity = level.getOrNull(1)?.toDoubleOrNull()
        if (price == null || quantity == null) null else OrderLevel(price, quantity)
    }
)

private fun OkxBooksDto.toDomain(): OrderBook = OrderBook(
    bids = bids.mapNotNull { level ->
        val price = level.getOrNull(0)?.toDoubleOrNull()
        val quantity = level.getOrNull(1)?.toDoubleOrNull()
        if (price == null || quantity == null) null else OrderLevel(price, quantity)
    },
    asks = asks.mapNotNull { level ->
        val price = level.getOrNull(0)?.toDoubleOrNull()
        val quantity = level.getOrNull(1)?.toDoubleOrNull()
        if (price == null || quantity == null) null else OrderLevel(price, quantity)
    }
)

private fun List<String>.toOkxKline(): Kline {
    val open = getOrNull(1)?.toDoubleOrNull() ?: 0.0
    val high = getOrNull(2)?.toDoubleOrNull() ?: open
    val low = getOrNull(3)?.toDoubleOrNull() ?: open
    val close = getOrNull(4)?.toDoubleOrNull() ?: open
    val volume = getOrNull(7)?.toDoubleOrNull() ?: getOrNull(5)?.toDoubleOrNull() ?: 0.0
    return Kline(open = open, high = high, low = low, close = close, volume = volume)
}

fun String.toOkxInstId(): String =
    if (endsWith("USDT")) removeSuffix("USDT") + "-USDT" else this
