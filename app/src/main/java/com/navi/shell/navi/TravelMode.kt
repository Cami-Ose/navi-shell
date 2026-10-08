package com.navi.shell.navi

/**
 * 怎么去：开车 / 走路 / 骑车。
 *
 * 三种算出来的路**完全不一样** —— 不是同一条路换个说法：
 * 步行会钻胡同、能逆行单行道；骑行避开快速路；开车才有收费和红绿灯数。
 * 所以不是「显示不同」，是**真的换一条路算**。
 */
enum class TravelMode(val label: String) {
    DRIVE("开车"),
    WALK("走路"),
    RIDE("骑车"),
    ;

    /** 这个模式要不要显示车速表和红绿灯数（只有开车有意义）。 */
    val showsDrivingGauges: Boolean get() = this == DRIVE
}
