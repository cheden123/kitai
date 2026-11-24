package ru.kitai.app

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface KitaiUpdateState {
    data object Idle : KitaiUpdateState
    data object Checking : KitaiUpdateState
    data object UpToDate : KitaiUpdateState
    data class Available(val release: KitaiRelease, val message: String? = null) : KitaiUpdateState
    data object Downloading : KitaiUpdateState
    data class Ready(val release: KitaiRelease, val message: String? = null) : KitaiUpdateState
    data class Installing(val release: KitaiRelease, val uri: Uri) : KitaiUpdateState
    data class Message(val text: String, val error: Boolean = true) : KitaiUpdateState
}

internal class KitaiUpdateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val updateSource = KitaiUpdateSource()
    private val cache = File(app.cacheDir, "kitai-updates")
    private val mutableState = MutableStateFlow<KitaiUpdateState>(KitaiUpdateState.Idle)
    val state = mutableState.asStateFlow()
    private var verifiedApk: File? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            cache.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    fun check() {
        when (state.value) {
            KitaiUpdateState.Checking, KitaiUpdateState.Downloading, is KitaiUpdateState.Installing -> return
            else -> Unit
        }
        val previousApk = verifiedApk
        verifiedApk = null
        mutableState.value = KitaiUpdateState.Checking
        viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) {
                    previousApk?.delete()
                    val installed = installedPackage().versionName?.let(KitaiReleaseVersion::parse)
                        ?: throw KitaiUpdateException("Не удалось определить установленную версию Kitai.")
                    updateSource.findUpdate(installed)
                }
                mutableState.value = release?.let { KitaiUpdateState.Available(it) } ?: KitaiUpdateState.UpToDate
            } catch (error: KitaiUpdateException) {
                mutableState.value = KitaiUpdateState.Message(error.message.orEmpty())
            } catch (_: IOException) {
                mutableState.value = KitaiUpdateState.Message("Не удалось связаться с GitHub. Проверьте подключение к интернету.")
            }
        }
    }

    fun download() {
        val release = (state.value as? KitaiUpdateState.Available)?.release ?: return
        mutableState.value = KitaiUpdateState.Downloading
        viewModelScope.launch {
            var tempFile: File? = null
            try {
                val apk = withContext(Dispatchers.IO) {
                    if (!cache.mkdirs() && !cache.isDirectory) throw IOException("Cannot create update cache")
                    File.createTempFile("kitai-", ".apk", cache).also { file ->
                        tempFile = file
                        file.outputStream().buffered().use { updateSource.download(release, it) }
                        verifyArchive(file, release.version)
                    }
                }
                verifiedApk = apk
                mutableState.value = KitaiUpdateState.Ready(release)
            } catch (error: KitaiUpdateException) {
                mutableState.value = KitaiUpdateState.Available(release, error.message)
            } catch (_: IOException) {
                mutableState.value = KitaiUpdateState.Available(
                    release, "Не удалось загрузить APK. Проверьте подключение и свободное место.",
                )
            } finally {
                if (verifiedApk != tempFile) tempFile?.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun install(launch: (Intent) -> Unit) {
        val ready = state.value as? KitaiUpdateState.Ready ?: return
        val apk = verifiedApk?.takeIf { it.isFile }
        if (apk == null) {
            verifiedApk = null
            mutableState.value = KitaiUpdateState.Available(ready.release, "APK удалён из кэша. Загрузите его снова.")
            return
        }

        val manager = app.packageManager
        try {
            if (!manager.canRequestPackageInstalls()) {
                launch(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", app.packageName, null)))
                mutableState.value = ready.copy(
                    message = "Разрешите Kitai установку из этого источника. Затем вернитесь и нажмите «Установить обновление».",
                )
                return
            }

            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", apk)
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
            val installer = manager.queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY,
            ).firstOrNull()?.activityInfo ?: throw ActivityNotFoundException()
            intent.component = ComponentName(installer.packageName, installer.name)
            mutableState.value = KitaiUpdateState.Installing(ready.release, uri)
            launch(intent)
        } catch (_: ActivityNotFoundException) {
            mutableState.value = ready.copy(message = "На устройстве недоступен системный установщик или экран разрешения.")
        } catch (_: SecurityException) {
            mutableState.value = ready.copy(message = "Android ограничил установку обновления на этом устройстве.")
        } catch (_: IllegalArgumentException) {
            mutableState.value = ready.copy(message = "Не удалось передать APK системному установщику.")
        }
    }

    fun installFinished(resultCode: Int) {
        val installing = state.value as? KitaiUpdateState.Installing ?: return
        app.revokeUriPermission(installing.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        verifiedApk?.delete()
        verifiedApk = null
        mutableState.value = if (resultCode == Activity.RESULT_OK) {
            KitaiUpdateState.Message("Системный установщик завершил обновление.", error = false)
        } else {
            KitaiUpdateState.Available(installing.release, "Установка не завершена.")
        }
    }

    override fun onCleared() {
        // После запуска установщика файл доступен до его результата или очистки старого кэша.
        if (state.value !is KitaiUpdateState.Installing) verifiedApk?.delete()
        super.onCleared()
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo {
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                app.packageManager.getPackageInfo(app.packageName, flags)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            throw KitaiUpdateException("Не удалось определить установленную версию Kitai.")
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(apk: File, release: KitaiReleaseVersion) {
        val manager = app.packageManager
        // Android 9 also needs GET_SIGNATURES to collect and verify the archive certificates.
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val archive = if (Build.VERSION.SDK_INT >= 33) {
            manager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            manager.getPackageArchiveInfo(apk.absolutePath, flags)
        } ?: throw KitaiUpdateException("Не удалось проверить содержимое и подпись APK.")
        val installed = installedPackage()
        if (archive.packageName != installed.packageName) {
            throw KitaiUpdateException("APK содержит другой пакет приложения.")
        }
        val installedVersion = installed.versionName?.let(KitaiReleaseVersion::parse)
        if (archive.versionName != release.toString() || installedVersion == null || release <= installedVersion ||
            archive.longVersionCode <= installed.longVersionCode
        ) {
            throw KitaiUpdateException("Версия APK не соответствует выпуску или установленной версии Kitai.")
        }
        val currentSigners = installed.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        val archiveSigners = archive.signingInfo?.apkContentsSigners?.toSet().orEmpty()
        if (currentSigners.isEmpty() || archiveSigners != currentSigners) {
            throw KitaiUpdateException("Подпись APK отличается от подписи установленного Kitai.")
        }
    }
}

class KitaiUpdateFileProvider : FileProvider()
