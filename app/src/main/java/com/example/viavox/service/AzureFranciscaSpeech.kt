package com.example.viavox.service

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Optional cloud voice. A HTTPS server-side broker must call Azure Speech with
 * pt-BR-FranciscaNeural and return audio/mpeg or audio/wav bytes.
 * No Azure key or token is stored in the APK.
 */
class AzureFranciscaSpeech(
    private val context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    @Volatile private var generation = 0L
    var endpoint: String? = null
    var accessToken: String? = null

    fun speak(text: String, onUnavailable: () -> Unit) {
        val url = endpoint?.takeIf { it.startsWith("https://") }
        if (url == null) {
            onUnavailable()
            return
        }
        val ticket = ++generation
        executor.execute {
            var cached: File? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 3500
                    conn.readTimeout = 6000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    accessToken?.takeIf { it.isNotBlank() }?.let { token ->
                        conn.setRequestProperty("Authorization", "Bearer $token")
                    }
                    conn.outputStream.use {
                        it.write(JSONObject().put("text", text)
                            .put("voice", "pt-BR-FranciscaNeural").toString()
                            .toByteArray(Charsets.UTF_8))
                    }
                    if (conn.responseCode !in 200..299) error("Voice service returned HTTP ${conn.responseCode}")
                    val mime = conn.contentType.orEmpty()
                    if (!mime.startsWith("audio/")) error("Expected audio response")
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size > 2_000_000) error("Voice response too large")
                    cached = File.createTempFile("viavox_", if (mime.contains("mpeg")) ".mp3" else ".wav", context.cacheDir)
                    cached.writeBytes(bytes)
                } finally {
                    conn.disconnect()
                }
                val audioFile = cached
                handler.post {
                    if (ticket != generation) {
                        audioFile?.delete()
                        return@post
                    }
                    try {
                        player?.release()
                        val mp = MediaPlayer()
                        player = mp
                        mp.setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        mp.setDataSource(requireNotNull(audioFile).absolutePath)
                        mp.setOnCompletionListener {
                            it.release()
                            if (player === it) player = null
                            audioFile.delete()
                            onSpeakingChanged(false)
                        }
                        mp.setOnErrorListener { p, _, _ ->
                            p.release()
                            if (player === p) player = null
                            audioFile.delete()
                            onSpeakingChanged(false)
                            true
                        }
                        mp.prepare()
                        onSpeakingChanged(true)
                        mp.start()
                    } catch (e: Exception) {
                        audioFile?.delete()
                        Log.w("ViaVoxVoice", "Playback failed", e)
                        onUnavailable()
                    }
                }
            } catch (e: Exception) {
                cached?.delete()
                Log.w("ViaVoxVoice", "Francisca unavailable; falling back to Android TTS", e)
                handler.post { if (ticket == generation) onUnavailable() }
            }
        }
    }

    fun stop() {
        generation++
        player?.release()
        player = null
        onSpeakingChanged(false)
    }

    fun shutdown() {
        stop()
        executor.shutdownNow()
    }
}
