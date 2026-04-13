package com.cryptoinsight.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private val launchRoute = mutableStateOf(NotificationRoute())
    private lateinit var walletActivityResultSender: ActivityResultSender

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        walletActivityResultSender = ActivityResultSender(this)
        launchRoute.value = intent.toNotificationRoute()
        enableEdgeToEdge()
        setContent {
            CryptoInsightTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    CryptoInsightApp(
                        launchRoute = launchRoute.value,
                        onLaunchHandled = { launchRoute.value = NotificationRoute() },
                        walletSender = walletActivityResultSender
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchRoute.value = intent.toNotificationRoute()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CryptoInsightApp(
    launchRoute: NotificationRoute = NotificationRoute(),
    onLaunchHandled: () -> Unit = {},
    walletSender: ActivityResultSender,
    viewModel: CryptoInsightViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AppTab.Overview) }
    var insightPresetSymbol by rememberSaveable { mutableStateOf("All") }
    val context = LocalContext.current
    val walletManager = remember(context) { SolanaWalletManager(context) }
    val scope = rememberCoroutineScope()
    var walletSession by remember { mutableStateOf<WalletSession?>(null) }
    var walletBusy by remember { mutableStateOf(false) }
    var walletError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.updateNotificationPermission(granted)
    }

    LaunchedEffect(Unit) {
        SolanaWalletStore.clear(context)
    }

    LaunchedEffect(launchRoute) {
        if (!launchRoute.symbol.isNullOrBlank()) {
            viewModel.selectCoin(launchRoute.symbol)
            launchRoute.timeframe?.let(viewModel::setTimeframeFromLabel)
            tab = when (launchRoute.tab) {
                "insights" -> {
                    insightPresetSymbol = launchRoute.symbol
                    AppTab.Insights
                }
                "watchlist" -> AppTab.Watchlist
                else -> AppTab.Detail
            }
            onLaunchHandled()
        }
    }

    if (walletSession == null) {
        WalletGateScreen(
            busy = walletBusy,
            error = walletError,
            onConnect = {
                if (walletBusy) {
                    return@WalletGateScreen
                }
                walletBusy = true
                walletError = null
                scope.launch {
                    when (val result = walletManager.signIn(walletSender)) {
                        is WalletAuthResult.Success -> walletSession = result.session
                        is WalletAuthResult.Failure -> walletError = result.message
                    }
                    walletBusy = false
                }
            }
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Crypto Insight", fontWeight = FontWeight.Bold)
                        Text(
                            "Market-only analytics, never trading execution",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tab) {
                AppTab.Overview -> OverviewScreen(
                    state = state,
                    onRefresh = viewModel::refreshMarket,
                    onCoinSelected = {
                        viewModel.selectCoin(it)
                        tab = AppTab.Detail
                    }
                )

                AppTab.Sectors -> SectorScreen(
                    state = state,
                    onSectorSelected = viewModel::setSectorFilter,
                    onCoinSelected = {
                        viewModel.selectCoin(it)
                        tab = AppTab.Detail
                    }
                )

                AppTab.Detail -> DetailScreen(
                    state = state,
                    onTimeframeSelected = viewModel::setTimeframe,
                    onWatchlistToggle = viewModel::toggleWatchlist,
                    onIndicatorToggle = viewModel::toggleIndicator
                )

                AppTab.Insights -> InsightScreen(
                    state = state,
                    presetSymbol = insightPresetSymbol,
                    onPresetConsumed = { insightPresetSymbol = "All" }
                )
                AppTab.Watchlist -> WatchlistScreen(
                    state = state,
                    onQueryChanged = viewModel::setSearchQuery,
                    onCoinSelected = {
                        viewModel.selectCoin(it)
                        tab = AppTab.Detail
                    },
                    onWatchlistToggle = viewModel::toggleWatchlist
                )
                AppTab.Settings -> SettingsScreen(
                    state = state,
                    onNotificationsChanged = viewModel::setNotificationsEnabled,
                    onRefreshSelected = viewModel::setRefreshInterval,
                    onDataSourceSelected = viewModel::setDataSource,
                    onRefreshNow = viewModel::refreshMarket,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.updateNotificationPermission(true)
                        }
                    },
                    onSendTestPush = viewModel::sendTestPush
                )
            }
        }
    }
}

data class NotificationRoute(
    val symbol: String? = null,
    val tab: String = "detail",
    val timeframe: String? = null
)

enum class AppTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Overview("Overview", Icons.Outlined.Home),
    Sectors("Sectors", Icons.AutoMirrored.Outlined.List),
    Detail("异常", Icons.Outlined.Search),
    Insights("Insights", Icons.Outlined.Info),
    Watchlist("Watchlist", Icons.Outlined.Star),
    Settings("Settings", Icons.Outlined.Settings)
}

@Immutable
data class CoinMarket(
    val symbol: String,
    val name: String,
    val sector: String,
    val price: Double,
    val change24h: Double,
    val volume24h: Double,
    val sparkline: List<Double>,
    val klinesByFrame: Map<Timeframe, List<Kline>>,
    val orderBook: OrderBook,
    val marketCap: Double
)

@Immutable
data class Kline(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

@Immutable
data class OrderBook(
    val bids: List<OrderLevel>,
    val asks: List<OrderLevel>
)

@Immutable
data class OrderLevel(
    val price: Double,
    val quantity: Double
)

enum class InsightPriority { High, Medium, Low }
enum class Timeframe(val label: String) { M1("1m"), M5("5m"), M15("15m"), H1("1h"), H4("4h"), D1("1d") }
enum class Indicator(val label: String) { MA("MA"), RSI("RSI"), MACD("MACD"), BOLL("BOLL") }
enum class RefreshInterval(val label: String) { Realtime("Realtime"), S15("15s"), M1("1m"), M5("5m") }
enum class DataSourceOption(val label: String) { Binance("Binance"), Okx("OKX"), Cached("Cached") }

@Immutable
data class Insight(
    val id: String,
    val title: String,
    val reason: String,
    val priority: InsightPriority,
    val symbol: String?,
    val sector: String?,
    val marketTag: String
)

@Immutable
data class SectorDigest(
    val name: String,
    val avgChange: Double,
    val totalVolume: Double,
    val momentum: String,
    val symbols: List<String>
)

@Immutable
data class MarketSnapshot(
    val upCount: Int,
    val downCount: Int,
    val flatCount: Int,
    val totalVolume: Double,
    val sentiment: String
)

data class UserPreferences(
    val notificationsEnabled: Boolean = true,
    val refreshInterval: RefreshInterval = RefreshInterval.Realtime,
    val dataSource: DataSourceOption = DataSourceOption.Binance,
    val pushMode: String = "Local fallback",
    val notificationPermissionGranted: Boolean = false
)

data class UiState(
    val snapshot: MarketSnapshot,
    val coins: List<CoinMarket>,
    val sectors: List<SectorDigest>,
    val insights: List<Insight>,
    val selectedCoin: CoinMarket,
    val selectedSector: String = "All",
    val selectedTimeframe: Timeframe = Timeframe.H1,
    val enabledIndicators: Set<Indicator> = setOf(Indicator.MA, Indicator.RSI, Indicator.MACD),
    val watchlist: Set<String> = setOf("BTCUSDT", "SOLUSDT", "DOGEUSDT"),
    val searchQuery: String = "",
    val preferences: UserPreferences = UserPreferences(),
    val isRefreshing: Boolean = false,
    val liveStatus: String = "Sample data",
    val testPushStatus: String = ""
)

interface MarketRepository {
    fun loadCoins(): List<CoinMarket>
}

class FakeMarketRepository : MarketRepository {
    override fun loadCoins(): List<CoinMarket> = AssetCatalog.placeholderCoins()
}

object PreferenceStore {
    private const val FILE_NAME = "crypto_insight_prefs"
    private const val KEY_WATCHLIST = "watchlist"
    private const val KEY_NOTIFICATIONS = "notifications_enabled"
    private const val KEY_REFRESH = "refresh_interval"
    private const val KEY_DATA_SOURCE = "data_source"
    private const val KEY_PUSH_MODE = "push_mode"
    private const val KEY_NOTIFICATION_PERMISSION = "notification_permission"

    fun load(context: Context): Pair<Set<String>, UserPreferences> {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val watchlist = prefs.getStringSet(KEY_WATCHLIST, setOf("BTCUSDT", "SOLUSDT", "DOGEUSDT")) ?: emptySet()
        val preferences = UserPreferences(
            notificationsEnabled = prefs.getBoolean(KEY_NOTIFICATIONS, true),
            refreshInterval = prefs.getString(KEY_REFRESH, RefreshInterval.Realtime.name)
                ?.let { runCatching { RefreshInterval.valueOf(it) }.getOrNull() }
                ?: RefreshInterval.Realtime,
            dataSource = prefs.getString(KEY_DATA_SOURCE, DataSourceOption.Binance.name)
                ?.let { runCatching { DataSourceOption.valueOf(it) }.getOrNull() }
                ?: DataSourceOption.Binance,
            pushMode = prefs.getString(KEY_PUSH_MODE, "Local fallback") ?: "Local fallback",
            notificationPermissionGranted = prefs.getBoolean(KEY_NOTIFICATION_PERMISSION, false)
        )
        return watchlist to preferences
    }

    fun saveWatchlist(context: Context, watchlist: Set<String>) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_WATCHLIST, watchlist)
            .apply()
    }

    fun savePreferences(context: Context, preferences: UserPreferences) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NOTIFICATIONS, preferences.notificationsEnabled)
            .putString(KEY_REFRESH, preferences.refreshInterval.name)
            .putString(KEY_DATA_SOURCE, preferences.dataSource.name)
            .putString(KEY_PUSH_MODE, preferences.pushMode)
            .putBoolean(KEY_NOTIFICATION_PERMISSION, preferences.notificationPermissionGranted)
            .apply()
    }
}

