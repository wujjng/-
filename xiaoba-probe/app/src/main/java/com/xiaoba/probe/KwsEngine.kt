package com.xiaoba.probe

import android.content.Context
import android.content.res.AssetManager
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream

data class KwsOptions(
    val source: Int,
    val int8: Boolean,
    val chunk: Int,
    val threshold: Float,
    val score: Float,
    val keywords: List<String>,
    val bgPlay: Boolean,
    val bgUsage: Int,
)

object KwsEngine {
    const val ASSET_DIR = "kws"
    val DEFAULT_KEYWORDS = listOf("x iǎo ài t óng x ué @小爱同学")

    class Engine(val spotter: KeywordSpotter, val desc: String)

    /** 首次运行时把 assets/kws 复制到内部存储，之后用文件路径加载（方便热换唤醒词）。 */
    fun prepare(ctx: Context): File {
        val dst = File(ctx.filesDir, ASSET_DIR)
        val stamp = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        val marker = File(dst, ".copied")
        if (!marker.exists() || marker.readText() != stamp) {
            dst.deleteRecursively()
            copyAssets(ctx.assets, ASSET_DIR, dst)
            marker.writeText(stamp)
        }
        require(File(dst, "tokens.txt").exists()) { "APK 里没有 KWS 模型（assets/kws/tokens.txt），请先运行 scripts/fetch_deps.sh 再编译" }
        return dst
    }

    private fun copyAssets(am: AssetManager, path: String, dst: File) {
        val children = am.list(path) ?: emptyArray()
        if (children.isEmpty()) {
            dst.parentFile?.mkdirs()
            am.open(path).use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
        } else {
            dst.mkdirs()
            for (c in children) copyAssets(am, "$path/$c", File(dst, c))
        }
    }

    fun tokenSet(dir: File): Set<String> =
        File(dir, "tokens.txt").readLines()
            .mapNotNull { it.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { t -> t.isNotEmpty() } }
            .toSet()

    /** 返回一行唤醒词里模型不认识的 token */
    fun invalidTokens(line: String, tokens: Set<String>): List<String> {
        val body = line.substringBefore("@").trim()
        return body.split(Regex("\\s+"))
            .filter { it.isNotEmpty() && !it.startsWith(":") && !it.startsWith("#") }
            .filter { it !in tokens }
    }

    private fun pick(dir: File, prefix: String, chunk: Int, int8: Boolean): File? {
        val files = dir.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }.orEmpty()
        val byChunk = files.filter { "chunk-$chunk-" in it.name }.ifEmpty { files }
        val preferred = if (int8) byChunk.firstOrNull { ".int8." in it.name } else byChunk.firstOrNull { ".int8." !in it.name }
        return preferred ?: byChunk.firstOrNull()
    }

    fun create(ctx: Context, o: KwsOptions): Engine {
        val dir = prepare(ctx)
        val tokensFile = File(dir, "tokens.txt")
        val enc = pick(dir, "encoder", o.chunk, o.int8) ?: error("找不到 encoder 模型")
        val dec = pick(dir, "decoder", o.chunk, false) ?: error("找不到 decoder 模型")   // decoder 用 fp32
        val joi = pick(dir, "joiner", o.chunk, o.int8) ?: error("找不到 joiner 模型")

        val tokens = tokenSet(dir)
        val valid = mutableListOf<String>()
        for (line in o.keywords) {
            val bad = invalidTokens(line, tokens)
            if (bad.isEmpty()) valid += line else Report.log("[KWS] 跳过无效唤醒词「$line」，模型不认识的 token：$bad")
        }
        require(valid.isNotEmpty()) { "没有有效的唤醒词" }
        val kwFile = File(ctx.filesDir, "keywords.txt")
        kwFile.writeText(valid.joinToString("\n") + "\n")

        val cfg = KeywordSpotterConfig(
            featConfig = FeatureConfig(),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = enc.absolutePath,
                    decoder = dec.absolutePath,
                    joiner = joi.absolutePath,
                ),
                tokens = tokensFile.absolutePath,
                numThreads = 1,
                modelType = "zipformer2",
            ),
            maxActivePaths = 4,
            keywordsFile = kwFile.absolutePath,
            keywordsScore = o.score,
            keywordsThreshold = o.threshold,
            numTrailingBlanks = 1,
        )
        val rssBefore = ProcStats.rssMb()
        val t0 = SystemClock.elapsedRealtime()
        val spotter = KeywordSpotter(assetManager = null, config = cfg)
        val loadMs = SystemClock.elapsedRealtime() - t0
        val desc = "模型加载 ${loadMs}ms，RSS ${rssBefore}→${ProcStats.rssMb()}MB | encoder=${enc.name} decoder=${dec.name} joiner=${joi.name} | " +
            "threshold=${o.threshold} score=${o.score} | 录音源=${AudioScan.srcName(o.source)} | 唤醒词=${valid.joinToString(" ; ")}"
        return Engine(spotter, desc)
    }
}
