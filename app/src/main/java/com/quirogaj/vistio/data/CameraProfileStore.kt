package com.quirogaj.vistio.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CameraProfile(
    val id: String,
    val name: String,
    val address: String,
    val username: String,
    val encryptedPassword: String?,
    val fingerprint: String? = null,
    val preferH264: Boolean = false,
    val rtspPort: Int? = null,
    val customMjpeg: Boolean = false,
    val cropMjpegToWide: Boolean = false,
)

internal class CameraProfileStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "camera-profiles.json"))

    fun load(): List<CameraProfile> {
        val source = try {
            file.readFully().toString(Charsets.UTF_8)
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        val rows = JSONArray(source)
        return List(rows.length()) { index ->
            val row = rows.getJSONObject(index)
            CameraProfile(
                row.getString("id"), row.getString("name"), row.getString("address"),
                row.getString("username"), row.optString("password").takeIf(String::isNotEmpty),
                row.optString("fingerprint").takeIf(String::isNotEmpty),
                row.optBoolean("preferH264"),
                row.optInt("rtspPort").takeIf { it in 1..65535 },
                row.optBoolean("customMjpeg"),
                row.optBoolean("cropMjpegToWide"),
            )
        }
    }

    fun save(profiles: List<CameraProfile>) {
        val rows = JSONArray()
        profiles.forEach { profile ->
            rows.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("address", profile.address)
                put("username", profile.username)
                put("password", profile.encryptedPassword)
                put("fingerprint", profile.fingerprint)
                put("preferH264", profile.preferH264)
                put("rtspPort", profile.rtspPort)
                put("customMjpeg", profile.customMjpeg)
                put("cropMjpegToWide", profile.cropMjpegToWide)
            })
        }
        val output = file.startWrite()
        try {
            output.write(rows.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun encrypt(profileId: String, password: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        cipher.updateAAD(profileId.toByteArray())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(password.toByteArray()), Base64.NO_WRAP)
    }

    fun password(profile: CameraProfile): String {
        val encrypted = checkNotNull(profile.encryptedPassword)
        val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
        require(bytes.size >= IV_BYTES + 16)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        cipher.updateAAD(profile.id.toByteArray())
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).toString(Charsets.UTF_8)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "No hay clave para este perfil" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "vistio-camera-profiles"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
