package com.vayunmathur.email.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.email.MainActivity
import com.vayunmathur.email.data.OutlookOAuth
import kotlinx.coroutines.launch
import com.vayunmathur.email.R
import com.vayunmathur.library.util.AppMessages

/**
 * Receives the Outlook OAuth redirect (`com.vayunmathur.email://oauth`),
 * completes the PKCE token exchange + account creation, then returns to the app.
 */
class OutlookOAuthActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val email = OutlookOAuth.complete(applicationContext, data)
            AppMessages.show(if (email != null) applicationContext.getString(R.string.added, email) else applicationContext.getString(R.string.microsoft_sign_in_failed))
            startActivity(
                Intent(this@OutlookOAuthActivity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
        }
    }
}
