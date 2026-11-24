package com.xiaoba.probe

import android.media.AudioRecord
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object AudioUtil {

    fun readFully(rec: AudioRecord, buf: ShortArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = rec.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    /** 从交织数据里取出第 c 个声道 */
    fun channel(x: ShortArray, nch: Int, c: Int): ShortArray {
        val frames = x.size / nch
        return ShortArray(frames) { x[it * nch + c] }
    }

    /** RMS，单位 dBFS；全零返回 -120 */
    fun rmsDb(x: ShortArray, len: Int = x.size, from: Int = 0): Double {
        val end = min(len, x.size)
        if (end - from <= 0) return -120.0
        var s = 0.0
        for (i in from until end) {
            val v = x[i].toDouble()
            s += v * v
        }
        val rms = sqrt(s / (end - from))
        return if (rms < 1e-9) -120.0 else 20.0 * log10(rms / 32768.0)
    }

    /** 皮尔逊相关系数：判断多声道是不是同一路信号复制出来的 */
    fun corr(a: ShortArray, b: ShortArray): Double {
        val n = min(a.size, b.size)
        if (n == 0) return 0.0
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) { ma += a[i]; mb += b[i] }
        ma /= n; mb /= n
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            sab += x * y; saa += x * x; sbb += y * y
        }
        return if (saa <= 0.0 || sbb <= 0.0) 0.0 else sab / sqrt(saa * sbb)
    }

    fun channelSummary(d: ShortArray, nch: Int): String {
        val chans = (0 until nch).map { channel(d, nch, it) }
        val rms = chans.map { rmsDb(it) }
        val sb = StringBuilder("RMS(dBFS)=" + rms.joinToString("/") { "%.0f".format(it) })
        if (nch > 1) {
            sb.append("  与ch0相关=" + (1 until nch).joinToString("/") { "%.2f".format(corr(chans[0], chans[it])) })
        }
        if (rms.all { it <= -119.0 }) sb.append("  ⚠全零（麦克风可能被占用，或该路没有信号）")
        return sb.toString()
    }

    fun writeWav(f: File, data: ShortArray, len: Int, rate: Int, nch: Int) {
        val n = min(len, data.size)
        val bytes = n * 2
        val bb = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)); bb.putInt(36 + bytes)
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)); bb.putInt(16)
        bb.putShort(1); bb.putShort(nch.toShort()); bb.putInt(rate)
        bb.putInt(rate * nch * 2); bb.putShort((nch * 2).toShort()); bb.putShort(16)
        bb.put("data".toByteArray(Charsets.US_ASCII)); bb.putInt(bytes)
        for (i in 0 until n) bb.putShort(data[i])
        f.parentFile?.mkdirs()
        f.writeBytes(bb.array())
    }

    /** 读 16bit PCM WAV（兼容被中断、头部长度不准的 tinycap 文件）。返回 (数据, 采样率, 声道数) */
    fun readWav(f: File): Triple<ShortArray, Int, Int>? {
        val b = try { f.readBytes() } catch (e: Throwable) { return null }
        if (b.size < 44) return null
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val nch = max(1, bb.getShort(22).toInt())
        val rate = bb.getInt(24)
        var pos = 12
        var dataPos = 44
        while (pos + 8 <= b.size) {
            val id = String(b, pos, 4, Charsets.US_ASCII)
            val sz = bb.getInt(pos + 4)
            if (id == "data") { dataPos = pos + 8; break }
            if (sz < 0 || sz > b.size) break
            pos += 8 + sz
        }
        val n = (b.size - dataPos) / 2
        if (n <= 0) return null
        val out = ShortArray(n) { bb.getShort(dataPos + it * 2) }
        return Triple(out, rate, nch)
    }

    /** 类人声测试音：基频 100~220Hz 的谐波 + 两个共振峰 + 4Hz 音节包络，用来模拟 TTS 播报 */
    fun testSignal(rate: Int, sec: Double): ShortArray {
        val n = (rate * sec).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val f0 = 160.0 + 60.0 * sin(2 * PI * 0.7 * t)
            phase += 2 * PI * f0 / rate
            var v = 0.0
            for (h in 1..14) {
                val fh = f0 * h
                if (fh > rate / 2.2) break
                val g1 = (fh - 700.0) / 400.0
                val g2 = (fh - 1800.0) / 500.0
                val formant = exp(-g1 * g1) + 0.6 * exp(-g2 * g2) + 0.25
                v += formant * sin(h * phase) / h
            }
            val env = 0.55 + 0.45 * sin(2 * PI * 4.0 * t)
            out[i] = (v * env * 0.18 * 32767.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return out
    }
}
