package com.example.viavox.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Coordinates primary voice (Microsoft Azure pt-BR-FranciscaNeural via HTTPS broker)
 * and fallback voice (Android native TextToSpeech), respecting user voice toggle
 * and prioritizing street announcements without overlapping utterances.
 */
class SpeechFallbackRouter(
    var isVoiceEnabled: Boolean = true,
    var franciscaEndpoint: String? = null,
    private val primarySpeak: (text: String, endpoint: String, onUnavailable: () -> Unit) -> Unit,
    private val fallbackSpeak: (text: String) -> Unit,
    private val stopAll: () -> Unit
) {
    private var lastSpokenMessage: String? = null
    private var lastSpokenTimeMs: Long = 0L

    fun setVoiceEnabledState(enabled: Boolean) {
        isVoiceEnabled = enabled
        if (!enabled) {
            stopAll()
        }
    }

    fun speak(text: String, forceRepeat: Boolean = false, nowMs: Long = System.currentTimeMillis()): Boolean {
        val cleanText = text.trim()
        if (cleanText.isBlank() || !isVoiceEnabled) return false

        if (!forceRepeat && cleanText == lastSpokenMessage && (nowMs - lastSpokenTimeMs) < 15_000L) {
            return false
        }

        lastSpokenMessage = cleanText
        lastSpokenTimeMs = nowMs

        val endpoint = franciscaEndpoint?.trim()
        if (!endpoint.isNullOrBlank() && endpoint.startsWith("https://")) {
            primarySpeak(cleanText, endpoint) {
                if (isVoiceEnabled) {
                    fallbackSpeak(cleanText)
                }
            }
        } else {
            fallbackSpeak(cleanText)
        }
        return true
    }

    fun stop() {
        stopAll()
    }
}

