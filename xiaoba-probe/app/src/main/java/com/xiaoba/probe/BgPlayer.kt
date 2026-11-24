package com.xiaoba.probe

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max
import kotlin.math.min

/** 播放测试音：模拟 TTS 播报，用于回声测试和"播报中打断"测试。 */
object BgPlayer {

    private fun build(rate: Int, usage: Int): AudioTrack {
        val attrs = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(
                if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) AudioAttributes.CONTENT_TYPE_SPEECH
                else AudioAttributes.CONTENT_TYPE_MUSIC
            )
            .build()
        val fmt = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minB = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(fmt)
            .setBufferSizeInBytes(max(minB, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun playOnce(sig: ShortArray, rate: Int, usage: Int) {
        val t = build(rate, usage)
        try {
            t.play()
            var off = 0
            while (off < sig.size) {
                val n = t.write(sig, off, min(1600, sig.size - off))
                if (n <= 0) break
                off += n
            }
            Thread.sleep(200)
            t.stop()
        } catch (e: Throwable) {
            Report.log("[播放] 异常：$e")
        } finally {
            t.release()
        }
    }

    fun loop(usage: Int, shouldStop: () -> Boolean) {
        val rate = 16000
        val sig = AudioUtil.testSignal(rate, 4.0)
        val t = build(rate, usage)
        try {
            t.play()
            while (!shouldStop()) {
                var off = 0
                while (off < sig.size && !shouldStop()) {
                    val n = t.write(sig, off, min(1600, sig.size - off))
                    if (n <= 0) return
                    off += n
                }
            }
            t.stop()
        } catch (e: Throwable) {
            Report.log("[播放] 异常：$e")
        } finally {
            t.release()
        }
    }
}
