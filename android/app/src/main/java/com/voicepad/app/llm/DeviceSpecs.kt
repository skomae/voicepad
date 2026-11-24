package com.voicepad.app.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** What [LlmGuidance] needs to know about the phone. */
data class DeviceSpecs(
    val totalRamGb: Double,
    val cores: Int,
    val soc: String,
) {
    companion object {
        fun read(context: Context): DeviceSpecs {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            val totalRamGb = info.totalMem / (1024.0 * 1024.0 * 1024.0)
            val cores = Runtime.getRuntime().availableProcessors()
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.takeIf { it.isNotBlank() } ?: Build.HARDWARE
            } else {
                Build.HARDWARE
            }
            return DeviceSpecs(totalRamGb, cores, soc)
        }
    }
}
