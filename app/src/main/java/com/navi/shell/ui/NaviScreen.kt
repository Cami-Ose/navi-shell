package com.navi.shell.ui

import android.content.Context
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.navi.enums.IconType
import com.amap.api.navi.model.NaviLatLng
import com.amap.api.services.geocoder.GeocodeAddress
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeResult
import com.navi.shell.R
import com.navi.shell.data.NaviTrip
import com.navi.shell.data.PlaceStore
import com.navi.shell.location.HeadingSensor
import com.navi.shell.location.Locator
import com.navi.shell.navi.NaviController
import com.navi.shell.navi.NaviService
import com.navi.shell.navi.TravelMode
import com.navi.shell.navi.TurnState
import com.navi.shell.voice.VoicePhase
import com.navi.shell.voice.VoiceSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NaviScreen(context: Context) {
    val controller = remember { NaviController(context) }
    val locator = remember { Locator(context) }
    val places = remember { PlaceStore(context) }
    val heading = remember { HeadingSensor(context) }
    val voice = remember { VoiceSession(context, "navi_${System.currentTimeMillis()}") }
    val state by controller.state.collectAsState()
    val voiceState by voice.state.collectAsState()
    val deviceAzimuth by heading.azimuth.collectAsState()

    /**
     * 光标朝哪转。
     *
     * 导航中用车头（GPS 航向更稳、不受手机在支架上歪没歪影响），
     * 平时用**手机朝向** —— 原来只看 GPS 航向，而它不动就不更新，
     * 于是你有事没事转个手机，箭头纹丝不动（实测就是这么发现的）。
     */
    val bearing = if (state.navigating) state.bearing else deviceAzimuth

    var meLatLng by remember { mutableStateOf<LatLng?>(null) }
    var meName by remember { mutableStateOf("") }
    var meCity by remember { mutableStateOf("") }
    var destText by remember { mutableStateOf("") }
    var destName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf(places.favorites()) }
    var recentList by remember { mutableStateOf(places.recents()) }
    var sheetUp by remember { mutableStateOf(false) }
    var travelMode by remember { mutableStateOf(TravelMode.DRIVE) }
    var meMarker by remember { mutableStateOf<Marker?>(null) }

    // ---- 足迹 ----
    var footprintsOpen by remember { mutableStateOf(false) }
    var trips by remember { mutableStateOf<List<NaviTrip>>(emptyList()) }
    var footprintsLoading by remember { mutableStateOf(false) }
    var tracksOn by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    /** 画在图上还没收掉的足迹图层（清的时候只收这些，不碰导航路线）。 */
    val fpLines = remember { mutableListOf<Polyline>() }

    val mapView = remember { MapView(context).also { it.onCreate(Bundle()) } }
    val amap = remember { mapView.map }

    DisposableEffect(Unit) {
        mapView.onResume()
        heading.start()
        // 使用高德默认地图样式。需要深色底图时改成 AMap.MAP_TYPE_NIGHT
        amap.mapType = AMap.MAP_TYPE_NORMAL
        amap.uiSettings.apply {
            isZoomControlsEnabled = false
            isCompassEnabled = false          // 方向指示由界面自己画，位置才好摆
            isMyLocationButtonEnabled = false
            isScaleControlsEnabled = false
        }
        locator.once { loc ->
            if (loc != null && loc.errorCode == 0) {
                val p = LatLng(loc.latitude, loc.longitude)
                meLatLng = p
                meName = loc.address ?: loc.city ?: ""
                // 搜目的地时拿它限定城市。不限的话「颐和园」可能在几百公里外给你找着一个同名的，
                // 算出来就是「全程 621 公里」—— 实测踩过。
                meCity = loc.city.orEmpty()
                amap.moveCamera(CameraUpdateFactory.newLatLngZoom(p, 16f))
            }
        }
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
            locator.destroy()
            heading.stop()
            voice.release()
            controller.release()
            NaviService.stop(context)
        }
    }

    // 高德说该播什么，我们决定谁来播（预录优先、实时兜底）
    LaunchedEffect(Unit) {
        controller.onSpeak = { _, text -> voice.speakNavigation(text) }
        // 限速/服务区提醒：词表里有现成的就用现成的，没有才实时合成
        controller.onAlert = { key, fallback -> voice.speakFixed(key, fallback) }
    }

    // 打开足迹页时才去拉列表（不打开不打扰）
    LaunchedEffect(footprintsOpen) {
        if (!footprintsOpen) return@LaunchedEffect
        footprintsLoading = true
        trips = voice.loadTrips()
        footprintsLoading = false
    }

    /**
     * 收掉图上的足迹图层。
     * **只收足迹自己加的**，不调 amap.clear() —— 那会把导航路线和位置光标一起抹掉。
     */
    fun clearFootprints() {
        fpLines.forEach { runCatching { it.remove() } }
        fpLines.clear()
        tracksOn = false
    }

    /** 点一条记录：把它的那条线画出来。 */
    fun drawTrip(t: NaviTrip) {
        scope.launch {
            val pts = voice.loadTrack(t.id)
            val latLngs = pts.mapNotNull { if (it.size >= 2) LatLng(it[0], it[1]) else null }
            if (latLngs.size < 2) return@launch
            clearFootprints()
            fpLines += amap.addPolyline(
                PolylineOptions().addAll(latLngs).width(16f).color(UiColors.Accent.toArgb()).zIndex(30f)
            )
            val b = LatLngBounds.builder().apply { latLngs.forEach { include(it) } }.build()
            amap.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 140))
            footprintsOpen = false   // 收起来才看得见图
        }
    }

    /** 全部路线：一趟一条线叠着画。 */
    fun toggleTracks() {
        if (tracksOn) {
            clearFootprints()
            return
        }
        scope.launch {
            val all = voice.loadTracks()
            clearFootprints()
            if (all.isEmpty()) {
                localError = "还没有存下路线"
                return@launch
            }
            // 画法：**画线不画点**，一趟一条细线，用**半透明叠加**表达密度。
            // 线细（10）、不透明度约三成、同色 —— 走一次很淡，
            // 常走的路段叠加后变亮，走向保持在地图上。
            //
            // 颜色取界面强调色，整张图单一色相，在浅色和深色底图上都清楚。
            val bounds = LatLngBounds.builder()
            var drawn = 0
            all.forEach { pts ->
                val latLngs = pts.mapNotNull { if (it.size >= 2) LatLng(it[0], it[1]) else null }
                if (latLngs.size < 2) return@forEach
                fpLines += amap.addPolyline(
                    PolylineOptions()
                        .addAll(latLngs)
                        .width(10f)
                        .color((UiColors.Accent.copy(alpha = 0.35f)).toArgb())
                        .zIndex(20f)
                )
                latLngs.forEach { bounds.include(it) }
                drawn++
            }
            if (drawn == 0) {
                localError = "还没有存下路线"
                return@launch
            }
            tracksOn = true
            amap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 140))
            footprintsOpen = false
        }
    }

    // 这里原本还有一个「热力图」按钮（把所有轨迹点堆成点云上色）。
    // 点云不管怎么调色都是一坨糊状光斑，删掉了 —— 看密度用上面那个「全部路线」就够。
    //后端如果还留着 /api/navi/footprints 接口，不调它就行。

    // 告诉它现在是怎么去的 —— 走路时它不该说「你在车上」
    LaunchedEffect(state.mode) {
        voice.setTravelMode(state.mode.name.lowercase())
    }

    // 把后端上的常用地点拉下来。**服务端那份才是真身**（后端也在写它），
    // 本地只是缓存：拉回来就把本地的补上，拉不到就用本地的。
    LaunchedEffect(Unit) {
        val remote = voice.loadRemotePlaces()
        if (remote.isNotEmpty()) {
            remote.forEach { places.addFavorite(it) }   // 本地是「新的在前」，逐个加会把顺序倒过来
            favorites = places.favorites()
        }
    }

    // 到了就说一句，顺手把这趟记下来
    LaunchedEffect(state.arrived) {
        if (state.arrived) {
            NaviService.stop(context)
            voice.speakFixed("navi_end", "到了，就这儿。")
            controller.finishTrip()?.let { voice.reportTrip(it) }
            voice.setNaviMode(false)
        }
    }

    // 路线画出来
    LaunchedEffect(state.pathCoords, meLatLng) {
        amap.clear()
        meMarker = null
        val coords = state.pathCoords
        if (coords.size >= 2) {
            val pts = coords.map { LatLng(it.latitude, it.longitude) }
            amap.addPolyline(
                PolylineOptions().addAll(pts).width(20f).color(UiColors.Accent.toArgb()).zIndex(2f)
            )
            val bounds = LatLngBounds.builder().apply { pts.forEach { include(it) } }.build()
            amap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 130))
            // 终点：高德默认标记
            amap.addMarker(MarkerOptions().position(pts.last()).zIndex(9f))
        }
        // 我在这。最后加 = 压在最上层。用高德默认标记
        meLatLng?.let { p ->
            meMarker = amap.addMarker(
                MarkerOptions().position(p)
                    .icon(
                        BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
                    )
                    .zIndex(20f)
            )
        }
    }

    // 导航中：光标跟着车走（地图镜头也跟）
    LaunchedEffect(state.navigating) {
        if (!state.navigating) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1500)
            val c = controller.lastLocation?.coord ?: continue
            meMarker?.position = LatLng(c.latitude, c.longitude)
        }
    }

    // ★ 高德的「高德地图」logo 是条款要求必须完整露出的，不能被我们自己的卡片压住。
    // 底部那块东西时高时矮，所以跟着状态把 logo 往上抬。
    LaunchedEffect(state.navigating, state.route) {
        val d = context.resources.displayMetrics.density
        val bottomDp = when {
            state.navigating -> 108
            state.route != null -> 196
            else -> 236
        }
        amap.uiSettings.setLogoBottomMargin((bottomDp * d).toInt())
        amap.uiSettings.setLogoLeftMargin((10 * d).toInt())
    }

    // 导航中：地图跟着车头转、压成 3D 视角 —— 这是「像个导航软件」的一半
    LaunchedEffect(state.bearing, state.navigating) {
        if (!state.navigating) return@LaunchedEffect
        controller.lastLocation?.coord?.let { c ->
            amap.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder()
                        .target(LatLng(c.latitude, c.longitude))
                        .bearing(state.bearing)
                        .tilt(48f)
                        .zoom(17f)
                        .build()
                ),
                900,
                null,
            )
        }
    }

    // 导航中每 2 秒读一眼位置（avoid 依赖 state 变化——高德不动时位置也在变）
    LaunchedEffect(state.navigating) {
        while (state.navigating) {
            delay(2000)
            controller.lastLocation?.coord?.let { c ->
                amap.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.builder()
                            .target(LatLng(c.latitude, c.longitude))
                            .bearing(controller.state.value.bearing)
                            .tilt(48f)
                            .zoom(17f)
                            .build()
                    ),
                    900,
                    null,
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().background(UiColors.Background)) {

        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        val navigating = state.navigating

        // ---- 导航中：顶部转向条 + 数据 + 指南针 ----
        if (navigating) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.turn?.let { TurnBanner(it) }
                // 限速 / 服务区：有就插一条，没有就不占地方
                state.camera?.let { c ->
                    HintStrip(
                        text = "📷 前方 " + fmtDistance(c.distance) +
                            if (c.speed > 0) " · 限速 ${c.speed}" else "",
                        tint = UiColors.Error,
                    )
                }
                state.service?.let { sv ->
                    HintStrip(
                        text = "⛽ 前方 " + fmtDistance(sv.remainDist) + " · " + sv.name,
                        tint = UiColors.Warning,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    state.turn?.let { NavStats(it, state.altitude, state.mode.showsDrivingGauges) }
                    Compass(bearing)
                }
            }
        }

        // ---- 导航中：右下大圆速度表（只有开车才有意义）----
        if (navigating && state.mode.showsDrivingGauges) {
            SpeedDial(
                speed = state.turn?.speed ?: 0,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp),
            )
        }

        // ---- 右下：回到我的位置 ----
        if (!navigating) {
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = if (state.route != null) 176.dp else 224.dp)
                    .size(46.dp)
                    .background(UiColors.Surface, CircleShape)
                    .border(1.dp, UiColors.Accent.copy(alpha = 0.5f), CircleShape)
                    .clickable {
                        meLatLng?.let { amap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 16f)) }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text("定位", color = UiColors.Accent, fontSize = 11.sp)
            }
        }

        // ---- 顶部：出发前那条细信息带（导航中有转向条顶着，就不用它了）----
        if (!navigating) {
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)
                    .background(UiColors.Surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("navi-shell", color = UiColors.AccentBright, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text("·", color = UiColors.TextDim, fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (meLatLng == null) "正在定位…" else "已定位",
                    color = if (meLatLng == null) UiColors.Warning else UiColors.TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                // 传感器没有的话直说 —— 不然「转手机箭头不动」会被当成 bug
                Text(
                    if (heading.available) "罗盘 ✓" else "无罗盘",
                    color = UiColors.TextDim,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.background(UiColors.Background, RoundedCornerShape(10.dp))
                        .clickable { footprintsOpen = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("足迹", color = UiColors.AccentBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ---- 底部 ----
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // ★ imePadding：键盘弹出来时整块跟着抬上去。
                //   targetSdk 36 在 Android 15+ 是强制 edge-to-edge 的，
                //   光靠 windowSoftInputMode="adjustResize" 窗口不会缩，输入框会被键盘盖住。
                .imePadding()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            (localError ?: state.error)?.let { e ->
                Box(
                    Modifier.fillMaxWidth()
                        .background(UiColors.Surface, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) { Text(e, color = UiColors.Warning, fontSize = 13.sp) }
            }

            if (navigating) {
                state.lastVoice?.let { v -> AiLine(v.text) }
                TalkButton(voice, voiceState.phase)
            } else {
                val route = state.route
                if (route == null) {
                    PlaceSheet(
                        value = destText,
                        onValueChange = { destText = it },
                        meName = meName,
                        favorites = favorites,
                        recents = recentList,
                        busy = busy,
                        canGo = meLatLng != null,
                        mode = travelMode,
                        onModeChange = { m ->
                            travelMode = m
                            // 换了模式就把算好的路作废 —— 留着会显示一条不是这个模式的路
                            if (state.route != null) controller.resetRoute()
                        },
                        expanded = sheetUp,
                        onExpandedChange = { sheetUp = it },
                        onPick = { destText = it },
                        onToggleFavorite = { name ->
                            val adding = !places.isFavorite(name)
                            if (adding) places.addFavorite(name) else places.removeFavorite(name)
                            favorites = places.favorites()
                            // 同步到后端——后端才看得到（后端也在写它）
                            voice.pushPlace(name, adding)
                        },
                        onClearRecents = {
                            places.clearRecents()
                            recentList = places.recents()
                        },
                        onGo = {
                            busy = true
                            localError = null
                            val q = destText.trim()
                            val from = meLatLng
                            if (from == null) {
                                busy = false
                                localError = "还没定位到，等一下"
                                return@PlaceSheet
                            }
                            searchPlace(context, q, meCity) { addr ->
                                busy = false
                                if (addr?.latLonPoint != null) {
                                    destName = addr.formatAddress ?: q
                                    places.addRecent(q)
                                    recentList = places.recents()
                                    sheetUp = false
                                    controller.calcRoute(
                                        travelMode,
                                        NaviLatLng(from.latitude, from.longitude),
                                        NaviLatLng(addr.latLonPoint.latitude, addr.latLonPoint.longitude),
                                    )
                                } else {
                                    localError = "没找到「$q」，换个说法试试"
                                }
                            }
                        },
                    )
                } else {
                    RouteCard(
                        distanceM = route.distance,
                        timeS = route.time,
                        lights = route.lights,
                        destName = destName,
                        mode = state.mode,
                        onStart = {
                            if (controller.startGpsNavi()) {
                                NaviService.start(context)
                                controller.startTrip(meName, destName, state.mode)
                                voice.setNaviMode(true)
                                voice.speakFixed("navi_start", "好，出发。気をつけて。")
                            }
                        },
                        onReset = {
                            destText = ""
                            controller.resetRoute()
                        },
                    )
                }
            }
        }

        // ---- 足迹页（盖在地图上，关掉就回地图）----
        if (footprintsOpen) {
            FootprintsPanel(
                trips = trips,
                loading = footprintsLoading,
                tracksOn = tracksOn,
                onClose = { footprintsOpen = false },
                onPickTrip = { drawTrip(it) },
                onToggleTracks = { toggleTracks() },
                onClearDrawing = { clearFootprints() },
            )
        }
    }
}

// ------------------------------------------------------------------ 顶部转向条

@Composable
private fun TurnBanner(turn: TurnState) {
    Row(
        Modifier.fillMaxWidth()
            .background(UiColors.Surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            turnArrow(turn.iconType),
            color = UiColors.AccentBright,
            fontSize = 46.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                fmtDistance(turn.stepRetain),
                color = UiColors.TextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            val road = turn.nextRoad.ifBlank { turn.currentRoad }
            if (road.isNotBlank()) {
                Text(road, color = UiColors.TextSecondary, fontSize = 14.sp)
            }
        }
    }
}

/** 一条窄提示（限速 / 服务区）。 */
@Composable
private fun HintStrip(text: String, tint: Color) {
    Row(
        Modifier.fillMaxWidth()
            .background(UiColors.Surface.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 右下那个大圆速度表（B 那套）。 */
@Composable
private fun SpeedDial(speed: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(96.dp)
            .background(UiColors.Background.copy(alpha = 0.92f), CircleShape)
            .border(3.dp, UiColors.Accent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "$speed",
                color = UiColors.AccentBright,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text("km/h", color = UiColors.TextDim, fontSize = 10.sp)
        }
    }
}

/** 左边那块小数据：还剩多少、几点到、几盏灯、现在海拔多高。 */
@Composable
private fun NavStats(turn: TurnState, altitude: Double, showLights: Boolean) {
    val eta = remember(turn.pathRetainTime) {
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(System.currentTimeMillis() + turn.pathRetainTime * 1000L))
    }
    Column(
        Modifier.background(UiColors.Surface.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        StatLine(format = fmtDistance(turn.pathRetain), label = "剩余")
        StatLine(format = fmtTime(turn.pathRetainTime), label = "用时")
        StatLine(format = eta, label = "到达")
        // 红绿灯只有开车数得有意义
        if (showLights && turn.lights > 0) {
            StatLine(format = "${turn.lights}", label = "红绿灯", tint = UiColors.Warning)
        }
        // 海拔：上立交、进地库的时候，这个数是分辨「楼上楼下」的唯一线索
        StatLine(format = fmtAltitude(altitude), label = "海拔", tint = UiColors.TextSecondary)
    }
}

/** 海拔：0 或负数 = 没测到（不是「在海平面」）。 */
internal fun fmtAltitude(meters: Double): String = when {
    meters <= 0.5 -> "--"
    meters < 1000 -> "${meters.toInt()} 米"
    else -> String.format("%.1f 公里", meters / 1000.0)
}

@Composable
private fun StatLine(format: String, label: String, tint: Color = UiColors.Accent) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(format, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(5.dp))
        Text(label, color = UiColors.TextDim, fontSize = 10.sp)
    }
}

/** 自己画的指南针：N 会跟着车头转，北永远指对。 */
@Composable
private fun Compass(bearing: Float) {
    Box(
        Modifier.size(40.dp)
            .background(UiColors.Surface.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.rotate(-bearing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("N", color = UiColors.AccentBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Box(
                Modifier.width(2.dp).height(8.dp)
                    .background(UiColors.AccentBright.copy(alpha = 0.7f))
            )
        }
    }
}

// ------------------------------------------------------------------ 路线 / 出发前

@Composable
private fun RouteCard(
    distanceM: Int,
    timeS: Int,
    lights: Int,
    destName: String,
    mode: TravelMode,
    onStart: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(UiColors.Surface, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (destName.isNotBlank()) {
            Text(destName, color = UiColors.TextSecondary, fontSize = 13.sp, maxLines = 1)
        }
        Text(
            // 红绿灯数只有开车才有意义 —— 走路骑车报「22 个红绿灯」是废话
            "${mode.label} · 全程 ${fmtDistance(distanceM)} · 约 ${fmtTime(timeS)}" +
                if (lights > 0 && mode.showsDrivingGauges) " · $lights 个红绿灯" else "",
            color = UiColors.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UiColors.AccentBright,
                    contentColor = UiColors.Background,
                ),
            ) { Text("开始导航", fontWeight = FontWeight.Bold) }
            Button(
                onClick = onReset,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UiColors.Background,
                    contentColor = UiColors.Accent,
                ),
            ) { Text("重选") }
        }
    }
}

/**
 * 出发前那一屏：**可以往上拉的抽屉**。
 *
 * 拉上来露两栏：常用（它自己存的）+ 刚刚搜过（自动记的）。
 * 长按任意一个地点＝存/取消常用 —— 比每格挂个星星干净，也不用额外解释按钮。
 */
@Composable
private fun PlaceSheet(
    value: String,
    onValueChange: (String) -> Unit,
    meName: String,
    favorites: List<String>,
    recents: List<String>,
    busy: Boolean,
    canGo: Boolean,
    mode: TravelMode,
    onModeChange: (TravelMode) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onClearRecents: () -> Unit,
    onGo: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(UiColors.Surface, RoundedCornerShape(20.dp))
            .padding(bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 上拉把手：往上一拖就展开 ----
        Box(
            Modifier.fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dy ->
                        if (dy < -6f) onExpandedChange(true)
                        if (dy > 6f) onExpandedChange(false)
                    }
                }
                .clickable { onExpandedChange(!expanded) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.width(44.dp).height(4.dp)
                    .background(UiColors.Accent.copy(alpha = 0.45f), RoundedCornerShape(2.dp))
            )
        }

        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("navi-shell", color = UiColors.AccentBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (meName.isBlank()) "正在定位…" else "现在在 $meName",
                    color = UiColors.TextDim,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }

            // ---- 怎么去：开车 / 走路 / 骑车 ----
            ModeSelector(mode, onModeChange)

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    placeholder = { Text("想去哪？", color = UiColors.TextDim, fontSize = 16.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = UiColors.TextPrimary,
                        unfocusedTextColor = UiColors.TextPrimary,
                        focusedBorderColor = UiColors.Accent,
                        unfocusedBorderColor = UiColors.TextDim,
                        cursorColor = UiColors.Accent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.size(54.dp)
                        .background(
                            if (canGo && !busy) UiColors.Accent else UiColors.TextDim,
                            CircleShape,
                        )
                        .clickable(enabled = canGo && !busy) { onGo() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (busy) "找…" else "去",
                        color = UiColors.Background,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // ---- 拉上来才露的部分 ----
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (favorites.isEmpty() && recents.isEmpty()) "搜过一次就有记录了；长按可以存成常用"
                        else "长按任意地点 = 存成常用 / 取消常用",
                        color = UiColors.TextDim,
                        fontSize = 10.sp,
                    )
                    if (favorites.isNotEmpty()) {
                        PlaceRow("常用", favorites, UiColors.AccentBright, onPick, onToggleFavorite)
                    }
                    if (recents.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("刚刚搜过", color = UiColors.TextDim, fontSize = 11.sp)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "清空",
                                color = UiColors.TextDim,
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { onClearRecents() },
                            )
                        }
                        FlowChips(recents, UiColors.TextSecondary, onPick, onToggleFavorite)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** 开车 / 走路 / 骑车 三选一。 */
@Composable
private fun ModeSelector(mode: TravelMode, onChange: (TravelMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(UiColors.Background, RoundedCornerShape(13.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        TravelMode.entries.forEach { m ->
            val on = m == mode
            Box(
                Modifier.weight(1f)
                    .background(
                        if (on) UiColors.Accent else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onChange(m) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    m.label,
                    color = if (on) UiColors.Background else UiColors.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun PlaceRow(
    title: String,
    items: List<String>,
    color: androidx.compose.ui.graphics.Color,
    onPick: (String) -> Unit,
    onLongPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, color = UiColors.TextDim, fontSize = 11.sp)
        FlowChips(items, color, onPick, onLongPick)
    }
}

/**
 * 一行横着排的胶囊。
 *
 * 本来想用 FlowRow 换行，但那个是实验 API；这里用横向滚动更稳，
 * 也多存不了几个地点，滚一下够用。
 */
@Composable
private fun FlowChips(
    items: List<String>,
    color: androidx.compose.ui.graphics.Color,
    onPick: (String) -> Unit,
    onLongPick: (String) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { name ->
            Box(
                Modifier.background(UiColors.Background, RoundedCornerShape(14.dp))
                    .combinedClickable(
                        onClick = { onPick(name) },
                        onLongClick = { onLongPick(name) },
                    )
                    .padding(horizontal = 13.dp, vertical = 8.dp)
            ) {
                Text(name, color = color, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

// ------------------------------------------------------------------ 它说的话

@Composable
private fun AiLine(text: String) {
    // 名字留空就光显示说的话（见 strings.xml 的 ai_name）
    val who = stringResource(R.string.ai_name)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.background(UiColors.Background.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                if (who.isBlank()) text else "$who · $text",
                color = UiColors.Accent,
                fontSize = 13.sp,
            )
        }
    }
}

/** 按住说话。松开就送出去。 */
@Composable
private fun TalkButton(voice: VoiceSession, phase: VoicePhase) {
    val listening = phase == VoicePhase.LISTENING
    val busy = phase == VoicePhase.THINKING || phase == VoicePhase.SPEAKING

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier.size(68.dp)
                .background(
                    when {
                        listening -> UiColors.AccentBright
                        busy -> UiColors.Surface
                        else -> UiColors.Accent
                    },
                    CircleShape,
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            voice.startListening()
                            tryAwaitRelease()
                            voice.stopAndSend()
                        }
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    listening -> "松开"
                    phase == VoicePhase.THINKING -> "…"
                    phase == VoicePhase.SPEAKING -> "在说"
                    else -> "说话"
                },
                color = UiColors.Background,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ------------------------------------------------------------------ 小工具

/** 转向图标：先用 Unicode 箭头顶着，够用再换自绘。 */
private fun turnArrow(iconType: Int): String = when (iconType) {
    IconType.LEFT -> "⬅"
    IconType.RIGHT -> "➡"
    IconType.LEFT_FRONT -> "↖"
    IconType.RIGHT_FRONT -> "↗"
    IconType.LEFT_BACK -> "↙"
    IconType.RIGHT_BACK -> "↘"
    IconType.LEFT_TURN_AROUND -> "⤺"
    IconType.STRAIGHT, IconType.DEFAULT -> "⬆"
    IconType.ENTER_ROUNDABOUT, IconType.ENTRY_RING_LEFT,
    IconType.ENTRY_RING_RIGHT, IconType.ENTRY_RING_CONTINUE -> "↻"
    IconType.OUT_ROUNDABOUT, IconType.LEAVE_LEFT_RING -> "↺"
    IconType.ARRIVED_DESTINATION -> "🏁"
    IconType.MERGE_LEFT -> "↖"
    IconType.MERGE_RIGHT -> "↗"
    else -> "⬆"
}

internal fun fmtDistance(meters: Int): String = when {
    meters < 0 -> "--"
    meters < 1000 -> "$meters 米"
    else -> String.format("%.1f 公里", meters / 1000.0)
}

internal fun fmtTime(seconds: Int): String = when {
    seconds < 0 -> "--"
    seconds < 60 -> "$seconds 秒"
    seconds < 3600 -> "${seconds / 60} 分钟"
    else -> "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分"
}

/** 地址转坐标。city 用来限定在哪个城市找（空串 = 全国找，容易找着同名的）。 */
private fun searchPlace(
    context: Context,
    name: String,
    city: String,
    onResult: (GeocodeAddress?) -> Unit,
) {
    try {
        val search = GeocodeSearch(context)
        search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
            override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) {}
            override fun onGeocodeSearched(result: GeocodeResult?, code: Int) {
                onResult(result?.geocodeAddressList?.firstOrNull())
            }
        })
        search.getFromLocationNameAsyn(GeocodeQuery(name, city))
    } catch (e: Exception) {
        onResult(null)
    }
}
