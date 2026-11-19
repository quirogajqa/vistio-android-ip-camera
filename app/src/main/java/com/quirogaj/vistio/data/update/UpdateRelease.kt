package com.quirogaj.vistio.data.update

import java.net.URL

internal class UpdateChannel(val owner: String, val repository: String, val token: String) {
    companion object {
        private val ownerPattern = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
        private val repoPattern = Regex("[A-Za-z0-9_.-]{1,100}")

        fun from(owner: String, repo: String, token: String): UpdateChannel? {
            if (!ownerPattern.matches(owner) || !repoPattern.matches(repo) || repo == "." || repo == "..") return null
            return UpdateChannel(owner, repo, token)
        }
    }

    val path get() = "/repos/$owner/$repository"
}

internal data class UpdateRelease(
    val versionCode: Long,
    val versionName: String,
    val notes: String,
    val assetUrl: URL,
    val size: Long,
    val sha256: String,
)

internal enum class UpdateFailure(val description: String) {
    ACCESS("GitHub rechazó el acceso al canal de actualizaciones."),
    NETWORK("No se pudo conectar con GitHub. Revisa la conexión y vuelve a intentar."),
    RELEASE("La publicación no contiene metadatos de actualización válidos."),
    APK("El APK no coincide con la versión, el hash o la firma de Vistio."),
    STORAGE("No se pudo guardar la actualización. Comprueba el espacio disponible."),
}

internal class UpdateException(val reason: UpdateFailure) : Exception(reason.name)
