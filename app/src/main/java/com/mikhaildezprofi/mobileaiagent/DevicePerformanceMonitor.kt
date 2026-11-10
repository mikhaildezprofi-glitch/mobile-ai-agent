package com.mikhaildezprofi.mobileaiagent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.ActivityManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.Locale

/** Lightweight, permission-free performance sampler for local inference diagnostics. */
class DevicePerformanceMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var previousCpuMs = Process.getElapsedCpuTime()
    private var previousWallMs = SystemClock.elapsedRealtime()

    data class Snapshot(
        val appCpuPercent: Double,
        val coreCount: Int,
        val appPssMb: Double,
        val javaHeapUsedMb: Double,
        val javaHeapMaxMb: Double,
        val availableRamMb: Double,
        val totalRamMb: Double,
        val lowMemory: Boolean,
        val thermalStatus: String,
        val batteryTemperatureC: Double?
    ) {
        fun asText(): String = buildString {
            appendLine("CPU приложения: ${fmt(appCpuPercent)}% от одного ядра (${coreCount} ядер в системе)")
            appendLine("Память приложения PSS: ${fmt(appPssMb)} МБ")
            appendLine("Java heap: ${fmt(javaHeapUsedMb)} / ${fmt(javaHeapMaxMb)} МБ")
            appendLine("Доступная RAM: ${fmt(availableRamMb)} / ${fmt(totalRamMb)} МБ")
            appendLine("Android lowMemory: ${if (lowMemory) "ДА" else "нет"}")
            appendLine("Тепловой статус Android: $thermalStatus")
            append("Температура батареи: ${batteryTemperatureC?.let { "${fmt(it)} °C" } ?: "недоступна"}")
        }

        private fun fmt(value: Double) = String.format(Locale.US, "%.1f", value)
    }

    fun sample(): Snapshot {
        val nowCpu = Process.getElapsedCpuTime()
        val nowWall = SystemClock.elapsedRealtime()
        val wallDelta = (nowWall - previousWallMs).coerceAtLeast(1L)
        val cpuDelta = (nowCpu - previousCpuMs).coerceAtLeast(0L)
        previousCpuMs = nowCpu
        previousWallMs = nowWall

        val memory = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memory)
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryTemp = battery?.getIntExtra("temperature", -1)?.takeIf { it > 0 }?.div(10.0)
        val thermal = if (android.os.Build.VERSION.SDK_INT >= 29) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Норма"
                PowerManager.THERMAL_STATUS_LIGHT -> "Небольшой нагрев"
                PowerManager.THERMAL_STATUS_MODERATE -> "Умеренный нагрев"
                PowerManager.THERMAL_STATUS_SEVERE -> "Сильный нагрев"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Критический нагрев"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Аварийный нагрев"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Отключение из-за перегрева"
                else -> "Неизвестно"
            }
        } else "API не поддерживается"

        return Snapshot(
            appCpuPercent = cpuDelta.toDouble() * 100.0 / wallDelta.toDouble(),
            coreCount = Runtime.getRuntime().availableProcessors(),
            appPssMb = Debug.getPss().toDouble() / 1024.0,
            javaHeapUsedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()).toDouble() / 1048576.0,
            javaHeapMaxMb = Runtime.getRuntime().maxMemory().toDouble() / 1048576.0,
            availableRamMb = memory.availMem.toDouble() / 1048576.0,
            totalRamMb = memory.totalMem.toDouble() / 1048576.0,
            lowMemory = memory.lowMemory,
            thermalStatus = thermal,
            batteryTemperatureC = batteryTemp
        )
    }
}
