package com.navi.shell.voice

import android.content.Context
import com.navi.shell.data.AppJson
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 「这句话有没有预录」的判官。
 *
 * 高德把要播的原话交给我们（onGetNavigationText）。我们拿它去词表里对：
 *   - 对得上 → 播预录的 mp3，几乎零延迟
 *   - 对不上 → 交回实时 /tts，慢一点但不会说错
 *
 * 词表的真身是 tools/gen-voice.mjs，产物 assets/voice/phrases.json。
 * **这边不另抄一份**，词以那份为准。
 */
class BroadcastScript(private val context: Context) {

    /** key（= 音频文件名）→ 该说的那句话 */
    private val table: Map<String, String>

    /** 归一化后的文本 → key */
    private val byText: Map<String, String>

    init {
        val loaded = runCatching {
            context.assets.open("voice/phrases.json").use { ins ->
                AppJson.decodeFromString(
                    MapSerializer(String.serializer(), String.serializer()),
                    ins.readBytes().toString(Charsets.UTF_8),
                )
            }
        }.getOrDefault(emptyMap())
        table = loaded
        byText = loaded.entries.associate { (k, v) -> normalize(v) to k }
    }

    val size: Int get() = table.size

    /** 高德的这句原话有预录吗？有就回 key，没有回 null。 */
    fun keyFor(amapText: String): String? = byText[normalize(amapText)]

    /**
     * 预录音频在包里的实际路径，没有就回 null（生成脚本没跑过就是 null）。
     *
     * 两种后缀都认：后端的 `/tts` 回的是 **WAV**，但历史上缓存里也有 mp3。
     * 写死一种的话，换一边就静默失效 —— 表现是「播报突然都变慢了」（退回实时 TTS），不报错。
     */
    fun assetPath(key: String): String? =
        EXTENSIONS.firstOrNull { ext ->
            runCatching { context.assets.open("voice/$key$ext").close(); true }.getOrDefault(false)
        }?.let { "voice/$key$it" }

    fun hasAudio(key: String): Boolean = assetPath(key) != null

    /** 这个 key 该说的文本，出错提示用。 */
    fun textOf(key: String): String? = table[key]

    companion object {
        /** 先找 wav 再找 mp3（/tts 现在回 wav，老缓存里可能是 mp3）。 */
        private val EXTENSIONS = listOf(".wav", ".mp3")
        /**
         * 把两边的写法归一。
         * 高德爱用阿拉伯数字（前方500米左转），我们的词表是中文数字（前方五百米左转），
         * 不归一就永远对不上。标点空格一律去掉。
         */
        fun normalize(raw: String): String {
            var t = raw.trim()
            // 全角数字 → 半角
            t = t.map { c -> if (c in '０'..'９') ('0' + (c - '０')) else c }.joinToString("")
            // 长词在前，别让「一公里」先吃掉「一公里半」
            for ((cn, ar) in NUM_WORDS) t = t.replace(cn, ar)
            t = t.replace("千米", "公里").replace("公尺", "米")
            // 只留字和数字，标点空格全丢
            return t.filter { it.isLetterOrDigit() }
        }

        private val NUM_WORDS = listOf(
            "一公里半" to "1.5公里",
            "一公里" to "1公里",
            "两公里" to "2公里",
            "二公里" to "2公里",
            "五十米" to "50米",
            "一百米" to "100米",
            "两百米" to "200米",
            "三百米" to "300米",
            "五百米" to "500米",
            "八百米" to "800米",
        )
    }
}
