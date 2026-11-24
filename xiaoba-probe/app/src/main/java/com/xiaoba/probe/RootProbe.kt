package com.xiaoba.probe

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** 需要 Magisk root：读声卡/混音器/原厂算法库，并用 tinycap 试抓原始多声道。 */
object RootProbe {

    fun su(cmd: String, timeoutSec: Long = 10): String {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = thread(name = "su-reader") {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(out) { if (out.length < 200_000) out.append(line).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy()
                synchronized(out) { out.append("[超时]\n") }
            }
            reader.join(1000)
            synchronized(out) { out.toString() }
        } catch (e: Throwable) {
            "[执行失败] $e"
        }
    }

    fun run(ctx: Context) {
        Report.section("Root 音频探查")
        Report.log("Magisk 弹出授权时请点「允许」。")
        val id = su("id").trim()
        Report.log("su id: $id")
        if (!id.contains("uid=0")) {
            Report.log("❌ 没拿到 root，探查终止。")
            return
        }
        val cmds = listOf(
            "cat /proc/asound/cards",
            "cat /proc/asound/pcm",
            "ls -l /dev/snd",
            "ls /system/bin /system/xbin /vendor/bin 2>/dev/null | grep -i tiny",
            "tinymix 2>&1 | head -250",
            "getprop | grep -iE 'audio|voice|aec|mic|record'",
            "ls /system/etc /vendor/etc 2>/dev/null | grep -iE 'audio|effect|mixer'",
            "cat /vendor/etc/audio_effects.conf /system/etc/audio_effects.conf 2>/dev/null | head -150",
            "ps -A | grep -iE 'joyfish|voice|speech|aispeech|iflytek|soundai|dui|audioserver'",
            "ls /system/lib /vendor/lib /system/lib/soundfx /vendor/lib/soundfx 2>/dev/null | grep -iE 'aec|beam|speech|voice|kws|wake|aispeech|duilite|iflytek|msc|soundai|webrtc|effect'",
            "pm path com.joyfish.voiceprobe",
            "dumpsys media.audio_flinger | head -300",
        )
        for (c in cmds) {
            Report.log("$ $c\n" + su(c).trimEnd())
        }
        tinycapProbe(ctx)
    }

    private fun tinycapProbe(ctx: Context) {
        Report.log("—— tinycap 原始多声道试抓 ——")
        val path = su("which tinycap 2>/dev/null; ls /system/bin/tinycap /system/xbin/tinycap /vendor/bin/tinycap 2>/dev/null")
            .lines().map { it.trim() }.firstOrNull { it.endsWith("tinycap") }
        if (path == null) {
            Report.log("未找到 tinycap，跳过（之后可以推一个静态编译的 tinycap 进去再测）。")
            return
        }
        val pcm = su("cat /proc/asound/pcm")
        val caps = pcm.lines().mapNotNull { line ->
            val m = Regex("^(\\d+)-(\\d+):").find(line.trim())
            if (m != null && line.contains("capture")) m.groupValues[1].toInt() to m.groupValues[2].toInt() else null
        }
        if (caps.isEmpty()) {
            Report.log("/proc/asound/pcm 里没有 capture 设备。")
            return
        }
        Report.log("capture 设备：${caps.joinToString { "card${it.first}-dev${it.second}" }}。原厂语音服务正在录音时可能报 busy，这本身也是有用的信息。")
        val outDir = Report.outDir(ctx)
        val tmpDir = "/data/local/tmp/xiaoba"
        su("mkdir -p $tmpDir")
        for ((card, dev) in caps) {
            for (ch in intArrayOf(2, 4, 6, 8)) {
                for (rate in intArrayOf(16000, 48000)) {
                    val name = "tinycap_c${card}d${dev}_${ch}ch_$rate.wav"
                    val tmp = "$tmpDir/$name"
                    val dst = File(outDir, name)
                    val res = su(
                        "rm -f $tmp; $path $tmp -D $card -d $dev -c $ch -r $rate -b 16 > $tmpDir/tc.log 2>&1 & P=\$!; " +
                            "sleep 3; kill -2 \$P 2>/dev/null; sleep 1; kill -9 \$P 2>/dev/null; " +
                            "cp $tmp '${dst.absolutePath}' 2>/dev/null; chmod 666 '${dst.absolutePath}' 2>/dev/null; " +
                            "ls -l $tmp 2>&1; tail -3 $tmpDir/tc.log",
                        12,
                    ).trim().replace("\n", " | ")
                    val w = if (dst.exists() && dst.length() > 2000) AudioUtil.readWav(dst) else null
                    if (w != null) {
                        Report.log("✓ card$card dev$dev 请求 ${ch}ch/${rate}Hz → 文件 ${w.third}ch/${w.second}Hz  ${AudioUtil.channelSummary(w.first, w.third)}")
                    } else {
                        dst.delete()
                        Report.log("✗ card$card dev$dev ${ch}ch/${rate}Hz：$res")
                    }
                }
            }
        }
        Report.log("tinycap 原始文件也保留在 $tmpDir/，可以 adb pull。判读：如果某个配置拿到 6~8 路，并且其中有一两路和喇叭播放内容高度相关，那就是回采参考通道（情况 B）。")
    }
}
