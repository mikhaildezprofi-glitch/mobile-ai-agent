package com.mikhaildezprofi.mobileaiagent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** Holds the active generation job so the foreground notification can stop it. */
object BackgroundGenerationController {
    // Process-wide scope: the generation job must not be cancelled when MainScreen
    // leaves composition or the Activity is recreated while the app is backgrounded.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var currentJob: Job? = null

    fun cancelCurrent() {
        currentJob?.cancel()
    }
}
