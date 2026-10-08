package com.navi.shell.voice

/**
 * 把一段回复切成一口气能说完的句子。
 *
 * 为什么要切：合成一句就要等一次 `/tts`（几秒）。整段一起合成的话，
 * 它开着车要干等好几秒才听到第一个字 —— box_call 就是这么一句一句来的。
 *
 * 切法跟 box_call 一致：句号 / 感叹号 / 问号 / 省略号 / 换行。
 */
internal fun splitSentences(text: String): List<String> {
    val out = mutableListOf<String>()
    val buf = StringBuilder()
    for (ch in text) {
        if (ch == '\n' || ch == '\r') {
            flush(buf, out)
            continue
        }
        buf.append(ch)
        if (ch in SENTENCE_END) flush(buf, out)
    }
    flush(buf, out)
    return out.ifEmpty { if (text.isBlank()) emptyList() else listOf(text.trim()) }
}

private fun flush(buf: StringBuilder, out: MutableList<String>) {
    val s = buf.toString().trim()
    buf.setLength(0)
    if (s.isNotEmpty()) out += s
}

/** 中文句末 + 英文句末 + 省略号。省略号在中文里常写成两个字符 ……，逐字符判也能切开。 */
private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', '…', '．')
