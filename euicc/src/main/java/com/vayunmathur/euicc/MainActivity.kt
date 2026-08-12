package com.vayunmathur.euicc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vayunmathur.euicc.ui.EuiccApp
import com.vayunmathur.library.ui.DynamicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                EuiccApp()
            }
        }
    }
}
