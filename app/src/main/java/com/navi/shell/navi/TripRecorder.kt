package com.navi.shell.navi

import com.amap.api.navi.model.AMapNaviLocation
import com.navi.shell.data.NaviTrip
import com.navi.shell.data.TrackPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 记一次出行：从点了「开始导航」到停下。
 *
 * 里程记的是**真开出去的距离**（把每个 GPS 点之间的位移加起来），不是高德算的那条计划里程 ——
 * 走偏、绕路、中途改道它都算得进去。
 *
 * 轨迹点**存了**（为了回放和「全部路线」）。
 * 但不是每个 GPS 点都留 —— 走够 [TRACK_MIN_METERS] 才记一个，
 * 否则两小时车程能攒下几万个点，传上去又慢又占地方。
 */
class TripRecorder {

    private var startAt: Long = 0
    private var lastLat = Double.NaN
    private var lastLng = Double.NaN
    private var meters: Double = 0.0

    /** 抽稀后的轨迹点。 */
    private val track = mutableListOf<TrackPoint>()

    /** 上一个**记下来**的点（跟 lastLat/lastLng 不是一回事：那个是每个 GPS 点都更新）。 */
    private var anchorLat = Double.NaN
    private var anchorLng = Double.NaN

    val isRecording: Boolean get() = startAt > 0

    fun start(fromName: String, toName: String, mode: TravelMode) {
        startAt = System.currentTimeMillis()
        fromNameCache = fromName
        toNameCache = toName
        modeCache = mode.name.lowercase()
        lastLat = Double.NaN
        lastLng = Double.NaN
        anchorLat = Double.NaN
        anchorLng = Double.NaN
        meters = 0.0
        track.clear()
    }

    private var fromNameCache = ""
    private var toNameCache = ""
    private var modeCache = "drive"

    /** 每个定位点喂进来。 */
    fun onLocation(loc: AMapNaviLocation) {
        if (!isRecording) return
        val c = loc.coord ?: return
        val lat = c.latitude
        val lng = c.longitude
        if (!lastLat.isNaN()) {
            val d = haversine(lastLat, lastLng, lat, lng)
            // 抖动（<8米）当噪声；跳变（>1公里/次）当丢星漂移，都不计
            if (d in 8.0..1000.0) meters += d
        }
        lastLat = lat
        lastLng = lng

        // ---- 轨迹抽稀：走够 30 米才记一个点 ----
        if (track.size >= MAX_TRACK_POINTS) return   // 上限兜底，别让一趟车把内存撑爆
        if (anchorLat.isNaN() || haversine(anchorLat, anchorLng, lat, lng) >= TRACK_MIN_METERS) {
            val t = ((System.currentTimeMillis() - startAt) / 1000).toInt()
            track += TrackPoint(lat, lng, t)
            anchorLat = lat
            anchorLng = lng
        }
    }

    /** 结束，交出一条记录。没真的开始过就返回 null。 */
    fun finish(): NaviTrip? {
        if (!isRecording) return null
        val ended = System.currentTimeMillis()
        val trip = NaviTrip(
            id = "navi_$startAt",
            mode = modeCache,
            started_at = startAt / 1000,
            ended_at = ended / 1000,
            from_name = fromNameCache,
            to_name = toNameCache,
            distance_m = meters.toInt(),
            duration_s = ((ended - startAt) / 1000).toInt(),
            track = track.toList(),
        )
        startAt = 0
        lastLat = Double.NaN
        lastLng = Double.NaN
        anchorLat = Double.NaN
        anchorLng = Double.NaN
        meters = 0.0
        track.clear()
        return trip
    }

    private companion object {
        /**
         * 走够这么多米才记一个点。
         *
         * 30 米是算过的：市内走走停停大约每 3-5 秒一个点，两小时车程 ~1500 点、
         * 传上去几十 KB。定太小（比如每个 GPS 点都记）会攒到几万个，传输和存储都不划算；
         * 定太大（200 米）画出来的线就一段一段的，拐弯看不清。
         */
        const val TRACK_MIN_METERS = 30.0

        /** 一趟最多记这么多点，兜底防内存爆（真到上限基本是 GPS 在乱跳）。 */
        const val MAX_TRACK_POINTS = 4000
    }

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}
