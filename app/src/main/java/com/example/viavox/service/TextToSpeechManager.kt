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
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Speech routing is independent of the Android engine so it can be unit tested.
 * The selected online voice is attempted only after a real endpoint is configured.
 */
class SpeechFallbackRouter(
    var isVoiceEnabled: Boolean = true,
    var franciscaEndpoint: String? = null,
    private val primarySpeak: (text: String, endpoint: String, onUnavailable: () -> Unit) -> Unit,
    private val fallbackSpeak: (text: String) -> Unit,
    private val stopAll: () -> Unit
) {
    private var lastSpokenMessage: String? = null
    private var lastSpokenTimeMs = 0L

    fun setVoiceEnabledState(enabled: Boolean) {
        isVoiceEnabled = enabled
        if (!enabled) stopAll()
    }

    fun speak(text: String, forceRepeat: Boolean = false, nowMs: Long = System.currentTimeMillis()): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || !isVoiceEnabled) return false
        if (!forceRepeat && clean == lastSpokenMessage && nowMs - lastSpokenTimeMs < 15_000L) {
            return false
        }
        lastSpokenMessage = clean
        lastSpokenTimeMs = nowMs
        val endpoint = franciscaEndpoint?.trim().orEmpty()
        if (endpoint.startsWith("https://")) {
            primarySpeak(clean, endpoint) {
                if (isVoiceEnabled) fallbackSpeak(clean)
            }
        } else fallbackSpeak(clean)
        return true
    }

    fun stop() = stopAll()
}

/** Installed means that this engine will not have to download data to synthesize. */
fun isInstalledPortugueseVoice(voice: Voice): Boolean =
    voice.locale.language == "pt" &&
        !voice.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)

/** Prefer a genuine, downloaded Brazilian offline voice; never force unfinished downloads. */
fun selectReadyPortugueseVoice(voices: Collection<Voice>, preferredId: String = "", excludeId: String = ""): Voice? {
    val installed = voices.filter { isInstalledPortugueseVoice(it) && it.name != excludeId }
    return installed.firstOrNull { it.name == preferredId }
        ?: installed.sortedWith(
            compareBy<Voice> { if (it.locale.country == "BR") 0 else 1 }
                .thenBy { if (it.isNetworkConnectionRequired) 1 else 0 }
                .thenByDescending { it.quality }
        ).firstOrNull()
}

