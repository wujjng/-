package com.xiaoba.probe

import android.system.Os
import android.system.OsConstants
import java.io.File

/** 进程 CPU / 内存采样（读 /proc，不需要 root）。 */
object ProcStats {
    val clkTck: Long by lazy {
        try {
            val v = Os.sysconf(OsConstants._SC_CLK_TCK)
            if (v > 0) v else 100L
        } catch (e: Throwable) {
            100L
        }
    }

    /** 本进程累计 CPU 时间（utime + stime，单位：时钟节拍） */
    fun cpuTicks(): Long = try {
        val s = File("/proc/self/stat").readText()
        val rest = s.substring(s.lastIndexOf(')') + 2).trim().split(" ")
        // rest[0] 是第 3 个字段 state；utime=第14个字段，stime=第15个字段
        rest[11].toLong() + rest[12].toLong()
    } catch (e: Throwable) {
        0L
    }

    fun rssMb(): Long = try {
        val line = File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }
        line.filter { it.isDigit() }.toLong() / 1024
    } catch (e: Throwable) {
        -1L
    }
}
