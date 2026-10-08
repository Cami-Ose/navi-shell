package com.navi.shell

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 界面那一路：**输地名 → 点「去」→ 弹出路线**。
 *
 * 为什么这条要用 Compose 测试写：adb 打不进中文（`input text "天安门"` 会直接崩），
 * 所以之前只能停在「底层算路验过了，但界面接线没验」。
 * Compose 的 performTextInput 是**直接往输入框塞文本**，绕开输入法 —— 中文也能塞。
 */
@RunWith(AndroidJUnit4::class)
class NaviUiTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun 输地名点去能弹出路线() {
        // 首次启动会先出隐私同意页
        val consent = rule.onAllNodesWithText("同意").fetchSemanticsNodes()
        if (consent.isNotEmpty()) {
            rule.onNodeWithText("同意").performClick()
            rule.waitForIdle()
        }

        // 等定位回来 —— 没定位到，「去」按钮是灰的
        rule.waitUntil(timeoutMillis = 30_000) {
            rule.onAllNodesWithText("正在定位…").fetchSemanticsNodes().isEmpty()
        }

        // 往输入框塞一个真地名（颐和园，离模拟器所在的王府井一带二十来公里）
        rule.onNode(hasSetTextAction()).performTextInput("颐和园")
        rule.waitForIdle()

        rule.onNodeWithText("去").performClick()

        // 等路线摘要出来（「全程 xx · 约 xx」）
        rule.waitUntil(timeoutMillis = 40_000) {
            rule.onAllNodesWithText("全程", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        val summary = rule.onAllNodesWithText("全程", substring = true)
            .fetchSemanticsNodes()
        assertTrue("路线摘要出来了但节点是空的", summary.isNotEmpty())

        // 「开始导航」得在
        assertTrue(
            "没看到「开始导航」按钮",
            rule.onAllNodesWithText("开始导航").fetchSemanticsNodes().isNotEmpty(),
        )

        // 把这一刻存下来。断言只能证明「节点在」，存张图才知道**长什么样** ——
        // 路线画没画出来、转向面板对不对，得眼睛看。存到 cacheDir，外面用 run-as 取。
        //
        // 等几秒再拍：断言一过就拍的话，地图瓦片和折线还没画上来，
        // 拍出来是一张空白底图，看着像功能坏了（踩过）。
        Thread.sleep(7000)
        capture("route.png")
    }

    private fun capture(name: String) {
        runCatching {
            val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            java.io.File(ctx.cacheDir, name).outputStream().use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
