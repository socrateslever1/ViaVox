package com.example.viavox.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import okhttp3.CacheControl
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.viavox.BuildConfig
import com.example.viavox.MainActivity
import com.example.viavox.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Metadata published next to the APK by the Android build workflow. */
data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val sha256: String,
    val sizeBytes: Long,
    val notes: String
)

/** Pure rules: no Android types, covered by unit tests. */
object AppUpdatePolicy {
    const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val SHA256 = Regex("[0-9a-f]{64}")

    fun parse(json: String): AppUpdateInfo? {
        if (json.length > 20_000) return null
        val data = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val code = data.optInt("versionCode", 0)
        val name = data.optString("versionName").trim().take(40)
        val sha = data.optString("sha256").trim().lowercase()
        val size = data.optLong("size", 0L)
        if (code <= 0 || name.isBlank() || !SHA256.matches(sha) ||
            size !in 1_000_000L..MAX_APK_BYTES) return null
        return AppUpdateInfo(code, name, sha, size,
            data.optString("notes").trim().take(300))
    }

    fun isNewer(info: AppUpdateInfo, installedVersionCode: Int): Boolean =
        info.versionCode > installedVersionCode

    fun shouldCheck(nowMs: Long, lastCheckMs: Long, force: Boolean): Boolean =
        force || lastCheckMs <= 0L || nowMs < lastCheckMs ||
            nowMs - lastCheckMs >= CHECK_INTERVAL_MS

    const val CHECK_INTERVAL_MS = 5L * 60 * 1000
}

/**
 * Finds, downloads and hands a newer ViaVox APK to the system installer.
 * The APK comes only from this project's own GitHub release and is installed
 * only after its SHA-256 matches the published metadata. Android itself also
 * refuses any APK not signed with the same key as the installed app.
 */
class AppUpdateManager(
    context: Context,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("viavox_app_update", Context.MODE_PRIVATE)

    /** Returns a newer published build, or null when up to date/unreachable. */
    suspend fun check(force: Boolean = false): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!AppUpdatePolicy.shouldCheck(now, prefs.getLong("last_check", 0L), force)) {
            return@withContext cached()
        }
        try {
            val request = Request.Builder().url(METADATA_URL)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("User-Agent", "ViaVox-Android/" + BuildConfig.VERSION_NAME)
                .header("Accept", "application/json")
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext cached()
                response.body?.string().orEmpty()
            }
            val parsed = AppUpdatePolicy.parse(body) ?: return@withContext cached()
            val previous = AppUpdatePolicy.parse(prefs.getString("metadata", "").orEmpty())
            if (previous == null || parsed.versionCode >= previous.versionCode) {
                prefs.edit().putLong("last_check", now).putString("metadata", body).apply()
            }
            cached()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.i(TAG, "Update check unavailable", error)
            cached()
        }
    }

    private fun cached(): AppUpdateInfo? =
        AppUpdatePolicy.parse(prefs.getString("metadata", "").orEmpty())
            ?.takeIf { AppUpdatePolicy.isNewer(it, BuildConfig.VERSION_CODE) }

    fun wasPostponed(info: AppUpdateInfo): Boolean =
        prefs.getInt("postponed_code", 0) == info.versionCode &&
            System.currentTimeMillis() - prefs.getLong("postponed_at", 0L) in 0 until POSTPONE_MS

    fun postpone(info: AppUpdateInfo) {
        prefs.edit().putInt("postponed_code", info.versionCode)
            .putLong("postponed_at", System.currentTimeMillis()).apply()
    }

    private fun updateFile(info: AppUpdateInfo): File =
        File(File(context.cacheDir, "updates"), "ViaVox-" + info.versionCode + ".apk")

    /** Verify a previous download before reusing it, including after process death. */
    suspend fun readyApk(info: AppUpdateInfo): File? = withContext(Dispatchers.IO) {
        val target = updateFile(info)
        if (!AppUpdatePolicy.isNewer(info, BuildConfig.VERSION_CODE) ||
            !target.isFile || target.length() != info.sizeBytes) return@withContext null
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            target.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            target.takeIf { digest.digest().joinToString("") { "%02x".format(it) } == info.sha256 }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun requestInstallPermission() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Downloads and verifies the APK; returns the file ready for install. */
    suspend fun download(info: AppUpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val folder = File(context.cacheDir, "updates").apply { mkdirs() }
            readyApk(info)?.let { return@withContext it }
            val target = updateFile(info)
            val partial = File(folder, target.name + ".part")
            partial.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                val request = Request.Builder().url(APK_URL)
                    .header("User-Agent", "ViaVox-Android/" + BuildConfig.VERSION_NAME)
                    .header("Accept", "application/octet-stream")
                    .build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP " + response.code }
                    val body = checkNotNull(response.body) { "Resposta vazia" }
                    partial.outputStream().buffered().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var written = 0L
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                check(written <= AppUpdatePolicy.MAX_APK_BYTES) { "APK excede o limite" }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                                onProgress((written.toFloat() / info.sizeBytes).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(partial.length() == info.sizeBytes && actual == info.sha256) {
                    // The release was replaced mid-download or the file is corrupt.
                    "Arquivo não confere com a versão publicada"
                }
                check(partial.renameTo(target)) { "Não foi possível salvar o APK" }
                folder.listFiles()?.filter { it != target }?.forEach { it.delete() }
                target
            } catch (error: Exception) {
                partial.delete()
                // Metadata may be stale if a newer build was just published.
                prefs.edit().putLong("last_check", 0L).apply()
                throw error
            }
        }

    /** Opens the system installer; the user confirms the update there. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(
            context, context.packageName + ".fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** One notification per build and an updated notification when ready. */
    fun notifyAvailable(info: AppUpdateInfo, ready: Boolean = false) {
        if (prefs.getInt("notified_code", 0) == info.versionCode &&
            (!ready || prefs.getInt("ready_notified_code", 0) == info.versionCode)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Atualizações do ViaVox",
                NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SHOW_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (!manager.areNotificationsEnabled() ||
            manager.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) return
        val text = if (ready) "Atualização baixada. Toque para instalar."
            else "Versão " + info.versionName + " disponível. Toque para atualizar."
        manager.notify(NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(if (ready) "ViaVox pronto para atualizar" else "Nova versão do ViaVox")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    if (info.notes.isBlank()) text else text + "\n" + info.notes))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        prefs.edit().putInt("notified_code", info.versionCode).apply {
            if (ready) putInt("ready_notified_code", info.versionCode)
        }.apply()
    }

    fun clearNotification() {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "ViaVoxUpdate"
        private const val RELEASE =
            "https://github.com/socrateslever1/ViaVox/releases/download/viavox-debug-latest/"
        const val METADATA_URL = RELEASE + "version.json"
        const val APK_URL = RELEASE + "ViaVox.apk"
        const val EXTRA_SHOW_UPDATE = "viavox.show_update"
        private const val CHANNEL_ID = "viavox_app_updates"
        private const val NOTIFICATION_ID = 4107
        private const val POSTPONE_MS = 12L * 60 * 60 * 1000
    }
}
