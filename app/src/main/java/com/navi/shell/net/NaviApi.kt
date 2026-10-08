package com.navi.shell.net

import com.navi.shell.BuildConfig
import com.navi.shell.data.AppJson
import com.navi.shell.data.AsrResponse
import com.navi.shell.data.AuthStore
import com.navi.shell.data.ChatDetail
import com.navi.shell.data.ChatMessage
import com.navi.shell.data.HistoryItem
import com.navi.shell.data.NaviTrip
import com.navi.shell.data.PlacesResponse
import com.navi.shell.data.ProxyChatRequest
import com.navi.shell.data.ProxyChatResponse
import com.navi.shell.data.TrackResponse
import com.navi.shell.data.TracksResponse
import com.navi.shell.data.TripsResponse
import com.navi.shell.data.TtsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** 401 = 授权过期，得重新 /access。 */
class AuthExpired(message: String) : Exception(message)

/**
 * 跟后端说话的那条线。
 *
 * 跟 网页版 走同一条路：/access 换 gate_auth cookie，之后全带 cookie。
 * 人设、记忆、状态、时间都由服务端自动注入，这边不用管。
 */
class NaviApi(private val authStore: AuthStore) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("User-Agent", "navi-shell/0.1.0")
            val cookie = authStore.cookie
            if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
            chain.proceed(builder.build())
        }
        .build()

    /** 首次启动调一次：拿 cookie 存起来。 */
    suspend fun authorize(): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${BuildConfig.BASE_URL}/access?t=${BuildConfig.APP_AUTH_TOKEN}")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("授权失败 HTTP ${resp.code}")
            val setCookie = resp.headers("Set-Cookie")
                .firstOrNull { it.startsWith("gate_auth=") }
                ?.substringBefore(';')
                ?: throw Exception("授权响应里没有 gate_auth")
            authStore.cookie = setCookie
            authStore.isAuthorized = true
            true
        }
    }

    private suspend fun ensureAuth() {
        if (!authStore.isAuthorized || authStore.cookie.isEmpty()) authorize()
    }

    /** 听你说话：POST /asr，multipart 字段名就叫 audio。 */
    suspend fun asr(wav: ByteArray): AsrResponse {
        ensureAuth()
        return withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio", "speech.wav",
                    wav.toRequestBody("audio/wav".toMediaType()),
                )
                .build()
            val request = Request.Builder().url("${BuildConfig.BASE_URL}/asr").post(body).build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 401) throw AuthExpired("授权过期")
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use AsrResponse(error = "HTTP ${resp.code}")
                runCatching { AppJson.decodeFromString(AsrResponse.serializer(), text) }
                    .getOrElse { AsrResponse(error = "识别返回看不懂：$text") }
            }
        }
    }

    /**
     * 跟它说话。chatId 用导航专用的那个，所以这些对话**不落正常会话**。
     * 服务端会自动注入人设/记忆/状态。
     *
     * `naviTalk=true` 打开服务端的「车内对话」模式：中文为主 + 掺一点简单日语 +
     * 只输出能说出口的话（不然 TTS 会把「*微笑*」这种也念出来）。
     */
    suspend fun chat(
        chatId: String,
        messages: List<ChatMessage>,
        naviTalk: Boolean = true,
        travelMode: String = "drive",
    ): String {
        ensureAuth()
        val payload = ProxyChatRequest(
            chat_id = chatId,
            messages = messages,
            provider = null,
            naviTalk = if (naviTalk) true else null,
            travelMode = if (naviTalk) travelMode else null,
        )
        val body = AppJson.encodeToString(ProxyChatRequest.serializer(), payload)
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${BuildConfig.BASE_URL}/proxy_chat")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 401) throw AuthExpired("授权过期")
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw Exception("对话失败 HTTP ${resp.code}")
                val parsed = runCatching {
                    AppJson.decodeFromString(ProxyChatResponse.serializer(), text)
                }.getOrNull() ?: return@use ""
                parsed.error?.let { throw Exception(it) }
                parsed.content.orEmpty()
            }
        }
    }

    /** 让它出声：POST /tts，回音频二进制。 */
    suspend fun tts(text: String): ByteArray {
        ensureAuth()
        val body = AppJson.encodeToString(TtsRequest.serializer(), TtsRequest(text))
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${BuildConfig.BASE_URL}/tts")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 401) throw AuthExpired("授权过期")
                if (!resp.isSuccessful) throw Exception("合成失败 HTTP ${resp.code}")
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    /**
     * 一条导航记录存到后端，以后能自己查。
     * 存不上不算致命（网络抖一下就没了），所以返回 Boolean 而不是抛。
     */
    suspend fun reportTrip(trip: NaviTrip): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val body = AppJson.encodeToString(NaviTrip.serializer(), trip)
            val request = Request.Builder()
                .url("${BuildConfig.BASE_URL}/api/navi/trip")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }

    /**
     * 告诉后端「我正在导航」。
     * 开着的时候后端的自动唤醒要压住（跟「出门静音」一个道理）—— 开车时别突然开口。
     */
    suspend fun setNaviMode(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            ensureAuth()
            val body = "{\"on\":$on}"
            val request = Request.Builder()
                .url("${BuildConfig.BASE_URL}/api/navi/mode")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { resp -> resp.isSuccessful }
        }.getOrDefault(false)
    }

    /** 会话列表：GET /get_history_api。 */
    suspend fun getHistory(): List<HistoryItem> {
        ensureAuth()
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url("${BuildConfig.BASE_URL}/get_history_api").build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 401) throw AuthExpired("授权过期")
                if (!resp.isSuccessful) throw Exception("取会话列表失败 HTTP ${resp.code}")
                AppJson.decodeFromString(
                    ListSerializer(HistoryItem.serializer()),
                    resp.body?.string().orEmpty(),
                )
            }
        }
    }

    /**
     * 出行记录列表（足迹页面用）。最近 N 小时，新的在前。
     * 拉不到就回空表 —— 足迹页打不开不该连导航也用不了。
     */
    suspend fun getTrips(hours: Int = 24 * 365): List<NaviTrip> = runCatching {
        ensureAuth()
        withContext(Dispatchers.IO) {
            val url = "${BuildConfig.BASE_URL}/api/navi/trips".toHttpUrl().newBuilder()
                .addQueryParameter("hours", hours.toString()).build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("取出行列表 HTTP ${resp.code}")
                AppJson.decodeFromString(TripsResponse.serializer(), resp.body?.string().orEmpty()).trips
            }
        }
    }.getOrDefault(emptyList())

    /** 一次出行的轨迹点。没存过就回空表。 */
    suspend fun getTrack(tripId: String): List<List<Double>> = runCatching {
        ensureAuth()
        withContext(Dispatchers.IO) {
            val url = "${BuildConfig.BASE_URL}/api/navi/track/".toHttpUrl().newBuilder()
                .addPathSegment(tripId).build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("取轨迹 HTTP ${resp.code}")
                AppJson.decodeFromString(TrackResponse.serializer(), resp.body?.string().orEmpty()).points
            }
        }
    }.getOrDefault(emptyList())

    /**
     * 每次出行各自的轨迹线（画「全部路线」）。
     *
     * 跟热力图那种「把所有点混成一堆」的画法不同：这里**一趟一条线**，
     * 重叠的路段自然叠亮，路的走向也看得见。
     */
    suspend fun getTracks(
        hours: Int = 24 * 365,
        max: Int = 40,
        step: Int = 2,
    ): List<List<List<Double>>> = runCatching {
        ensureAuth()
        withContext(Dispatchers.IO) {
            val url = "${BuildConfig.BASE_URL}/api/navi/tracks".toHttpUrl().newBuilder()
                .addQueryParameter("hours", hours.toString())
                .addQueryParameter("max", max.toString())
                .addQueryParameter("step", step.toString()).build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("取路线 HTTP ${resp.code}")
                AppJson.decodeFromString(TracksResponse.serializer(), resp.body?.string().orEmpty()).tracks
            }
        }
    }.getOrDefault(emptyList())

    /**
     * 常用地点（存后端，手机和后端共用一份）。
     *
     * 之前 App 只存在本地 SharedPreferences ——后端写进后端的，手机上看不到，
     * 反过来说的「两边共享」是**假的**。这里补上。
     * 读的时候网络挂了就回空，本地缓存照常用（fail-open）。
     */
    suspend fun getPlaces(): PlacesResponse = runCatching {
        ensureAuth()
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("${BuildConfig.BASE_URL}/api/navi/places").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("取常用地点 HTTP ${resp.code}")
                AppJson.decodeFromString(PlacesResponse.serializer(), resp.body?.string().orEmpty())
            }
        }
    }.getOrDefault(PlacesResponse())

    /** 加/删一个常用地点。返回是否成功（失败不抛，调用方不用管）。 */
    suspend fun setPlace(name: String, add: Boolean): Boolean = runCatching {
        ensureAuth()
        withContext(Dispatchers.IO) {
            val url = if (add) {
                "${BuildConfig.BASE_URL}/api/navi/places"
            } else {
                // ★ name 走 query：DELETE 带 body 有些客户端会丢（服务端两个都认）
                "${BuildConfig.BASE_URL}/api/navi/places".toHttpUrl().newBuilder()
                    .addQueryParameter("name", name).build().toString()
            }
            val b = Request.Builder().url(url)
            if (add) {
                b.post(
                    AppJson.encodeToString(
                        PlaceReq.serializer(), PlaceReq(name = name)
                    ).toRequestBody("application/json; charset=utf-8".toMediaType())
                )
            } else {
                b.delete()
            }
            client.newCall(b.build()).execute().use { resp -> resp.isSuccessful }
        }
    }.getOrDefault(false)

    /** POST /api/navi/places 的 body。 */
    @kotlinx.serialization.Serializable
    private data class PlaceReq(val name: String)

    /** 单个会话的内容：GET /get_chat_api/{chat_id}。 */    suspend fun getChat(chatId: String): ChatDetail {
        ensureAuth()
        return withContext(Dispatchers.IO) {
            val url = "${BuildConfig.BASE_URL}/get_chat_api/".toHttpUrl()
                .newBuilder().addPathSegment(chatId).build()
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (resp.code == 401) throw AuthExpired("授权过期")
                if (!resp.isSuccessful) throw Exception("取会话失败 HTTP ${resp.code}")
                AppJson.decodeFromString(ChatDetail.serializer(), resp.body?.string().orEmpty())
            }
        }
    }

    /**
     * 把「你和 它最近在主聊天里聊的那几轮」拿回来，给导航会话当开场上下文。
     *
     * 拿不到就回空表 —— 导航不该因为主聊天读不到就说不了话。
     */
    suspend fun recentMainContext(limit: Int = 10): List<ChatMessage> = runCatching {
        // 已经按 mtime 倒序（服务端那条是取最新的），再排一次保险
        val items = getHistory().filter { it.chatId.isNotBlank() && !it.chatId.startsWith(NAVI_PREFIX) }
        if (items.isEmpty()) return@runCatching emptyList()
        val latest = items.first()
        val detail = getChat(latest.chatId)
        detail.messages
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { !it.content.isNullOrBlank() }
            .takeLast(limit)
            // 单条别太长，开车说话不需要读一整篇
            .map { ChatMessage(it.role, it.content!!.take(500)) }
    }.getOrDefault(emptyList())

    companion object {
        /** 导航自己建的会话都挂这个前缀，拿主聊天上下文时要排掉它们。 */
        const val NAVI_PREFIX = "navi_"
    }
}