class CryptoInsightViewModel(application: Application) : AndroidViewModel(application) {
    private val seedRepository: MarketRepository = FakeMarketRepository()
    private val binanceRepository: LiveMarketRepository = BinanceMarketRepository()
    private val okxRepository: LiveMarketRepository = OkxMarketRepository()
    private val binanceStreamService = BinanceStreamService()
    private val okxStreamService = OkxStreamService()
    private val messagingBackend: MessagingBackendRepository = HttpMessagingBackendRepository()
    private val seedCoins = seedRepository.loadCoins()
    private val engine = RuleBasedInsightEngine()
    private val defaultCoin = seedCoins.first()
    private val appContext = getApplication<Application>()
    private val stored = PreferenceStore.load(appContext)
    private var restKeepaliveJob: Job? = null
    private var lastNotifiedInsightId: String? = null

    private val _uiState = MutableStateFlow(
        buildState(
            coins = seedCoins,
            selectedCoin = defaultCoin,
            selectedSector = "All",
            selectedTimeframe = Timeframe.H1,
            enabledIndicators = setOf(Indicator.MA, Indicator.RSI, Indicator.MACD),
            watchlist = stored.first,
            searchQuery = "",
            preferences = stored.second,
            isRefreshing = false,
            liveStatus = "Loading live market data",
            testPushStatus = ""
        )
    )
    val uiState: StateFlow<UiState> = _uiState

    init {
        NotificationSupport.ensureChannel(appContext)
        updateNotificationPermission(NotificationSupport.hasNotificationPermission(appContext))
        if (FirebaseTokenSyncPlaceholder.isPending(appContext)) {
            FirebaseTokenSyncPlaceholder.enqueueSync(appContext)
        }
        initializeMessaging()
        refreshMarket()
    }

    fun selectCoin(symbol: String) {
        _uiState.update { state ->
            val coin = state.coins.firstOrNull { it.symbol == symbol } ?: state.selectedCoin
            state.copy(selectedCoin = coin)
        }
        refreshSelectedCoinDetails(symbol)
    }

    fun setSectorFilter(sector: String) {
        _uiState.update { state ->
            buildState(
                coins = state.coins,
                selectedCoin = state.selectedCoin,
                selectedSector = sector,
                selectedTimeframe = state.selectedTimeframe,
                enabledIndicators = state.enabledIndicators,
                watchlist = state.watchlist,
                searchQuery = state.searchQuery,
                preferences = state.preferences,
                isRefreshing = state.isRefreshing,
                liveStatus = state.liveStatus,
                testPushStatus = state.testPushStatus
            )
        }
    }

    fun setTimeframe(timeframe: Timeframe) {
        _uiState.update { it.copy(selectedTimeframe = timeframe) }
    }

    fun toggleIndicator(indicator: Indicator) {
        _uiState.update { state ->
            val indicators = if (indicator in state.enabledIndicators) {
                state.enabledIndicators - indicator
            } else {
                state.enabledIndicators + indicator
            }
            state.copy(enabledIndicators = indicators)
        }
    }

