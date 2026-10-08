package com.navi.shell.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import java.io.File

/**
 * 出声。
 *
 * 两条路都用 MediaPlayer：
 *  - 预录的转向播报（assets 里的 mp3，几乎零延迟）
 *  - 实时合成的话（/tts 回来的 mp3 bytes）
 *
 * 走 STREAM_MUSIC + 请求音频焦点，免得导航播报跟车里的音乐打架。
 */
class VoicePlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var focus: android.media.AudioFocusRequest? = null

    var onDone: (() -> Unit)? = null

    /** 现在有没有在出声。 */
    val isSpeaking: Boolean get() = player?.isPlaying == true

    /**
     * 播一段音频字节。正在播的会被顶掉。
     *
     * 扩展名要**看头猜**，不能写死 mp3：实测后端的 `/tts` 回来的是
     * **32kHz 单声道 16bit 的 WAV**（RIFF 头），不是 mp3。
     * 存成 .mp3 有些机型 MediaPlayer 直接拒播 —— 而这种错只在运行时出现，编译期一点提示都没有。
     */
    fun playBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val f = File(context.cacheDir, "voice_now" + sniffExt(bytes))
        f.writeBytes(bytes)
        playFile(f)
    }

    private fun sniffExt(b: ByteArray): String = when {
        b.size > 12 &&
            b[0] == 'R'.code.toByte() && b[1] == 'I'.code.toByte() &&
            b[2] == 'F'.code.toByte() && b[3] == 'F'.code.toByte() -> ".wav"
        b.size > 3 && b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte() -> ".mp3"
        else -> ".mp3"
    }

    /** 播 assets 里的预录音频，比如 "voice/left_500.mp3"。 */
    fun playAsset(path: String) {
        stop()
        runCatching {
            val afd = context.assets.openFd(path)
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs())
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            startPlayer(mp)
        }
    }

    private fun playFile(f: File) {
        stop()
        runCatching {
            val mp = MediaPlayer()
            mp.setAudioAttributes(attrs())
            mp.setDataSource(f.absolutePath)
            startPlayer(mp)
        }
    }

    private fun startPlayer(mp: MediaPlayer) {
        mp.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            abandonFocus()
            onDone?.invoke()
        }
        mp.setOnErrorListener { p, _, _ ->
            p.release()
            if (player === p) player = null
            abandonFocus()
            true
        }
        player = mp
        requestFocus()
        mp.prepare()
        mp.start()
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        abandonFocus()
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun requestFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (focus == null) {
            focus = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs())
                .build()
        }
        focus?.let { am.requestAudioFocus(it) }
    }

    private fun abandonFocus() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focus?.let { am.abandonAudioFocusRequest(it) }
    }
}
