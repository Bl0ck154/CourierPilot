package com.block154.courierpilot

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val APP_UPDATE_PREFS = "courierpilot_app_updates"
private const val KEY_AUTO_DOWNLOAD = "auto_download"
private const val KEY_WIFI_ONLY = "wifi_only"
private const val KEY_CHECK_INTERVAL_MS = "check_interval_ms"
private const val KEY_LAST_CHECK_AT = "last_check_at"
private const val KEY_LATEST_VERSION = "latest_version"
private const val KEY_READY_VERSION = "ready_version"
private const val KEY_READY_PATH = "ready_path"
private const val KEY_LAST_ERROR = "last_error"
private const val KEY_DISMISSED_VERSION = "dismissed_version"

internal enum class AppUpdateCheckFrequency(val intervalMs: Long, val label: String) {
    FIFTEEN_MINUTES(15L * 60L * 1000L, "Every 15 minutes"),
    THIRTY_MINUTES(30L * 60L * 1000L, "Every 30 minutes"),
    ONE_HOUR(60L * 60L * 1000L, "Every 1 hour"),
    THREE_HOURS(3L * 60L * 60L * 1000L, "Every 3 hours"),
    SIX_HOURS(6L * 60L * 60L * 1000L, "Every 6 hours");

    companion object {
        fun fromIntervalMs(value: Long): AppUpdateCheckFrequency =
            entries.firstOrNull { it.intervalMs == value } ?: ONE_HOUR
    }
}

internal object AppUpdateSettings {
    fun autoDownload(context: Context): Boolean =
        context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_DOWNLOAD, true)

    fun setAutoDownload(context: Context, enabled: Boolean) {
        context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_DOWNLOAD, enabled)
            .apply()
    }

    fun wifiOnly(context: Context): Boolean =
        context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIFI_ONLY, enabled)
            .apply()
    }

    fun checkFrequency(context: Context): AppUpdateCheckFrequency {
        val value = context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_CHECK_INTERVAL_MS, AppUpdateCheckFrequency.ONE_HOUR.intervalMs)
        return AppUpdateCheckFrequency.fromIntervalMs(value)
    }

    fun setCheckFrequency(context: Context, frequency: AppUpdateCheckFrequency) {
        context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CHECK_INTERVAL_MS, frequency.intervalMs)
            .apply()
        BackgroundAppUpdateScheduler.reschedule(context.applicationContext)
    }

    fun lastCheckAt(context: Context): Long =
        context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK_AT, 0L)

    internal fun dismissedVersion(context: Context): String? =
        context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DISMISSED_VERSION, null)

    internal fun dismissVersion(context: Context, version: String?) {
        if (version.isNullOrBlank()) return
        context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DISMISSED_VERSION, version)
            .apply()
    }
}

internal enum class AppUpdatePhase {
    IDLE,
    CHECKING,
    AVAILABLE,
    DOWNLOADING,
    READY,
    UP_TO_DATE,
    ERROR,
}

internal data class AppUpdateStatus(
    val phase: AppUpdatePhase,
    val version: String? = null,
    val progressPercent: Int? = null,
    val message: String,
)

internal enum class InstallLaunchResult {
    INSTALLER_OPENED,
    PERMISSION_SETTINGS_OPENED,
    NOT_READY,
}

internal object AppUpdateVersion {
    fun normalize(raw: String?): String? {
        val value = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
        if (value.isBlank()) return null
        val core = value.substringBefore('-').substringBefore('+')
        if (core.split('.').any { it.isBlank() || it.toIntOrNull() == null }) return null
        return core
    }

    fun compare(left: String?, right: String?): Int {
        val a = normalize(left)?.split('.')?.map(String::toInt) ?: return 0
        val b = normalize(right)?.split('.')?.map(String::toInt) ?: return 0
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    fun isNewer(candidate: String?, current: String?): Boolean = compare(candidate, current) > 0
}

internal object AppUpdateIntegrity {
    private val SHA256_PATTERN = Regex("(?i)(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")

    fun parseSha256(text: String): String? =
        SHA256_PATTERN.find(text)?.value?.lowercase(Locale.US)
}

internal object AppUpdateManager {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/Bl0ck154/CourierPilot/releases/latest"
    private const val EXPECTED_SIGNER_SHA256 =
        "74556417f1289281bcaf1a2c6f3f4aa119db24b079a13759a583c3cc66796b70"
    private const val UPDATE_CHANNEL_ID = "courierpilot_updates"
    internal const val UPDATE_NOTIFICATION_ID = 1550

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CourierPilotAppUpdate").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val inFlight = AtomicBoolean(false)

