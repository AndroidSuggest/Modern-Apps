package com.vayunmathur.email.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.email.MainActivity
import com.vayunmathur.email.R
import com.vayunmathur.email.data.OutlookOAuth
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

/**
 * Microsoft OAuth redirect handler for `com.vayunmathur.email://oauth`.
 * Azure portal: Mobile and desktop applications — matches Thunderbird Desktop
 * `useExternalBrowser=true` pattern (RFC 8252 custom-scheme).
 *
 * Handles both onCreate and onNewIntent (singleTask launchMode) so second
 * login attempts land here even if activity already exists.
 */
class OAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val raw = intent?.data
        if (raw == null) {
            Log.w(TAG, "OAuthActivity no data")
            AppMessages.show("Microsoft sign-in failed: no redirect data")
            finish()
            return
        }
        Log.d(TAG, "Redirect raw=$raw host=${raw.host} path=${raw.path} query=${raw.query}")

        lifecycleScope.launch {
            val result: OutlookOAuth.OAuthResult = try {
                OutlookOAuth.complete(applicationContext, raw)
            } catch (t: Throwable) {
                Log.e(TAG, "complete threw", t)
                OutlookOAuth.OAuthResult.Failure(
                    reason = t.message ?: "${t.javaClass.simpleName} during sign-in",
                    error = t.javaClass.simpleName,
                    errorDescription = t.message
                )
            }

            val msg = when (result) {
                is OutlookOAuth.OAuthResult.Success -> {
                    Log.d(TAG, "OAuth success email=${result.email}")
                    getString(R.string.added, result.email)
                }
                is OutlookOAuth.OAuthResult.Failure -> {
                    // Surface the actual callback error instead of hardcoded string
                    val parts = listOfNotNull(
                        result.reason.takeIf { it.isNotBlank() },
                        result.error?.takeIf { it.isNotBlank() },
                        result.errorDescription?.takeIf { it.isNotBlank() }
                    ).distinct()
                    val detailed = parts.joinToString(": ")
                    Log.e(TAG, "OAuth failure: $detailed raw=$raw")
                    // Show callback error (error_description from Azure) not generic check-redirect message
                    detailed
                }
            }
            AppMessages.show(msg)

            startActivity(
                Intent(this@OAuthActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
    }

    companion object { private const val TAG = "OAuthActivity" }
}
