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
            finish()
            return
        }
        Log.d(TAG, "Redirect raw=$raw host=${raw.host} path=${raw.path} query=${raw.query}")

        lifecycleScope.launch {
            var email: String? = null
            var failReason: String? = null
            try {
                email = OutlookOAuth.complete(applicationContext, raw)
                if (email == null) failReason = "token exchange or email resolution failed"
            } catch (t: Throwable) {
                Log.e(TAG, "complete threw", t)
                failReason = t.javaClass.simpleName + ": " + (t.message ?: "")
            }

            val msg = if (email != null) getString(R.string.added, email) else getString(R.string.oauth_sign_in_failed)
            Log.d(TAG, "OAuth result email=$email fail=$failReason")
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