    fun snapshot(context: Context): AppUpdateStatus {
        val prefs = prefs(context)
        val readyVersion = prefs.getString(KEY_READY_VERSION, null)
        val readyPath = prefs.getString(KEY_READY_PATH, null)
        if (
            AppUpdateVersion.isNewer(readyVersion, BuildConfig.VERSION_NAME) &&
            !readyPath.isNullOrBlank() &&
            File(readyPath).isFile
        ) {
            return AppUpdateStatus(
                phase = AppUpdatePhase.READY,
                version = readyVersion,
                message = "CourierPilot $readyVersion is downloaded and verified.",
            )
        }

        val lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty()
        if (lastError.isNotBlank()) {
            return AppUpdateStatus(AppUpdatePhase.ERROR, message = lastError)
        }

        val latest = prefs.getString(KEY_LATEST_VERSION, null)
        if (AppUpdateVersion.isNewer(latest, BuildConfig.VERSION_NAME)) {
            return AppUpdateStatus(
                AppUpdatePhase.AVAILABLE,
                version = latest,
                message = "CourierPilot $latest is available.",
            )
        }

        return if (prefs.getLong(KEY_LAST_CHECK_AT, 0L) > 0L) {
            AppUpdateStatus(
                AppUpdatePhase.UP_TO_DATE,
                version = BuildConfig.VERSION_NAME,
                message = "You're up to date.",
            )
        } else {
            AppUpdateStatus(
                AppUpdatePhase.IDLE,
                version = BuildConfig.VERSION_NAME,
                message = "Automatic checks: ${AppUpdateSettings.checkFrequency(context).label.lowercase()}.",
            )
        }
    }

    fun checkNow(context: Context, onStatus: (AppUpdateStatus) -> Unit) {
        startCheck(
            context = context.applicationContext,
            manual = true,
            onStatus = onStatus,
            onComplete = null,
        )
    }

    fun checkIfDue(context: Context, onComplete: (() -> Unit)? = null) {
        val app = context.applicationContext
        val lastCheckAt = prefs(app).getLong(KEY_LAST_CHECK_AT, 0L)
        val intervalMs = AppUpdateSettings.checkFrequency(app).intervalMs
        if (lastCheckAt > 0L && System.currentTimeMillis() - lastCheckAt < intervalMs) {
            onComplete?.invoke()
            return
        }
        startCheck(app, manual = false, onStatus = null, onComplete = onComplete)
    }

    fun requestInstall(context: Context): InstallLaunchResult {
        val app = context.applicationContext
        val prefs = prefs(app)
        val readyVersion = prefs.getString(KEY_READY_VERSION, null)
        val readyPath = prefs.getString(KEY_READY_PATH, null)
        if (!AppUpdateVersion.isNewer(readyVersion, BuildConfig.VERSION_NAME) || readyPath.isNullOrBlank()) {
            return InstallLaunchResult.NOT_READY
        }
        val apk = File(readyPath)
        if (!apk.isFile) return InstallLaunchResult.NOT_READY

        if (!app.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${app.packageName}"),
            ).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return InstallLaunchResult.PERMISSION_SETTINGS_OPENED
        }

        val uri = FileProvider.getUriForFile(
            app,
            "${app.packageName}.researchfiles",
            apk,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        cancelNotification(app)
        context.startActivity(installIntent)
        return InstallLaunchResult.INSTALLER_OPENED
    }

    internal fun dismissNotification(context: Context, version: String?) {
        AppUpdateSettings.dismissVersion(context, version)
        cancelNotification(context)
    }

    private fun startCheck(
        context: Context,
        manual: Boolean,
        onStatus: ((AppUpdateStatus) -> Unit)?,
        onComplete: (() -> Unit)?,
    ) {
        if (!inFlight.compareAndSet(false, true)) {
            emit(onStatus, AppUpdateStatus(AppUpdatePhase.CHECKING, message = "Update check already running…"))
            finish(onComplete)
            return
        }

        emit(onStatus, AppUpdateStatus(AppUpdatePhase.CHECKING, message = "Checking GitHub Releases…"))
        executor.execute {
            try {
                performCheck(context, manual, onStatus)
            } catch (error: Throwable) {
                val message = updateErrorMessage(error)
                prefs(context).edit().putString(KEY_LAST_ERROR, message).apply()
                emit(onStatus, AppUpdateStatus(AppUpdatePhase.ERROR, message = message))
            } finally {
                inFlight.set(false)
                finish(onComplete)
            }
        }
    }

