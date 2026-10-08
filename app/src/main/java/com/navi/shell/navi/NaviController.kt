package com.navi.shell.navi

import android.content.Context
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.enums.BroadcastMode
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviPath
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviLatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 当前这一步要转的向（转向面板用）。 */
data class TurnState(
    val iconType: Int = 0,
    val stepRetain: Int = 0,      // 到下一个动作还有多少米
    val pathRetain: Int = 0,      // 全程还剩多少米
    val pathRetainTime: Int = 0,  // 全程还剩多少秒
    val currentRoad: String = "",
    val nextRoad: String = "",
    val speed: Int = 0,           // km/h
    val lights: Int = 0,          // 前方红绿灯数
)

/** 算出来的这条路长什么样（开始导航前给他看）。 */
data class RouteSummary(
    val distance: Int = 0,   // 米
    val time: Int = 0,       // 秒
    val lights: Int = 0,
    val toll: Int = 0,       // 元
)

/** 要播的话。type 见 NaviTTSType，预录音频靠它分类。 */
data class VoiceLine(val type: Int, val text: String)

/** 前方有摄像头。speed 是那条路的限速（0 = 没测到）。 */
data class CameraAlert(val distance: Int, val speed: Int, val type: Int)

/** 前方有服务区。 */
data class ServiceAlert(val name: String, val remainDist: Int, val type: Int)

/**
 * 要提醒一件事。key 是预录词表里的键（有就用现成的那句，没有就实时合成 fallback）。
 * 之所以让 UI 决定怎么出声：预录的调用在 VoiceSession 那边。
 */
typealias AlertSink = (key: String, fallback: String) -> Unit

data class NaviUiState(
    val routing: Boolean = false,
    val navigating: Boolean = false,
    val arrived: Boolean = false,
    val gpsWeak: Boolean = false,
    val route: RouteSummary? = null,
    val turn: TurnState? = null,
    val lastVoice: VoiceLine? = null,
    val error: String? = null,
    /** 前方摄像头（限速提醒）。导航里会一直更新。 */
    val camera: CameraAlert? = null,
    /** 前方服务区。 */
    val service: ServiceAlert? = null,
    /** 车头朝哪（度，正北为 0）。地图跟着它转、指南针也靠它。 */
    val bearing: Float = 0f,
    /** 海拔（米）。看立交桥上下层、坡道用的。0 或负数当没测到。 */
    val altitude: Double = 0.0,
    /** 路线坐标，给地图画线用 */
    val pathCoords: List<NaviLatLng> = emptyList(),
    /** 这次是按什么算的路（开车/走路/骑车）。界面靠它决定显不显示车速表。 */
    val mode: TravelMode = TravelMode.DRIVE,
)

/**
 * 高德导航的封装。
 *
 * 关键一条：**关掉高德内置播报**（setUseInnerVoice(false)），
 * 这样 SDK 不再自己出声，而是把要播的话从 onGetNavigationText 交给我们，
 * 由它来说。这也是我们能做预录音频的前提。
 */
class NaviController(context: Context) {

    private val navi: AMapNavi = AMapNavi.getInstance(context.applicationContext)

    private val _state = MutableStateFlow(NaviUiState())
    val state: StateFlow<NaviUiState> = _state.asStateFlow()

    /** 导航中位置变化，给地图动车标用。不在 state 里，避免每 100ms 重组一次 Compose。 */
    @Volatile
    var lastLocation: AMapNaviLocation? = null
        private set

    /** 要播一句话时回调（type, text）。 */
    var onSpeak: ((Int, String) -> Unit)? = null

    /** 要提醒一件事（限速/服务区）。key 是预录词表里的键，UI 决定用预录还是实时合成。 */
    var onAlert: AlertSink? = null

    // 已经报过的档位，避免同一件事每秒重复念
    private var announcedCameraBucket = -1
    private var announcedServiceBucket = -1

    /** 这次出行的账本。开始导航时开张，结束时交账。 */
    val trip = TripRecorder()