class TextToSpeechManager(
    private val context: Context,
    private val onSpeakingStateChanged: (Boolean) -> Unit = {},
    private val onVoiceIssue: (String?) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private val francisca = AzureFranciscaSpeech(context.applicationContext, onSpeakingStateChanged)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var isInitialized = false
    private var initFailed = false
    private var voiceProvider = "android"
    private var nativeVoiceId = ""
    private var currentSpeechRate = 0.95f
    private var queuedBeforeInit: String? = null
    private var completionCallback: (() -> Unit)? = null
    private var managedTimeout: Runnable? = null

    private var activeUtteranceId = ""
    private var activeText = ""
    private var retriedMissingData = false
    private var lastFailedVoice = ""

    var franciscaEndpoint: String?
        get() = francisca.endpoint
        set(value) {
            francisca.endpoint = value
            router.franciscaEndpoint = if (voiceProvider == "francisca") value else null
        }

    var franciscaAccessToken: String?
        get() = francisca.accessToken
        set(value) { francisca.accessToken = value }

    private val router = SpeechFallbackRouter(
        primarySpeak = { text, endpoint, fallback ->
            tts?.stop()
            francisca.endpoint = endpoint
            requestAudioFocus()
            francisca.speak(text, fallback) { onManagedFinished() }
        },
        fallbackSpeak = { speakLocally(it) },
        stopAll = {
            queuedBeforeInit = null
            completionCallback = null
            managedTimeout?.let { mainHandler.removeCallbacks(it) }
            managedTimeout = null
            activeUtteranceId = ""
            francisca.stop()
            tts?.stop()
            releaseAudioFocus()
            onSpeakingStateChanged(false)
        }
    )

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            initFailed = true
            reportVoiceIssue("O motor de voz não iniciou. Abra as configurações de síntese de voz.")
            Log.e(TAG, "TTS engine initialization failed", e)
        }
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            initFailed = true
            reportVoiceIssue("O sintetizador de voz do Android não está disponível.")
            onManagedFinished()
            return
        }
        isInitialized = true
        initFailed = false
        val engine = tts ?: return
        configureAudio(engine)
        applyInstalledVoiceSelection()
        engine.setSpeechRate(currentSpeechRate)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == activeUtteranceId) onSpeakingStateChanged(true)
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId != activeUtteranceId) return
                activeUtteranceId = ""
                reportVoiceIssue(null)
                releaseAudioFocus()
                onSpeakingStateChanged(false)
                onManagedFinished()
            }
            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) =
                handleSynthesisError(utteranceId, TextToSpeech.ERROR)
            override fun onError(utteranceId: String?, errorCode: Int) =
                handleSynthesisError(utteranceId, errorCode)
        })
        queuedBeforeInit?.let {
            queuedBeforeInit = null
            if (router.isVoiceEnabled) speakLocally(it)
        }
    }

    private fun configureAudio(engine: TextToSpeech) {
        engine.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
    }

    private fun applyInstalledVoiceSelection(exclude: String = ""): Boolean {
        val engine = tts ?: return false
        val available = try { engine.voices.orEmpty() } catch (_: Exception) { emptySet() }
        val selected = selectReadyPortugueseVoice(available, nativeVoiceId, exclude)
        if (selected != null && engine.setVoice(selected) == TextToSpeech.SUCCESS) {
            Log.d(TAG, "Using ready voice: ${selected.name} (offline=${!selected.isNetworkConnectionRequired})")
            reportVoiceIssue(null)
            return true
        }
        // setLanguage may initiate a download; don't claim it succeeded before playback.
        val localeResult = engine.setLanguage(Locale("pt", "BR"))
        reportVoiceIssue(
            if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED)
                "Falta a voz em português. Toque em Instalar voz para baixar os dados."
            else
                "Voz em português ainda não disponível. Toque em Instalar voz."
        )
        return false
    }

    private fun handleSynthesisError(utteranceId: String?, errorCode: Int) {
        if (utteranceId != activeUtteranceId) return
        if (errorCode == TextToSpeech.ERROR_NOT_INSTALLED_YET && !retriedMissingData) {
            retriedMissingData = true
            mainHandler.post {
                val failed = tts?.voice?.name.orEmpty()
                lastFailedVoice = failed
                val replacement = try {
                    selectReadyPortugueseVoice(tts?.voices.orEmpty(), excludeId = failed)
                } catch (_: Exception) { null }
                if (replacement != null && tts?.setVoice(replacement) == TextToSpeech.SUCCESS) {
                    Log.w(TAG, "Voice data missing for $failed; trying ready voice ${replacement.name}")
                    val sentence = activeText
                    queueLocalSpeech(sentence, retry = true)
                } else {
                    failLocalSpeech("A voz selecionada ainda não foi baixada (erro -9). Toque em Instalar voz.")
                }
            }
            return
        }
        val message = when (errorCode) {
            TextToSpeech.ERROR_NOT_INSTALLED_YET ->
                "Voz ainda não instalada (erro -9). Toque em Instalar voz nas configurações."
            TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT ->
                "Falha de rede da voz. Escolha uma voz offline nas configurações."
            TextToSpeech.ERROR_OUTPUT ->
                "Falha na saída de áudio. Verifique o volume de mídia e a saída Bluetooth."
            else -> "Falha de narração Android (código $errorCode). Teste outra voz nas configurações."
        }
        failLocalSpeech(message)
    }

    private fun failLocalSpeech(message: String) {
        activeUtteranceId = ""
        reportVoiceIssue(message)
        releaseAudioFocus()
        onSpeakingStateChanged(false)
        onManagedFinished()
    }

    fun setVoiceSelection(provider: String, androidVoiceId: String) {
        voiceProvider = if (provider == "francisca") "francisca" else "android"
        nativeVoiceId = androidVoiceId
        router.franciscaEndpoint = if (voiceProvider == "francisca") francisca.endpoint else null
        if (isInitialized) applyInstalledVoiceSelection()
    }

    fun setVoiceEnabled(enabled: Boolean) = router.setVoiceEnabledState(enabled)

    fun setSpeechRate(rate: Float) {
        currentSpeechRate = rate.coerceIn(0.7f, 1.4f)
        if (isInitialized) tts?.setSpeechRate(currentSpeechRate)
    }

    private fun reportVoiceIssue(issue: String?) {
        mainHandler.post { onVoiceIssue(issue) }
    }

    fun speak(text: String, forceRepeat: Boolean = false) {
        router.speak(text, forceRepeat)
    }

    /** Caller serializes play requests; callback fires on *actual* playback end. */
    fun speakManaged(text: String, onFinished: () -> Unit) {
        // A serialized queue must be the sole owner of managed playback.
        // Generation checks in SpeechOrchestrator reject obsolete completions.
        managedTimeout?.let { mainHandler.removeCallbacks(it) }
        completionCallback = onFinished
        val watchdog = Runnable {
            if (completionCallback === onFinished) {
                Log.e(TAG,"Managed speech timed out; recovering serialized queue")
                reportVoiceIssue("A voz demorou demais para responder. Tente novamente.")
                francisca.stop()
                tts?.stop()
                onSpeakingStateChanged(false)
                onManagedFinished()
            }
        }
        managedTimeout = watchdog
        // Cloud synthesis can take a few seconds; the bound prevents an
        // offline/remote engine that never emits onDone from blocking all speech.
        val timeoutMs = (text.length * 150L + 10_000L).coerceIn(16_000L,45_000L)
        mainHandler.postDelayed(watchdog,timeoutMs)
        if (!router.speak(text, forceRepeat = true)) onManagedFinished()
    }

    private fun onManagedFinished() {
        val callback = completionCallback ?: return
        completionCallback = null
        managedTimeout?.let { mainHandler.removeCallbacks(it) }
        managedTimeout = null
        mainHandler.post { callback() }
    }

    fun testVoice() {
        // A diagnostic test must not fail merely because the voice toggle is off.
        if (!router.isVoiceEnabled) {
            reportVoiceIssue("Ative o botão de voz no painel para ouvir os anúncios.")
        } else {
            speak("ViaVox. Teste de voz em português.", forceRepeat = true)
        }
    }

    private fun speakLocally(text: String) {
        if (!isInitialized) {
            if (initFailed) {
                reportVoiceIssue("Motor de voz indisponível. Instale uma voz em português.")
                onManagedFinished()
            } else queuedBeforeInit = text
            return
        }
        francisca.stop()
        tts?.stop()
        activeText = text
        retriedMissingData = false
        queueLocalSpeech(text, retry = false)
    }

    private fun queueLocalSpeech(text: String, retry: Boolean) {
        val engine = tts ?: return
        requestAudioFocus()
        val utteranceId = "viavox_${System.nanoTime()}"
        activeUtteranceId = utteranceId
        try {
            val rc = engine.speak(text, if (retry) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, utteranceId)
            if (rc != TextToSpeech.SUCCESS) {
                failLocalSpeech("O Android rejeitou a reprodução. Verifique a voz instalada.")
            } else if (!retry) {
                // Error state is only cleared on actual successful synthesis (onDone).
                Log.d(TAG, "TTS playback requested")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS speak exception", e)
            failLocalSpeech("Não foi possível iniciar a reprodução de voz.")
        }
    }

    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus unavailable", e)
        }
    }

    private fun releaseAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
    }

    fun stop() = router.stop()

    fun shutdown() {
        stop()
        tts?.shutdown()
        francisca.shutdown()
        tts = null
        isInitialized = false
    }

    private companion object { const val TAG = "ViaVoxTTS" }
}
