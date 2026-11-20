package com.quirogaj.vistio.viewmodel

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.view.Surface
import androidx.media3.common.Player
import com.quirogaj.vistio.data.RtspPlayer
import kotlinx.coroutines.Job
import com.quirogaj.vistio.data.AvcSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.quirogaj.vistio.data.CameraCertificateMismatch
import com.quirogaj.vistio.data.CameraCertificatePin
import com.quirogaj.vistio.data.CameraCertificateOffer
import com.quirogaj.vistio.data.inspectCameraCertificate
import com.quirogaj.vistio.data.CameraControl
import com.quirogaj.vistio.data.CameraSettings
import com.quirogaj.vistio.data.CameraDirection
import com.quirogaj.vistio.data.CameraEndpoint
import com.quirogaj.vistio.data.CameraRepository
import com.quirogaj.vistio.data.CameraProfile
import com.quirogaj.vistio.data.CameraProfileStore
import java.util.UUID
import com.quirogaj.vistio.data.CameraResponse
import com.quirogaj.vistio.data.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import org.json.JSONException
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

internal enum class ConnectionStatus { Disconnected, Connecting, Live, Error }
internal enum class CameraAudioStatus { Off, Connecting, Playing }

private const val AUDIO_ENABLED = "audio_enabled"
private const val FALCON_AUDIO_ENABLED = "falcon_audio_enabled"

internal data class CameraForm(
    val id: String? = null,
    val name: String = "",
    val rememberPassword: Boolean = false,
    val address: String = "",
    val fingerprint: String = "",
    val preferH264: Boolean = false,
    val preferRtsp: Boolean = false,
    val rtspPort: String = "8554",
    val username: String = "",
    val password: String = "",
    val customMjpeg: Boolean = false,
)

internal data class CameraUiState(
    val profiles: List<CameraProfile> = emptyList(),
    val selectedProfile: CameraProfile? = null,
    val configuring: Boolean = false,
    val editor: CameraForm? = null,
    val editorError: String? = null,
    val passwordPrompt: CameraProfile? = null,
    val certificatePrompt: CameraCertificateOffer? = null,
    val profilesBusy: Boolean = true,
    val profilesLoadFailed: Boolean = false,
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val statusText: String = "Desconectado",
    val frame: Bitmap? = null,
    val usingH264: Boolean = false,
    val usingRtsp: Boolean = false,
    val avcSize: AvcSize? = null,
    val fullscreen: Boolean = false,
    val audioStatus: CameraAudioStatus = CameraAudioStatus.Off,
    val audioEnabled: Boolean = false,
    val falconAudioEnabled: Boolean = false,
    val capturing: Boolean = false,
    val sendingAnnouncement: Boolean = false,
    val snapshot: Snapshot? = null,
    val notice: String? = null,
    val settings: CameraSettings? = null,
    val settingsBusy: Boolean = false,
    val settingsError: String? = null,
)

