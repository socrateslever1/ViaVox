package com.example.viavox.service

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast

/**
 * User-initiated way to complete missing TTS voice downloads.
 * The app never installs software silently or changes system settings.
 */
object VoiceDataInstaller {
    fun open(context: Context) {
        val options = listOf(
            Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
            Intent("com.android.settings.TTS_SETTINGS"),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (option in options) {
            try {
                val intent = option.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return
                }
            } catch (e: Exception) {
                Log.w("ViaVoxVoice", "TTS setup action unavailable", e)
            }
        }
        Toast.makeText(
            context, "Acesse Configurações → Gerenciamento geral → Texto para fala",
            Toast.LENGTH_LONG
        ).show()
    }
}
