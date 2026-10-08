package com.navi.shell.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 播报词表的自检。
 *
 * 这里最容易出的错不是崩溃，是**静默说错话**：
 * 高德念「前方500米左转」，词表里是「前方五百米左转」，对不上就悄悄退化成实时 TTS —— 功能看着正常，
 * 但零延迟的意义就没了，而且不看日志根本发现不了。所以这两件事必须钉死：
 *   1. 中文数字 / 阿拉伯数字两边写法能归一
 *   2. 词表里没有两条归一后撞车（撞车会静默丢掉一条）
 */
class BroadcastScriptTest {

    private fun loadPhrases(): Map<String, String> {
        // 单测直接从工程里读，跟打包进 assets 的是同一个文件
        val f = File("src/main/assets/voice/phrases.json")
        assertTrue("找不到词表：${f.absolutePath}（先在工程根跑 node tools/gen-voice.mjs --manifest-only）", f.exists())
        val text = f.readText()
        val out = mutableMapOf<String, String>()
        Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"").findAll(text).forEach {
            out[it.groupValues[1]] = it.groupValues[2]
        }
        return out
    }

    @Test
    fun `中文数字和阿拉伯数字归一`() {
        assertEquals(
            BroadcastScript.normalize("前方500米左转"),
            BroadcastScript.normalize("前方五百米左转"),
        )
        assertEquals(
            BroadcastScript.normalize("前方1公里掉头"),
            BroadcastScript.normalize("前方一公里掉头"),
        )
        // 长词优先：一公里半 不能被 一公里 先吃掉
        assertEquals(
            BroadcastScript.normalize("前方1.5公里掉头"),
            BroadcastScript.normalize("前方一公里半掉头"),
        )
        assertEquals(
            BroadcastScript.normalize("2公里"),
            BroadcastScript.normalize("两公里"),
        )
    }

    @Test
    fun `标点和空格不影响匹配`() {
        assertEquals(
            BroadcastScript.normalize("前方，500米左转。"),
            BroadcastScript.normalize("前方五百米左转"),
        )
    }

    @Test
    fun `千米和公里等价`() {
        assertEquals(
            BroadcastScript.normalize("1公里"),
            BroadcastScript.normalize("1千米"),
        )
    }

    @Test
    fun `词表里没有两条归一后撞车`() {
        val phrases = loadPhrases()
        assertTrue("词表是空的", phrases.isNotEmpty())
        val seen = mutableMapOf<String, String>()
        val clashes = mutableListOf<String>()
        for ((key, text) in phrases) {
            val n = BroadcastScript.normalize(text)
            val prev = seen.put(n, key)
            if (prev != null) clashes += "$prev 和 $key 归一后都是「$n」"
        }
        assertTrue("词表撞车：\n" + clashes.joinToString("\n"), clashes.isEmpty())
    }

    @Test
    fun `高德真会说的话能对上预录`() {
        val phrases = loadPhrases()
        val byText = phrases.entries.associate { (k, v) -> BroadcastScript.normalize(v) to k }

        // 高德播报常见的几种写法，都要能落到预录上
        assertEquals("left_m500", byText[BroadcastScript.normalize("前方500米左转")])
        assertEquals("right_m200", byText[BroadcastScript.normalize("前方两百米右转")])
        assertEquals("uturn_km1", byText[BroadcastScript.normalize("前方一公里掉头")])
        assertEquals("keep_left_m100", byText[BroadcastScript.normalize("前方一百米靠左")])
        assertEquals("ring_in_m300", byText[BroadcastScript.normalize("前方300米进入环岛")])
        // 对不上的怪句子必须老实回 null，好让调用方走实时 TTS（不能瞎套一句预录）
        assertNull(byText[BroadcastScript.normalize("沿当前道路行驶12公里经过3个红绿灯")])

        // 整句那几条**不写死文本** —— 词是我改的（中文掺了日语），
        // 写死的话每改一次词就红一次，测的就不是逻辑了。改成「自己那一条能对上自己」。
        for (k in listOf("navi_start", "navi_end", "recalc_yaw", "recalc_jam", "gps_weak", "speed_camera", "arrive_near")) {
            val t = phrases[k]
            assertNotNull("词表里没有整句 $k", t)
            assertEquals("整句 $k 对不回自己", k, byText[BroadcastScript.normalize(t!!)])
        }
    }

    @Test
    fun `词表长这样`() {
        val phrases = loadPhrases()
        // 14 个动作 × 9 个距离 + 7 条整句
        assertEquals(14 * 9 + 7, phrases.size)
        assertNotNull(phrases["left_m50"])
        assertNotNull(phrases["navi_end"])
    }
}
