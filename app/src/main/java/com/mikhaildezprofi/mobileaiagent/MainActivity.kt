package com.mikhaildezprofi.mobileaiagent

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.mikhaildezprofi.mobileaiagent.ui.MainScreen
import com.mikhaildezprofi.mobileaiagent.ui.theme.MobileAIAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4107)
        }
        setContent {
            MobileAIAgentTheme {
                MainScreen()
            }
        }
    }
}