    fun toggleWatchlist(symbol: String) {
        _uiState.update { state ->
            val watchlist = if (symbol in state.watchlist) state.watchlist - symbol else state.watchlist + symbol
            PreferenceStore.saveWatchlist(appContext, watchlist)
            state.copy(watchlist = watchlist)
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { state -> state.copy(searchQuery = query) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        _uiState.update { state ->
            val preferences = state.preferences.copy(notificationsEnabled = enabled)
            PreferenceStore.savePreferences(appContext, preferences)
            state.copy(preferences = preferences)
        }
    }

    fun updateNotificationPermission(granted: Boolean) {
        _uiState.update { state ->
            val preferences = state.preferences.copy(notificationPermissionGranted = granted)
            PreferenceStore.savePreferences(appContext, preferences)
            state.copy(preferences = preferences)
        }
    }

    fun setRefreshInterval(interval: RefreshInterval) {
        _uiState.update { state ->
            val preferences = state.preferences.copy(refreshInterval = interval)
            PreferenceStore.savePreferences(appContext, preferences)
            state.copy(preferences = preferences)
        }
    }

    fun setDataSource(option: DataSourceOption) {
        _uiState.update { state ->
            val preferences = state.preferences.copy(dataSource = option)
            PreferenceStore.savePreferences(appContext, preferences)
            state.copy(preferences = preferences)
        }
        if (option != DataSourceOption.Binance) {
            binanceStreamService.close()
        }
        if (option != DataSourceOption.Okx) {
            okxStreamService.close()
        }
        stopRestKeepalive()
        refreshMarket()
    }

    fun setTimeframeFromLabel(label: String) {
        Timeframe.entries.firstOrNull { it.label.equals(label, ignoreCase = true) }?.let(::setTimeframe)
    }

    fun sendTestPush() {
        viewModelScope.launch {
            val prefs = appContext.getSharedPreferences("crypto_insight_prefs", Context.MODE_PRIVATE)
            val token = prefs.getString("fcm_token", null)
            val symbol = _uiState.value.selectedCoin.symbol
            val timeframe = _uiState.value.selectedTimeframe.label

            val message = if (token.isNullOrBlank()) {
                "No FCM token available on device"
            } else if (messagingBackend.isConfigured()) {
                val result = messagingBackend.sendTestPush(token, symbol, timeframe)
                if (result.success) {
                    FirebaseTokenSyncPlaceholder.markSynced(appContext)
                    "Backend test push requested successfully"
                } else {
                    "Backend request failed, local test notification sent"
                }
            } else {
                "Backend messaging not configured, local test notification sent"
            }

            NotificationSupport.notify(
                context = appContext,
                title = "$symbol test signal",
                body = "Notification route test for $symbol on $timeframe",
                symbol = symbol,
                tab = "insights",
                timeframe = timeframe
            )

            _uiState.update { state ->
                state.copy(testPushStatus = message)
            }
        }
    }

    fun refreshMarket() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, liveStatus = "Refreshing...") }
            val current = _uiState.value
            val repository = liveRepositoryFor(current.preferences.dataSource)
            val baseCoins = if (current.coins.isEmpty()) seedCoins else current.coins
            val updatedCoins = when (current.preferences.dataSource) {
                DataSourceOption.Binance, DataSourceOption.Okx -> repository?.let {
                    runCatching { it.refreshMarket(baseCoins) }.getOrElse { baseCoins }
                } ?: baseCoins
                DataSourceOption.Cached -> seedCoins
            }
            val selected = updatedCoins.firstOrNull { it.symbol == current.selectedCoin.symbol } ?: updatedCoins.first()
            val status = when (current.preferences.dataSource) {
                DataSourceOption.Binance -> if (updatedCoins.any { it.price > 0.0 }) "Live Binance + CoinGecko" else "Binance data unavailable"
                DataSourceOption.Okx -> if (updatedCoins.any { it.price > 0.0 }) "Live OKX + CoinGecko" else "OKX data unavailable"
                DataSourceOption.Cached -> "Sample cached data"
            }
            _uiState.value = buildState(
                coins = updatedCoins,
                selectedCoin = selected,
                selectedSector = current.selectedSector,
                selectedTimeframe = current.selectedTimeframe,
                enabledIndicators = current.enabledIndicators,
                watchlist = current.watchlist,
                searchQuery = current.searchQuery,
                preferences = current.preferences,
                isRefreshing = false,
                liveStatus = status,
                testPushStatus = current.testPushStatus
            )
            if (current.preferences.dataSource == DataSourceOption.Binance) {
                refreshSelectedCoinDetails(selected.symbol)
                connectBinanceStreams(updatedCoins, selected.symbol)
            } else if (current.preferences.dataSource == DataSourceOption.Okx) {
                refreshSelectedCoinDetails(selected.symbol)
                connectOkxStreams(updatedCoins, selected.symbol)
            } else {
                stopRestKeepalive()
            }
        }
    }

    private fun refreshSelectedCoinDetails(symbol: String) {
        val current = _uiState.value
        val repository = liveRepositoryFor(current.preferences.dataSource)
        if (repository == null) return

        viewModelScope.launch {
            val stateBefore = _uiState.value
            val selected = stateBefore.coins.firstOrNull { it.symbol == symbol } ?: return@launch
            val detailed = runCatching { repository.refreshCoinDetails(selected) }.getOrElse { selected }
            val updatedCoins = stateBefore.coins.map { if (it.symbol == symbol) detailed else it }
            _uiState.value = buildState(
                coins = updatedCoins,
                selectedCoin = detailed,
                selectedSector = stateBefore.selectedSector,
                selectedTimeframe = stateBefore.selectedTimeframe,
                enabledIndicators = stateBefore.enabledIndicators,
                watchlist = stateBefore.watchlist,
                searchQuery = stateBefore.searchQuery,
                preferences = stateBefore.preferences,
                isRefreshing = false,
                liveStatus = stateBefore.liveStatus,
                testPushStatus = stateBefore.testPushStatus
            )
        }
    }

    private fun connectBinanceStreams(coins: List<CoinMarket>, selectedSymbol: String) {
        okxStreamService.close()
        binanceStreamService.connect(
            symbols = coins.map { it.symbol },
            selectedSymbol = selectedSymbol,
            onStatus = { status ->
                _uiState.update { it.copy(liveStatus = status) }
                if (status.contains("retry", ignoreCase = true) || status.contains("failed", ignoreCase = true) || status.contains("closed", ignoreCase = true)) {
                    startRestKeepalive()
                } else if (status.contains("live", ignoreCase = true) || status.contains("rotating", ignoreCase = true)) {
                    stopRestKeepalive()
                }
            },
            onUpdate = { update ->
                _uiState.update { state ->
                    val updatedCoins = state.coins.map { coin ->
                        val ticker = update.tickers[coin.symbol]
                        if (ticker != null) {
                            coin.copy(
                                price = ticker.price,
                                change24h = ticker.changePercent,
                                volume24h = ticker.quoteVolume,
                                sparkline = (coin.sparkline + ticker.price).takeLast(24)
                            )
                        } else if (coin.symbol == state.selectedCoin.symbol) {
                            val nextKline = update.selectedKline
                            val nextDepth = update.selectedDepth
                            coin.copy(
                                klinesByFrame = if (nextKline != null) coin.klinesByFrame.updateLiveKline(nextKline) else coin.klinesByFrame,
                                orderBook = nextDepth ?: coin.orderBook,
                                sparkline = if (nextKline != null) (coin.sparkline + nextKline.close).takeLast(24) else coin.sparkline
                            )
                        } else {
                            coin
                        }
                    }
                    val selected = updatedCoins.firstOrNull { it.symbol == state.selectedCoin.symbol } ?: state.selectedCoin
                    val snapshot = MarketAnalytics.snapshot(updatedCoins)
                    val sectors = MarketAnalytics.sectors(updatedCoins)
                    val insights = engine.buildInsights(updatedCoins, sectors)
                    maybeNotifyTopInsight(state.preferences, insights)
                    state.copy(
                        snapshot = snapshot,
                        coins = updatedCoins,
                        sectors = sectors,
                        insights = insights,
                        selectedCoin = selected
                    )
                }
            }
        )
    }

    private fun connectOkxStreams(coins: List<CoinMarket>, selectedSymbol: String) {
        binanceStreamService.close()
        okxStreamService.connect(
            symbols = coins.map { it.symbol },
            selectedSymbol = selectedSymbol,
            onStatus = { status ->
                _uiState.update { it.copy(liveStatus = status) }
                if (status.contains("retry", ignoreCase = true) || status.contains("failed", ignoreCase = true) || status.contains("closed", ignoreCase = true)) {
                    startRestKeepalive()
                } else if (status.contains("live", ignoreCase = true) || status.contains("rotating", ignoreCase = true)) {
                    stopRestKeepalive()
                }
            },
            onUpdate = { update ->
                _uiState.update { state ->
                    val updatedCoins = state.coins.map { coin ->
                        val ticker = update.tickers[coin.symbol]
                        if (ticker != null) {
                            coin.copy(
                                price = ticker.price,
                                change24h = ticker.changePercent,
                                volume24h = ticker.quoteVolume,
                                sparkline = (coin.sparkline + ticker.price).takeLast(24)
                            )
                        } else if (coin.symbol == state.selectedCoin.symbol) {
                            val nextKline = update.selectedKline
                            val nextDepth = update.selectedDepth
                            coin.copy(
                                klinesByFrame = if (nextKline != null) coin.klinesByFrame.updateLiveKline(nextKline) else coin.klinesByFrame,
                                orderBook = nextDepth ?: coin.orderBook,
                                sparkline = if (nextKline != null) (coin.sparkline + nextKline.close).takeLast(24) else coin.sparkline
                            )
                        } else {
                            coin
                        }
                    }
                    val selected = updatedCoins.firstOrNull { it.symbol == state.selectedCoin.symbol } ?: state.selectedCoin
                    val snapshot = MarketAnalytics.snapshot(updatedCoins)
                    val sectors = MarketAnalytics.sectors(updatedCoins)
                    val insights = engine.buildInsights(updatedCoins, sectors)
                    state.copy(
                        snapshot = snapshot,
                        coins = updatedCoins,
                        sectors = sectors,
                        insights = insights,
                        selectedCoin = selected
                    )
                }
            }
        )
    }

    private fun startRestKeepalive() {
        if (restKeepaliveJob?.isActive == true) return
        restKeepaliveJob = viewModelScope.launch {
            while (true) {
                delay(currentKeepaliveMs())
                val state = _uiState.value
                val repository = liveRepositoryFor(state.preferences.dataSource) ?: break
                val sourceLabel = when (state.preferences.dataSource) {
                    DataSourceOption.Binance -> "Binance"
                    DataSourceOption.Okx -> "OKX"
                    DataSourceOption.Cached -> "Cached"
                }
                val refreshedCoins = runCatching { repository.refreshMarket(state.coins) }.getOrElse { state.coins }
                val selected = refreshedCoins.firstOrNull { it.symbol == state.selectedCoin.symbol } ?: state.selectedCoin
                val insights = engine.buildInsights(refreshedCoins, MarketAnalytics.sectors(refreshedCoins))
                maybeNotifyTopInsight(state.preferences, insights)
                _uiState.value = buildState(
                    coins = refreshedCoins,
                    selectedCoin = selected,
                    selectedSector = state.selectedSector,
                    selectedTimeframe = state.selectedTimeframe,
                    enabledIndicators = state.enabledIndicators,
                    watchlist = state.watchlist,
                    searchQuery = state.searchQuery,
                    preferences = state.preferences,
                    isRefreshing = false,
                    liveStatus = "$sourceLabel REST keepalive while reconnecting",
                    testPushStatus = state.testPushStatus
                )
            }
        }
    }

    private fun stopRestKeepalive() {
        restKeepaliveJob?.cancel()
        restKeepaliveJob = null
    }

    private fun currentKeepaliveMs(): Long = when (_uiState.value.preferences.refreshInterval) {
        RefreshInterval.Realtime -> 15_000L
        RefreshInterval.S15 -> 15_000L
        RefreshInterval.M1 -> 60_000L
        RefreshInterval.M5 -> 300_000L
    }

    private fun initializeMessaging() {
        if (!FirebaseRegistration.isConfigured(appContext)) {
            _uiState.update { state ->
                val preferences = state.preferences.copy(pushMode = "Firebase not configured")
                PreferenceStore.savePreferences(appContext, preferences)
                state.copy(preferences = preferences)
            }
            return
        }

        FirebaseRegistration.fetchToken(
            context = appContext,
            onSuccess = { token ->
                appContext.getSharedPreferences("crypto_insight_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("fcm_token", token)
                    .putBoolean("fcm_token_pending_sync", true)
                    .apply()
                viewModelScope.launch {
                    val syncResult = if (messagingBackend.isConfigured()) {
                        messagingBackend.syncDeviceToken(token, "fcm")
                    } else {
                        BackendResult(false, "Backend messaging not configured")
                    }
                    if (syncResult.success) {
                        FirebaseTokenSyncPlaceholder.markSynced(appContext)
                    }
                    val pushMode = when {
                        syncResult.success -> "FCM ready, backend synced"
                        FirebaseTokenSyncPlaceholder.isPending(appContext) -> "FCM ready, token pending sync"
                        else -> "FCM ready"
                    }
                    _uiState.update { state ->
                        val preferences = state.preferences.copy(pushMode = pushMode)
                        PreferenceStore.savePreferences(appContext, preferences)
                        state.copy(preferences = preferences)
                    }
                    if (FirebaseTokenSyncPlaceholder.isPending(appContext)) {
                        FirebaseTokenSyncPlaceholder.enqueueSync(appContext)
                    }
                }
            },
            onFailure = {
                _uiState.update { state ->
                    val preferences = state.preferences.copy(pushMode = "FCM token unavailable")
                    PreferenceStore.savePreferences(appContext, preferences)
                    state.copy(preferences = preferences)
                }
            }
        )
    }

    private fun maybeNotifyTopInsight(preferences: UserPreferences, insights: List<Insight>) {
        if (!preferences.notificationsEnabled) return
        val top = insights.firstOrNull { it.priority == InsightPriority.High } ?: return
        if (lastNotifiedInsightId == top.id) return
        lastNotifiedInsightId = top.id
        NotificationSupport.notify(appContext, top.title, top.reason)
    }

    private fun liveRepositoryFor(dataSource: DataSourceOption): LiveMarketRepository? = when (dataSource) {
        DataSourceOption.Binance -> binanceRepository
        DataSourceOption.Okx -> okxRepository
        DataSourceOption.Cached -> null
    }

    override fun onCleared() {
        binanceStreamService.close()
        okxStreamService.close()
        stopRestKeepalive()
        super.onCleared()
    }

    private fun buildState(
        coins: List<CoinMarket>,
        selectedCoin: CoinMarket,
        selectedSector: String,
        selectedTimeframe: Timeframe,
        enabledIndicators: Set<Indicator>,
        watchlist: Set<String>,
        searchQuery: String,
        preferences: UserPreferences,
        isRefreshing: Boolean,
        liveStatus: String,
        testPushStatus: String
    ): UiState {
        val snapshot = MarketAnalytics.snapshot(coins)
        val sectors = MarketAnalytics.sectors(coins)
        val insights = engine.buildInsights(coins, sectors)
        return UiState(
            snapshot = snapshot,
            coins = coins,
            sectors = sectors,
            insights = insights,
            selectedCoin = selectedCoin,
            selectedSector = selectedSector,
            selectedTimeframe = selectedTimeframe,
            enabledIndicators = enabledIndicators,
            watchlist = watchlist,
            searchQuery = searchQuery,
            preferences = preferences,
            isRefreshing = isRefreshing,
            liveStatus = liveStatus,
            testPushStatus = testPushStatus
        )
    }
}

