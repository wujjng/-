package com.xiaoba.probe

import java.util.Locale

/**
 * 带声调拼音 → sherpa-onnx 中文 KWS token（声母 + 带调韵母）。
 * 输入例子："xiao3 ai4 tong2 xue2" 或 "xiǎo ài tóng xué"
 * 输出例子："x iǎo ài t óng x ué @小爱同学"
 * 规则对齐 pypinyin strict=False（sherpa-onnx text2token 用的规则）：y/w 算声母，xue 的韵母是 ue。
 */
object Pinyin {
    private val INITIALS = listOf(
        "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "r", "z", "c", "s", "y", "w",
    )
    private val TONES = mapOf(
        'a' to "āáǎà", 'e' to "ēéěè", 'i' to "īíǐì",
        'o' to "ōóǒò", 'u' to "ūúǔù", 'ü' to "ǖǘǚǜ",
    )

    fun syllableCount(pinyin: String): Int =
        pinyin.trim().split(Regex("[\\s,，]+")).count { it.isNotEmpty() }

    fun toKeywordLine(pinyin: String, label: String): String {
        val tokens = pinyin.trim().split(Regex("[\\s,，]+"))
            .filter { it.isNotEmpty() }
            .flatMap { syllable(it) }
        val name = label.trim().ifEmpty { pinyin.trim() }.replace(Regex("\\s+"), "_")
        return tokens.joinToString(" ") + " @" + name
    }

    fun syllable(raw: String): List<String> {
        var s = raw.lowercase(Locale.ROOT).replace('v', 'ü')
        if (s.isEmpty()) return emptyList()
        var tone = 0
        val last = s.last()
        if (last in '0'..'5') {
            tone = last - '0'
            s = s.dropLast(1)
        }
        if (s.isEmpty()) return emptyList()
        val ini = INITIALS.firstOrNull { s.startsWith(it) && s.length > it.length } ?: ""
        var fin = s.substring(ini.length)
        if (tone in 1..4) fin = applyTone(fin, tone)
        return if (ini.isEmpty()) listOf(fin) else listOf(ini, fin)
    }

    private fun applyTone(fin: String, tone: Int): String {
        val idx = when {
            'a' in fin -> fin.indexOf('a')
            'e' in fin -> fin.indexOf('e')
            "ou" in fin -> fin.indexOf('o')
            else -> fin.indexOfLast { it in "iouü" }
        }
        if (idx < 0) return fin
        val marks = TONES[fin[idx]] ?: return fin
        return fin.substring(0, idx) + marks[tone - 1] + fin.substring(idx + 1)
    }
}
