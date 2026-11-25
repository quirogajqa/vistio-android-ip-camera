package com.quirogaj.vistio.view.camera

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.Surface as VideoSurface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import com.quirogaj.vistio.view.tv.CameraAction
import com.quirogaj.vistio.view.tv.TvCameraHome
import com.quirogaj.vistio.view.tv.TvHorizontalMargin
import com.quirogaj.vistio.view.tv.TvVerticalMargin
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.quirogaj.vistio.BuildConfig
import com.quirogaj.vistio.R
import com.quirogaj.vistio.view.tv.TvActionButton
import com.quirogaj.vistio.view.tv.TvFocusColor
import com.quirogaj.vistio.view.tv.TvFocusTextColor
import com.quirogaj.vistio.view.tv.tvFocusOutline
import com.quirogaj.vistio.data.CameraControl
import com.quirogaj.vistio.data.CameraDirection
import com.quirogaj.vistio.data.CameraProfile
import com.quirogaj.vistio.data.CameraEndpoint
import com.quirogaj.vistio.viewmodel.CameraAudioStatus
import com.quirogaj.vistio.viewmodel.CameraForm
import com.quirogaj.vistio.viewmodel.CameraUiState
import com.quirogaj.vistio.viewmodel.ConnectionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraScreen(
    state: CameraUiState,
    isTv: Boolean,
    updateAvailable: Boolean,
    openUpdates: () -> Unit,
    connect: (CameraProfile, Boolean) -> Unit,
    showProfiles: () -> Unit,
    editProfile: (CameraProfile?) -> Unit,
    dismissEditor: () -> Unit,
    saveProfile: (CameraForm) -> Unit,
    deleteProfile: (String) -> Unit,
    loadProfiles: () -> Unit,
    submitPassword: (String) -> Unit,
    dismissPassword: () -> Unit,
    trustCertificate: () -> Unit,
    dismissCertificate: () -> Unit,
    disconnect: () -> Unit,
    fullscreen: (Boolean) -> Unit,
    capture: () -> Unit,
    toggleAudio: () -> Unit,
    sendAnnouncement: () -> Unit,
    onVideoSurface: (VideoSurface, Boolean) -> Unit,
    reloadSettings: () -> Unit,
    changeSettings: (CameraControl) -> Unit,
    setMjpegCrop: (Boolean) -> Unit,
    moveCamera: (CameraDirection) -> (() -> Unit)?,
    panel: () -> Unit,
    dismissSnapshot: () -> Unit,
    save: () -> Unit,
    noticeShown: (String) -> Unit,
) {
    val messages = remember { SnackbarHostState() }
    var confirmMobileDelete by remember(state.selectedProfile?.id) { mutableStateOf(false) }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            messages.showSnackbar(it)
            noticeShown(it)
        }
    }

    Scaffold(
        contentWindowInsets = if (state.fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!isTv && state.selectedProfile == null) {
                TopAppBar(
                    title = { Text("Mis cámaras", maxLines = 1) },
                    actions = {
                        TextButton(onClick = openUpdates) {
                            Text(if (updateAvailable) "Actualizar" else "Actualizaciones")
                        }
                        Text("v${BuildConfig.VERSION_NAME}", Modifier.padding(end = 20.dp))
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(messages) },
    ) { padding ->
        val selected = state.selectedProfile
        Box(Modifier.fillMaxSize().padding(padding)) {
            val body = Modifier.fillMaxSize().then(
                if (state.configuring && !isTv) Modifier.padding(top = 64.dp) else Modifier,
            )
            when {
                selected == null && isTv -> TvCameraHome(
                    state, body, { connect(it, false) }, { connect(it, true) },
                    { editProfile(null) }, loadProfiles, updateAvailable, openUpdates,
                )
                selected == null -> CameraProfiles(
                    state, body, { connect(it, false) }, { connect(it, true) }, editProfile, loadProfiles,
                )
                state.configuring && isTv -> TvConfiguration(
                    name = selected.name,
                    modifier = body,
                    editorClosed = state.editor == null && state.passwordPrompt == null,
                    editProfile = { editProfile(selected) },
                    panel = panel,
                ) {
                    if (selected.customMjpeg) {
                        CameraPreview(state, selected, true, { connect(selected, true) }, moveCamera)
                    }
                    MjpegCropSwitch(selected.cropMjpegToWide, true, !state.profilesLoadFailed, setMjpegCrop)
                    if (!selected.customMjpeg) {
                        CameraSettingsCard(state, {
                            if (state.settings == null) connect(selected, true) else reloadSettings()
                        }, changeSettings, isTv = true)
                    }
                }
                state.configuring -> Box(body, contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth()
                            .verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                        if (selected.customMjpeg) {
                            CameraPreview(state, selected, false, { connect(selected, true) }, moveCamera)
                        } else {
                            CameraSettingsCard(state, {
                                if (state.settings == null) connect(selected, true) else reloadSettings()
                            }, changeSettings)
                        }
                        MjpegCropSwitch(selected.cropMjpegToWide, false, !state.profilesLoadFailed, setMjpegCrop)
                        OutlinedButton(onClick = { editProfile(selected) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Editar perfil")
                        }
                        TextButton(onClick = panel, modifier = Modifier.fillMaxWidth()) { Text("Abrir panel") }
                        TextButton(
                            onClick = { confirmMobileDelete = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.profilesBusy && !state.profilesLoadFailed,
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("Eliminar cámara") }
                    }
                }
                else -> PlayerCard(
                    state, body, isTv, capture, toggleAudio,
                    fullscreen = { fullscreen(!state.fullscreen) }, onVideoSurface = onVideoSurface,
                    reconnect = { connect(selected, false) }, disconnect = disconnect, showProfiles = showProfiles,
                    configure = { connect(selected, true) },
                    deleteCamera = { deleteProfile(selected.id) },
                    moveCamera = moveCamera,
                    setMjpegCrop = setMjpegCrop,
                    sendAnnouncement = sendAnnouncement,
                )
            }
            if (!isTv && selected != null && !state.fullscreen) {
                SmallFloatingActionButton(
                    onClick = showProfiles,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 8.dp),
                ) {
                    Text("Volver", modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }

    if (!isTv && state.configuring && confirmMobileDelete) {
        state.selectedProfile?.let { profile ->
            DeleteCameraDialog(
                name = profile.name,
                isTv = false,
                onDismiss = { confirmMobileDelete = false },
                onConfirm = {
                    confirmMobileDelete = false
                    deleteProfile(profile.id)
                },
            )
        }
    }
    state.editor?.let {
        ProfileEditor(it, state.profilesBusy, state.editorError, dismissEditor, saveProfile, deleteProfile, isTv)
    }
    state.certificatePrompt?.let { certificate ->
        val cancelFocus = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = dismissCertificate,
            title = { Text("¿Confiar en este certificado?") },
            text = {
                Column(
                    Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.selectedProfile?.address.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text("Acepta solo si reconoces la dirección de tu cámara. Si puedes, compara la huella con la del servidor.")
                    Text(certificate.subject, style = MaterialTheme.typography.bodySmall)
                    Text("SHA-256 · " + certificate.fingerprint.chunked(2).joinToString(":"),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { CameraAction(isTv, "Confiar y conectar", trustCertificate) },
            dismissButton = {
                LaunchedEffect(isTv) { if (isTv) cancelFocus.requestFocus() }
                CameraAction(isTv, "Cancelar", dismissCertificate, Modifier.focusRequester(cancelFocus))
            },
        )
    }
    state.passwordPrompt?.let { profile ->
        ProfilePasswordDialog(profile, submitPassword, dismissPassword, isTv)
    }

    state.snapshot?.let { snapshot ->
        AlertDialog(
            onDismissRequest = dismissSnapshot,
            title = { Text("Captura JPEG") },
            text = { Video(snapshot.preview, Modifier.heightIn(max = 320.dp).fillMaxWidth()) },
            confirmButton = { CameraAction(isTv, "Guardar…", save) },
            dismissButton = { CameraAction(isTv, "Cerrar", dismissSnapshot) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerCard(
    state: CameraUiState,
    modifier: Modifier,
    isTv: Boolean,
    capture: () -> Unit,
    toggleAudio: () -> Unit,
    sendAnnouncement: () -> Unit,
    fullscreen: () -> Unit,
    onVideoSurface: (VideoSurface, Boolean) -> Unit,
    reconnect: () -> Unit,
    disconnect: () -> Unit,
    showProfiles: () -> Unit,
    configure: () -> Unit,
    deleteCamera: () -> Unit,
    moveCamera: (CameraDirection) -> (() -> Unit)?,
    setMjpegCrop: (Boolean) -> Unit,
) {
    val live = state.status == ConnectionStatus.Live
    val profile = state.selectedProfile
    val canMove = remember(profile) { profile?.hasMovementControls() == true }
    val portrait = !isTv && LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val sidePanelVisible = canMove && !state.fullscreen && !portrait
    if (sidePanelVisible && !isTv) {
        val view = LocalView.current
        DisposableEffect(view) {
            val wasKeepingScreenOn = view.keepScreenOn
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = wasKeepingScreenOn }
        }
    }
    val cropToWide = profile?.cropMjpegToWide == true && !sidePanelVisible
    val controlFocus = remember { FocusRequester() }
    val videoFocus = remember { FocusRequester() }
    var videoFocused by remember(state.fullscreen) { mutableStateOf(false) }
    var confirmDelete by remember(state.selectedProfile?.id) { mutableStateOf(false) }
    val dialogClosed = state.passwordPrompt == null && state.certificatePrompt == null && state.snapshot == null
    LaunchedEffect(isTv, state.fullscreen, state.status, dialogClosed) {
        if (isTv && dialogClosed) controlFocus.requestFocus()
    }
    val colors = MaterialTheme.colorScheme
    val statusColor = when (state.status) {
        ConnectionStatus.Live -> colors.primary
        ConnectionStatus.Error -> colors.error
        else -> colors.onSurfaceVariant
    }
    val picture: @Composable (Modifier) -> Unit = { frameModifier ->
        Box(frameModifier.background(Color(0xFF090C12)), contentAlignment = Alignment.Center) {
            if (state.usingH264 || state.usingRtsp) {
                val size = state.avcSize
                val ratio = if (size != null) size.width.toFloat() / size.height else 16f / 9f
                AvcSurface(ratio, onVideoSurface)
            } else {
                Video(state.frame, Modifier.fillMaxSize(), cropToWide)
            }
            if (state.fullscreen || (live && (!isTv || canMove))) {
                val showVideoFocus = isTv && canMove && !state.fullscreen
                val focusModifier = when {
                    isTv && state.fullscreen -> Modifier.focusRequester(controlFocus)
                    showVideoFocus -> Modifier.focusRequester(videoFocus)
                        .focusProperties { right = controlFocus }
                        .onFocusChanged { videoFocused = it.isFocused }
                    else -> Modifier
                }
                Box(
                    Modifier.matchParentSize()
                        .then(focusModifier)
                        .clickable(
                            onClickLabel = if (state.fullscreen) "Salir de pantalla completa" else "Abrir pantalla completa",
                            onClick = fullscreen,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (showVideoFocus && videoFocused) {
                        Box(
                            Modifier.matchParentSize().padding(6.dp)
                                .border(4.dp, colors.primary, RoundedCornerShape(14.dp)),
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.primary,
                            contentColor = colors.onPrimary,
                        ) {
                            Text(
                                "Pantalla completa",
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }
            if (state.status == ConnectionStatus.Connecting) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color.White)
                    if (state.fullscreen) Text(state.statusText, color = Color.White)
                }
            } else if (!live && ((!isTv && !canMove) || state.fullscreen)) {
                val action = if (state.status == ConnectionStatus.Error) "Reintentar" else "Conectar"
                if (isTv) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = TvHorizontalMargin),
                    ) {
                        Text(state.statusText, color = Color.White, style = MaterialTheme.typography.bodyLarge)
                        CameraAction(true, action, reconnect, Modifier.focusRequester(controlFocus))
                    }
                } else {
                    OutlinedButton(onClick = reconnect) { Text(action, color = Color.White) }
                }
            }
        }
    }
    if (state.fullscreen) {
        picture(modifier)
        return
    }

    val status: @Composable () -> Unit = {
        Text(
            state.statusText,
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = if (isTv) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            color = statusColor,
        )
    }
    val canCapture = live && !state.capturing && !state.settingsBusy
    val captureLabel = if (state.capturing) "Capturando…" else "Captura"
    val customMjpeg = profile?.customMjpeg == true
    val audioAvailable = !customMjpeg || canMove
    val audioEnabled = if (customMjpeg) state.falconAudioEnabled else state.audioEnabled
    val audioLabel = when (state.audioStatus) {
        CameraAudioStatus.Off -> if (audioEnabled) "Desactivar audio" else "Activar audio"
        CameraAudioStatus.Connecting -> "Cancelar audio"
        CameraAudioStatus.Playing -> "Silenciar audio"
    }
    if (canMove && profile != null) {
        val panel: @Composable (Modifier) -> Unit = { panelModifier ->
            Surface(
                modifier = panelModifier,
                color = colors.surfaceContainer,
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = if (isTv) 12.dp else 8.dp, vertical = if (isTv) 14.dp else 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (isTv) 8.dp else 4.dp),
                ) {
                    status()
                    MovementArrows(isTv, live, moveCamera, buttonSize = 48.dp)
                    HorizontalDivider()
                    val mainAction = Modifier.fillMaxWidth().focusRequester(controlFocus)
                    when (state.status) {
                        ConnectionStatus.Live -> {
                            if (isTv) {
                                TvActionButton(
                                    "Pantalla completa", fullscreen,
                                    mainAction.focusProperties { left = videoFocus }, primary = true,
                                )
                                MjpegCropSwitch(
                                    profile.cropMjpegToWide, true, !state.profilesLoadFailed,
                                    setMjpegCrop, "Recorte 16:9",
                                )
                                HorizontalDivider()
                            }
                            CameraAction(
                            isTv, if (state.sendingAnnouncement) "Enviando aviso…" else "Enviar aviso",
                            sendAnnouncement, Modifier.fillMaxWidth(), enabled = !state.sendingAnnouncement,
                        )
                        if (portrait) {
                                FlowRow(horizontalArrangement = Arrangement.Center) {
                                    CameraAction(false, captureLabel, capture, enabled = canCapture)
                                    CameraAction(false, audioLabel, toggleAudio)
                                    CameraAction(false, "Desconectar", disconnect)
                                }
                            } else {
                                CameraAction(isTv, captureLabel, capture, Modifier.fillMaxWidth(), canCapture)
                                CameraAction(isTv, audioLabel, toggleAudio, Modifier.fillMaxWidth())
                                CameraAction(isTv, "Desconectar", disconnect, Modifier.fillMaxWidth())
                            }
                        }
                        ConnectionStatus.Connecting -> CameraAction(isTv, "Desconectar", disconnect, mainAction)
                        else -> CameraAction(isTv, "Reintentar", reconnect, mainAction)
                    }
                }
            }
        }
        if (portrait) {
            BoxWithConstraints(modifier.background(colors.background)) {
                val panelLimit = maxHeight * 0.6f
                Column(Modifier.fillMaxSize()) {
                    picture(Modifier.weight(1f).fillMaxWidth())
                    panel(Modifier.fillMaxWidth().heightIn(max = panelLimit))
                }
            }
        } else {
            Row(modifier.fillMaxSize().background(colors.background)) {
                picture(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.width(if (isTv) 232.dp else 164.dp).fillMaxHeight())
            }
        }
        return
    }
    if (isTv) {
        Row(
            modifier.background(colors.background).padding(horizontal = TvHorizontalMargin, vertical = TvVerticalMargin),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            picture(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)))
            Surface(
                modifier = Modifier.weight(0.4f).fillMaxHeight(),
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainer,
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.selectedProfile?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)
                    status()
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    val mainAction = Modifier.fillMaxWidth().focusRequester(controlFocus)
                    when (state.status) {
                        ConnectionStatus.Live -> CameraAction(true, "Pantalla completa", fullscreen, mainAction)
                        ConnectionStatus.Connecting -> CameraAction(true, "Desconectar", disconnect, mainAction)
                        else -> CameraAction(true, "Reintentar", reconnect, mainAction)
                    }
                    if (live) {
                        if (audioAvailable) {
                            CameraAction(true, audioLabel, toggleAudio, Modifier.fillMaxWidth())
                        }
                        CameraAction(true, captureLabel, capture, Modifier.fillMaxWidth(), canCapture)
                    }
                    CameraAction(true, "Configurar cámara", configure, Modifier.fillMaxWidth())
                    if (live) CameraAction(true, "Desconectar", disconnect, Modifier.fillMaxWidth())
                    CameraAction(true, "Mis cámaras", showProfiles, Modifier.fillMaxWidth())
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    CameraAction(
                        true, "Eliminar cámara", { confirmDelete = true }, Modifier.fillMaxWidth(),
                        enabled = !state.profilesBusy && !state.profilesLoadFailed,
                    )
                }
            }
        }
        if (confirmDelete) {
            DeleteCameraDialog(
                name = state.selectedProfile?.name.orEmpty(),
                isTv = true,
                onDismiss = { confirmDelete = false },
                onConfirm = {
                    confirmDelete = false
                    deleteCamera()
                },
            )
        }
    } else {
        Surface(modifier = modifier, color = colors.surfaceContainer) {
            Column {
                picture(Modifier.weight(1f).fillMaxWidth())
                Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { status() }
                HorizontalDivider(color = colors.outlineVariant)
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    CameraAction(false, captureLabel, capture, enabled = canCapture)
                    if (live && audioAvailable) CameraAction(false, audioLabel, toggleAudio)
                    if (live || state.status == ConnectionStatus.Connecting) {
                        CameraAction(false, "Desconectar", disconnect)
                    }
                }
            }
        }
    }
}
@Composable
private fun DeleteCameraDialog(name: String, isTv: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val cancelFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Eliminar cámara?") },
        text = { Text("Se quitará «$name» de Mis cámaras en este dispositivo.") },
        confirmButton = {
            if (isTv) {
                CameraAction(true, "Eliminar cámara", onConfirm)
            } else {
                TextButton(
                    onClick = onConfirm,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Eliminar cámara") }
            }
        },
        dismissButton = {
            LaunchedEffect(isTv) { if (isTv) cancelFocus.requestFocus() }
            CameraAction(isTv, "Cancelar", onDismiss, if (isTv) Modifier.focusRequester(cancelFocus) else Modifier)
        },
    )
}
@Composable
private fun MjpegCropSwitch(
    checked: Boolean,
    isTv: Boolean,
    enabled: Boolean,
    change: (Boolean) -> Unit,
    label: String = "Recortar MJPEG a 16:9",
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusOutline(isTv)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = change)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun CameraPreview(
    state: CameraUiState,
    profile: CameraProfile,
    isTv: Boolean,
    retry: () -> Unit,
    move: (CameraDirection) -> (() -> Unit)?,
) {
    val canMove = remember(profile) { profile.hasMovementControls() }
    val live = state.status == ConnectionStatus.Live
    val preview: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier.aspectRatio(if (profile.cropMjpegToWide) 16f / 9 else 4f / 3).clip(RoundedCornerShape(16.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            Video(state.frame, Modifier.fillMaxSize(), profile.cropMjpegToWide)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Vista previa", style = MaterialTheme.typography.titleMedium)
        if (isTv && canMove) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                preview(Modifier.weight(1f))
                MovementArrows(isTv, live, move)
            }
        } else {
            preview(Modifier.fillMaxWidth())
            if (canMove) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MovementArrows(isTv, live, move)
            }
        }
        if (!live) {
            Text(state.statusText, style = MaterialTheme.typography.bodyMedium)
            if (state.status != ConnectionStatus.Connecting && state.passwordPrompt == null &&
                state.certificatePrompt == null
            ) CameraAction(isTv, "Conectar vista previa", retry)
        }
    }
}