    private fun performCheck(
        context: Context,
        manual: Boolean,
        onStatus: ((AppUpdateStatus) -> Unit)?,
    ) {
        val release = fetchLatestRelease()
        prefs(context).edit()
            .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            .putString(KEY_LATEST_VERSION, release.version)
            .putString(KEY_LAST_ERROR, "")
            .apply()

        if (!AppUpdateVersion.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            clearReadyUpdate(context)
            emit(
                onStatus,
                AppUpdateStatus(
                    AppUpdatePhase.UP_TO_DATE,
                    version = BuildConfig.VERSION_NAME,
                    message = "You're up to date. CourierPilot ${BuildConfig.VERSION_NAME} is the latest release.",
                ),
            )
            return
        }

        val savedReadyVersion = prefs(context).getString(KEY_READY_VERSION, null)
        val savedReadyPath = prefs(context).getString(KEY_READY_PATH, null)
        if (
            AppUpdateVersion.normalize(savedReadyVersion) == AppUpdateVersion.normalize(release.version) &&
            !savedReadyPath.isNullOrBlank() &&
            File(savedReadyPath).isFile
        ) {
            val status = AppUpdateStatus(
                AppUpdatePhase.READY,
                version = release.version,
                progressPercent = 100,
                message = "CourierPilot ${release.version} is already downloaded and ready to install.",
            )
            emit(onStatus, status)
            if (!manual) notifyUpdate(context, status, ready = true)
            return
        }

        val shouldDownload = manual || AppUpdateSettings.autoDownload(context)
        if (!shouldDownload) {
            val status = AppUpdateStatus(
                AppUpdatePhase.AVAILABLE,
                version = release.version,
                message = "CourierPilot ${release.version} is available. Tap Check & download now to get it.",
            )
            emit(onStatus, status)
            if (!manual) notifyUpdate(context, status, ready = false)
            return
        }

        if (!manual && AppUpdateSettings.wifiOnly(context) && !isOnWifi(context)) {
            val status = AppUpdateStatus(
                AppUpdatePhase.AVAILABLE,
                version = release.version,
                message = "CourierPilot ${release.version} is available and waiting for Wi-Fi.",
            )
            emit(onStatus, status)
            notifyUpdate(context, status, ready = false)
            return
        }

        val expectedSha256 = release.digestSha256
            ?: release.checksumUrl?.let { AppUpdateIntegrity.parseSha256(readText(it, "text/plain")) }
            ?: throw IOException("Release checksum is missing")

        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        updateDir.listFiles()?.forEach { it.delete() }
        val temp = File(updateDir, "${release.apkName}.download")
        val finalApk = File(updateDir, release.apkName)

        downloadFile(release.apkUrl, temp, release.apkSize) { percent ->
            emit(
                onStatus,
                AppUpdateStatus(
                    AppUpdatePhase.DOWNLOADING,
                    version = release.version,
                    progressPercent = percent,
                    message = "Downloading CourierPilot ${release.version}… $percent%",
                ),
            )
        }

        val actualSha256 = sha256(temp)
        if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
            temp.delete()
            throw SecurityException("Downloaded APK checksum does not match the GitHub release")
        }

        verifyArchive(context, temp, release.version)
        if (finalApk.exists()) finalApk.delete()
        if (!temp.renameTo(finalApk)) {
            temp.copyTo(finalApk, overwrite = true)
            temp.delete()
        }

        prefs(context).edit()
            .putString(KEY_READY_VERSION, release.version)
            .putString(KEY_READY_PATH, finalApk.absolutePath)
            .putString(KEY_LAST_ERROR, "")
            .apply()

        val status = AppUpdateStatus(
            AppUpdatePhase.READY,
            version = release.version,
            progressPercent = 100,
            message = "CourierPilot ${release.version} is downloaded, checksum-verified and signature-verified.",
        )
        emit(onStatus, status)
        if (!manual) notifyUpdate(context, status, ready = true)
    }

    private data class ReleaseInfo(
        val version: String,
        val apkName: String,
        val apkUrl: String,
        val apkSize: Long,
        val digestSha256: String?,
        val checksumUrl: String?,
    )

