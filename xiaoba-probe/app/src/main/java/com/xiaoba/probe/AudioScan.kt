package com.xiaoba.probe

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.max

object AudioScan {

    val SOURCES = listOf(
        0 to "DEFAULT",
        1 to "MIC",
        5 to "CAMCORDER",
        6 to "VOICE_RECOGNITION",
        7 to "VOICE_COMMUNICATION",
        9 to "UNPROCESSED",
        1999 to "HOTWORD_系统级",
    )

    fun srcName(s: Int): String = SOURCES.firstOrNull { it.first == s }?.second ?: "SRC_$s"

    class RecResult(
        val error: String?,
        val rate: Int = 0,
        val channels: Int = 0,
        val data: ShortArray? = null,
        val summary: String = "",
    )

    fun tryRecord(src: Int, rate: Int, nch: Int, ms: Int): RecResult {
        val rec: AudioRecord = try {
            val fb = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
            when (nch) {
                1 -> fb.setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                2 -> fb.setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                else -> fb.setChannelIndexMask((1 shl nch) - 1)
            }
            AudioRecord.Builder()
                .setAudioSource(src)
                .setAudioFormat(fb.build())
                .setBufferSizeInBytes(max(rate * nch * 2 / 2, 8192))
                .build()
        } catch (e: Throwable) {
            return RecResult("创建失败: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        }
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) return RecResult("未能初始化")
            val ch = rec.channelCount
            val r = rec.sampleRate
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return RecResult("startRecording 失败（可能被其它进程占用）")
            }
            val data = ShortArray(r * ch * ms / 1000)
            val n = AudioUtil.readFully(rec, data)
            rec.stop()
            if (n <= 0) return RecResult("读取失败")
            val d = if (n == data.size) data else data.copyOf(n)
            return RecResult(null, r, ch, d, AudioUtil.channelSummary(d, ch))
        } catch (e: Throwable) {
            return RecResult("录音异常: ${e.message}")
        } finally {
            rec.release()
        }
    }

    /** 扫描 录音源 × 采样率 × 声道数，找出能不能拿到原始多麦。 */
    fun scan(ctx: Context) {
        Report.section("录音源 / 多声道扫描")
        Report.log("说明：扫描时可以正常说几句话，方便对比各声道。每项录 0.6 秒。")
        val outDir = Report.outDir(ctx)
        val saved = HashSet<Int>()
        for ((src, name) in SOURCES) {
            for (rate in intArrayOf(16000, 48000)) {
                for (nch in intArrayOf(1, 2, 4, 6, 8)) {
                    val r = tryRecord(src, rate, nch, 600)
                    if (r.error != null) {
                        Report.log("✗ $name ${rate}Hz ${nch}ch：${r.error}")
                        continue
                    }
                    Report.log("✓ $name 请求 ${rate}Hz/${nch}ch → 实际 ${r.rate}Hz/${r.channels}ch  ${r.summary}")
                    if (r.channels >= 4 && src !in saved) {
                        Report.log("   ↳ 发现 ≥4 声道，录 3 秒样本，请正常说话…")
                        val r2 = tryRecord(src, rate, nch, 3000)
                        val d = r2.data
                        if (r2.error == null && d != null) {
                            val f = File(outDir, "multich_${name}_${r2.rate}_${r2.channels}ch.wav")
                            AudioUtil.writeWav(f, d, d.size, r2.rate, r2.channels)
                            saved += src
                            Report.log("   ↳ 已保存 ${f.name}  ${r2.summary}")
                        }
                    }
                }
            }
        }
        Report.log(
            "判读：①≥4 声道、各声道 RMS 不同、与 ch0 相关系数 <0.95 → 拿到了原始多麦（情况 B 候选）；" +
                "②所有声道相关≈1.00 → 只是单声道复制；③全零 → 被原厂进程占用，或该路没有信号。"
        )
    }

    data class EchoCase(val name: String, val source: Int, val mode: Int, val aec: Boolean, val playUsage: Int)

    /** 边播放类人声测试音边录音，比较各录音源的回声残留。残留越低 = 越可能有硬件/系统 AEC。 */
    fun echoTest(ctx: Context) {
        Report.section("回声消除 (AEC) 测试")
        Report.log("测试时喇叭会播放约 3 秒测试音（共 7 轮）。请保持安静、不要说话。")
        Report.log("系统音效可用性：AEC=${AcousticEchoCanceler.isAvailable()} NS=${NoiseSuppressor.isAvailable()} AGC=${AutomaticGainControl.isAvailable()}")
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val oldMusic = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val oldCall = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.7).toInt(), 0)
        am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) * 0.7).toInt(), 0)
        val media = AudioAttributes.USAGE_MEDIA
        val voice = AudioAttributes.USAGE_VOICE_COMMUNICATION
        val cases = listOf(
            EchoCase("MIC", 1, AudioManager.MODE_NORMAL, false, media),
            EchoCase("VOICE_RECOGNITION", 6, AudioManager.MODE_NORMAL, false, media),
            EchoCase("CAMCORDER", 5, AudioManager.MODE_NORMAL, false, media),
            EchoCase("UNPROCESSED", 9, AudioManager.MODE_NORMAL, false, media),
            EchoCase("VOICE_COMMUNICATION", 7, AudioManager.MODE_NORMAL, false, media),
            EchoCase("VOICE_COMMUNICATION+AEC", 7, AudioManager.MODE_NORMAL, true, media),
            EchoCase("VC+通话模式+AEC+通话播放", 7, AudioManager.MODE_IN_COMMUNICATION, true, voice),
        )
        val results = LinkedHashMap<String, Double>()
        try {
            for (c in cases) {
                val r = runEcho(ctx, am, c)
                if (r != null) results[c.name] = r
                Thread.sleep(600)
            }
        } finally {
            am.mode = AudioManager.MODE_NORMAL
            am.setStreamVolume(AudioManager.STREAM_MUSIC, oldMusic, 0)
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, oldCall, 0)
        }
        if (results.isEmpty()) {
            Report.log("没有任何一路录音成功，请检查录音权限，以及麦克风是否被原厂服务占用。")
            return
        }
        val ref = results["MIC"] ?: results.values.max()
        val best = results.minByOrNull { it.value }!!
        val gain = ref - best.value
        Report.log("—— 结论 ——")
        results.forEach { (k, v) -> Report.log("  %-28s 回声残留 %.1f dBFS（比 MIC 低 %.1f dB）".format(k, v, ref - v)) }
        val verdict = when {
            gain >= 15 -> "✅ 「${best.key}」比 MIC 低 %.1f dB：很可能有可用的系统/硬件 AEC（情况 A 候选）".format(gain)
            gain >= 6 -> "🟡 「${best.key}」比 MIC 低 %.1f dB：有部分回声抑制，需要结合听感和打断测试判断".format(gain)
            else -> "❌ 各路差别 <6 dB：系统层基本没有 AEC，需要走情况 B（原始多麦 + 回采）或情况 C"
        }
        Report.log(verdict)
        Report.log("提示：原厂语音服务运行时和冻结后各测一次，对比结论。录音已保存为 echo_*.wav，可以 adb pull 下来听。")
    }

    private fun runEcho(ctx: Context, am: AudioManager, c: EchoCase): Double? {
        am.mode = c.mode
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(c.source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate * 2))
        } catch (e: Throwable) {
            Report.log("✗ ${c.name}：创建失败 ${e.message}")
            return null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            Report.log("✗ ${c.name}：未能初始化（该录音源不可用）")
            return null
        }
        var aec: AcousticEchoCanceler? = null
        try {
            if (c.aec) {
                aec = try { AcousticEchoCanceler.create(rec.audioSessionId) } catch (e: Throwable) { null }
                if (aec == null) Report.log("  ${c.name}：AcousticEchoCanceler.create 返回 null")
                else { aec.enabled = true; Report.log("  ${c.name}：AEC 已挂载 enabled=${aec.enabled}") }
            }
            val signal = AudioUtil.testSignal(rate, 3.0)
            rec.startRecording()
            val base = ShortArray(rate)
            AudioUtil.readFully(rec, base)
            val player = thread(name = "echo-play") { BgPlayer.playOnce(signal, rate, c.playUsage) }
            val during = ShortArray(rate * 3)
            val n = AudioUtil.readFully(rec, during)
            player.join(5000)
            rec.stop()
            val baseDb = AudioUtil.rmsDb(base)
            val echoDb = AudioUtil.rmsDb(during, n, rate * 3 / 10)
            val all = ShortArray(base.size + n)
            System.arraycopy(base, 0, all, 0, base.size)
            System.arraycopy(during, 0, all, base.size, n)
            AudioUtil.writeWav(File(Report.outDir(ctx), "echo_${c.name.replace(Regex("[^A-Za-z_+]"), "_")}.wav"), all, all.size, rate, 1)
            val warn = if (baseDb <= -119.0 && echoDb <= -119.0) "  ⚠ 全零：麦克风可能被占用" else ""
            Report.log("✓ %-28s 安静底噪 %.1f dBFS | 播放时 %.1f dBFS%s".format(c.name, baseDb, echoDb, warn))
            return echoDb
        } catch (e: Throwable) {
            Report.log("✗ ${c.name}：$e")
            return null
        } finally {
            aec?.release()
            rec.release()
        }
    }

    fun recordWav(ctx: Context, source: Int, sec: Int) {
        Report.section("录音 ${sec} 秒（${srcName(source)}）")
        Report.log("请在 3 米左右正常音量说「小爱同学」，再说一句话…")
        val r = tryRecord(source, 16000, 1, sec * 1000)
        val d = r.data
        if (r.error != null || d == null) { Report.log("✗ 录音失败：${r.error}"); return }
        val f = File(Report.outDir(ctx), "rec_${srcName(source)}_${System.currentTimeMillis() / 1000}.wav")
        AudioUtil.writeWav(f, d, d.size, r.rate, r.channels)
        Report.log("✓ 已保存 ${f.name}  ${r.summary}")
    }
}