object MarketAnalytics {
    fun snapshot(coins: List<CoinMarket>): MarketSnapshot {
        val up = coins.count { it.change24h > 1.0 }
        val down = coins.count { it.change24h < -1.0 }
        val flat = coins.size - up - down
        val totalVolume = coins.sumOf { it.volume24h }
        val avg = coins.map { it.change24h }.average()
        val sentiment = when {
            avg > 4 -> "Greed"
            avg < -4 -> "Fear"
            else -> "Neutral"
        }
        return MarketSnapshot(up, down, flat, totalVolume, sentiment)
    }

    fun sectors(coins: List<CoinMarket>): List<SectorDigest> =
        coins.groupBy { it.sector }
            .map { (sector, members) ->
                val avgChange = members.map { it.change24h }.average()
                val totalVolume = members.sumOf { it.volume24h }
                SectorDigest(
                    name = sector,
                    avgChange = avgChange,
                    totalVolume = totalVolume,
                    momentum = when {
                        avgChange > 6 -> "Breakout"
                        avgChange > 2 -> "Active"
                        avgChange < -4 -> "Cooling"
                        else -> "Balanced"
                    },
                    symbols = members.map { it.symbol }
                )
            }
            .sortedByDescending { it.avgChange }
}

object IndicatorCalculator {
    fun movingAverage(values: List<Double>, period: Int): List<Double> =
        values.mapIndexed { index, _ ->
            val start = max(0, index - period + 1)
            values.subList(start, index + 1).average()
        }

