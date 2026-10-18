package com.example.viavox.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

data class AndroidVoiceOption(
    val id: String,
    val label: String,
    val requiresNetwork: Boolean
)

/**
 * Show only voices that Android reports as READY, not voices which are
 * available in the catalogue but whose synthesis files are still downloading.
 */
class AndroidVoiceCatalog(
    context: Context,
    private val onLoaded: (List<AndroidVoiceOption>) -> Unit
) : TextToSpeech.OnInitListener {

    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false

    init {
        engine = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (closed) return
        val tts = engine
        if (status != TextToSpeech.SUCCESS || tts == null) {
            onLoaded(emptyList())
            return
        }
        ready = true
        val options = try {
            tts.voices.orEmpty()
                .filter(::isInstalledPortugueseVoice)
                .distinctBy(Voice::getName)
                .sortedWith(
                    compareBy<Voice> { if (it.locale.country == "BR") 0 else 1 }
                        .thenBy { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                ).mapIndexed { i, voice ->
                    val language = if (voice.locale.country == "BR") "Português (Brasil)" else "Português"
                    val delivery = if (voice.isNetworkConnectionRequired) "online" else "offline"
                    AndroidVoiceOption(voice.name, "Voz ${i + 1} · $language · $delivery", voice.isNetworkConnectionRequired)
                }
        } catch (e: Exception) {
            Log.w("ViaVoxVoice", "Could not load installed TTS voices", e)
            emptyList()
        }
        onLoaded(options)
    }

    fun preview(selectedVoiceId: String, speechRate: Float): Boolean {
        val tts = engine ?: return false
        if (!ready || closed) return false
        return try {
            val voice = selectReadyPortugueseVoice(tts.voices.orEmpty(), selectedVoiceId)
                ?: return false
            if (tts.setVoice(voice) != TextToSpeech.SUCCESS) return false
            tts.setSpeechRate(speechRate.coerceIn(0.7f, 1.4f))
            tts.speak(
                "ViaVox. Teste de voz em português.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "viavox_preview"
            ) == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            Log.w("ViaVoxVoice", "Voice preview failed", e)
            false
        }
    }

    fun close() {
        closed = true
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