class TextToSpeechManager(
    private val context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit = {},
    private val onVoiceIssue: (String?) -> Unit = {}
) : TextToSpeech.OnInitListener {

    // Create TTS only after all callbacks/router state exist; an engine may call onInit quickly.
    private var tts: TextToSpeech? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val francisca = AzureFranciscaSpeech(context.applicationContext, onSpeakingStateChanged)
    var franciscaEndpoint: String?
        get() = francisca.endpoint
        set(value) {
            francisca.endpoint = value
            router.franciscaEndpoint = if (voiceProvider == "francisca") value else null
        }

    var franciscaAccessToken: String?
        get() = francisca.accessToken
        set(value) { francisca.accessToken = value }

    private var isInitialized = false
    private var initFailed = false
    private var currentSpeechRate = 0.95f
    private var voiceProvider = "android"
    private var selectedAndroidVoiceId = ""
    private var pendingUtteranceBeforeInit: String? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val router = SpeechFallbackRouter(
        isVoiceEnabled = true,
        franciscaEndpoint = francisca.endpoint,
        primarySpeak = { text, endpoint, onUnavailable ->
            tts?.stop()
            francisca.endpoint = endpoint
            requestAudioFocus()
            francisca.speak(text) {
                onUnavailable()
            }
        },
        fallbackSpeak = { text ->
            speakLocally(text)
        },
        stopAll = {
            pendingUtteranceBeforeInit = null
            francisca.stop()
            tts?.stop()
            releaseAudioFocus()
            onSpeakingStateChanged(false)
        }
    )

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            applyNativeVoiceSelection()

            tts?.setSpeechRate(currentSpeechRate)

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(attributes)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    onSpeakingStateChanged(true)
                }

                override fun onDone(utteranceId: String?) {
                    reportVoiceIssue(null)
                    releaseAudioFocus()
                    onSpeakingStateChanged(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    reportVoiceIssue("A voz do Android não conseguiu reproduzir. Verifique o mecanismo de texto para fala.")
                    releaseAudioFocus()
                    onSpeakingStateChanged(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    reportVoiceIssue("Falha de narração Android (código $errorCode). Confira as vozes instaladas.")
                    releaseAudioFocus()
                    onSpeakingStateChanged(false)
                }
            })
            isInitialized = true
            initFailed = false
            reportVoiceIssue(null)
            Log.d("ViaVoxTTS", "TextToSpeech initialized successfully")

            // Speak any utterance queued while engine was initializing
            pendingUtteranceBeforeInit?.let { queued ->
                pendingUtteranceBeforeInit = null
                if (router.isVoiceEnabled) {
                    speakLocally(queued)
                }
            }
        } else {
            initFailed = true
            pendingUtteranceBeforeInit = null
            reportVoiceIssue("Motor de voz indisponível. Ative ou instale uma voz em Configurações do Android.")
            Log.e("ViaVoxTTS", "Failed to initialize TextToSpeech engine")
        }
    }

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            initFailed = true
            reportVoiceIssue("O mecanismo de voz do Android não pôde ser iniciado.")
            Log.e("ViaVoxTTS", "Could not create TTS engine", e)
        }
    }

    private fun reportVoiceIssue(message: String?) {
        mainHandler.post { onVoiceIssue(message) }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        router.setVoiceEnabledState(enabled)
    }

    /**
     * By default only the Android synthesizer speaks. Selecting the online
     * Francisca option opts into Azure; it falls back to the Android voice
     * when the broker is missing or unavailable.
     */
    fun setVoiceSelection(provider: String, androidVoiceId: String) {
        voiceProvider = if (provider == "francisca") "francisca" else "android"
        selectedAndroidVoiceId = androidVoiceId
        router.franciscaEndpoint = if (voiceProvider == "francisca") francisca.endpoint else null
        if (isInitialized) applyNativeVoiceSelection()
    }

    private fun applyNativeVoiceSelection() {
        val speech = tts ?: return
        val ptBr = Locale("pt", "BR")
        val langResult = speech.setLanguage(ptBr)
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.w("ViaVoxTTS", "pt-BR voice missing; using engine default")
            speech.setLanguage(Locale.getDefault())
        }
        if (selectedAndroidVoiceId.isNotBlank()) {
            try {
                val selected = speech.voices?.firstOrNull { voice ->
                    voice.name == selectedAndroidVoiceId && voice.locale.language == "pt"
                }
                if (selected != null) {
                    val result = speech.setVoice(selected)
                    if (result != TextToSpeech.SUCCESS) {
                        Log.w("ViaVoxTTS", "Selected Android voice unavailable; default retained")
                        speech.setLanguage(ptBr)
                    }
                } else {
                    Log.w("ViaVoxTTS", "Selected voice is no longer installed; using default")
                }
            } catch (e: Exception) {
                Log.w("ViaVoxTTS", "Unable to apply Android voice; using default", e)
            }
        }
    }

    fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.7f, 1.4f)
        if (isInitialized) {
            tts?.setSpeechRate(currentSpeechRate)
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (audioManager == null) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* Focus change listener */ }
                .build()

            audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releaseAudioFocus() {
        if (audioManager == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }

    fun speak(text: String, forceRepeat: Boolean = false) {
        router.speak(text, forceRepeat = forceRepeat)
    }

    fun testVoice() {
        // Voice test always runs through selected TTS provider + local fallback.
        speak("ViaVox. Teste de voz. Você está na Rua Aristides Rocha.", forceRepeat = true)
    }

    private fun speakLocally(text: String) {
        if (!isInitialized) {
            if (initFailed) {
                reportVoiceIssue("Voz Android não inicializada. Verifique as configurações de síntese de voz.")
            } else {
                pendingUtteranceBeforeInit = text
            }
            return
        }
        francisca.stop()
        tts?.stop()
        requestAudioFocus()
        val utteranceId = "viavox_${System.currentTimeMillis()}"
        try {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                releaseAudioFocus()
                reportVoiceIssue("Não foi possível iniciar a fala. Verifique a voz e o volume de mídia.")
                onSpeakingStateChanged(false)
            } else {
                reportVoiceIssue(null)
            }
        } catch (e: Exception) {
            releaseAudioFocus()
            reportVoiceIssue("Falha ao usar o mecanismo de voz do Android.")
            Log.w("ViaVoxTTS", "Android voice playback error", e)
        }
    }

    fun stop() {
        router.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        francisca.shutdown()
        tts = null
        isInitialized = false
    }
}
