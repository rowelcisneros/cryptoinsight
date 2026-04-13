# Crypto Insight

Android MVP scaffold implemented from the PRD in `crypto_insight_prd.docx`.

## Legal

- [Privacy Policy](https://rowelcisneros.github.io/cryptoinsight/privacy-policy.html)
- [Terms and Conditions](https://rowelcisneros.github.io/cryptoinsight/terms-and-conditions.html)
- [Copyright Notice](https://rowelcisneros.github.io/cryptoinsight/copyright.html)
- [License](https://rowelcisneros.github.io/cryptoinsight/license.html)

## Included

- Jetpack Compose single-module Android app
- Dark theme aligned to the PRD color direction
- Market overview dashboard
- Sector browser with filtering
- Coin detail screen with timeframe switching, simple charting, depth view, and indicator toggles
- Insight center with 3-dimensional filters
- Search + local watchlist interactions
- Local watchlist persistence
- Settings page with refresh interval, notifications, and data source preferences
- Insight text copy action
- Live Binance REST market refresh with cached fallback
- Rule-based insight engine with 5 signal types
- Repository abstraction ready to swap from local sample data to Binance/Firebase
- App-wide disclaimer to avoid investment-advice phrasing
- Gradle wrapper checked in for local builds

## Current data mode

The project now supports a live Binance REST mode plus an in-app cached fallback. The integration seam is:

- `MarketRepository`
- `LiveMarketRepository`
- `FakeMarketRepository`
- `BinanceMarketRepository`
- `RuleBasedInsightEngine`
- `PreferenceStore`

Current live coverage:

1. Binance 24h ticker refresh for the tracked coin universe
2. Binance kline refresh for the selected coin
3. Binance depth refresh for the selected coin
4. Cached fallback when network calls fail

Next backend expansion points:

1. Binance WebSocket ingestion
2. Firebase-backed cached market snapshots
3. Firestore-driven insight feeds
4. FCM delivery

## PRD coverage

- `M-01` 首页市场概览: implemented
- `M-02` 板块浏览: implemented
- `M-03` 币种详情: implemented with lightweight custom charting
- `M-04` 洞见中心: implemented
- `M-05` 搜索与自选: implemented locally with persistence
- `M-07` 设置页: implemented locally
- `A-01` 市场涨跌分布: implemented
- `A-02` MA / RSI / MACD: implemented
- `A-03` 成交量异常: implemented
- `A-04` 规则引擎机会判断: implemented
- `D-01` `D-03` 数据获取层: implemented for Binance REST on tracked pairs
- `D-04` Depth: implemented for selected coin via Binance REST
- Firebase / FCM / backend scheduling: not yet implemented
- Binance WebSocket / OKX live fallback / screenshot sharing / real push delivery: reserved for later phases

## Run

Open the folder in Android Studio and let it import the Gradle Kotlin DSL project.

CLI build:

```bash
./gradlew assembleDebug
```

## Firebase And Messaging

Firebase is optional at build time. The app only enables the `google-services` plugin when `app/google-services.json` exists.

To enable real FCM on device:

1. Put your Firebase config at `app/google-services.json`
2. Rebuild the app

Optional backend messaging endpoints can be configured through Gradle properties:

```properties
messaging.baseUrl=https://your-backend.example.com/
messaging.apiKey=your-shared-api-key
```

You can place those in:

- `~/.gradle/gradle.properties`
- project `gradle.properties`

Expected backend endpoints:

- `POST /api/device/register`
- `POST /api/notifications/test`

If the backend is not configured, the app still works and falls back to local notification tests.
FCM token sync is retried automatically in the background through WorkManager when the backend is configured and the device has network access.

## Firebase Functions Backend

This repo now includes a minimal Firebase Functions backend in [functions/index.js](/home/kai/myprojects/cryptoinsight/functions/index.js).

Implemented public endpoints after deploy:

- `GET /api/health`
- `POST /api/device/register`
- `POST /api/notifications/test`

The backend uses:

- Firestore collection `deviceTokens` for device registrations
- Firestore collection `pushEvents` for test push audit records
- Firebase Admin SDK for actual FCM delivery

### Backend setup

1. Enable Firestore in your Firebase project
2. Install dependencies in `functions/`
3. Set the shared API key
4. Deploy the functions

Example:

```bash
cd functions
npm install
firebase functions:secrets:set BACKEND_SHARED_API_KEY
firebase deploy --only functions
```

After deploy, point the Android app to the function base URL:

```properties
messaging.baseUrl=https://<your-region>-<your-project>.cloudfunctions.net/
messaging.apiKey=<same-shared-api-key>
```

### Local syntax check

```bash
cd functions
node --check index.js
```