    private val listener = object : NaviListenerAdapter() {

        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
            val path: AMapNaviPath = navi.naviPath ?: run {
                _state.update { it.copy(routing = false, error = "算出了路但拿不到路线") }
                return
            }
            _state.update {
                it.copy(
                    routing = false,
                    error = null,
                    route = RouteSummary(
                        distance = path.allLength,
                        time = path.allTime,
                        lights = path.trafficLightCount,
                        toll = path.tollCost,
                    ),
                    pathCoords = path.steps.flatMap { s -> s.coords ?: emptyList() },
                )
            }
        }

        override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
            _state.update {
                it.copy(
                    routing = false,
                    error = "算路失败：${result?.errorDescription ?: result?.errorCode ?: "未知原因"}",
                )
            }
        }

        override fun onStartNavi(p0: Int) {
            _state.update { it.copy(navigating = true, arrived = false) }
        }

        override fun onNaviInfoUpdate(p0: NaviInfo?) {
            val info = p0 ?: return
            _state.update {
                it.copy(
                    turn = TurnState(
                        iconType = info.iconType,
                        stepRetain = info.curStepRetainDistance,
                        pathRetain = info.pathRetainDistance,
                        pathRetainTime = info.pathRetainTime,
                        currentRoad = info.currentRoadName.orEmpty(),
                        nextRoad = info.nextRoadName.orEmpty(),
                        speed = info.currentSpeed,
                        lights = info.routeRemainLightCount,
                    )
                )
            }
        }

        override fun onLocationChange(p0: AMapNaviLocation?) {
            lastLocation = p0
            p0?.let {
                trip.onLocation(it)
                // 约每秒一次，进 state 不会把 Compose 累着
                _state.update { s -> s.copy(bearing = it.bearing, altitude = it.altitude) }
            }
        }

        override fun onGetNavigationText(type: Int, text: String?) {
            val t = text?.trim().orEmpty()
            if (t.isEmpty()) return
            _state.update { it.copy(lastVoice = VoiceLine(type, t)) }
            onSpeak?.invoke(type, t)
        }

        override fun onArriveDestination() {
            _state.update { it.copy(arrived = true, navigating = false) }
        }

        override fun onReCalculateRouteForYaw() {
            _state.update { it.copy(error = "走偏了，正在重新算路") }
        }

        override fun onReCalculateRouteForTrafficJam() {
            _state.update { it.copy(error = "前面堵了，给你换条路") }
        }

        override fun onGpsSignalWeak(p0: Boolean) {
            _state.update { it.copy(gpsWeak = p0) }
        }

        // ---- 限速摄像头：高德一直在报，我们按档位播一次，别每秒念 ----
        override fun updateCameraInfo(p0: Array<out AMapNaviCameraInfo>?) {
            val c = p0?.asSequence()
                ?.filter { it.cameraDistance > 0 }
                ?.minByOrNull { it.cameraDistance }
                ?: return
            val bucket = cameraBucket(c.cameraDistance)
            _state.update { it.copy(camera = CameraAlert(c.cameraDistance, c.cameraSpeed, c.cameraType)) }
            if (bucket == announcedCameraBucket) return
            announcedCameraBucket = bucket
            val limitTxt = if (c.cameraSpeed > 0) "限速 ${c.cameraSpeed}" else "有测速"
            // ★ 报得出具体限速值的时候**不用预录** —— 预录那句是笼统的「前面有测速」，
            //   而「限速 60」才是真有用的信息。key 给空串，调用方就会走实时合成。
            onAlert?.invoke(
                if (c.cameraSpeed > 0) "" else "speed_camera",
                "前方$limitTxt，注意点。",
            )
        }

        // ---- 服务区 ----
        override fun onServiceAreaUpdate(p0: Array<out AMapServiceAreaInfo>?) {
            val s = p0?.asSequence()
                ?.filter { it.remainDist > 0 && !it.name.isNullOrBlank() }
                ?.minByOrNull { it.remainDist }
                ?: return
            val bucket = serviceBucket(s.remainDist)
            _state.update { it.copy(service = ServiceAlert(s.name.orEmpty(), s.remainDist, s.type)) }
            if (bucket == announcedServiceBucket) return
            announcedServiceBucket = bucket
            onAlert?.invoke("", "前面 ${fmtKm(s.remainDist)} 有 ${s.name} 服务区。")
        }
    }

    private fun fmtKm(m: Int): String =
        if (m >= 1000) String.format("%.1f 公里", m / 1000.0) else "$m 米"

    init {
        // 这两行是「让它来说」能上场的先决条件，别删
        navi.setUseInnerVoice(false)
        navi.setBroadcastMode(BroadcastMode.CONCISE)
        navi.addAMapNaviListener(listener)
        // 导航时屏幕别灭 —— 灭了就等于瞎了
        runCatching { navi.naviSetting.setScreenAlwaysBright(true) }
        // 摄像头（限速）和服务区：默认是关的，不开就收不到回调
        runCatching { navi.naviSetting.setMonitorCameraEnabled(true) }
        runCatching { navi.setCameraInfoUpdateEnabled(true) }
        runCatching { navi.setServiceAreaDetailsEnable(true) }
    }

    /**
     * 按模式算路。**三种模式是真换一条路算**，不是同一条路换个说法 ——
     * 步行会钻胡同、骑行避开快速路、开车才有红绿灯和收费。
     */
    fun calcRoute(mode: TravelMode, from: NaviLatLng, to: NaviLatLng) {
        _state.update {
            it.copy(routing = true, error = null, route = null, turn = null, arrived = false, mode = mode)
        }
        when (mode) {
            TravelMode.DRIVE ->
                navi.calculateDriveRoute(listOf(from), listOf(to), null, PathPlanningStrategy.DRIVING_DEFAULT)
            TravelMode.WALK -> navi.calculateWalkRoute(from, to)
            TravelMode.RIDE -> navi.calculateRideRoute(from, to)
        }
    }

    /** 开始在真实 GPS 下导航。 */
    fun startGpsNavi(): Boolean {
        resetAlertBuckets()
        return navi.startNavi(NaviType.GPS)
    }

    /** 开张记账：这一趟从哪到哪、怎么去的。 */
    fun startTrip(fromName: String, toName: String, mode: TravelMode) =
        trip.start(fromName, toName, mode)

    /** 清掉「已经报过」的档位 —— 重开一趟导航，之前报过的不算数。 */
    private fun resetAlertBuckets() {
        announcedCameraBucket = -1
        announcedServiceBucket = -1
    }

    /** 交账，拿这一趟的记录。没真的导航过就返回 null。 */
    fun finishTrip(): com.navi.shell.data.NaviTrip? = trip.finish()

    fun stopNavi() {
        navi.stopNavi()
        _state.update { it.copy(navigating = false, turn = null, gpsWeak = false) }
    }

    /** 清掉算出来的路，回到「选目的地」状态。 */
    fun resetRoute() {
        stopNavi()
        _state.update {
            it.copy(route = null, turn = null, pathCoords = emptyList(), lastVoice = null, error = null, arrived = false)
        }
    }

    /** 退出页面时收摊，避免高德单例残留。 */
    fun release() {
        navi.removeAMapNaviListener(listener)
        navi.stopNavi()
    }
}

/**
 * 把距离归到「档」上：同一个档只报一次。
 *
 * 2 公里以外每 1 公里一档，近了每 200 米一档 —— 越近报得越勤，
 * 但**绝不能每秒都报**（它以为坏了，而且开车时聒噪）。
 *
 * 放成顶层 internal 是为了能被单测直接调到 —— 这段坏起来是「静默的」：
 * 档太粗 = 只报一次就再也不响；档太细 = 一路念个不停。
 */
internal fun cameraBucket(m: Int): Int = if (m > 2000) m / 1000 else m / 200

/** 服务区不用报那么勤：2 公里以内每 500 米一档，之外每 2 公里一档。 */
internal fun serviceBucket(m: Int): Int = if (m > 2000) m / 2000 else m / 500