    fun rsi(values: List<Double>, period: Int = 14): Double {
        if (values.size <= period) return 50.0
        var gains = 0.0
        var losses = 0.0
        for (i in values.size - period until values.lastIndex) {
            val delta = values[i + 1] - values[i]
            if (delta >= 0) gains += delta else losses -= delta
        }
        if (losses == 0.0) return 100.0
        val rs = gains / period / (losses / period)
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun macd(values: List<Double>): Pair<Double, Double> {
        val ema12 = ema(values, 12)
        val ema26 = ema(values, 26)
        val macdLine = ema12.last() - ema26.last()
        val signal = ema((ema12 zip ema26).map { it.first - it.second }, 9).last()
        return macdLine to signal
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val multiplier = 2.0 / (period + 1)
        val result = mutableListOf(values.first())
        values.drop(1).forEach { value ->
            result += (value - result.last()) * multiplier + result.last()
        }
        return result
    }
}

class RuleBasedInsightEngine {
    fun buildInsights(coins: List<CoinMarket>, sectors: List<SectorDigest>): List<Insight> {
        val insights = mutableListOf<Insight>()

        coins.forEach { coin ->
            val closes = coin.klinesByFrame.getValue(Timeframe.H1).map { it.close }
            val volumes = coin.klinesByFrame.getValue(Timeframe.H1).map { it.volume }
            val rsi = IndicatorCalculator.rsi(closes)
            val (macd, signal) = IndicatorCalculator.macd(closes)
            val avgVolume = volumes.dropLast(1).average().takeIf { !it.isNaN() } ?: volumes.last()
            val recentHigh = closes.dropLast(1).takeLast(10).maxOrNull() ?: closes.last()

            if (rsi < 30) {
                insights += Insight(
                    id = "${coin.symbol}-rsi-low",
                    title = "${coin.symbol} momentum is washed out",
                    reason = "RSI is ${rsi.format(1)}, showing heavy selling pressure that may be easing.",
                    priority = InsightPriority.High,
                    symbol = coin.symbol,
                    sector = coin.sector,
                    marketTag = "Momentum"
                )
            }

            if (rsi > 70) {
                insights += Insight(
                    id = "${coin.symbol}-rsi-high",
                    title = "${coin.symbol} is running hot",
                    reason = "RSI is ${rsi.format(1)} after a sharp move. Expect volatility rather than certainty.",
                    priority = InsightPriority.Medium,
                    symbol = coin.symbol,
                    sector = coin.sector,
                    marketTag = "Momentum"
                )
            }

            if (macd > signal) {
                insights += Insight(
                    id = "${coin.symbol}-macd",
                    title = "${coin.symbol} keeps positive trend pressure",
                    reason = "MACD ${macd.format(2)} is above signal ${signal.format(2)}, suggesting momentum remains constructive.",
                    priority = InsightPriority.Medium,
                    symbol = coin.symbol,
                    sector = coin.sector,
                    marketTag = "Trend"
                )
            }

            if (closes.last() > recentHigh) {
                insights += Insight(
                    id = "${coin.symbol}-breakout",
                    title = "${coin.symbol} cleared recent resistance",
                    reason = "Price moved above the prior 10-candle high, which often marks a change in participation.",
                    priority = InsightPriority.High,
                    symbol = coin.symbol,
                    sector = coin.sector,
                    marketTag = "Breakout"
                )
            }

            if (volumes.last() > avgVolume * 2) {
                insights += Insight(
                    id = "${coin.symbol}-volume",
                    title = "${coin.symbol} volume spike detected",
                    reason = "Latest volume is ${(volumes.last() / avgVolume).format(1)}x the recent average, so attention is increasing quickly.",
                    priority = InsightPriority.High,
                    symbol = coin.symbol,
                    sector = coin.sector,
                    marketTag = "Volume"
                )
            }
        }

        sectors.take(3).forEach { sector ->
            insights += Insight(
                id = "${sector.name}-sector",
                title = "${sector.name} leads sector heat",
                reason = "Average change ${sector.avgChange.format(2)}% with ${sector.totalVolume.compact()} volume. Momentum is ${sector.momentum.lowercase()}.",
                priority = if (sector.avgChange > 5) InsightPriority.High else InsightPriority.Medium,
                symbol = null,
                sector = sector.name,
                marketTag = "Sector"
            )
        }

        return insights.sortedWith(compareByDescending<Insight> { priorityRank(it.priority) }.thenBy { it.title })
    }

    private fun priorityRank(priority: InsightPriority): Int = when (priority) {
        InsightPriority.High -> 3
        InsightPriority.Medium -> 2
        InsightPriority.Low -> 1
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OverviewScreen(
    state: UiState,
    onRefresh: () -> Unit,
    onCoinSelected: (String) -> Unit
) {
    val gainers = state.coins.sortedByDescending { it.change24h }.take(10)
    val losers = state.coins.sortedBy { it.change24h }.take(10)

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                DisclaimerCard()
            }
            item {
                FirebaseStatusCard(state.preferences.pushMode)
            }
            item {
                LiveStatusCard(
                    status = state.liveStatus,
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh
                )
            }
            item {
                MarketMoodCard(state.snapshot)
            }
            item {
                DistributionCard(state.snapshot)
            }
            item {
                SectionTitle("Hot Sectors")
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.sectors) { sector ->
                        SectorCard(sector)
                    }
                }
            }
            item {
                SectionTitle("Top Movers")
            }
            item {
                TopMoversCard(gainers, losers, onCoinSelected)
            }
        }
    }
}

@Composable
private fun SectorScreen(
    state: UiState,
    onSectorSelected: (String) -> Unit,
    onCoinSelected: (String) -> Unit
) {
    val sectorNames = listOf("All") + state.sectors.map { it.name }
    val filteredCoins = if (state.selectedSector == "All") state.coins else state.coins.filter { it.sector == state.selectedSector }
    val summary = if (state.selectedSector == "All") {
        SectorDigest(
            name = "All",
            avgChange = state.coins.map { it.change24h }.average(),
            totalVolume = state.coins.sumOf { it.volume24h },
            momentum = state.snapshot.sentiment,
            symbols = state.coins.map { it.symbol }
        )
    } else {
        state.sectors.firstOrNull { it.name == state.selectedSector }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sectorNames.forEach { sector ->
                        FilterChip(
                            selected = state.selectedSector == sector,
                            onClick = { onSectorSelected(sector) },
                            label = { Text(sector) }
                        )
                    }
                }
            }
            item {
                summary?.let {
                    SectorSummaryCard(it, state.selectedSector == "All")
                }
            }
            items(filteredCoins) { coin ->
                CoinRow(coin = coin, inWatchlist = coin.symbol in state.watchlist, onClick = { onCoinSelected(coin.symbol) })
            }
        }
    }
}

@Composable
private fun DetailScreen(
    state: UiState,
    onTimeframeSelected: (Timeframe) -> Unit,
    onWatchlistToggle: (String) -> Unit,
    onIndicatorToggle: (Indicator) -> Unit
) {
    val coin = state.selectedCoin
    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                CoinHeader(coin, coin.symbol in state.watchlist, onWatchlistToggle)
            }
            item {
                InfoCard(
                    title = "异常",
                    accent = AmberAccent,
                    body = "这里将追踪合约相对于现货的交易量和价格异常变动。这类偏离通常预示主力建仓、试盘、洗盘或拉抬动作。该功能会在不久后的更新中上线，并以付费方式访问。"
                )
            }
        }
    }
}

@Composable
private fun WalletGateScreen(
    busy: Boolean,
    error: String?,
    onConnect: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("Connect Wallet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Use Solana Mobile Wallet Adapter to connect and sign in before entering Crypto Insight.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!error.isNullOrBlank()) {
                    Text(error, color = NegativeRed)
                }
                Button(
                    onClick = onConnect,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(if (busy) "Connecting..." else "Connect Wallet")
                }
            }
        }
    }
}

