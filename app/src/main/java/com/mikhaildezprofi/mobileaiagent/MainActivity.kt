package com.mikhaildezprofi.mobileaiagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mikhaildezprofi.mobileaiagent.ui.MainScreen
import com.mikhaildezprofi.mobileaiagent.ui.theme.MobileAIAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MobileAIAgentTheme {
                MainScreen()
            }
        }
    }
}
