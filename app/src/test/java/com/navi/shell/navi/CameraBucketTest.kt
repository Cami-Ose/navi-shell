package com.navi.shell.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 限速/服务区提醒的「报一次」分档。
 *
 * 这段坏起来是**静默的**，两种坏法都不报错：
 *   档太粗 → 报了第一次之后再响都不响（你会以为功能没做）
 *   档太细 → 一路念个不停（开车时聒噪，比不报还烦）
 */
class CameraBucketTest {

    @Test
    fun `同一个档里距离变了也只算一档`() {
        // 800 米和 850 米应该同档（都在 200 米档里）
        assertEquals(cameraBucket(800), cameraBucket(850))
        // 但 800 和 1100 必须换档，否则近了也不提醒
        assertNotEquals(cameraBucket(800), cameraBucket(1100))
    }

    @Test
    fun `越近档越密`() {
        // 远：每公里一档
        assertEquals(3, cameraBucket(3500))
        assertEquals(4, cameraBucket(4500))
        // 近：每 200 米一档
        assertEquals(3, cameraBucket(700))
        assertEquals(4, cameraBucket(900))
    }

    @Test
    fun `跨过两公里时档不会倒退`() {
        // 1999/200 → 9，2100/1000 → 2 —— 数会变小，但**只要跟上一档不等就会播**
        // 这条测的是「不会因为变小就被当成同一档而漏报」
        assertNotEquals(cameraBucket(1999), cameraBucket(2100))
    }

    @Test
    fun `服务区没那么勤`() {
        assertEquals(serviceBucket(1800), serviceBucket(1900))   // 500 米档内
        assertNotEquals(serviceBucket(1800), serviceBucket(2400))
        assertEquals(3, serviceBucket(6500))                     // 远处每 2 公里档
    }
}
