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

class TextToSpeechManager(
    private val context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private val francisca = AzureFranciscaSpeech(context.applicationContext, onSpeakingStateChanged)
    var franciscaEndpoint: String?
        get() = francisca.endpoint
        set(value) { francisca.endpoint = value }
    private var isInitialized = false
    private var currentSpeechRate = 0.95f

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ptBr = Locale("pt", "BR")
            val langResult = tts?.setLanguage(ptBr)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w("ViaVoxTTS", "pt-BR not directly available, falling back to default locale")
                tts?.setLanguage(Locale.getDefault())
            }

            tts?.setSpeechRate(currentSpeechRate)

            // Configure audio attributes for speech guidance
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
        } else {
            Log.e("ViaVoxTTS", "Failed to initialize TextToSpeech engine")
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

    fun speak(text: String) {
        if (text.isBlank()) return
        if (!franciscaEndpoint.isNullOrBlank()) {
            tts?.stop()
            francisca.speak(text) { speakLocally(text) }
            return
        }
        speakLocally(text)
    }

    private fun speakLocally(text: String) {
        if (!isInitialized) return
        tts?.stop()
        requestAudioFocus()
        val utteranceId = "viavox_${System.currentTimeMillis()}"
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        francisca.stop()
        tts?.stop()
        releaseAudioFocus()
        onSpeakingStateChanged(false)
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        francisca.shutdown()
        tts = null
        isInitialized = false
    }
}
