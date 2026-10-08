package com.navi.shell.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 全局 JSON：容忍服务端多给的字段。 */
val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
)

@Serializable
data class ProviderRef(
    val id: String? = null,
    val endpoint: String? = null,
    val key: String? = null,
    val model: String? = null,
    val system: String? = null,
)

@Serializable
data class ProxyChatRequest(
    val chat_id: String,
    val messages: List<ChatMessage>,
    val provider: ProviderRef? = null,
    val replace_from: Int? = null,
    /**
     * 车内对话模式（服务端 private 字段，跟着 _summary_only / _no_save / _call_ja 一个路子）。
     * 开着：中文为主 + 掺一点简单日语 + 只出能说出口的话（禁动作/神态描写，否则 TTS 会念出来）。
     *
     * 注意：服务端 `_extract_message_context` 会剥掉**消息上**的 `_` 字段，
     * 但这个是请求体顶层的，不受影响 —— 跟 `_call_ja` 一样读得到。
     */
    @SerialName("_navi_talk") val naviTalk: Boolean? = null,
    /**
     * 怎么去的：drive / walk / ride。
     * 只影响服务端提示词里的场景词 —— 走路时它不该说「你在车上」。
     */
    @SerialName("_travel_mode") val travelMode: String? = null,
)

@Serializable
data class ProxyChatResponse(
    val role: String = "assistant",
    val content: String? = null,
    val error: String? = null,
)

/** POST /asr 的回答。emotion 是本地 SenseVoice 真听音频判出来的。 */
@Serializable
data class AsrResponse(
    val text: String = "",
    val emotion: String? = null,
    val error: String? = null,
)

/** POST /tts 请求体。响应是音频二进制，不是 JSON。 */
@Serializable
data class TtsRequest(
    val text: String,
)

/**
 * 轨迹上的一个点。
 *
 * `t` 是**出发后第几秒**，不是绝对时间戳 —— 一个 int 就够（省字节），
 * 而且不暴露「你几点几分在哪」。要看绝对时间用 trip 的 started_at 加一下。
 */
@Serializable
data class TrackPoint(
    val lat: Double,
    val lng: Double,
    val t: Int,
)

/** 导航记录的一条（存后端，它自己能查）。 */
@Serializable
data class NaviTrip(
    val id: String,
    /**
     * 怎么去的：drive / walk / ride。
     *
     * 服务端现在**还不存这个字段**（`navi_store.add_trip` 只挑它认识的键，
     * 多出来的会安静忽略）—— 要让后端也记上，得单独改一次 navi_store。
     * 这边先发着，不改也不影响别的。
     */
    val mode: String = "drive",
    val started_at: Long,
    val ended_at: Long,
    val from_name: String,
    val to_name: String,
    val distance_m: Int,
    val duration_s: Int,
    /**
     * 轨迹点。**存了** —— 有它才能回放、才能画「全部路线」。
     *
     * 已经抽稀过（见 TripRecorder）：移动不足 30 米不记点，所以两小时的车程
     * 大概一千多个点、几十 KB，不是每秒一个。
     */
    val track: List<TrackPoint> = emptyList(),
    /**
     * 这条记录存了几个轨迹点。**服务端算的**，发出去时不用填 ——
     * 足迹列表里靠它显示「有没有轨迹」。
     */
    val points: Int? = null,
)

/** 出行列表：GET /api/navi/trips。 */
@Serializable
data class TripsResponse(
    val ok: Boolean = false,
    val trips: List<NaviTrip> = emptyList(),
)

/**
 * 一次出行的轨迹：GET /api/navi/track/{id}。
 *
 * `points` 是 `[[lat, lng, t], ...]` —— 服务端存的就是数组不是对象，
 * 一公里几十个点，数组省掉一半字节。
 */
@Serializable
data class TrackResponse(
    val ok: Boolean = false,
    val id: String = "",
    @SerialName("started_at") val startedAt: Long = 0,
    val points: List<List<Double>> = emptyList(),
)

/** 每次出行各自的轨迹线：GET /api/navi/tracks。 */
@Serializable
data class TracksResponse(
    val ok: Boolean = false,
    val count: Int = 0,
    val tracks: List<List<List<Double>>> = emptyList(),
)

/** 会话列表项：GET /get_history_api 回来的是 [{"title","url":"/app/{chat_id}"}]。 */
@Serializable
data class HistoryItem(
    val title: String = "",
    val url: String = "",
) {
    /** 从 "/app/xxx" 里抠出 chat_id。 */
    val chatId: String get() = url.substringAfterLast('/')
}

/** 会话详情：GET /get_chat_api/{chat_id}。 */
@Serializable
data class ChatDetail(
    @SerialName("chat_id") val chatId: String = "",
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
)

/** 常用地点：GET /api/navi/places。手机和后端共用这一份。 */
@Serializable
data class PlacesResponse(
    val ok: Boolean = false,
    val favorites: List<String> = emptyList(),
    val notes: Map<String, String> = emptyMap(),
)
