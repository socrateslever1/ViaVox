package com.example.viavox.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Lists the Portuguese voices supplied by the installed Android TTS engine.
 * These are real engine voices, not mock labels or a hardcoded catalogue.
 */
data class AndroidVoiceOption(
    val id: String,
    val label: String,
    val requiresNetwork: Boolean
)

class AndroidVoiceCatalog(
    context: Context,
    private val onLoaded: (List<AndroidVoiceOption>) -> Unit
) : TextToSpeech.OnInitListener {

    private var engine: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var closed = false

    override fun onInit(status: Int) {
        if (closed) return
        val tts = engine
        if (status != TextToSpeech.SUCCESS || tts == null) {
            onLoaded(emptyList())
            return
        }
        ready = true
        val available = try {
            val portuguese = tts.voices.orEmpty().filter {
                it.locale.language == "pt" &&
                    (it.locale.country == "BR" || it.locale.country.isBlank())
            }
            val numbered = portuguese
                .distinctBy { it.name }
                .sortedWith(compareBy<Voice> { it.isNetworkConnectionRequired }.thenBy { it.name })
            numbered.mapIndexed { index, voice ->
                val origin = if (voice.isNetworkConnectionRequired) "precisa de internet" else "no aparelho"
                AndroidVoiceOption(
                    id = voice.name,
                    label = "Voz ${index + 1} · Português (Brasil) · $origin",
                    requiresNetwork = voice.isNetworkConnectionRequired
                )
            }
        } catch (e: Exception) {
            Log.w("ViaVoxVoice", "Unable to query installed Android voices", e)
            emptyList()
        }
        onLoaded(available)
    }

    fun preview(selectedVoiceId: String, speechRate: Float): Boolean {
        val tts = engine ?: return false
        if (!ready || closed) return false
        try {
            // Reset to pt-BR before previewing. The default entry never chooses
            // a randomly named engine voice.
            tts.setLanguage(Locale("pt", "BR"))
            if (selectedVoiceId.isNotBlank()) {
                val selected = tts.voices?.firstOrNull { it.name == selectedVoiceId && it.locale.language == "pt" }
                if (selected != null) tts.setVoice(selected)
            }
            tts.setSpeechRate(speechRate.coerceIn(0.7f, 1.4f))
            return tts.speak(
                "Você está na Rua Aristides Rocha, bairro Petrópolis.",
                TextToSpeech.QUEUE_FLUSH,
                null,
                "viavox_preview"
            ) == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            Log.w("ViaVoxVoice", "Could not preview selected voice", e)
            return false
        }
    }

    fun close() {
        closed = true
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
