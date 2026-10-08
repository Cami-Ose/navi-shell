package com.navi.shell.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 断句。
 *
 * 为什么值得一个小测试：断错了不会崩 —— 顶多是**该一起说的被切成两半**，
 * 或者**整段没切开、它要多等好几秒才出声**。这两种都是「不报错的坏」，
 * 而开车时那几秒等待是能感觉到的。
 */
class SpeechTest {

    @Test
    fun `按中文句末切开`() {
        assertEquals(
            listOf("好，出发。", "気をつけて。"),
            splitSentences("好，出发。気をつけて。"),
        )
    }

    @Test
    fun `问号感叹号省略号都算句末`() {
        assertEquals(listOf("真的？", "太好了！", "那就这样…"), splitSentences("真的？太好了！那就这样…"))
    }

    @Test
    fun `换行也切`() {
        assertEquals(listOf("第一句", "第二句"), splitSentences("第一句\n第二句"))
    }

    @Test
    fun `结尾没有标点也算一句`() {
        assertEquals(listOf("我在", "你说吧。"), splitSentences("我在\n你说吧。"))
    }

    @Test
    fun `空白和空行不留空句子`() {
        assertEquals(listOf("一。", "二。"), splitSentences("  一。\n\n  二。  \n"))
        assertTrue("全空白应该回空表", splitSentences("   \n  ").isEmpty())
    }

    @Test
    fun `没标点的整段不会被吞掉`() {
        assertEquals(listOf("一直开到头再右拐"), splitSentences("一直开到头再右拐"))
    }
}
