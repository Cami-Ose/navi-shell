package com.navi.shell.voice

import android.content.Context
import com.navi.shell.audio.VoicePlayer
import com.navi.shell.audio.WavRecorder
import com.navi.shell.data.AuthStore
import com.navi.shell.data.ChatMessage
import com.navi.shell.data.NaviTrip
import com.navi.shell.net.NaviApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoicePhase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val lastHeard: String = "",
    val lastSaid: String = "",
    val error: String? = null,
    /** 开场垫进来的主聊天轮数（0 = 没拿到，不影响能用）。 */
    val seededTurns: Int = 0,
)

/**
 * 一句话的来回：录 → /asr 听清 → /proxy_chat 它想 → /tts 说出来。
 *
 * 关键：用的是导航专用 chatId，这些对话**不落正常会话**。
 * 正常会话里只会在导航结束后留一条「刚去了哪」。
 */
class VoiceSession(
    private val context: Context,
    /** 导航专用会话 id。跟正常聊天的 chat_id 不是一回事。 */
    private val chatId: String,
) {

    private val api = NaviApi(AuthStore(context))
    private val recorder = WavRecorder()
    private val player = VoicePlayer(context)
    private val script = BroadcastScript(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** 这一轮里跟它的往返，只活在内存里，不落盘。 */
    private val history = mutableListOf<ChatMessage>()

    /**
     * 现在是怎么去的（drive/walk/ride）。
     * 影响服务端提示词里的场景词 —— 走路时它不该说「你在车上」。
     */
    @Volatile
    private var travelMode: String = "drive"

    fun setTravelMode(mode: String) {
        travelMode = mode
    }

    /**
     * 开场准备：换 cookie，再把「你和 它最近在主聊天里聊的那几轮」拿回来垫在对话最前面。
     *
     * 为什么要这个：导航会话是**另一个 chat_id**，它本来看不到主聊天。
     * 不垫的话，你上车说「带我去上次那家烤肉」，它一脸茫然 —— 记忆库能兜一部分，
     * 但「刚刚你俩正聊的那件事」只有原文能兜住。
     *
     * 拿不到就空着，导航照样能用（fail-open）。
     */
    private val bootstrap: Job = scope.launch {
        runCatching { api.authorize() }
            .onFailure { e -> _state.update { it.copy(error = "连不上后端：${e.message}") } }
        val seed = runCatching { api.recentMainContext(limit = 10) }.getOrDefault(emptyList())
        if (seed.isNotEmpty()) {
            history += seed
            _state.update { it.copy(seededTurns = seed.size) }
        }
    }

    fun startListening() {
        if (recorder.isRecording) return
        player.stop()
        if (recorder.start()) {
            _state.update { it.copy(phase = VoicePhase.LISTENING, error = null) }
        } else {
            _state.update { it.copy(phase = VoicePhase.ERROR, error = "麦克风打不开") }
        }
    }

    /** 说完松手：这一整段送去识别、送给它、再让它说回来。 */
    fun stopAndSend() {
        val wav = recorder.stop()
        if (wav == null) {
            _state.update { it.copy(phase = VoicePhase.IDLE, error = "没听清，再说一次") }
            return
        }
        _state.update { it.copy(phase = VoicePhase.THINKING) }
        scope.launch {
            try {
                // 先等开场上下文就位，别让第一句在没上下文的情况下发出去
                bootstrap.join()
                val heard = withContext(Dispatchers.IO) { api.asr(wav) }
                if (heard.error != null || heard.text.isBlank()) {
                    _state.update { it.copy(phase = VoicePhase.IDLE, error = heard.error ?: "没听清") }
                    return@launch
                }
                _state.update { it.copy(lastHeard = heard.text) }
                // 情绪是 SenseVoice 真听音频判的，带给它（私有字段，后端不落盘）
                history += ChatMessage("user", heard.text)
                val reply = withContext(Dispatchers.IO) {
                    api.chat(chatId, history.toList(), travelMode = travelMode)
                }
                if (reply.isBlank()) {
                    // **不报错**：它偶尔只动手不动嘴（比如调了 places 工具把地点存了，
                    // 正文就是空的）。报「它没出声」会把一次成功的操作说成失败。
                    _state.update { it.copy(phase = VoicePhase.IDLE) }
                    return@launch
                }
                history += ChatMessage("assistant", reply)
                _state.update { it.copy(lastSaid = reply, phase = VoicePhase.SPEAKING) }
                speak(reply)
            } catch (e: Exception) {
                _state.update { it.copy(phase = VoicePhase.ERROR, error = e.message ?: "出错了") }
            }
        }
    }

    /**
     * 让它说一段话（导航开场、到达、或者它回的聊天）。
     *
     * **一句一句合成、一句一句播**（跟 box_call 一个做法）：
     * 合成一句要等一次 `/tts`，整段一起等的话它开着车要干等好几秒才听到第一个字。
     * 逐句的话，第一句合成完就出声了，后面几句在它说的时候正好合上。
     */
    fun speak(text: String) {
        scope.launch {
            _state.update { it.copy(phase = VoicePhase.SPEAKING, lastSaid = text) }
            try {
                val parts = splitSentences(text)
                if (parts.isEmpty()) {
                    _state.update { it.copy(phase = VoicePhase.IDLE) }
                    return@launch
                }
                for (p in parts) {
                    val bytes = withContext(Dispatchers.IO) { api.tts(p) }
                    val done = CompletableDeferred<Unit>()
                    player.onDone = { done.complete(Unit) }
                    player.playBytes(bytes)
                    done.await()          // 等这句播完，再合下一句
                }
                _state.update { it.copy(phase = VoicePhase.IDLE) }
            } catch (e: Exception) {
                _state.update { it.copy(phase = VoicePhase.ERROR, error = e.message ?: "说不出话") }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** 告诉后端我在导航 / 我不导航了（后端的自动唤醒据此压住）。 */
    fun setNaviMode(on: Boolean) {
        scope.launch { withContext(Dispatchers.IO) { api.setNaviMode(on) } }
    }

    /** 把这趟出行存到后端，以后能自己查。 */
    fun reportTrip(trip: NaviTrip) {
        scope.launch { withContext(Dispatchers.IO) { api.reportTrip(trip) } }
    }

    /**
     * 常用地点：**存在后端上，手机和后端共用一份**。
     * 拉不到就回空表，本地缓存照常用（fail-open）。
     */
    suspend fun loadRemotePlaces(): List<String> =
        runCatching { withContext(Dispatchers.IO) { api.getPlaces().favorites } }.getOrDefault(emptyList())

    /** 加/删一个常用地点，写到后端。失败不影响本地（本地是缓存）。 */
    fun pushPlace(name: String, add: Boolean) {
        scope.launch { withContext(Dispatchers.IO) { api.setPlace(name, add) } }
    }

    /**
     * 高德要播的那句原话。
     * 词表里有 → 放预录（零延迟）；没有（或音频还没生成）→ 实时合成兜底。
     * 一句话也不会说错：只在**原话跟预录文本对得上**时才用预录。
     */
    fun speakNavigation(amapText: String) {
        val key = script.keyFor(amapText)
        if (key != null && script.hasAudio(key)) {
            playAsset(key, script.textOf(key) ?: amapText)
        } else {
            speak(amapText)
        }
    }

    /** 播一条固定整句（出发 / 到了 / 重算路线），同样预录优先。 */
    fun speakFixed(key: String, fallback: String) {
        if (script.hasAudio(key)) playAsset(key, script.textOf(key) ?: fallback)
        else speak(fallback)
    }

    // ---------------------------------------------------------------- 足迹

    /** 出行记录列表（足迹页）。拉不到回空表，页面显示「还没有记录」。 */
    suspend fun loadTrips(): List<NaviTrip> =
        runCatching { withContext(Dispatchers.IO) { api.getTrips() } }.getOrDefault(emptyList())

    /** 一次出行的轨迹点：[[lat, lng, t], ...]。 */
    suspend fun loadTrack(tripId: String): List<List<Double>> =
        runCatching { withContext(Dispatchers.IO) { api.getTrack(tripId) } }.getOrDefault(emptyList())

    /** 每次出行各自的轨迹线（画「全部路线」）。 */
    suspend fun loadTracks(): List<List<List<Double>>> =
        runCatching { withContext(Dispatchers.IO) { api.getTracks() } }.getOrDefault(emptyList())

    private fun playAsset(key: String, display: String) {
        // 路径问 script 要，别自己拼后缀 —— /tts 回的是 wav，硬写 .mp3 会打不开
        val path = script.assetPath(key) ?: return speak(display)
        player.stop()
        _state.update { it.copy(phase = VoicePhase.SPEAKING, lastSaid = display, error = null) }
        player.onDone = { _state.update { it.copy(phase = VoicePhase.IDLE) } }
        player.playAsset(path)
    }

    fun release() {
        recorder.stop()
        player.stop()
        bootstrap.cancel()
        scope.cancel()
    }
}
