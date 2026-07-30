package com.vayunmathur.keyboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vayunmathur.keyboard.ui.SetupScreen
import com.vayunmathur.library.ui.DynamicTheme

/**
 * Setup + settings entry point. The IME itself lives in [com.vayunmathur.keyboard.ime.KeyboardService];
 * this activity only helps the user enable/select the keyboard and tune its preferences.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                SetupScreen()
            }
        }
    }
}