@Composable
private fun InsightScreen(
    state: UiState,
    presetSymbol: String = "All",
    onPresetConsumed: () -> Unit = {}
) {
    var selectedMarket by rememberSaveable { mutableStateOf("All") }
    var selectedSector by rememberSaveable { mutableStateOf("All") }
    var selectedSymbol by rememberSaveable { mutableStateOf("All") }

    LaunchedEffect(presetSymbol) {
        if (presetSymbol != "All") {
            selectedSymbol = presetSymbol
            onPresetConsumed()
        }
    }

    val filtered = state.insights.filter {
        (selectedMarket == "All" || it.marketTag == selectedMarket) &&
            (selectedSector == "All" || it.sector == selectedSector) &&
            (selectedSymbol == "All" || it.symbol == selectedSymbol)
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                FilterStrip(
                    title = "Market",
                    values = listOf("All") + state.insights.map { it.marketTag }.distinct(),
                    selected = selectedMarket,
                    onSelected = { selectedMarket = it }
                )
            }
            item {
                FilterStrip(
                    title = "Sector",
                    values = listOf("All") + state.sectors.map { it.name },
                    selected = selectedSector,
                    onSelected = { selectedSector = it }
                )
            }
            item {
                FilterStrip(
                    title = "Symbol",
                    values = listOf("All") + state.coins.map { it.symbol },
                    selected = selectedSymbol,
                    onSelected = { selectedSymbol = it }
                )
            }
            items(filtered) { insight ->
                InsightCard(insight)
            }
        }
    }
}

@Composable
private fun WatchlistScreen(
    state: UiState,
    onQueryChanged: (String) -> Unit,
    onCoinSelected: (String) -> Unit,
    onWatchlistToggle: (String) -> Unit
) {
    val filtered = state.coins.filter {
        state.searchQuery.isBlank() ||
            it.symbol.contains(state.searchQuery, ignoreCase = true) ||
            it.name.contains(state.searchQuery, ignoreCase = true)
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.searchQuery,
                    onValueChange = onQueryChanged,
                    label = { Text("Search pairs") },
                    singleLine = true
                )
            }
            item {
                SectionTitle("Watchlist")
            }
            items(filtered) { coin ->
                CoinRow(
                    coin = coin,
                    inWatchlist = coin.symbol in state.watchlist,
                    onClick = { onCoinSelected(coin.symbol) },
                    onTrailingClick = { onWatchlistToggle(coin.symbol) }
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: UiState,
    onNotificationsChanged: (Boolean) -> Unit,
    onRefreshSelected: (RefreshInterval) -> Unit,
    onDataSourceSelected: (DataSourceOption) -> Unit,
    onRefreshNow: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSendTestPush: () -> Unit
) {
    var showFeatureGuide by rememberSaveable { mutableStateOf(false) }

    if (showFeatureGuide) {
        AlertDialog(
            onDismissRequest = { showFeatureGuide = false },
            confirmButton = {
                TextButton(onClick = { showFeatureGuide = false }) {
                    Text("关闭")
                }
            },
            title = { Text("功能说明") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Overview：市场总览，查看整体涨跌分布、热点板块和强弱币。")
                    Text("Sectors：板块追踪，按赛道查看板块热度、成交量和动量变化。")
                    Text("异常：监控合约相对现货的量价异常，识别潜在主力动向。")
                    Text("Insights：规则洞见流，按市场、板块、币种筛选信号。")
                    Text("Watchlist：搜索币种并管理你的自选列表。")
                    Text("Settings：管理通知、权限、刷新频率、数据源和测试推送。")
                }
            }
        )
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                InfoCard(
                    title = "Data Pipeline",
                    accent = BrandBlue,
                    body = "${state.liveStatus}. Live mode now prefers real exchange and market metadata feeds. Sample data is only used when you explicitly switch to Cached."
                )
            }
            item {
                TextButton(onClick = onRefreshNow) {
                    Text(if (state.isRefreshing) "Refreshing..." else "Refresh market now")
                }
            }
            item {
                TextButton(onClick = { showFeatureGuide = true }) {
                    Text("功能说明")
                }
            }
            item {
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Delivery mode: ${state.preferences.pushMode}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "Permission: ${if (state.preferences.notificationPermissionGranted) "Granted" else "Not granted"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (state.testPushStatus.isNotBlank()) {
                            Text(
                                "Last test result: ${state.testPushStatus}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Signal notifications")
                                Text("Stored locally and reserved for future FCM delivery", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = state.preferences.notificationsEnabled,
                                onCheckedChange = onNotificationsChanged
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onRequestNotificationPermission) {
                                Text("Request permission")
                            }
                            TextButton(onClick = onSendTestPush) {
                                Text("Send test push")
                            }
                        }
                    }
                }
            }
            item {
                FilterCard(
                    title = "Refresh interval",
                    values = RefreshInterval.entries.map { it.label },
                    selected = state.preferences.refreshInterval.label,
                    onSelected = { selected ->
                        RefreshInterval.entries.firstOrNull { it.label == selected }?.let(onRefreshSelected)
                    }
                )
            }
            item {
                FilterCard(
                    title = "Preferred data source",
                    values = DataSourceOption.entries.map { it.label },
                    selected = state.preferences.dataSource.label,
                    onSelected = { selected ->
                        DataSourceOption.entries.firstOrNull { it.label == selected }?.let(onDataSourceSelected)
                    }
                )
            }
            item {
                InfoCard(
                    title = "Sector mapping",
                    accent = AmberAccent,
                    body = "Current local mapping covers AI, DeFi, Meme, Layer1, Layer2, Oracle, RWA, and Store of Value. Backend syncing can replace it later."
                )
            }
        }
    }
}

@Composable
private fun DisclaimerCard() {
    InfoCard(
        title = "Disclaimer",
        accent = AmberAccent,
        body = "This app summarizes public crypto market data only. It does not place orders and should not be interpreted as investment advice."
    )
}

@Composable
private fun FirebaseStatusCard(pushMode: String) {
    InfoCard(
        title = "Push Status",
        accent = BrandBlue,
        body = pushMode
    )
}

@Composable
private fun LiveStatusCard(
    status: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Feed status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRefresh, enabled = !isRefreshing) {
                Text(if (isRefreshing) "Refreshing" else "Refresh")
            }
        }
    }
}

@Composable
private fun MarketMoodCard(snapshot: MarketSnapshot) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BrandBlue),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Market Sentiment", color = Color.White.copy(alpha = 0.72f))
            Text(snapshot.sentiment, style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                "${snapshot.upCount} up / ${snapshot.downCount} down / ${snapshot.flatCount} flat",
                color = Color.White
            )
            Text("24h volume ${snapshot.totalVolume.compact()}", color = Color.White)
        }
    }
}

@Composable
private fun DistributionCard(snapshot: MarketSnapshot) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Market Breadth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DonutChart(
                    values = listOf(snapshot.upCount, snapshot.downCount, snapshot.flatCount),
                    colors = listOf(PositiveGreen, NegativeRed, AmberAccent),
                    modifier = Modifier.size(140.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LegendItem("Up", PositiveGreen, snapshot.upCount.toString())
                    LegendItem("Down", NegativeRed, snapshot.downCount.toString())
                    LegendItem("Flat", AmberAccent, snapshot.flatCount.toString())
                }
            }
        }
    }
}

