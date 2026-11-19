package com.quirogaj.vistio.data.update

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class GitHubUpdateSource(private val channel: UpdateChannel, private val packageName: String) {
    private val api = "https://api.github.com"

    suspend fun latest(): UpdateRelease? {
        val latest = URL(api + channel.path + "/releases/latest")
        val connection = open(latest, asset = false, allowMissingRelease = true) ?: return null
        val release = readJson(connection, 1024 * 1024)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) throw UpdateException(UpdateFailure.RELEASE)
        val assets = release.getJSONArray("assets")
        val manifest = assets.named("update.json")
        val metadata = readJson(requireNotNull(open(asset(manifest), asset = true)), 64 * 1024)
        if (metadata.optString("applicationId") != packageName) throw UpdateException(UpdateFailure.RELEASE)
        val code = metadata.getLong("versionCode")
        val name = metadata.getString("versionName")
        val checksum = metadata.getString("sha256").lowercase()
        val apkName = metadata.getString("apkAsset")
        if (code !in 1..2_100_000_000 || !versionPattern.matches(name) || !hashPattern.matches(checksum) ||
            !apkPattern.matches(apkName)
        ) throw UpdateException(UpdateFailure.RELEASE)
        val apk = assets.named(apkName)
        val size = apk.getLong("size")
        if (size !in 1..MAX_APK_BYTES) throw UpdateException(UpdateFailure.RELEASE)
        return UpdateRelease(code, name, release.optString("body").take(4000), asset(apk), size, checksum)
    }

    suspend fun download(release: UpdateRelease, file: File, onProgress: (Int) -> Unit) {
        val connection = requireNotNull(open(release.assetUrl, asset = true))
        try {
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var reported = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count == -1) break
                        currentCoroutineContext().ensureActive()
                        received += count
                        if (received > release.size) throw UpdateException(UpdateFailure.APK)
                        output.write(buffer, 0, count)
                        val progress = (received * 100 / release.size).toInt()
                        if (progress != reported) {
                            onProgress(progress)
                            reported = progress
                        }
                    }
                    if (received != release.size) throw UpdateException(UpdateFailure.APK)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray.named(name: String): JSONObject {
        val matches = (0 until length()).map(::getJSONObject).filter { it.optString("name") == name }
        return matches.singleOrNull() ?: throw UpdateException(UpdateFailure.RELEASE)
    }

    private fun asset(json: JSONObject): URL {
        val id = json.getLong("id")
        if (id <= 0) throw UpdateException(UpdateFailure.RELEASE)
        return if (channel.token.isNotEmpty()) {
            URL(api + channel.path + "/releases/assets/" + id)
        } else {
            URL(json.getString("browser_download_url")).also {
                val prefix = "/" + channel.owner + "/" + channel.repository + "/releases/download/"
                if (it.host != "github.com" || !it.path.startsWith(prefix, ignoreCase = true)) {
                    throw UpdateException(UpdateFailure.RELEASE)
                }
            }
        }
    }

    private suspend fun readJson(connection: HttpsURLConnection, maximumBytes: Int): JSONObject {
        try {
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > maximumBytes) throw UpdateException(UpdateFailure.RELEASE)
                    output.write(buffer, 0, count)
                }
                JSONObject(output.toString("UTF-8"))
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun open(initial: URL, asset: Boolean, allowMissingRelease: Boolean = false): HttpsURLConnection? {
        var url = initial
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val host = url.host.lowercase()
            if (!allowed(url)) throw UpdateException(UpdateFailure.RELEASE)
            val connection = (url.openConnection() as HttpsURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "Vistio-Updater")
                setRequestProperty("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json")
                if (host == "api.github.com") {
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    if (channel.token.isNotEmpty() && (url.path == channel.path || url.path.startsWith(channel.path + "/"))) {
                        setRequestProperty("Authorization", "Bearer " + channel.token)
                    }
                }
            }
            var keepOpen = false
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        keepOpen = true
                        return connection
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw UpdateException(UpdateFailure.RELEASE)
                        url = URL(url, location)
                    }
                    404 -> {
                        if (allowMissingRelease) {
                            val repository = open(URL(api + channel.path), asset = false)
                            repository?.disconnect()
                            return null
                        }
                        throw UpdateException(UpdateFailure.ACCESS)
                    }
                    401, 403, 429 -> throw UpdateException(UpdateFailure.ACCESS)
                    else -> throw UpdateException(UpdateFailure.NETWORK)
                }
            } finally {
                if (!keepOpen) connection.disconnect()
            }
        }
        throw UpdateException(UpdateFailure.NETWORK)
    }

    companion object {
        private val versionPattern = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]?)\\.(0|[1-9][0-9]?)")
        private val hashPattern = Regex("[a-f0-9]{64}")
        private val apkPattern = Regex("[A-Za-z0-9_.-]{1,120}\\.apk")
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        private val githubHosts = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

        internal fun allowed(url: URL): Boolean =
            url.protocol == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
                url.host.lowercase() in githubHosts
    }
}
