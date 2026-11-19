package com.quirogaj.vistio.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import androidx.core.content.pm.PackageInfoCompat
import com.quirogaj.vistio.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException

internal class UpdateRepository(private val context: Context) {
    private val channel = UpdateChannel.from(
        BuildConfig.UPDATE_GITHUB_OWNER, BuildConfig.UPDATE_GITHUB_REPOSITORY, BuildConfig.UPDATE_GITHUB_TOKEN,
    )
    private val source = channel?.let { GitHubUpdateSource(it, context.packageName) }
    private val preferences = context.getSharedPreferences("vistio_updates", Context.MODE_PRIVATE)
    val configured get() = channel != null

    suspend fun check(automatic: Boolean): UpdateCheck = withContext(Dispatchers.IO) {
        checkLock.withLock {
            val remote = requireNotNull(source)
            val key = "checked:" + channel?.path
            val now = System.currentTimeMillis()
            val last = preferences.getLong(key, 0)
            if (automatic && last > 0 && now >= last && now - last < CHECK_INTERVAL) return@withLock UpdateCheck.Skipped
            val release = network { remote.latest() }
            val installed = PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
            preferences.edit().putLong(key, System.currentTimeMillis()).apply()
            UpdateCheck.Completed(release?.takeIf { it.versionCode > installed })
        }
    }

    suspend fun download(release: UpdateRelease, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        downloadLock.withLock {
            val directory = File(context.cacheDir, "updates")
            if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException(UpdateFailure.STORAGE)
            val partial = try {
                File.createTempFile("staging-", ".apk", directory)
            } catch (_: IOException) {
                throw UpdateException(UpdateFailure.STORAGE)
            }
            val verifiedDirectory = File(directory, "verified")
            if (!verifiedDirectory.isDirectory && !verifiedDirectory.mkdirs()) {
                partial.delete()
                throw UpdateException(UpdateFailure.STORAGE)
            }
            val verified = File(verifiedDirectory, "vistio-" + release.versionCode + ".apk")
            try {
                network { requireNotNull(source).download(release, partial, progress) }
                currentCoroutineContext().ensureActive()
                verify(partial, release)
                currentCoroutineContext().ensureActive()
                try {
                    Os.rename(partial.absolutePath, verified.absolutePath)
                } catch (_: ErrnoException) {
                    throw UpdateException(UpdateFailure.STORAGE)
                }
                verified
            } finally {
                partial.delete()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verify(file: File, release: UpdateRelease) {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        } catch (_: IOException) {
            throw UpdateException(UpdateFailure.STORAGE)
        }
        if (digest.digest().toHex() != release.sha256) throw UpdateException(UpdateFailure.APK)
        val pm = context.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flag) ?: throw UpdateException(UpdateFailure.APK)
        val installed = pm.getPackageInfo(context.packageName, flag)
        val archivedSigners = signatures(archive).ifEmpty {
            pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)?.let(::signatures).orEmpty()
        }
        val installedSigners = signatures(installed).ifEmpty {
            signatures(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES))
        }
        if (archive.packageName != context.packageName || archive.versionName != release.versionName ||
            PackageInfoCompat.getLongVersionCode(archive) != release.versionCode ||
            release.versionCode <= PackageInfoCompat.getLongVersionCode(installed) ||
            archivedSigners.isEmpty() || archivedSigners != installedSigners
        ) throw UpdateException(UpdateFailure.APK)
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> {
        val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        return (signers?.takeIf { it.isNotEmpty() } ?: info.signatures).orEmpty().map {
            MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex()
        }.toSet()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private suspend fun <T> network(action: suspend () -> T): T = try {
        action()
    } catch (_: JSONException) {
        throw UpdateException(UpdateFailure.RELEASE)
    } catch (_: IOException) {
        throw UpdateException(UpdateFailure.NETWORK)
    }

    companion object {
        private val checkLock = Mutex()
        private val downloadLock = Mutex()
        private const val CHECK_INTERVAL = 14L * 24 * 60 * 60 * 1000
    }
}

internal sealed interface UpdateCheck {
    data object Skipped : UpdateCheck
    data class Completed(val release: UpdateRelease?) : UpdateCheck
}
