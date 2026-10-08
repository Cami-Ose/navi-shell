package com.navi.shell.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * 录音，出 16kHz 单声道 16bit 的 WAV —— 跟网页版 box_call 送给 /asr 的格式一个样。
 * 识别那头是本地 SenseVoice，吃这个格式最省事。
 */
class WavRecorder(private val sampleRate: Int = 16_000) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var running = false

    /** 开始录。已经在录就什么都不做。 */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        pcm.reset()
        record = rec
        running = true
        rec.startRecording()
        thread = Thread {
            val chunk = ByteArray(minBuf)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) pcm.write(chunk, 0, n)
            }
        }.also { it.start() }
        return true
    }

    /** 停录并拿 WAV。太短（不足 0.4 秒）当噪声，返回 null。 */
    fun stop(): ByteArray? {
        if (!running) return null
        running = false
        thread?.join(600)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        val raw = pcm.toByteArray()
        val minBytes = (sampleRate * 0.4 * 2).toInt()
        if (raw.size < minBytes) return null
        return wrapWav(raw)
    }

    val isRecording: Boolean get() = running

    private fun wrapWav(data: ByteArray): ByteArray = wrapWav(data, sampleRate)

    companion object {
        /**
         * 给裸 PCM 套个 WAV 头（单声道 16bit）。
         *
         * 放在 companion 里是为了**能被测试直接调到** —— 麦克风那条路模拟器验不了，
         * 但「头拼得对不对」是能验的：把这函数的输出打给 /asr，看它认不认。
         */
        internal fun wrapWav(data: ByteArray, sampleRate: Int): ByteArray {
            val out = ByteArrayOutputStream(44 + data.size)
            val byteRate = sampleRate * 2
            out.write("RIFF".toByteArray())
            out.write(le32(36 + data.size))
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.write(le32(16))
            out.write(le16(1))            // PCM
            out.write(le16(1))            // 单声道
            out.write(le32(sampleRate))
            out.write(le32(byteRate))
            out.write(le16(2))            // block align
            out.write(le16(16))           // 16bit
            out.write("data".toByteArray())
            out.write(le32(data.size))
            out.write(data)
            return out.toByteArray()
        }

        private fun le32(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
        )

        private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    }
}
