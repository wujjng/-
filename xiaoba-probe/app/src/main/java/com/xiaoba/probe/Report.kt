package com.xiaoba.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 统一日志：同时写入内存报告、logcat、界面。 */
object Report {
    private val sb = StringBuilder()
    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    @Volatile var sink: ((String) -> Unit)? = null

    fun log(msg: String) {
        val line = synchronized(timeFmt) { "[${timeFmt.format(Date())}] $msg" }
        synchronized(sb) { sb.append(line).append('\n') }
        Log.i("XiaobaProbe", msg)
        main.post { sink?.invoke(line) }
    }

    fun section(title: String) = log("\n========== $title ==========")

    fun text(): String = synchronized(sb) { sb.toString() }

    /** /sdcard/Android/data/com.xiaoba.probe/files ，adb pull 可直接拿，无需存储权限 */
    fun outDir(ctx: Context): File =
        (ctx.getExternalFilesDir(null) ?: ctx.filesDir).also { it.mkdirs() }

    fun save(ctx: Context): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val f = File(outDir(ctx), "probe_report_$ts.txt")
        f.writeText(text())
        return f
    }
}