@Composable
private fun TopMoversCard(
    gainers: List<CoinMarket>,
    losers: List<CoinMarket>,
    onCoinSelected: (String) -> Unit
) {
    var index by rememberSaveable { mutableStateOf(0) }
    val list = if (index == 0) gainers else losers

    Card(shape = RoundedCornerShape(24.dp)) {
        Column {
            TabRow(selectedTabIndex = index) {
                Tab(selected = index == 0, onClick = { index = 0 }, text = { Text("Gainers") })
                Tab(selected = index == 1, onClick = { index = 1 }, text = { Text("Losers") })
            }
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { coin ->
                    CoinRow(coin = coin, inWatchlist = false, onClick = { onCoinSelected(coin.symbol) })
                }
            }
        }
    }
}

@Composable
private fun SectorCard(sector: SectorDigest) {
    Card(
        modifier = Modifier.width(220.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(sector.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${sector.avgChange.format(2)}%", color = if (sector.avgChange >= 0) PositiveGreen else NegativeRed)
            Text("Volume ${sector.totalVolume.compact()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            AssistChip(onClick = {}, label = { Text(sector.momentum) })
        }
    }
}

@Composable
private fun SectorSummaryCard(sector: SectorDigest, aggregate: Boolean) {
    val title = if (aggregate) "All Sectors Snapshot" else "${sector.name} Snapshot"
    InfoCard(
        title = title,
        accent = BrandBlue,
        body = "Average change ${sector.avgChange.format(2)}%, volume ${sector.totalVolume.compact()}, momentum ${sector.momentum.lowercase()}."
    )
}

@Composable
private fun CoinHeader(
    coin: CoinMarket,
    inWatchlist: Boolean,
    onWatchlistToggle: (String) -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = DarkCard)) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("${coin.name} · ${coin.symbol}", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(coin.sector, color = Color.White.copy(alpha = 0.7f))
                }
                Switch(checked = inWatchlist, onCheckedChange = { onWatchlistToggle(coin.symbol) })
            }
            Text("$${coin.price.format(2)}", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("${coin.change24h.format(2)}% in 24h", color = if (coin.change24h >= 0) PositiveGreen else NegativeRed)
        }
    }
}

@Composable
private fun PriceChartCard(
    closes: List<Double>,
    movingAverage: List<Double>,
    showMovingAverage: Boolean
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Price Action", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LineChart(
                primary = closes,
                secondary = if (showMovingAverage) movingAverage else emptyList(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
            )
        }
    }
}

@Composable
private fun IndicatorPanel(
    enabledIndicators: Set<Indicator>,
    rsi: Double,
    macd: Double,
    signal: Double,
    onToggle: (Indicator) -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Indicators", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Indicator.entries.forEach { indicator ->
                    FilterChip(
                        selected = indicator in enabledIndicators,
                        onClick = { onToggle(indicator) },
                        label = { Text(indicator.label) }
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetricPill("RSI", rsi.format(1), if (rsi > 70 || rsi < 30) AmberAccent else BrandBlue)
                MetricPill("MACD", macd.format(2), BrandBlue)
                MetricPill("Signal", signal.format(2), Color.Gray)
            }
        }
    }
}

@Composable
private fun DepthCard(orderBook: OrderBook) {
    val maxQuantity = max(
        orderBook.bids.maxOf { it.quantity },
        orderBook.asks.maxOf { it.quantity }
    )

    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Order Book Depth", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Bids", color = PositiveGreen, fontWeight = FontWeight.SemiBold)
                    orderBook.bids.take(6).forEach { level ->
                        DepthBar(level.price.format(2), (level.quantity / maxQuantity).toFloat(), PositiveGreen)
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Asks", color = NegativeRed, fontWeight = FontWeight.SemiBold)
                    orderBook.asks.take(6).forEach { level ->
                        DepthBar(level.price.format(2), (level.quantity / maxQuantity).toFloat(), NegativeRed)
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight) {
    val context = LocalContext.current
    val color = when (insight.priority) {
        InsightPriority.High -> NegativeRed
        InsightPriority.Medium -> AmberAccent
        InsightPriority.Low -> BrandBlue
    }

    Card(shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
                Text(insight.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(insight.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                listOfNotNull(insight.marketTag, insight.sector, insight.symbol).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("insight", "${insight.title}\n${insight.reason}")
                    )
                }
            ) {
                Text("Copy insight")
            }
        }
    }
}

