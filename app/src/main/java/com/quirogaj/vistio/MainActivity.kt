package com.quirogaj.vistio

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import java.io.File
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.quirogaj.vistio.data.CameraRepository
import com.quirogaj.vistio.data.update.UpdateRepository
import com.quirogaj.vistio.data.RtspPlayer
import com.quirogaj.vistio.data.CameraProfileStore
import com.quirogaj.vistio.ui.theme.VistioTheme
import com.quirogaj.vistio.ui.theme.VistioTvTheme
import com.quirogaj.vistio.view.VistioSplash
import com.quirogaj.vistio.view.camera.CameraScreen
import com.quirogaj.vistio.viewmodel.CameraViewModel
import com.quirogaj.vistio.viewmodel.UpdatesViewModel
import com.quirogaj.vistio.viewmodel.UpdateStatus
import com.quirogaj.vistio.view.update.UpdatesDialog

open class MainActivity : ComponentActivity() {
    protected open val isTv: Boolean by lazy {
        getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }

    private val model: CameraViewModel by viewModels {
        viewModelFactory {
            initializer {
                CameraViewModel(
                    CameraRepository(),
                    CameraProfileStore(applicationContext),
                    RtspPlayer(applicationContext),
                    playbackPreferences = getSharedPreferences(if (isTv) "tv_playback" else "mobile_playback", MODE_PRIVATE),
                    audioEnabledByDefault = isTv,
                )
            }
        }
    }

    private val updates: UpdatesViewModel by viewModels {
        viewModelFactory { initializer { UpdatesViewModel(UpdateRepository(applicationContext)) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Vistio)
        super.onCreate(savedInstanceState)
        if (isTv && requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        enableEdgeToEdge()
        setContent {
            var showSplash by rememberSaveable { mutableStateOf(savedInstanceState == null) }
            if (showSplash) {
                VistioTheme(tv = isTv) {
                    VistioSplash(onFinished = { showSplash = false })
                }
                return@setContent
            }

            val state by model.state.collectAsStateWithLifecycle()
            val updateState by updates.state.collectAsStateWithLifecycle()
            var showUpdates by rememberSaveable { mutableStateOf(false) }
            val installerPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                if (packageManager.canRequestPackageInstalls()) {
                    updates.state.value.apk?.let(::openUpdateInstaller)
                } else {
                    updates.installationMessage("Android necesita que permitas instalar actualizaciones desde Vistio.")
                }
            }
            LaunchedEffect(state.selectedProfile == null) {
                if (state.selectedProfile == null) updates.checkAutomatically()
            }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                model.onPermissionResult(it)
            }
            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
                val resolver = contentResolver
                model.saveResult(uri?.let { target -> { resolver.openOutputStream(target) } })
            }
            LaunchedEffect(state.fullscreen) { setFullscreen(state.fullscreen) }
            BackHandler(state.selectedProfile != null) {
                if (state.fullscreen) model.setFullscreen(false)
                else model.showProfiles()
            }

            VistioTvTheme(enabled = isTv) {
                VistioTheme(tv = isTv) {
                    CameraScreen(
                        state = state,
                        isTv = isTv,
                        updateAvailable = updateState.status == UpdateStatus.AVAILABLE,
                        openUpdates = { showUpdates = true },
                        connect = { profile, configuring ->
                            model.connectProfile(profile, configuring, hasLanPermission()) { permission.launch(LAN_PERMISSION) }
                        },
                        showProfiles = model::showProfiles,
                        editProfile = model::editProfile,
                        dismissEditor = model::dismissEditor,
                        saveProfile = model::saveProfile,
                        deleteProfile = model::deleteProfile,
                        loadProfiles = model::loadProfiles,
                        submitPassword = model::submitPassword,
                        dismissPassword = model::dismissPassword,
                        trustCertificate = model::trustCertificate,
                        dismissCertificate = model::dismissCertificate,
                        disconnect = model::disconnect,
                        fullscreen = model::setFullscreen,
                        capture = model::takeSnapshot,
                        toggleAudio = model::toggleAudio,
                        onVideoSurface = model::onVideoSurface,
                        reloadSettings = model::loadSettings,
                        changeSettings = model::changeSettings,
                        setMjpegCrop = model::setMjpegCrop,
                        moveCamera = model::moveCamera,
                        panel = ::openPanel,
                        dismissSnapshot = model::dismissSnapshot,
                        save = {
                            val filename = model.prepareSave()
                            if (filename != null) {
                                try {
                                    save.launch(filename)
                                } catch (_: ActivityNotFoundException) {
                                    model.saveResult(null)
                                    model.notify("El dispositivo no tiene selector de archivos.")
                                }
                            }
                        },
                        noticeShown = model::noticeShown,
                    )
                    if (showUpdates) {
                        UpdatesDialog(
                            state = updateState,
                            isTv = isTv,
                            check = { updates.check() },
                            download = updates::download,
                            cancelDownload = updates::cancelDownload,
                            install = {
                                val apk = updateState.apk
                                if (apk == null || !apk.exists()) {
                                    updates.downloadMissing()
                                } else if (packageManager.canRequestPackageInstalls()) {
                                    openUpdateInstaller(apk)
                                } else {
                                    try {
                                        installerPermission.launch(
                                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                                        )
                                    } catch (_: Exception) {
                                        updates.installationMessage("Este dispositivo no puede abrir el permiso de instalación.")
                                    }
                                }
                            },
                            dismiss = { showUpdates = false },
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.disconnect()
        super.onStop()
    }

    private fun hasLanPermission(): Boolean = Build.VERSION.SDK_INT < 37 ||
        checkSelfPermission(LAN_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun openPanel() {
        val address = model.panelAddress()
        if (address == null) {
            model.notify("Introduce una dirección HTTP o HTTPS para abrir el panel.")
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address)))
        } catch (_: ActivityNotFoundException) {
            model.notify("El dispositivo no tiene un navegador para abrir el panel.")
        }
    }

    private fun openUpdateInstaller(apk: File) {
        if (!apk.isFile) {
            updates.downloadMissing()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", apk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            updates.installationMessage("No se pudo abrir el instalador de Android en este dispositivo.")
        }
    }

    private fun setFullscreen(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        const val LAN_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