private fun CameraProfile.hasMovementControls(): Boolean =
    customMjpeg && runCatching { CameraEndpoint.isFalconStream(CameraEndpoint.mjpegUrl(address)) }
        .getOrDefault(false)

@Composable
private fun MovementArrows(
    isTv: Boolean,
    enabled: Boolean,
    move: (CameraDirection) -> (() -> Unit)?,
    buttonSize: Dp = 52.dp,
) {
    val button: @Composable (CameraDirection) -> Unit = { direction ->
        MovementButton(direction, isTv, enabled, buttonSize, move)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        button(CameraDirection.Up)
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            button(CameraDirection.Left)
            Spacer(Modifier.width(buttonSize))
            button(CameraDirection.Right)
        }
        button(CameraDirection.Down)
    }
}

@Composable
private fun MovementButton(
    direction: CameraDirection,
    isTv: Boolean,
    enabled: Boolean,
    size: Dp,
    move: (CameraDirection) -> (() -> Unit)?,
) {
    val start by rememberUpdatedState(move)
    var release by remember { mutableStateOf<(() -> Unit)?>(null) }
    var keyHeld by remember { mutableStateOf(false) }
    var pointerHeld by remember { mutableStateOf(false) }
    var keyRelease by remember { mutableStateOf<(() -> Unit)?>(null) }
    var focused by remember { mutableStateOf(false) }
    fun stop() {
        release?.invoke()
        release = null
    }
    fun releaseKey() {
        keyHeld = false
        keyRelease?.let { end ->
            end()
            if (release === end) release = null
        }
        keyRelease = null
    }
    DisposableEffect(enabled) {
        onDispose {
            releaseKey()
            stop()
        }
    }

    val colors = MaterialTheme.colorScheme
    val highlighted = isTv && focused
    val pressed = enabled && (keyHeld || pointerHeld)
    val buttonScale by animateFloatAsState(if (pressed) 0.86f else 1f, label = "movementPress")
    val shape = RoundedCornerShape(14.dp)
    val rotation = when (direction) {
        CameraDirection.Up -> 0f
        CameraDirection.Right -> 90f
        CameraDirection.Down -> 180f
        CameraDirection.Left -> 270f
    }

    Box(
        Modifier.size(size)
            .graphicsLayer {
                scaleX = buttonScale
                scaleY = buttonScale
            }
            .background(
                when {
                    pressed && highlighted -> TvFocusColor.copy(alpha = 0.72f)
                    highlighted -> TvFocusColor
                    pressed -> colors.primaryContainer
                    else -> colors.secondaryContainer
                },
                shape,
            )
            .border(if (highlighted) 3.dp else 1.dp, colors.outlineVariant, shape)
            .onFocusChanged {
                focused = it.isFocused
                if (!focused && keyHeld) releaseKey()
            }
            .onPreviewKeyEvent { event ->
                if (!enabled || event.key !in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    false
                } else {
                    when (event.type) {
                        KeyEventType.KeyDown -> if (!keyHeld) {
                            keyHeld = true
                            keyRelease = start(direction)
                            keyRelease?.let { release = it }
                        }
                        KeyEventType.KeyUp -> releaseKey()
                    }
                    true
                }
            }
            .focusable(enabled)
            .pointerInput(direction, enabled) {
                if (enabled) detectTapGestures(onPress = {
                    pointerHeld = true
                    val end = start(direction)
                    if (end != null) release = end
                    try {
                        tryAwaitRelease()
                    } finally {
                        pointerHeld = false
                        end?.invoke()
                        if (release === end) release = null
                    }
                })
            }
            .semantics {
                role = Role.Button
                contentDescription = direction.description
                if (enabled) onClick {
                    start(direction)?.invoke()
                    true
                } else disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_movement_arrow),
            contentDescription = null,
            modifier = Modifier.size(34.dp).rotate(rotation),
            tint = (if (highlighted) TvFocusTextColor else colors.onSecondaryContainer)
                .copy(alpha = if (enabled) 1f else 0.38f),
        )
    }
}

