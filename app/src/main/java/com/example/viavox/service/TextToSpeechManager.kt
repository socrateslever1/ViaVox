package com.example.viavox.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
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
    private val onSpeakingStateChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private val francisca = AzureFranciscaSpeech(context.applicationContext, onSpeakingStateChanged)
    var franciscaEndpoint: String?
        get() = francisca.endpoint
        set(value) {
            francisca.endpoint = value
            router.franciscaEndpoint = value
        }

    var franciscaAccessToken: String?
        get() = francisca.accessToken
        set(value) { francisca.accessToken = value }

    private var isInitialized = false
    private var currentSpeechRate = 0.95f
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
            val ptBr = Locale("pt", "BR")
            val langResult = tts?.setLanguage(ptBr)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("ViaVoxTTS", "pt-BR not directly available, falling back to default locale")
                tts?.setLanguage(Locale.getDefault())
            }

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
                    releaseAudioFocus()
                    onSpeakingStateChanged(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    releaseAudioFocus()
                    onSpeakingStateChanged(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    releaseAudioFocus()
                    onSpeakingStateChanged(false)
                }
            })
            isInitialized = true
            Log.d("ViaVoxTTS", "TextToSpeech initialized successfully")

            // Speak any utterance queued while engine was initializing
            pendingUtteranceBeforeInit?.let { queued ->
                pendingUtteranceBeforeInit = null
                if (router.isVoiceEnabled) {
                    speakLocally(queued)
                }
            }
        } else {
            Log.e("ViaVoxTTS", "Failed to initialize TextToSpeech engine")
        }
    }

    fun setVoiceEnabled(enabled: Boolean) {
        router.setVoiceEnabledState(enabled)
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
                AudioManager.STREAM_NOTIFICATION,
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

    private fun speakLocally(text: String) {
        if (!isInitialized) {
            pendingUtteranceBeforeInit = text
            return
        }
        francisca.stop()
        tts?.stop()
        requestAudioFocus()
        val utteranceId = "viavox_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
