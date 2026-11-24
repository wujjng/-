package com.xiaoba.probe

import android.app.ActivityManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.io.File

object DeviceInfo {

    private fun read(path: String, max: Int = 4000): String = try {
        File(path).readText().take(max).trim()
    } catch (e: Throwable) {
        "(无法读取: ${e.javaClass.simpleName})"
    }

    fun collect(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("型号: ${Build.MANUFACTURER} ${Build.MODEL}  board=${Build.BOARD} hardware=${Build.HARDWARE} device=${Build.DEVICE}")
        sb.appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("fingerprint: ${Build.FINGERPRINT}")
        sb.appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}  | 本进程64位: ${android.os.Process.is64Bit()}")
        val cores = Runtime.getRuntime().availableProcessors()
        sb.appendLine("CPU 核心数: $cores")
        read("/proc/cpuinfo", 20000).lines()
            .filter { l -> listOf("Hardware", "Processor", "model name", "CPU part", "CPU implementer", "Features").any { l.startsWith(it) } }
            .distinct()
            .forEach { sb.appendLine("  $it") }
        for (i in 0 until cores) {
            sb.appendLine(
                "  cpu$i max=${read("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")}kHz " +
                    "gov=${read("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_governor")}"
            )
        }
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        sb.appendLine(
            "内存: total=${mi.totalMem / 1048576}MB avail=${mi.availMem / 1048576}MB " +
                "lowThreshold=${mi.threshold / 1048576}MB low=${mi.lowMemory} | 单App堆=${am.memoryClass}MB large=${am.largeMemoryClass}MB"
        )
        sb.appendLine("/proc/meminfo:")
        read("/proc/meminfo").lines().take(8).forEach { sb.appendLine("  $it") }
        sb.appendLine("/proc/swaps（zram）:")
        read("/proc/swaps").lines().forEach { sb.appendLine("  $it") }
        sb.appendLine("本进程 RSS: ${ProcStats.rssMb()}MB")

        val au = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        sb.appendLine(
            "音频: 输出采样率=${au.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)} " +
                "帧缓冲=${au.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)} " +
                "支持UNPROCESSED=${au.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)}"
        )
        sb.appendLine("系统音效: AEC=${AcousticEchoCanceler.isAvailable()} NS=${NoiseSuppressor.isAvailable()} AGC=${AutomaticGainControl.isAvailable()}")
        for (d in au.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            val tag = if (d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "(内置麦)" else ""
            sb.appendLine(
                "  输入设备: type=${d.type}$tag name=${d.productName} 声道数=${d.channelCounts.joinToString()} " +
                    "声道索引掩码=${d.channelIndexMasks.joinToString()} 采样率=${d.sampleRates.joinToString()}"
            )
        }
        for (d in au.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            sb.appendLine("  输出设备: type=${d.type} name=${d.productName}")
        }
        return sb.toString()
    }
}