    private fun fetchLatestRelease(): ReleaseInfo {
        val json = JSONObject(readText(LATEST_RELEASE_URL, "application/vnd.github+json"))
        val version = AppUpdateVersion.normalize(json.optString("tag_name"))
            ?: throw IOException("Latest GitHub release has an invalid version tag")
        val assets = json.optJSONArray("assets") ?: throw IOException("Latest GitHub release has no assets")

        var apkAsset: JSONObject? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.startsWith("CourierPilot-") && name.endsWith(".apk", ignoreCase = true)) {
                apkAsset = asset
                break
            }
        }
        if (apkAsset == null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkAsset = asset
                    break
                }
            }
        }
        val apk = apkAsset ?: throw IOException("Latest GitHub release has no APK asset")
        val apkName = apk.optString("name")
        val apkUrl = apk.optString("browser_download_url")
        if (apkName.isBlank() || apkUrl.isBlank()) throw IOException("Latest APK asset is incomplete")

        val digest = apk.optString("digest")
            .takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.lowercase(Locale.US)
            ?.takeIf { AppUpdateIntegrity.parseSha256(it) == it }

        var checksumUrl: String? = null
        val expectedChecksumName = "$apkName.sha256"
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            if (asset.optString("name") == expectedChecksumName) {
                checksumUrl = asset.optString("browser_download_url").takeIf(String::isNotBlank)
                break
            }
        }

        return ReleaseInfo(
            version = version,
            apkName = apkName,
            apkUrl = apkUrl,
            apkSize = apk.optLong("size", -1L),
            digestSha256 = digest,
            checksumUrl = checksumUrl,
        )
    }

    private fun readText(url: String, accept: String): String {
        val connection = openConnection(url, accept)
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
    ) {
        val connection = openConnection(url, "application/octet-stream")
        try {
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: expectedSize
            connection.inputStream.use { input ->
                FileOutputStream(target).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val percent = if (total > 0L) {
                            ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                    output.flush()
                }
            }
            if (!target.isFile || target.length() <= 0L) throw IOException("Downloaded APK is empty")
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, accept: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "CourierPilot/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            throw IOException("GitHub returned HTTP $code")
        }
        return connection
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(context: Context, apk: File, expectedVersion: String) {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw SecurityException("Downloaded APK cannot be inspected")
        val current = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)

        if (archive.packageName != context.packageName) {
            throw SecurityException("Downloaded APK package name does not match CourierPilot")
        }
        if (AppUpdateVersion.normalize(archive.versionName) != AppUpdateVersion.normalize(expectedVersion)) {
            throw SecurityException("Downloaded APK version does not match the GitHub release")
        }
        if (archive.longVersionCode <= current.longVersionCode) {
            throw SecurityException("Downloaded APK is not newer than the installed version")
        }

        val archiveSigners = signerDigests(archive)
        val currentSigners = signerDigests(current)
        if (EXPECTED_SIGNER_SHA256 !in archiveSigners) {
            throw SecurityException("Downloaded APK signing certificate is not trusted")
        }
        if (EXPECTED_SIGNER_SHA256 !in currentSigners) {
            throw SecurityException("Installed CourierPilot uses a different signing certificate")
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = info.signingInfo?.apkContentsSigners ?: return emptySet()
        return signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun clearReadyUpdate(context: Context) {
        val prefs = prefs(context)
        prefs.getString(KEY_READY_PATH, null)?.let { runCatching { File(it).delete() } }
        prefs.edit()
            .remove(KEY_READY_VERSION)
            .remove(KEY_READY_PATH)
            .remove(KEY_DISMISSED_VERSION)
            .putString(KEY_LAST_ERROR, "")
            .apply()
        cancelNotification(context)
    }

    private fun isOnWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun notifyUpdate(context: Context, status: AppUpdateStatus, ready: Boolean) {
        val version = status.version ?: return
        if (AppUpdateSettings.dismissedVersion(context) == version) return
        if (!canPostNotifications(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                "CourierPilot updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "New CourierPilot versions downloaded from GitHub Releases."
            }
        )

        val openUpdates = PendingIntent.getActivity(
            context,
            1551,
            Intent(context, AppUpdateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val primaryAction = PendingIntent.getActivity(
            context,
            1552,
            Intent(context, AppUpdateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (ready) putExtra(AppUpdateActivity.EXTRA_INSTALL_NOW, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val later = PendingIntent.getBroadcast(
            context,
            1553,
            Intent(context, AppUpdateNotificationReceiver::class.java).apply {
                action = AppUpdateNotificationReceiver.ACTION_DISMISS
                putExtra(AppUpdateNotificationReceiver.EXTRA_VERSION, version)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (ready) {
            "CourierPilot $version ready to install"
        } else {
            "CourierPilot $version available"
        }
        manager.notify(
            UPDATE_NOTIFICATION_ID,
            Notification.Builder(context, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_courierpilot)
                .setContentTitle(title)
                .setContentText(status.message)
                .setStyle(Notification.BigTextStyle().bigText(status.message))
                .setContentIntent(openUpdates)
                .setDeleteIntent(later)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .addAction(0, if (ready) "Install" else "Download", primaryAction)
                .addAction(0, "Later", later)
                .build()
        )
    }

    private fun cancelNotification(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(UPDATE_NOTIFICATION_ID)
    }

    private fun canPostNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun prefs(context: Context) =
        context.getSharedPreferences(APP_UPDATE_PREFS, Context.MODE_PRIVATE)

    private fun emit(callback: ((AppUpdateStatus) -> Unit)?, status: AppUpdateStatus) {
        callback ?: return
        mainHandler.post { callback(status) }
    }

    private fun finish(callback: (() -> Unit)?) {
        callback ?: return
        mainHandler.post(callback)
    }

    private fun updateErrorMessage(error: Throwable): String {
        val detail = error.message?.trim().orEmpty()
        return if (detail.isBlank()) {
            "Update check failed. Try again."
        } else {
            "Update check failed: ${detail.take(180)}"
        }
    }
}
