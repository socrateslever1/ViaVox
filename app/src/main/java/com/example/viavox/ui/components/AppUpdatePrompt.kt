package com.example.viavox.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.viavox.data.AppUpdateInfo
import com.example.viavox.data.AppUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Checks this project's release for a newer APK and offers it in-app.
 * [openRequest] changes when the user taps the update notification, which
 * forces a fresh check and shows the offer even if it was postponed.
 */
@Composable
fun AppUpdatePrompt(openRequest: Int) {
    val context = LocalContext.current
    val manager = remember { AppUpdateManager(context) }
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(openRequest) {
        val explicit = openRequest > 0
        val found = manager.check(force = explicit) ?: return@LaunchedEffect
        manager.notifyAvailable(found)
        if (explicit || !manager.wasPostponed(found)) offer = found
    }

    val info = offer ?: return
    AlertDialog(
        modifier = Modifier.testTag("app_update_dialog"),
        onDismissRequest = {
            if (!downloading) { manager.postpone(info); offer = null }
        },
        title = { Text("Nova versão do ViaVox") },
        text = {
            Column {
                Text("A versão " + info.versionName + " está disponível (" +
                    (info.sizeBytes / (1024 * 1024)) + " MB).")
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.notes)
                }
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Baixando… " + (progress * 100).toInt() + "%")
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    progress = 0f
                    error = null
                    scope.launch {
                        try {
                            val apk = manager.download(info) { progress = it }
                            manager.clearNotification()
                            manager.install(apk)
                            offer = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = "Não foi possível baixar a atualização. " +
                                "Verifique a conexão e tente novamente."
                        } finally {
                            downloading = false
                        }
                    }
                }
            ) { Text(if (error == null) "Atualizar agora" else "Tentar de novo") }
        },
        dismissButton = {
            TextButton(
                enabled = !downloading,
                onClick = { manager.postpone(info); offer = null }
            ) { Text("Depois") }
        }
    )
}
