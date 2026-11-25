package com.example.viavox.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.viavox.data.AppUpdateInfo
import com.example.viavox.data.AppUpdateManager
import com.example.viavox.data.AppUpdatePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Discover new builds on foreground entry and periodically while visible.
 * A downloaded APK stays available until the installed version changes.
 */
@Composable
fun AppUpdatePrompt(openRequest: Int) {
    val context = LocalContext.current
    val manager = remember { AppUpdateManager(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var offer by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var ready by remember { mutableStateOf<File?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var explicitHandled by remember { mutableIntStateOf(0) }

    LaunchedEffect(lifecycle, openRequest) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val explicit = openRequest > explicitHandled
                if (!downloading) {
                    val found = manager.check(force = explicit)
                    if (explicit) explicitHandled = openRequest
                    if (found != null && !downloading) {
                        val apk = manager.readyApk(found)
                        if (!downloading) {
                            manager.notifyAvailable(found, ready = apk != null)
                            if (explicit || !manager.wasPostponed(found)) {
                                if (offer?.versionCode != found.versionCode) error = null
                                offer = found
                                ready = apk
                            }
                        }
                    }
                }
                delay(AppUpdatePolicy.CHECK_INTERVAL_MS)
            }
        }
    }

    val info = offer ?: return
    AlertDialog(
        modifier = Modifier.testTag("app_update_dialog"),
        onDismissRequest = {
            if (!downloading) { manager.postpone(info); offer = null; ready = null }
        },
        title = { Text(if (ready != null) "Atualização pronta para instalar" else "Nova versão do ViaVox") },
        text = {
            Column {
                Text("Versão " + info.versionName + " · compilação " + info.versionCode +
                    " (" + (info.sizeBytes / (1024 * 1024)) + " MB).")
                if (ready != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Download concluído e verificado. Confirme a instalação pelo Android.")
                }
                if (info.notes.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(info.notes)
                }
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
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
                            val apk = manager.readyApk(info) ?: manager.download(info) { progress = it }
                            ready = apk
                            manager.notifyAvailable(info, ready = true)
                            if (!manager.canInstall()) {
                                error = "Autorize a instalação de apps por esta fonte e volte para tocar em Instalar."
                                manager.requestInstallPermission()
                            } else {
                                manager.install(apk)
                                // Keep the verified file and dialog if Android installation is cancelled.
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            error = if (ready != null)
                                "Não foi possível abrir o instalador. Toque em Instalar novamente."
                            else "Não foi possível baixar a atualização. Verifique a conexão e tente novamente."
                        } finally {
                            downloading = false
                        }
                    }
                }
            ) { Text(if (ready != null) "Instalar" else if (error != null) "Tentar de novo" else "Atualizar agora") }
        },
        dismissButton = {
            TextButton(
                enabled = !downloading,
                onClick = { manager.postpone(info); offer = null; ready = null }
            ) { Text("Depois") }
        }
    )
}