@Composable
private fun TvConfiguration(
    name: String,
    modifier: Modifier,
    editorClosed: Boolean,
    editProfile: () -> Unit,
    panel: () -> Unit,
    settings: @Composable () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(editorClosed) { if (editorClosed) focus.requestFocus() }
    Row(
        modifier.padding(start = TvHorizontalMargin, top = 8.dp, end = TvHorizontalMargin, bottom = TvVerticalMargin),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Surface(
            modifier = Modifier.weight(0.7f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                HorizontalDivider()
                CameraAction(true, "Editar perfil", editProfile, Modifier.fillMaxWidth().focusRequester(focus))
                CameraAction(true, "Abrir panel", panel, Modifier.fillMaxWidth())
            }
        }
        Column(Modifier.weight(1.2f).verticalScroll(rememberScrollState()).padding(4.dp)) {
            settings()
        }
    }
}

@Composable
private fun AvcSurface(ratio: Float, onSurface: (VideoSurface, Boolean) -> Unit) {
    val currentCallback by rememberUpdatedState(onSurface)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * ratio)
        AndroidView(
            modifier = Modifier.size(width, width / ratio),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        private var active: VideoSurface? = null

                        override fun surfaceCreated(holder: SurfaceHolder) {
                            active = holder.surface
                            currentCallback(holder.surface, true)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            active?.let { currentCallback(it, false) }
                            active = null
                        }
                    })
                }
            },
        )
    }
}

@Composable
private fun Video(bitmap: Bitmap?, modifier: Modifier, cropToWide: Boolean = false) {
    if (bitmap == null) {
        Box(modifier)
    } else {
        val painter = remember(bitmap, cropToWide) {
            val units = minOf(bitmap.width / 16, bitmap.height / 9)
            if (cropToWide && units > 0) {
                val width = units * 16
                val height = units * 9
                BitmapPainter(
                    bitmap.asImageBitmap(),
                    srcOffset = IntOffset((bitmap.width - width) / 2, (bitmap.height - height) / 2),
                    srcSize = IntSize(width, height),
                )
            } else {
                BitmapPainter(bitmap.asImageBitmap())
            }
        }
        Image(
            painter = painter,
            contentDescription = "Imagen de la cámara",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}
