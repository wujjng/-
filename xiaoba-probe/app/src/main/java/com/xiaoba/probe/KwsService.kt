package com.xiaoba.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.max

/** 前台服务：常驻录音 + KWS，持有 WakeLock（设备一直插电），息屏也能测 CPU 和唤醒。 */
class KwsService : Service() {

    companion object {
        @Volatile var running = false
        @Volatile var hits = 0
        @Volatile var options: KwsOptions? = null
        @Volatile var onStatus: ((String) -> Unit)? = null
        @Volatile var onHit: ((String) -> Unit)? = null
        private val main = Handler(Looper.getMainLooper())

        fun start(ctx: Context, opt: KwsOptions) {
            options = opt
            ctx.startForegroundService(Intent(ctx, KwsService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, KwsService::class.java))
        }

        fun postStatus(s: String) { main.post { onStatus?.invoke(s) } }
        fun postHit(s: String) { main.post { onHit?.invoke(s) } }
    }

    @Volatile private var stopFlag = false
    @Volatile private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        if (worker == null) {
            stopFlag = false
            worker = thread(name = "kws-loop") { loop() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopFlag = true
        worker?.join(3000)
        worker = null
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        postStatus("KWS 已停止")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("kws", "唤醒探针", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, "kws")
            .setContentTitle("小八探针")
            .setContentText("唤醒检测运行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun loop() {
        val opt = options ?: return
        running = true
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xiaoba:kws").apply { acquire() }
        var rec: AudioRecord? = null
        var bgThread: Thread? = null
        try {
            postStatus("正在加载模型…")
            val engine = KwsEngine.create(this, opt)
            Report.log("[KWS] ${engine.desc}")
            val spotter = engine.spotter
            val stream = spotter.createStream()
            val rate = 16000
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(opt.source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, rate * 2))
            rec = r
            check(r.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord 初始化失败（录音源 ${AudioScan.srcName(opt.source)} 不可用，或者麦克风被占用）"
            }
            r.startRecording()
            if (opt.bgPlay) bgThread = thread(name = "kws-bg") { BgPlayer.loop(opt.bgUsage) { stopFlag } }

            val buf = ShortArray(1600)          // 100ms
            val fbuf = FloatArray(1600)
            var audioSec = 0.0
            var decodeNs = 0L
            var peakDb = -120.0
            var zeroChunks = 0
            var chunks = 0
            var lastWall = SystemClock.elapsedRealtime()
            var lastTicks = ProcStats.cpuTicks()
            var lastLog = lastWall
            postStatus("运行中，请喊唤醒词")

            while (!stopFlag) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) error("AudioRecord.read 返回 $n")
                if (n == 0) continue
                for (i in 0 until n) fbuf[i] = buf[i] / 32768f
                val t0 = System.nanoTime()
                stream.acceptWaveform(if (n == fbuf.size) fbuf else fbuf.copyOf(n), rate)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val res = spotter.getResult(stream)
                    if (res.keyword.isNotBlank()) {
                        hits++
                        spotter.reset(stream)
                        val msg = "✅ 命中「${res.keyword}」 第 $hits 次"
                        Report.log("[KWS] $msg  tokens=${res.tokens.joinToString(" ")}")
                        postHit(msg)
                    }
                }
                decodeNs += System.nanoTime() - t0
                audioSec += n.toDouble() / rate
                val db = AudioUtil.rmsDb(buf, n)
                if (db > peakDb) peakDb = db
                chunks++
                if (db <= -119.0) zeroChunks++

                val now = SystemClock.elapsedRealtime()
                if (now - lastWall >= 3000) {
                    val ticks = ProcStats.cpuTicks()
                    val cpu = (ticks - lastTicks).toDouble() / ProcStats.clkTck / ((now - lastWall) / 1000.0) * 100.0
                    val rtf = if (audioSec > 0) decodeNs / 1e9 / audioSec else 0.0
                    val zeroWarn = if (chunks > 0 && zeroChunks == chunks) " | ⚠ 全零输入：麦克风可能被占用" else ""
                    val s = "运行中 | 命中 $hits | 进程CPU %.1f%%(单核) | RTF %.3f | RSS %dMB | 输入峰值 %.0f dBFS%s"
                        .format(cpu, rtf, ProcStats.rssMb(), peakDb, zeroWarn)
                    postStatus(s)
                    if (now - lastLog >= 30000) { Report.log("[KWS] $s"); lastLog = now }
                    lastWall = now; lastTicks = ticks
                    audioSec = 0.0; decodeNs = 0L; peakDb = -120.0; zeroChunks = 0; chunks = 0
                }
            }
            stream.release()
            spotter.release()
        } catch (e: Throwable) {
            Report.log("[KWS] 错误：$e")
            postStatus("KWS 出错：${e.message}")
            stopSelf()
        } finally {
            try { rec?.stop() } catch (_: Throwable) {}
            rec?.release()
            stopFlag = true
            bgThread?.join(2000)
            running = false
            worker = null
        }
    }
}
