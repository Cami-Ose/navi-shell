package com.navi.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.os.Build
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import com.navi.shell.navi.NaviListenerAdapter
import com.navi.shell.navi.NaviService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 在**真机/模拟器上**跑的自检。
 *
 * 为什么非要上设备：单测跑在电脑 JVM 上，碰不到高德的原生库、碰不到 assets、碰不到清单声明的那堆组件。
 * 「账面上编译通过」在上一轮已经被证明什么都说明不了 —— 定位那个坑就是编译全绿、跑起来全死。
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun 先过高德的合规口子() {
        // 不做这一步，高德 SDK 直接拒绝干活
        AmapPrivacy.apply(ctx)
    }

    /**
     * 算路。
     *
     * 这是整个 App 最要紧、也最难在电脑上验的一条：算路要 native 库 + key + 网络 + 清单，缺一样就静默失败。
     * 用坐标直接调，绕开界面（界面要输中文地名，自动化打不进去）。
     */
    @Test
    fun 算路能拿到真路线() {
        val navi = AMapNavi.getInstance(ctx)
        val latch = CountDownLatch(1)
        var path: AMapNaviPath? = null
        var err: String? = null

        navi.addAMapNaviListener(object : NaviListenerAdapter() {
            override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
                path = navi.naviPath
                latch.countDown()
            }

            override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
                err = result?.errorDescription ?: "错误码 ${result?.errorCode}"
                latch.countDown()
            }
        })

        // 天安门 → 首都机场，跨大半个北京，不可能算不出
        val from = NaviLatLng(39.9087, 116.3975)
        val to = NaviLatLng(40.0799, 116.6031)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            navi.calculateDriveRoute(listOf(from), listOf(to), null, PathPlanningStrategy.DRIVING_DEFAULT)
        }

        assertTrue("算路 20 秒没回调，八成是 key / 网络 / 清单哪个不对", latch.await(20, TimeUnit.SECONDS))
        assertNull("算路失败：$err", err)

        assertNotNull("回调成功了但 getNaviPath() 是空的", path)
        val p: AMapNaviPath = path!!
        assertTrue("全程里程不合理：${p.allLength} 米", p.allLength > 5_000)
        assertTrue("全程耗时不合理：${p.allTime} 秒", p.allTime > 300)

        // 路线坐标是画在地图上的东西，空的就等于画不出来
        val coords = p.steps.flatMap { it.coords ?: emptyList() }
        assertTrue("路线一个坐标点都没有（${p.steps.size} 段）", coords.size > 10)
    }

    /**
     * 真的把导航跑起来，看**转向数据**来不来。
     *
     * 这是「能实际开车导航」最核心的一条：算路成功 ≠ 导航能跑。
     * `startNavi(NaviType.GPS)` 之后高德才接管定位、才开始按步吐 `NaviInfo`
     * （转向图标、下一步还有多少米、当前路名）—— 我们那块转向面板全靠它。
     *
     * 刻意**不走界面**：界面点「开始导航」会让它开口（`/tts`，要花钱）。
     * 这里直接驱动 controller 那一层，验的是同一条链路，不花一分钱。
     */
    @Test
    fun 开始导航能收到转向数据() {
        val navi = AMapNavi.getInstance(ctx)
        val instr = InstrumentationRegistry.getInstrumentation()

        // ① 先算一条路出来（没路 startNavi 起不来）
        val routed = CountDownLatch(1)
        var routeErr: String? = null
        navi.addAMapNaviListener(object : NaviListenerAdapter() {
            override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) = routed.countDown()
            override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
                routeErr = result?.errorDescription ?: "码 ${result?.errorCode}"
                routed.countDown()
            }
        })
        instr.runOnMainSync {
            navi.calculateDriveRoute(
                listOf(NaviLatLng(39.9087, 116.3975)),
                listOf(NaviLatLng(39.9087, 116.3975)),
                null,
                PathPlanningStrategy.DRIVING_DEFAULT,
            )
        }
        // 起终点一样可能算不出，那就换一段短的
        if (!routed.await(20, TimeUnit.SECONDS) || routeErr != null) {
            val again = CountDownLatch(1)
            navi.addAMapNaviListener(object : NaviListenerAdapter() {
                override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) = again.countDown()
                override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) = again.countDown()
            })
            instr.runOnMainSync {
                navi.calculateDriveRoute(
                    listOf(NaviLatLng(39.9087, 116.3975)),
                    listOf(NaviLatLng(39.9500, 116.4500)),
                    null,
                    PathPlanningStrategy.DRIVING_DEFAULT,
                )
            }
            assertTrue("算路没成，后面没法验", again.await(20, TimeUnit.SECONDS))
        }

        // ② 监听器要在 startNavi **之前**挂上，不然第一条 NaviInfo 会漏掉
        val started = CountDownLatch(1)
        val gotInfo = CountDownLatch(1)
        var info: NaviInfo? = null
        navi.addAMapNaviListener(object : NaviListenerAdapter() {
            override fun onStartNavi(p0: Int) = started.countDown()
            override fun onNaviInfoUpdate(p0: NaviInfo?) {
                if (p0 != null) {
                    info = p0
                    gotInfo.countDown()
                }
            }
        })

        var ok = false
        instr.runOnMainSync { ok = navi.startNavi(NaviType.GPS) }
        assertTrue("startNavi 直接返回了 false", ok)
        assertTrue("startNavi 没回 onStartNavi", started.await(15, TimeUnit.SECONDS))

        assertTrue("导航起来了但 25 秒没吐转向数据（NaviInfo）", gotInfo.await(25, TimeUnit.SECONDS))
        val i: NaviInfo = info!!
        // 全程剩余里程得是个正经数 —— 0 或负数说明其实没在导航
        assertTrue("转向数据里全程剩余里程是 ${i.pathRetainDistance}", i.pathRetainDistance > 0)
        assertTrue("当前路名是空的（${i.currentRoadName}）", !i.currentRoadName.isNullOrBlank())

        instr.runOnMainSync { navi.stopNavi() }
    }

    /**
     * 预录词表在设备上读不读得到。
     *
     * 单测是直接读工程里的文件，**没验过 assets 这条路**。
     * assets 读不到的话，App 不会崩 —— 它会静默把所有播报都退化成实时 TTS，
     * 表现就是「能出声，但慢」，不专门看根本发现不了。
     */
    @Test
    fun 预录词表在设备上读得到() {
        val script = com.navi.shell.voice.BroadcastScript(ctx)
        assertTrue("词表没读出来（assets/voice/phrases.json）", script.size == 133)

        // 高德爱用阿拉伯数字，词表是中文数字，两边必须对得上
        assertTrue("「前方500米左转」没对上预录", script.keyFor("前方500米左转") == "left_m500")
        assertTrue("「前方一公里掉头」没对上预录", script.keyFor("前方一公里掉头") == "uturn_km1")
        assertNull("怪句子不该硬套预录", script.keyFor("沿当前道路行驶12公里经过3个红绿灯"))
    }

    /**
     * 导航前台服务起得来、也收得掉。
     *
     * 起来之后系统才不会在切走/锁屏时把进程掐了 —— 开车时被掐掉就不报转向了。
     *
     * ⚠️ 这条只在 API < 31 上如实反映。Android 12+ 起，前台服务**只能从界面前台启动**，
     * 测试是后台进程，直接调会抛 ForegroundServiceStartNotAllowedException ——
     * 那不是 App 的毛病（App 是在点「开始导航」的时候从 Activity 起的，位置合法）。
     */
    @Test
    fun 导航前台服务起得来也收得掉() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return   // 见上面那段说明

        NaviService.start(ctx)
        Thread.sleep(1500)
        assertTrue("前台服务没起来", isServiceRunning())

        NaviService.stop(ctx)
        Thread.sleep(1000)
        assertFalse("前台服务没收掉", isServiceRunning())
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return am.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == NaviService::class.java.name }
    }
}
