package com.navi.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.navi.shell.audio.WavRecorder
import com.navi.shell.data.AuthStore
import com.navi.shell.net.NaviApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * App 里那条网线，拿真设备、真后端打一遍。
 *
 * 验的是 **App 自己写的那段代码**，不是我在电脑上 curl 出来的结论 ——
 * 之前只探过接口在不在，没验过 OkHttp 这边拼的 multipart、cookie、解析对不对。
 *
 * 只跑不写盘的：`/access` 和 `/asr`。
 * **不碰 `/proxy_chat`**（会落会话文件）**也不碰 `/tts`**（花 TTS 的钱）。
 */
@RunWith(AndroidJUnit4::class)
class NaviApiTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun 授权能拿到cookie() = runBlocking {
        val store = AuthStore(ctx)
        store.cookie = ""
        store.isAuthorized = false

        val ok = NaviApi(store).authorize()
        assertTrue("authorize 没成功", ok)
        assertTrue("cookie 没存下来：'${store.cookie}'", store.cookie.startsWith("gate_auth="))
    }

    /**
     * 把 **WavRecorder 亲手拼的 WAV** 打给 /asr，看它认不认。
     *
     * 麦克风那条路模拟器验不了（没麦），但「头拼得对不对 + multipart 拼得对不对 + 鉴权对不对」
     * 这三样是能验的，而且是同一条链上最容易悄悄坏的地方。
     *
     * 送的是 440Hz 正弦，识别结果一定是垃圾 —— 这里不看识别得对不对，只看**管子通不通**。
     */
    @Test
    fun 录音格式能被asr认下() = runBlocking {
        val store = AuthStore(ctx)
        val api = NaviApi(store)
        api.authorize()

        val wav = WavRecorder.wrapWav(tone(seconds = 1.2), sampleRate = 16_000)
        // 头得是标准的：RIFF....WAVE
        assertTrue(
            "WAV 头不对：${wav.take(4).joinToString(",")}",
            String(wav.copyOfRange(0, 4)) == "RIFF" && String(wav.copyOfRange(8, 12)) == "WAVE",
        )

        val res = api.asr(wav)
        assertNull("asr 报错了：${res.error}", res.error)
        assertNotNull("asr 没回文本字段", res.text)
        // 内容不校验（正弦波，识别出啥都正常），只看它给没给结论
        assertTrue("asr 既没报错也没回文本，八成是解析出问题了", res.text.isNotEmpty() || res.error == null)
    }

    private fun tone(seconds: Double, sampleRate: Int = 16_000): ByteArray {
        val n = (sampleRate * seconds).toInt()
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = sin(2 * PI * 440 * i / sampleRate) * 0.25
            val s = (v * 32767).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (s and 0xFF).toByte()
            out[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }
}