@Composable
private fun CoinRow(
    coin: CoinMarket,
    inWatchlist: Boolean,
    onClick: () -> Unit,
    onTrailingClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${coin.symbol} · ${coin.name}", fontWeight = FontWeight.Bold)
                Text(coin.sector, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Vol ${coin.volume24h.compact()}", style = MaterialTheme.typography.labelMedium)
            }
            Sparkline(coin.sparkline, Modifier.width(88.dp).height(40.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$${coin.price.format(2)}", fontWeight = FontWeight.SemiBold)
                Text(
                    "${coin.change24h.format(2)}%",
                    color = if (coin.change24h >= 0) PositiveGreen else NegativeRed
                )
                if (onTrailingClick != null) {
                    Text(
                        if (inWatchlist) "Saved" else "Save",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onTrailingClick)
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterStrip(
    title: String,
    values: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelected(value) },
                    label = { Text(value) }
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

@Composable
private fun InfoCard(title: String, accent: Color, body: String) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.size(width = 48.dp, height = 6.dp).background(accent, RoundedCornerShape(50)))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilterCard(
    title: String,
    values: List<String>,
    selected: String,
    onSelected: (String) -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                values.forEach { value ->
                    FilterChip(
                        selected = selected == value,
                        onClick = { onSelected(value) },
                        label = { Text(value) }
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.14f))) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Text(label, modifier = Modifier.width(52.dp))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DepthBar(price: String, ratio: Float, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(price, style = MaterialTheme.typography.labelMedium)
            Text("${(ratio * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(min(1f, ratio))
                    .height(8.dp)
                    .background(color, RoundedCornerShape(50))
            )
        }
    }
}

@Composable
private fun DonutChart(values: List<Int>, colors: List<Color>, modifier: Modifier = Modifier) {
    val total = values.sum().toFloat().coerceAtLeast(1f)
    Canvas(modifier = modifier.aspectRatio(1f)) {
        val strokeWidth = 18.dp.toPx()
        var start = -90f
        values.forEachIndexed { index, value ->
            val sweep = value / total * 360f
            drawArc(
                color = colors[index],
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(strokeWidth, strokeWidth),
                size = Size(size.width - strokeWidth * 2, size.height - strokeWidth * 2),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            start += sweep
        }
    }
}

@Composable
private fun LineChart(primary: List<Double>, secondary: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (primary.size < 2) return@Canvas

        fun linePath(values: List<Double>): Path {
            val maxValue = values.maxOrNull() ?: 0.0
            val minValue = values.minOrNull() ?: 0.0
            val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
            val step = size.width / (values.size - 1)
            return Path().apply {
                values.forEachIndexed { index, value ->
                    val x = index * step
                    val y = size.height - ((value - minValue) / range * size.height).toFloat()
                    if (index == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
        }

        drawPath(linePath(primary), color = BrandBlue, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
        if (secondary.isNotEmpty()) {
            drawPath(linePath(secondary), color = AmberAccent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val maxValue = values.maxOrNull() ?: 0.0
        val minValue = values.minOrNull() ?: 0.0
        val range = (maxValue - minValue).takeIf { it > 0 } ?: 1.0
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * step
            val y = size.height - ((value - minValue) / range * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = if (values.last() >= values.first()) PositiveGreen else NegativeRed, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

object SampleData {
    val coins = listOf(
        coin("BTCUSDT", "Bitcoin", "Store of Value", 68420.0, 3.2, 35_200_000_000.0, 1_300_000_000_000.0, trend(65000.0, 68420.0), 68420.0),
        coin("ETHUSDT", "Ethereum", "Layer1", 3520.0, 4.6, 21_800_000_000.0, 422_000_000_000.0, trend(3200.0, 3520.0), 3520.0),
        coin("SOLUSDT", "Solana", "Layer1", 182.4, 9.1, 8_400_000_000.0, 84_000_000_000.0, trend(144.0, 182.4), 182.4),
        coin("DOGEUSDT", "Dogecoin", "Meme", 0.19, 11.8, 3_700_000_000.0, 28_000_000_000.0, trend(0.13, 0.19), 0.19),
        coin("PEPEUSDT", "Pepe", "Meme", 0.000011, 14.4, 1_900_000_000.0, 4_800_000_000.0, trend(0.000007, 0.000011), 0.000011),
        coin("LINKUSDT", "Chainlink", "Oracle", 21.5, 2.4, 970_000_000.0, 12_600_000_000.0, trend(19.0, 21.5), 21.5),
        coin("AAVEUSDT", "Aave", "DeFi", 127.8, -2.8, 620_000_000.0, 1_900_000_000.0, trend(138.0, 127.8), 127.8),
        coin("UNIUSDT", "Uniswap", "DeFi", 10.7, -4.2, 580_000_000.0, 8_000_000_000.0, trend(12.4, 10.7), 10.7),
        coin("ONDOUSDT", "Ondo", "RWA", 1.12, 7.4, 380_000_000.0, 1_500_000_000.0, trend(0.86, 1.12), 1.12),
        coin("ARBUSDT", "Arbitrum", "Layer2", 1.34, 1.8, 760_000_000.0, 4_200_000_000.0, trend(1.19, 1.34), 1.34),
        coin("OPUSDT", "Optimism", "Layer2", 2.81, 3.5, 690_000_000.0, 3_100_000_000.0, trend(2.44, 2.81), 2.81),
        coin("FETUSDT", "Fetch.ai", "AI", 2.44, 12.1, 1_450_000_000.0, 2_300_000_000.0, trend(1.72, 2.44), 2.44)
    )

    fun fallbackKlines(symbol: String, interval: String): List<Kline> {
        val coin = coins.firstOrNull { it.symbol == symbol } ?: coins.first()
        val timeframe = when (interval) {
            "1m" -> Timeframe.M1
            "5m" -> Timeframe.M5
            "15m" -> Timeframe.M15
            "1h" -> Timeframe.H1
            "4h" -> Timeframe.H4
            "1d" -> Timeframe.D1
            else -> Timeframe.H1
        }
        return coin.klinesByFrame.getValue(timeframe)
    }

    fun fallbackDepth(symbol: String): OrderBook =
        coins.firstOrNull { it.symbol == symbol }?.orderBook ?: coins.first().orderBook

    private fun coin(
        symbol: String,
        name: String,
        sector: String,
        price: Double,
        change24h: Double,
        volume: Double,
        marketCap: Double,
        sparkline: List<Double>,
        anchor: Double
    ): CoinMarket {
        val frames = mapOf(
            Timeframe.M1 to klines(anchor * 0.98, anchor, 18),
            Timeframe.M5 to klines(anchor * 0.97, anchor, 22),
            Timeframe.M15 to klines(anchor * 0.95, anchor, 26),
            Timeframe.H1 to klines(anchor * (1 - change24h / 180), anchor, 32),
            Timeframe.H4 to klines(anchor * (1 - change24h / 120), anchor, 28),
            Timeframe.D1 to klines(anchor * (1 - change24h / 65), anchor, 24)
        )

        return CoinMarket(
            symbol = symbol,
            name = name,
            sector = sector,
            price = price,
            change24h = change24h,
            volume24h = volume,
            sparkline = sparkline,
            klinesByFrame = frames,
            orderBook = orderBook(anchor),
            marketCap = marketCap
        )
    }

    private fun trend(start: Double, end: Double): List<Double> =
        List(16) { index ->
            val ratio = index / 15.0
            val wave = if (index % 3 == 0) -0.015 else 0.02
            (start + (end - start) * ratio) * (1 + wave * ratio)
        }

    private fun klines(start: Double, end: Double, count: Int): List<Kline> =
        List(count) { index ->
            val ratio = index / (count - 1.0)
            val base = start + (end - start) * ratio
            val drift = if (index % 5 == 0) 0.012 else -0.006
            val close = base * (1 + drift * ratio)
            val open = close * (1 - 0.01 + (index % 4) * 0.002)
            val high = max(open, close) * 1.012
            val low = min(open, close) * 0.988
            val volume = 1000 + index * 80 + if (index == count - 1) 1800 else 0
            Kline(open, high, low, close, volume.toDouble())
        }

    private fun orderBook(anchor: Double): OrderBook {
        val bids = (1..8).map { step ->
            OrderLevel(anchor * (1 - step * 0.0025), 60.0 + step * 18)
        }
        val asks = (1..8).map { step ->
            OrderLevel(anchor * (1 + step * 0.0025), 55.0 + step * 16)
        }
        return OrderBook(bids = bids, asks = asks)
    }
}

private fun Double.format(decimals: Int): String = "%.${decimals}f".format(this)

private fun Double.compact(): String = when {
    this >= 1_000_000_000 -> "${(this / 1_000_000_000).format(1)}B"
    this >= 1_000_000 -> "${(this / 1_000_000).format(1)}M"
    this >= 1_000 -> "${(this / 1_000).format(1)}K"
    else -> format(0)
}

private fun Intent?.toNotificationRoute(): NotificationRoute = NotificationRoute(
    symbol = this?.getStringExtra(NotificationSupport.EXTRA_SYMBOL),
    tab = this?.getStringExtra(NotificationSupport.EXTRA_TAB) ?: "detail",
    timeframe = this?.getStringExtra(NotificationSupport.EXTRA_TIMEFRAME)
)

private fun Map<Timeframe, List<Kline>>.updateLiveKline(kline: Kline): Map<Timeframe, List<Kline>> {
    val current = getValue(Timeframe.M1)
    val updated = if (current.isEmpty()) {
        listOf(kline)
    } else {
        current.drop(1) + kline
    }
    return this + mapOf(Timeframe.M1 to updated)
}

private val BrandBlue = Color(0xFF1A56DB)
private val DarkBase = Color(0xFF09111F)
private val DarkCard = Color(0xFF10233E)
private val PositiveGreen = Color(0xFF22C55E)
private val NegativeRed = Color(0xFFEF4444)
private val AmberAccent = Color(0xFFF59E0B)

@Composable
private fun CryptoInsightTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = BrandBlue,
            secondary = AmberAccent,
            background = DarkBase,
            surface = Color(0xFF111A2C),
            surfaceVariant = Color(0xFF1A2438),
            onPrimary = Color.White,
            onBackground = Color(0xFFF3F6FB),
            onSurface = Color(0xFFF3F6FB),
            onSurfaceVariant = Color(0xFFA6B4CF)
        ),
        content = content
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF09111F)
@Composable
private fun AppPreview() {
    val coins = SampleData.coins
    val previewState = UiState(
        snapshot = MarketAnalytics.snapshot(coins),
        coins = coins,
        sectors = MarketAnalytics.sectors(coins),
        insights = RuleBasedInsightEngine().buildInsights(coins, MarketAnalytics.sectors(coins)),
        selectedCoin = coins.first()
    )
    CryptoInsightTheme {
        OverviewScreen(
            state = previewState,
            onRefresh = {},
            onCoinSelected = {}
        )
    }
}