internal class CameraViewModel(
    private val repository: CameraRepository,
    private val profileStore: CameraProfileStore,
    private val rtspPlayer: RtspPlayer,
    private val playbackPreferences: SharedPreferences,
    audioEnabledByDefault: Boolean,
) : ViewModel() {
    private val connectionCloser = Executors.newCachedThreadPool()
    private val mutableState = MutableStateFlow(
        CameraUiState(
            audioEnabled = playbackPreferences.getBoolean(AUDIO_ENABLED, audioEnabledByDefault),
            falconAudioEnabled = playbackPreferences.getBoolean(FALCON_AUDIO_ENABLED, false),
        ),
    )
    val state = mutableState.asStateFlow()
    private var stream: CameraRepository.Request? = null
    private var videoSurface: Surface? = null
    private var rtspSession: Player? = null
    private var rtspToken: Any? = null
    private var rtspTimeout: Job? = null
    private var rtspCatchUp: Job? = null
    private var activeRtspPort: Int? = null
    private var audio: CameraRepository.Request? = null
    private var announcement: CameraRepository.Request? = null
    private var movement: CameraRepository.Request? = null
    private var movementRelease: CountDownLatch? = null
    private var autoAudioPending = false
    private var capture: CameraRepository.Request? = null
    private var endpoint: CameraEndpoint? = null
    private var settingsRequest: CameraRepository.Request? = null
    private var pendingSave: ByteArray? = null

    private var pendingPermission: Pair<CameraProfile, Boolean>? = null

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        mutableState.update { it.copy(profilesBusy = true, profilesLoadFailed = false) }
        viewModelScope.launch {
            try {
                val profiles = withContext(Dispatchers.IO) { profileStore.load() }
                mutableState.update { it.copy(profiles = profiles, profilesBusy = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(profilesBusy = false, profilesLoadFailed = true) }
            }
        }
    }

    fun editProfile(profile: CameraProfile?) {
        if (state.value.profilesBusy) return
        val form = if (profile == null) CameraForm() else CameraForm(
            id = profile.id, name = profile.name, address = profile.address,
            customMjpeg = profile.customMjpeg,
            username = profile.username, rememberPassword = profile.encryptedPassword != null,
            fingerprint = profile.fingerprint.orEmpty(), preferH264 = profile.preferH264,
            preferRtsp = profile.rtspPort != null, rtspPort = (profile.rtspPort ?: 8554).toString(),
        )
        if (profile?.encryptedPassword == null) {
            mutableState.update { it.copy(editor = form, editorError = null) }
            return
        }
        val selectedId = state.value.selectedProfile?.id
        mutableState.update { it.copy(profilesBusy = true, editorError = null) }
        viewModelScope.launch {
            try {
                val password = withContext(Dispatchers.IO) { runCatching { profileStore.password(profile) }.getOrNull() }
                if (state.value.selectedProfile?.id == selectedId) {
                    mutableState.update {
                        it.copy(
                            editor = form.copy(password = password.orEmpty()),
                            editorError = if (password == null) "No se pudo leer la contraseña guardada. Escríbela para actualizarla." else null,
                        )
                    }
                }
            } finally {
                mutableState.update { it.copy(profilesBusy = false) }
            }
        }
    }

    fun dismissEditor() {
        if (!state.value.profilesBusy) mutableState.update { it.copy(editor = null) }
    }

    fun saveProfile(form: CameraForm) {
        val current = state.value
        if (current.profilesBusy || current.profilesLoadFailed) return
        val (url, customMjpeg) = runCatching {
            val useMjpeg = form.customMjpeg || CameraEndpoint.hasStreamPath(form.address)
            val address = if (useMjpeg) CameraEndpoint.mjpegUrl(form.address) else CameraEndpoint.origin(form.address)
            address to useMjpeg
        }.getOrElse {
            mutableState.update { it.copy(editorError = "Introduce la dirección HTTP o HTTPS de la cámara.") }
            return
        }
        val fingerprint = if (url.protocol == "https" && form.fingerprint.isNotBlank()) {
            CameraCertificatePin.normalize(form.fingerprint) ?: run {
                mutableState.update { it.copy(editorError = "La huella SHA-256 debe tener 64 dígitos hexadecimales.") }
                return
            }
        } else null
        val rtspPort = if (!customMjpeg && url.protocol == "https" && form.preferRtsp) {
            form.rtspPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                mutableState.update { it.copy(editorError = "Introduce un puerto RTSPS entre 1 y 65535.") }
                return
            }
        } else null
        val username = form.username.trim()
        if (':' in username || username.any(Char::isISOControl) || (username.isEmpty() && form.password.isNotEmpty())) {
            mutableState.update { it.copy(editorError = "Comprueba el nombre de usuario de la cámara.") }
            return
        }
        val id = form.id ?: UUID.randomUUID().toString()
        val name = form.name.trim().ifEmpty { url.host }
        val address = url.toString()
        val original = current.profiles.firstOrNull { it.id == id }
        val previousPassword = original?.takeIf { it.address == address && it.username == username }?.encryptedPassword
        if (form.rememberPassword && username.isNotEmpty() && form.password.isEmpty() &&
            (previousPassword == null || current.editor?.password.isNullOrEmpty())
        ) {
            mutableState.update { it.copy(editorError = "Escribe la contraseña para poder recordarla.") }
            return
        }
        persistProfiles {
            val encrypted = when {
                !form.rememberPassword || username.isEmpty() -> null
                previousPassword != null && form.password == current.editor?.password -> previousPassword
                form.password.isNotEmpty() -> profileStore.encrypt(id, form.password)
                else -> previousPassword
            }
            val profile = CameraProfile(
                id, name, address, username, encrypted, fingerprint,
                !customMjpeg && form.preferH264, rtspPort, customMjpeg,
                cropMjpegToWide = original?.cropMjpegToWide ?: false,
            )
            if (original == null) current.profiles + profile
            else current.profiles.map { if (it.id == id) profile else it }
        }
    }

    fun deleteProfile(id: String) {
        val current = state.value
        if (current.profilesBusy || current.profilesLoadFailed) return
        persistProfiles { current.profiles.filterNot { it.id == id } }
    }

    fun setMjpegCrop(enabled: Boolean) {
        val current = state.value
        val profile = current.selectedProfile ?: return
        if (current.profilesBusy || current.profilesLoadFailed || profile.cropMjpegToWide == enabled
        ) return
        persistProfiles(keepSelection = true) {
            current.profiles.map { if (it.id == profile.id) it.copy(cropMjpegToWide = enabled) else it }
        }
    }

    private fun persistProfiles(keepSelection: Boolean = false, update: () -> List<CameraProfile>) {
        mutableState.update { it.copy(profilesBusy = true, editorError = null) }
        viewModelScope.launch {
            try {
                val profiles = withContext(Dispatchers.IO) {
                    update().also(profileStore::save)
                }
                if (keepSelection) {
                    mutableState.update { current ->
                        current.copy(
                            profiles = profiles,
                            selectedProfile = profiles.firstOrNull { it.id == current.selectedProfile?.id },
                        )
                    }
                } else {
                    if (state.value.selectedProfile != null) showProfiles()
                    mutableState.update { it.copy(profiles = profiles, editor = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (state.value.editor == null) {
                    notify("No se pudo guardar el cambio en este dispositivo.")
                } else {
                    mutableState.update { it.copy(editorError = "No se pudo guardar el cambio en este dispositivo.") }
                }
            } finally {
                mutableState.update { it.copy(profilesBusy = false) }
            }
        }
    }

    fun connectProfile(
        profile: CameraProfile,
        configuring: Boolean,
        hasPermission: Boolean,
        requestPermission: () -> Unit,
    ) {
        if (!hasPermission) {
            if (configuring) {
                disconnect()
                mutableState.update { it.copy(selectedProfile = profile, configuring = true) }
            }
            pendingPermission = profile to configuring
            requestPermission()
            return
        }
        if (configuring && profile.customMjpeg && profile == state.value.selectedProfile && stream != null) {
            autoAudioPending = false
            stopAudio()
            mutableState.update { it.copy(configuring = true, fullscreen = false) }
            return
        }
        openProfile(profile, configuring)
    }

    fun onPermissionResult(granted: Boolean) {
        val target = pendingPermission
        pendingPermission = null
        if (granted && target != null) openProfile(target.first, target.second)
        else if (!granted) notify("Concede el permiso de red local para conectar.")
    }

    private var connectionSequence = 0L

    private fun openProfile(profile: CameraProfile, configuring: Boolean) {
        disconnect()
        val sequence = connectionSequence
        mutableState.update { it.copy(selectedProfile = profile, configuring = configuring) }
        if (profile.address.startsWith("https://", ignoreCase = true) && profile.fingerprint == null) {
            mutableState.update { it.copy(status = ConnectionStatus.Connecting, statusText = "Verificando certificado HTTPS…") }
            viewModelScope.launch {
                try {
                    val offer = withContext(Dispatchers.IO) { inspectCameraCertificate(CameraEndpoint.origin(profile.address, allowQuery = profile.customMjpeg)) }
                    if (sequence != connectionSequence) return@launch
                    if (offer == null) {
                        requestProfilePassword(profile, sequence)
                    } else {
                        mutableState.update {
                            it.copy(
                                certificatePrompt = offer,
                                status = ConnectionStatus.Disconnected,
                                statusText = "Confirma el certificado de la cámara.",
                            )
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (sequence == connectionSequence) connectionFailure(message(error))
                }
            }
        } else {
            requestProfilePassword(profile, sequence)
        }
    }

    private fun requestProfilePassword(profile: CameraProfile, sequence: Long) {
        if (profile.username.isEmpty()) {
            connectWithPassword(profile, "")
        } else if (profile.encryptedPassword == null) {
            mutableState.update { it.copy(passwordPrompt = profile, status = ConnectionStatus.Disconnected) }
        } else {
            mutableState.update { it.copy(status = ConnectionStatus.Connecting, statusText = "Conectando…") }
            viewModelScope.launch {
                val password = withContext(Dispatchers.IO) { runCatching { profileStore.password(profile) }.getOrNull() }
                if (sequence != connectionSequence) return@launch
                if (password == null) {
                    disconnect()
                    mutableState.update { it.copy(passwordPrompt = profile, status = ConnectionStatus.Disconnected) }
                    notify("Escribe la contraseña para conectar. Puedes actualizarla al editar el perfil.")
                } else {
                    connectWithPassword(profile, password)
                }
            }
        }
    }

    fun dismissCertificate() {
        if (state.value.certificatePrompt == null) return
        mutableState.update { it.copy(certificatePrompt = null) }
        connectionFailure("El certificado no se guardó. Puedes reintentar la conexión.")
    }

    fun trustCertificate() {
        val current = state.value
        val profile = current.selectedProfile ?: return
        val offer = current.certificatePrompt ?: return
        if (current.profilesBusy || current.profilesLoadFailed ||
            current.profiles.none { it.id == profile.id && it.address == profile.address && it.fingerprint == null }
        ) return
        val sequence = connectionSequence
        val trusted = profile.copy(fingerprint = offer.fingerprint)
        val profiles = current.profiles.map { if (it.id == trusted.id) trusted else it }
        mutableState.update { it.copy(certificatePrompt = null, profilesBusy = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { profileStore.save(profiles) }
                mutableState.update { it.copy(profiles = profiles, profilesBusy = false) }
                if (sequence == connectionSequence) openProfile(trusted, current.configuring)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(profilesBusy = false) }
                if (sequence == connectionSequence) connectionFailure("No se pudo guardar el certificado en este dispositivo.")
            }
        }
    }

    private fun connectionFailure(text: String) {
        mutableState.update {
            it.copy(
                status = ConnectionStatus.Error,
                statusText = text,
                settingsError = if (it.configuring) text else it.settingsError,
            )
        }
    }
    fun submitPassword(password: String) {
        val profile = state.value.passwordPrompt ?: return
        dismissPassword()
        connectWithPassword(profile, password)
    }

    fun dismissPassword() = mutableState.update { it.copy(passwordPrompt = null) }

    fun showProfiles() {
        disconnect()
        pendingPermission = null
        mutableState.update { it.copy(selectedProfile = null, configuring = false) }
    }

    private fun connectWithPassword(profile: CameraProfile, password: String) {
        val origin = runCatching { CameraEndpoint.origin(profile.address, allowQuery = profile.customMjpeg) }.getOrElse {
            notify("Edita el perfil y comprueba la dirección de la cámara.")
            return
        }
        val camera = CameraEndpoint(
            origin, profile.username, password, profile.fingerprint,
            if (profile.customMjpeg) CameraEndpoint.mjpegUrl(profile.address) else null,
        )
        if (state.value.configuring && !profile.customMjpeg) {
            disconnect()
            endpoint = camera
            loadSettings()
        } else {
            start(camera, profile.preferH264, profile.rtspPort)
        }
    }

    fun panelAddress(): String? = endpoint?.origin?.toString() ?: state.value.selectedProfile?.let {
        CameraEndpoint.origin(it.address, allowQuery = it.customMjpeg).toString()
    }

    private fun start(camera: CameraEndpoint, preferH264: Boolean, rtspPort: Int?) {
        disconnect()
        endpoint = camera
        activeRtspPort = rtspPort
        autoAudioPending = when {
            state.value.configuring -> false
            camera.isFalconStream -> state.value.falconAudioEnabled
            camera.mjpegUrl == null -> state.value.audioEnabled
            else -> false
        }
        mutableState.update {
            it.copy(
                status = ConnectionStatus.Connecting,
                statusText = when {
                    rtspPort != null -> "Conectando por RTSPS…"
                    preferH264 -> "Conectando por H.264…"
                    else -> "Conectando por MJPEG…"
                },
                fullscreen = !it.configuring && !camera.isFalconStream, usingH264 = preferH264 && rtspPort == null, usingRtsp = rtspPort != null,
            )
        }
        if (!preferH264 && rtspPort == null) startMjpeg(camera)
    }

    fun onVideoSurface(surface: Surface, available: Boolean) {
        if (available) {
            val camera = endpoint ?: return
            if (videoSurface != null || stream != null) return
            val port = activeRtspPort
            if (state.value.usingRtsp && port != null) {
                videoSurface = surface
                startRtsp(camera, port, surface)
            } else if (state.value.usingH264) {
                videoSurface = surface
                startH264(camera, surface)
            }
        } else if (videoSurface === surface) {
            videoSurface = null
            releaseRtsp()
            stream?.cancel(connectionCloser)
            stream = null
            if (state.value.usingRtsp) {
                mutableState.update {
                    it.copy(status = ConnectionStatus.Connecting, statusText = "Conectando por RTSPS…", avcSize = null)
                }
            } else if (state.value.usingH264) {
                mutableState.update {
                    it.copy(status = ConnectionStatus.Connecting, statusText = "Conectando por H.264…", avcSize = null)
                }
            }
        }
    }

    private fun startRtsp(camera: CameraEndpoint, port: Int, surface: Surface) {
        val token = Any()
        rtspToken = token
        fun failed(error: Exception) {
            if (rtspToken !== token) return
            releaseRtsp()
            val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
            val tlsError = causes.filterIsInstance<SSLException>().firstOrNull()
            val rejected = causes.any { it.message?.contains(Regex("\\b(401|403)\\b")) == true }
            if (tlsError != null || rejected) {
                disconnect()
                mutableState.update {
                    it.copy(
                        status = ConnectionStatus.Error,
                        statusText = tlsError?.let(::message) ?: "Acceso rechazado por RTSPS. Comprueba las credenciales.",
                    )
                }
                return
            }
            videoSurface = null
            activeRtspPort = null
            mutableState.update {
                it.copy(
                    usingRtsp = false, avcSize = null,
                    status = ConnectionStatus.Connecting,
                    statusText = "RTSPS no disponible. Conectando por MJPEG…",
                )
            }
            startMjpeg(camera)
        }
        try {
            rtspSession = rtspPlayer.start(camera, port, surface, { size ->
                if (rtspToken === token) {
                    rtspTimeout?.cancel()
                    mutableState.update {
                        it.copy(status = ConnectionStatus.Live, statusText = "En directo · RTSPS", avcSize = size)
                    }
                    startPreferredAudio()
                }
            }, ::failed)
            val player = rtspSession ?: return
            rtspCatchUp = viewModelScope.launch {
                var speed = 1f
                while (rtspToken === token) {
                    delay(250)
                    val bufferMs = player.totalBufferedDuration
                    val target = when {
                        !player.isPlaying || bufferMs <= 180 -> 1f
                        bufferMs >= 400 -> 1.10f
                        else -> speed
                    }
                    if (target != speed) {
                        player.setPlaybackSpeed(target)
                        speed = target
                    }
                }
            }
            rtspTimeout = viewModelScope.launch {
                delay(12000)
                failed(IOException("RTSPS no produjo video"))
            }
        } catch (error: Exception) {
            failed(error)
        }
    }

    private fun releaseRtsp() {
        rtspToken = null
        rtspTimeout?.cancel()
        rtspTimeout = null
        rtspCatchUp?.cancel()
        rtspCatchUp = null
        rtspSession?.release()
        rtspSession = null
    }

    private fun startH264(camera: CameraEndpoint, surface: Surface) {
        val request = repository.request(camera)
        stream = request
        viewModelScope.launch(Dispatchers.IO) {
            try {
                for (attempt in 0..2) {
                    try {
                        request.streamH264(surface) { size ->
                            viewModelScope.launch {
                                if (stream === request) {
                                    mutableState.update { it.copy(status = ConnectionStatus.Live, statusText = "En directo · H.264", avcSize = size) }
                                    startPreferredAudio()
                                }
                            }
                        }
                        break
                    } catch (error: IOException) {
                        if (attempt == 2 || (error !is EOFException && error !is SocketTimeoutException)) throw error
                        request.close()
                        val stillPlaying = withContext(Dispatchers.Main) { stream === request }
                        if (!stillPlaying || !surface.isValid) break
                        delay(300L * (attempt + 1))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                viewModelScope.launch fallback@{
                    if (stream !== request || !surface.isValid) return@fallback
                    if (error is SSLException || error is CameraResponse && error.status in listOf(401, 403)) {
                        disconnect()
                        mutableState.update { it.copy(status = ConnectionStatus.Error, statusText = message(error)) }
                    } else {
                        android.util.Log.w("VistioVideo", "H.264 fallback: " + error.javaClass.simpleName +
                            if (error is android.media.MediaCodec.CodecException) " (" + error.diagnosticInfo + ")" else
                            if (error is IOException && error.message?.startsWith("Fotograma H.264") == true) " (input buffer)" else "")
                        videoSurface = null
                        mutableState.update {
                            it.copy(
                                usingH264 = false, avcSize = null,
                                status = ConnectionStatus.Connecting,
                                statusText = "H.264 no disponible. Conectando por MJPEG…",
                            )
                        }
                        startMjpeg(camera)
                    }
                }
            } finally {
                request.close()
            }
        }
    }

    private fun startMjpeg(camera: CameraEndpoint) {
        val request = repository.request(camera)
        stream = request
        val framePending = AtomicBoolean()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                request.stream { jpeg ->
                    if (framePending.compareAndSet(false, true)) {
                        val bitmap = CameraRepository.decode(jpeg, 2048)
                        viewModelScope.launch {
                            if (stream === request) {
                                mutableState.update {
                                    it.copy(status = ConnectionStatus.Live, statusText = "En directo · MJPEG", frame = bitmap)
                                }
                                startPreferredAudio()
                            }
                            framePending.set(false)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                viewModelScope.launch {
                    if (stream === request) {
                        disconnect()
                        mutableState.update { it.copy(status = ConnectionStatus.Error, statusText = message(error)) }
                    }
                }
            } finally {
                request.close()
            }
        }
    }

    fun moveCamera(direction: CameraDirection): (() -> Unit)? {
        val current = state.value
        val camera = endpoint ?: return null
        if (current.fullscreen || current.status != ConnectionStatus.Live ||
            !camera.isFalconStream || movement != null
        ) return null
        val request = repository.request(camera)
        val release = CountDownLatch(1)
        movement = request
        movementRelease = release
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { request.moveFalcon(direction, release) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (endpoint === camera) notify("No se pudo confirmar el movimiento o la parada de la cámara.")
            } finally {
                if (movement === request) {
                    movement = null
                    movementRelease = null
                }
            }
        }
        return release::countDown
    }

    fun disconnect() {
        announcement?.cancel(connectionCloser)
        announcement = null
        movement?.cancel(connectionCloser)
        movementRelease?.countDown()
        autoAudioPending = false
        stopAudio()
        connectionSequence++
        releaseRtsp()
        activeRtspPort = null
        stream?.cancel(connectionCloser)
        capture?.cancel(connectionCloser)
        settingsRequest?.cancel(connectionCloser)
        settingsRequest = null
        stream = null
        videoSurface = null
        capture = null
        endpoint = null
        mutableState.update {
            it.copy(status = ConnectionStatus.Disconnected, statusText = "Desconectado",
                frame = null, usingH264 = false, usingRtsp = false, avcSize = null, fullscreen = false, capturing = false, snapshot = null, passwordPrompt = null,
                settings = null, settingsBusy = false, settingsError = null, certificatePrompt = null,
                sendingAnnouncement = false)
        }
    }

    private fun startPreferredAudio() {
        if (!autoAudioPending) return
        autoAudioPending = false
        startAudio()
    }

    fun sendAnnouncement(loadClip: () -> ByteArray) {
        val camera = endpoint ?: return
        if (!camera.isFalconStream || announcement != null || state.value.status != ConnectionStatus.Live) return
        val request = repository.request(camera)
        announcement = request
        mutableState.update { it.copy(sendingAnnouncement = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { request.sendFalconAnnouncement(loadClip()) }
                if (announcement === request) notify("Aviso enviado a la Falcon.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (announcement === request) notify("No se pudo enviar el aviso a la Falcon.")
            } finally {
                if (announcement === request) {
                    announcement = null
                    mutableState.update { it.copy(sendingAnnouncement = false) }
                }
            }
        }
    }

    fun toggleAudio() {
        if (state.value.status != ConnectionStatus.Live) return
        val camera = endpoint ?: return
        if (camera.mjpegUrl != null) {
            if (camera.isFalconStream) {
                val enable = !state.value.falconAudioEnabled
                playbackPreferences.edit().putBoolean(FALCON_AUDIO_ENABLED, enable).apply()
                mutableState.update { it.copy(falconAudioEnabled = enable) }
                if (enable) startAudio() else stopAudio()
            }
            return
        }
        val enable = !state.value.audioEnabled
        playbackPreferences.edit().putBoolean(AUDIO_ENABLED, enable).apply()
        mutableState.update { it.copy(audioEnabled = enable) }
        if (enable) startAudio() else stopAudio()
    }

    private fun startAudio() {
        if (audio != null) return
        val camera = endpoint ?: return
        if (state.value.status != ConnectionStatus.Live) return
        val request = repository.request(camera)
        audio = request
        mutableState.update { it.copy(audioStatus = CameraAudioStatus.Connecting) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    request.streamAudio {
                        viewModelScope.launch {
                            if (audio === request) mutableState.update { it.copy(audioStatus = CameraAudioStatus.Playing) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (audio === request) {
                    val description = if (error is CameraResponse && error.status == 403) {
                        "El servidor rechazó el audio. Comprueba el permiso de micrófono en Android IP Camera."
                    } else {
                        "No se pudo reproducir el audio de la cámara. " + message(error)
                    }
                    notify(description)
                }
            } finally {
                if (audio === request) stopAudio()
            }
        }
    }

    private fun stopAudio() {
        val current = audio ?: return
        audio = null
        current.cancel(connectionCloser)
        mutableState.update { it.copy(audioStatus = CameraAudioStatus.Off) }
    }

    fun loadSettings() = updateSettings(null)

    fun changeSettings(control: CameraControl) {
        val current = state.value.settings ?: return
        if (control is CameraControl.Lens && current.lenses.none { it.id == control.id }) return
        if (control is CameraControl.Resolution && control.value != "auto" &&
            current.selected?.sizes?.none { it.value == control.value } != false
        ) return
        if (!control.matches(current)) updateSettings(control)
    }

    private fun updateSettings(control: CameraControl?) {
        val camera = endpoint ?: return
        if (!state.value.configuring || settingsRequest != null) return
        val request = repository.request(camera)
        settingsRequest = request
        mutableState.update { it.copy(settingsBusy = true, settingsError = null) }
        viewModelScope.launch {
            try {
                val fresh = withContext(Dispatchers.IO) {
                    if (control != null) request.apply(control)
                    var settings = readSettings(request)
                    if (control != null) {
                        for (wait in listOf(250L, 500L, 1000L)) {
                            if (control.matches(settings)) break
                            delay(wait)
                            settings = readSettings(request)
                        }
                    }
                    settings
                }
                if (settingsRequest === request) {
                    mutableState.update {
                        it.copy(
                            settings = fresh,
                            settingsError = when {
                                control != null && !control.matches(fresh) -> "La cámara aún no confirma el cambio. Actualiza los ajustes."
                                fresh.lenses.isEmpty() -> "La cámara no informó qué lentes están disponibles."
                                else -> null
                            },
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (settingsRequest === request) {
                    mutableState.update {
                        it.copy(settingsError = when (error) {
                            is SocketTimeoutException -> "La cámara tardó demasiado en responder a los ajustes. Pulsa Actualizar."
                            is JSONException -> "La cámara devolvió ajustes con un formato inesperado. Comprueba el panel original."
                            else -> message(error)
                        })
                    }
                }
            } finally {
                if (settingsRequest === request) {
                    settingsRequest = null
                    mutableState.update { it.copy(settingsBusy = false) }
                }
            }
        }
    }

    private suspend fun readSettings(request: CameraRepository.Request): CameraSettings {
        return try {
            request.settings()
        } catch (error: IOException) {
            val recoverable = error is SocketTimeoutException || error is EOFException ||
                error is SocketException || (error is CameraResponse && error.status in setOf(502, 503, 504))
            if (!recoverable) throw error
            delay(350)
            request.settings()
        }
    }

    fun setFullscreen(enabled: Boolean) {
        mutableState.update { it.copy(fullscreen = enabled && it.status == ConnectionStatus.Live) }
    }

    fun takeSnapshot() {
        val camera = endpoint ?: return
        if (capture != null || state.value.settingsBusy || state.value.status != ConnectionStatus.Live) return
        val request = repository.request(camera)
        capture = request
        mutableState.update { it.copy(capturing = true) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snapshot = request.snapshot()
                viewModelScope.launch {
                    if (capture === request) {
                        capture = null
                        mutableState.update { it.copy(capturing = false, snapshot = snapshot) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                viewModelScope.launch {
                    if (capture === request) {
                        capture = null
                        mutableState.update { it.copy(capturing = false, notice = message(error)) }
                    }
                }
            } finally {
                request.close()
            }
        }
    }

    fun dismissSnapshot() = mutableState.update { it.copy(snapshot = null) }

    fun prepareSave(): String? {
        pendingSave = state.value.snapshot?.bytes ?: return null
        dismissSnapshot()
        return "Vistio-" + System.currentTimeMillis() + ".jpg"
    }

    fun saveResult(open: (() -> OutputStream?)?) {
        val jpeg = pendingSave
        pendingSave = null
        if (open == null || jpeg == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val output = open() ?: throw IOException()
                output.use { it.write(jpeg) }
                "Captura guardada."
            } catch (_: IOException) {
                "No se pudo guardar la captura."
            } catch (_: SecurityException) {
                "No se pudo guardar la captura."
            }
            withContext(Dispatchers.Main) { notify(result) }
        }
    }

    fun notify(text: String) = mutableState.update { it.copy(notice = text) }

    fun noticeShown(text: String) {
        mutableState.update { if (it.notice == text) it.copy(notice = null) else it }
    }

    override fun onCleared() {
        disconnect()
        pendingSave = null
        connectionCloser.shutdown()
    }

    private fun message(error: Exception): String = when (error) {
        is SSLPeerUnverifiedException -> "HTTPS no pudo verificar la identidad de la cámara. Usa la dirección que figura en el certificado."
        is SSLException -> {
            val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
            when {
                causes.any { it is CameraCertificateMismatch } ->
                    "El certificado de la cámara no coincide con la huella guardada. Verifica la cámara antes de editar el perfil."
                causes.any { it is CertificateException || it is CertPathValidatorException } ->
                    "Android no confía en el certificado HTTPS. Si es autofirmado, verifica su huella y guárdala en el perfil."
                else -> "No se pudo establecer HTTPS. Revisa el certificado y la dirección que muestra Android IP Camera."
            }
        }
        is SocketTimeoutException -> "Tiempo de espera agotado. Revisa la red y la cámara."
        is EOFException -> "La cámara cerró el flujo. Puedes volver a conectar."
        is CameraResponse -> when (error.status) {
            401, 403 -> "Acceso rechazado. Comprueba el usuario y la contraseña."
            else -> "La cámara respondió con HTTP " + error.status + "."
        }
        else -> "No se pudo leer la cámara. Comprueba la dirección y abre el panel."
    }
}
