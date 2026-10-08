package com.navi.shell.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 界面上那几个数字怎么显示。
 *
 * 看着像小事，但**显示错了是会误导人的**，而且不崩、不报错，就那么一直错着。
 * 最要紧的一条是海拔：高德在没测到的时候给 0，如果照直显示「0 米」，
 * 它就以为自己在一楼 —— 而它看海拔正是为了分辨立交桥的上下层。
 */
class NavFormatTest {

    @Test
    fun `海拔没测到的时候显示 --，不是 0 米`() {
        assertEquals("--", fmtAltitude(0.0))
        assertEquals("--", fmtAltitude(0.4))
        assertEquals("--", fmtAltitude(-12.0))   // 高德没测到时会给负数
    }

    @Test
    fun `海拔正常时按米显示`() {
        assertEquals("43 米", fmtAltitude(43.2))
        assertEquals("1 米", fmtAltitude(1.0))
        assertEquals("999 米", fmtAltitude(999.9))
    }

    @Test
    fun `海拔上千换成公里`() {
        assertEquals("1.2 公里", fmtAltitude(1234.0))
    }

    @Test
    fun `距离一千米以下说米，以上说公里`() {
        assertEquals("500 米", fmtDistance(500))
        assertEquals("999 米", fmtDistance(999))
        assertEquals("1.0 公里", fmtDistance(1000))
        assertEquals("21.4 公里", fmtDistance(21400))
        assertEquals("--", fmtDistance(-1))
    }

    @Test
    fun `时长分档`() {
        assertEquals("45 秒", fmtTime(45))
        assertEquals("1 分钟", fmtTime(60))
        assertEquals("48 分钟", fmtTime(2880))
        assertEquals("1 小时 5 分", fmtTime(3900))
        assertEquals("--", fmtTime(-1))
    }
}
