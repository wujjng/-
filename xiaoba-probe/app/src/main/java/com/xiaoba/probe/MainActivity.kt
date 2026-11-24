package com.xiaoba.probe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var hitView: TextView
    private lateinit var statusView: TextView
    private lateinit var kwView: TextView
    private lateinit var srcSpinner: Spinner
    private lateinit var usageSpinner: Spinner
    private lateinit var cbInt8: CheckBox
    private lateinit var cbChunk8: CheckBox
    private lateinit var cbBg: CheckBox
    private lateinit var etThreshold: EditText
    private lateinit var etScore: EditText
    private lateinit var etPinyin: EditText
    private lateinit var etLabel: EditText

    @Volatile private var busy = false
    @Volatile private var tokenSet: Set<String>? = null
    private val prefs by lazy { getSharedPreferences("probe", MODE_PRIVATE) }

    private val sources = listOf(
        6 to "VOICE_RECOGNITION (6)",
        1 to "MIC (1)",
        7 to "VOICE_COMMUNICATION (7)",
        9 to "UNPROCESSED (9)",
        5 to "CAMCORDER (5)",
        0 to "DEFAULT (0)",
    )
    private val usages = listOf(
        AudioAttributes.USAGE_MEDIA to "干扰音走：媒体通道",
        AudioAttributes.USAGE_VOICE_COMMUNICATION to "干扰音走：通话通道",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        logView.text = Report.text()
        Report.sink = { line -> appendLog(line) }
        KwsService.onStatus = { s -> statusView.text = s }
        KwsService.onHit = { s -> hitView.text = s }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        Report.log("小八 M1 探针已启动。输出目录：${Report.outDir(this).absolutePath}")
        thread(name = "prepare") {
            try {
                val dir = KwsEngine.prepare(this)
                tokenSet = KwsEngine.tokenSet(dir)
                Report.log("KWS 模型就绪（${tokenSet?.size} 个 token）：${dir.list()?.sorted()?.joinToString()}")
            } catch (e: Throwable) {
                Report.log("KWS 模型准备失败：$e")
            }
        }
        refreshKeywords()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        Report.log(if (ok) "录音权限已授予" else "❌ 没有录音权限，探针无法工作")
    }

    override fun onDestroy() {
        Report.sink = null
        KwsService.onStatus = null
        KwsService.onHit = null
        super.onDestroy()
    }

    // ---------------- UI ----------------

    private fun buildUi() {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF161A28.toInt())
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
        }
        val leftScroll = ScrollView(this).apply { addView(left) }
        root.addView(leftScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(8), px(8), px(8), px(8))
        }
        hitView = text("—", 22f, 0xFF5DCAA5.toInt())
        statusView = text("KWS 未运行", 13f, 0xFF7C83FF.toInt())
        right.addView(hitView)
        right.addView(statusView)
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFFD0D6E8.toInt())
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        right.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f))
        setContentView(root)

        // ① 基础探查
        left.addView(header("① 基础探查（建议按顺序点）"))
        left.addView(button("1. 设备信息 / 内存 / zram") {
            bg("设备信息") { Report.section("设备信息"); Report.log(DeviceInfo.collect(this)) }
        })
        left.addView(button("2. 录音源 + 多声道扫描（约 1 分钟）") { bgAudio("扫描") { AudioScan.scan(this) } })
        left.addView(button("3. 回声消除(AEC)测试（喇叭会响）") { bgAudio("AEC测试") { AudioScan.echoTest(this) } })
        left.addView(button("4. Root 音频探查（含 tinycap）") { bgAudio("Root探查") { RootProbe.run(this) } })

        // ② KWS
        left.addView(header("② 本地唤醒 KWS"))
        left.addView(label("录音源"))
        srcSpinner = spinner(sources.map { it.second })
        left.addView(srcSpinner)
        cbInt8 = check("int8 量化模型（推荐）", true)
        cbChunk8 = check("低延迟 chunk-8（默认 chunk-16，更准）", false)
        left.addView(cbInt8)
        left.addView(cbChunk8)
        etThreshold = edit("threshold", prefs.getString("th", "0.25") ?: "0.25")
        etScore = edit("score", prefs.getString("sc", "1.5") ?: "1.5")
        left.addView(row(label("阈值(越小越灵敏)"), etThreshold))
        left.addView(row(label("加权 score"), etScore))
        cbBg = check("KWS 运行时循环播放干扰音（测播报中打断）", false)
        left.addView(cbBg)
        usageSpinner = spinner(usages.map { it.second })
        left.addView(usageSpinner)
        left.addView(row(
            button("▶ 开始 KWS") { startKws() },
            button("■ 停止") { KwsService.stop(this) },
        ))
        left.addView(button("试喊计数清零") {
            KwsService.hits = 0
            hitView.text = "计数已清零：请喊 5 次唤醒词"
        })

        kwView = text("", 12f, 0xFFEAF5FF.toInt())
        left.addView(kwView)
        etPinyin = edit("带声调拼音，例如 xiao3 ba1 xiao3 ba1", "")
        etLabel = edit("显示名，例如 小八小八", "")
        left.addView(etPinyin)
        left.addView(etLabel)
        left.addView(row(
            button("添加唤醒词") { addKeyword() },
            button("恢复默认") { prefs.edit().remove("kw").apply(); refreshKeywords(); Report.log("已恢复默认唤醒词") },
        ))

        // ③ 其它
        left.addView(header("③ 其它"))
        left.addView(button("用当前录音源录 5 秒 WAV") { bgAudio("录音") { AudioScan.recordWav(this, currentSource(), 5) } })
        left.addView(button("保存报告到文件") {
            val f = Report.save(this)
            Report.log("报告已保存：${f.absolutePath}\n在电脑上执行：adb pull ${Report.outDir(this).absolutePath} ./xiaoba_probe")
            toast("已保存")
        })
        left.addView(label("把输出目录整个 adb pull 下来发给接手 AI。"))
    }

    private fun text(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
    }

    private fun header(s: String) = text(s, 16f, 0xFF7C83FF.toInt()).apply { setPadding(0, 28, 0, 8) }
    private fun label(s: String) = text(s, 13f, 0xFFB8C0D8.toInt())

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun check(s: String, v: Boolean) = CheckBox(this).apply {
        text = s
        isChecked = v
        setTextColor(0xFFEAF5FF.toInt())
    }

    private fun edit(hintText: String, v: String) = EditText(this).apply {
        hint = hintText
        setText(v)
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF7F88A8.toInt())
        setSingleLine()
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun appendLog(line: String) {
        if (logView.length() > 80_000) {
            val t = logView.text.toString()
            logView.text = t.substring(t.length - 40_000)
        }
        logView.append(line + "\n")
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- 动作 ----------------

    private fun hasMic(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        return false
    }

    private fun bg(name: String, block: () -> Unit) {
        if (busy) { toast("有任务在运行，请稍候"); return }
        busy = true
        thread(name = name) {
            try {
                block()
            } catch (e: Throwable) {
                Report.log("[$name] 异常：$e")
            } finally {
                busy = false
                Report.log("[$name] 结束")
            }
        }
    }

    private fun bgAudio(name: String, block: () -> Unit) {
        if (KwsService.running) { toast("请先停止 KWS（同一时间只能有一路录音）"); return }
        if (!hasMic()) return
        bg(name, block)
    }

    private fun currentSource(): Int = sources[srcSpinner.selectedItemPosition].first

    private fun keywords(): List<String> =
        prefs.getString("kw", null)?.lines()?.filter { it.isNotBlank() }?.ifEmpty { null } ?: KwsEngine.DEFAULT_KEYWORDS

    private fun refreshKeywords() {
        val list = keywords()
        kwView.text = "当前唤醒词（${list.size}/3）：\n" + list.joinToString("\n") { "• $it" }
    }

    private fun addKeyword() {
        val py = etPinyin.text.toString().trim()
        if (py.isEmpty()) { toast("请输入带声调拼音"); return }
        val line = Pinyin.toKeywordLine(py, etLabel.text.toString())
        val ts = tokenSet
        if (ts != null) {
            val bad = KwsEngine.invalidTokens(line, ts)
            if (bad.isNotEmpty()) {
                Report.log("唤醒词「$line」里有模型不认识的 token：$bad，请检查拼音和声调")
                toast("拼音有误：$bad")
                return
            }
        } else {
            Report.log("⚠ 模型还没准备好，暂时不能校验 token")
        }
        val list = keywords().toMutableList()
        if (list.size >= 3) { toast("最多 3 个唤醒词，请先恢复默认"); return }
        val syl = Pinyin.syllableCount(py)
        if (syl <= 2) Report.log("⚠ 唤醒词只有 $syl 个字，误唤醒会明显升高（仍然允许测试）")
        list += line
        prefs.edit().putString("kw", list.joinToString("\n")).apply()
        refreshKeywords()
        Report.log("已添加唤醒词：$line（重新开始 KWS 后生效）")
    }

    private fun startKws() {
        if (busy) { toast("请等当前探查任务结束"); return }
        if (KwsService.running) { toast("KWS 已在运行"); return }
        if (!hasMic()) return
        val th = etThreshold.text.toString().toFloatOrNull() ?: 0.25f
        val sc = etScore.text.toString().toFloatOrNull() ?: 1.5f
        prefs.edit().putString("th", th.toString()).putString("sc", sc.toString()).apply()
        val opt = KwsOptions(
            source = currentSource(),
            int8 = cbInt8.isChecked,
            chunk = if (cbChunk8.isChecked) 8 else 16,
            threshold = th,
            score = sc,
            keywords = keywords(),
            bgPlay = cbBg.isChecked,
            bgUsage = usages[usageSpinner.selectedItemPosition].first,
        )
        KwsService.hits = 0
        Report.section("KWS 启动")
        Report.log("参数：$opt")
        KwsService.start(this, opt)
        hitView.text = "请喊唤醒词…"
    }
}
