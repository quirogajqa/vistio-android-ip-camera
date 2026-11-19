package com.quirogaj.vistio.view.camera

import android.graphics.Bitmap
import android.view.Surface as VideoSurface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.quirogaj.vistio.view.tv.CameraAction
import com.quirogaj.vistio.view.tv.TvCameraHome
import com.quirogaj.vistio.view.tv.TvControlGap
import com.quirogaj.vistio.view.tv.TvHorizontalMargin
import com.quirogaj.vistio.view.tv.TvVerticalMargin
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.quirogaj.vistio.BuildConfig
import com.quirogaj.vistio.data.CameraControl
import com.quirogaj.vistio.data.CameraProfile
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
    onVideoSurface: (VideoSurface, Boolean) -> Unit,
    reloadSettings: () -> Unit,
    changeSettings: (CameraControl) -> Unit,
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
            val profile = state.selectedProfile
            if (!state.fullscreen && isTv && state.configuring && profile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                        .padding(start = TvHorizontalMargin, top = TvVerticalMargin, end = TvHorizontalMargin, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(TvControlGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CameraAction(true, "Volver", showProfiles)
                    Text(
                        if (state.configuring) "Configuración" else profile.name,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (!state.fullscreen && !isTv) TopAppBar(
                title = {
                    val name = state.selectedProfile?.name
                    Text(if (state.configuring) "Configuración" else name ?: "Mis cámaras", maxLines = 1)
                },
                navigationIcon = {
                    if (state.selectedProfile != null) {
                        CameraAction(false, "Volver", showProfiles)
                    }
                },
                actions = {
                    if (state.selectedProfile == null) {
                        TextButton(onClick = openUpdates) {
                            Text(if (updateAvailable) "Actualizar" else "Actualizaciones")
                        }
                        Text("v${BuildConfig.VERSION_NAME}", Modifier.padding(end = 20.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(messages) },
    ) { padding ->
        val selected = state.selectedProfile
        val body = Modifier.fillMaxSize().padding(padding)
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
                CameraSettingsCard(state, {
                    if (state.settings == null) connect(selected, true) else reloadSettings()
                }, changeSettings, isTv = true)
            }
            state.configuring -> Box(body, contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(selected.name, style = MaterialTheme.typography.headlineSmall)
                    CameraSettingsCard(state, {
                        if (state.settings == null) connect(selected, true) else reloadSettings()
                    }, changeSettings)
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
                state, body, isTv, capture, { fullscreen(!state.fullscreen) }, onVideoSurface,
                reconnect = { connect(selected, false) }, disconnect = disconnect, showProfiles = showProfiles,
                configure = { connect(selected, true) },
                deleteCamera = { deleteProfile(selected.id) },
            )
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
    fullscreen: () -> Unit,
    onVideoSurface: (VideoSurface, Boolean) -> Unit,
    reconnect: () -> Unit,
    disconnect: () -> Unit,
    showProfiles: () -> Unit,
    configure: () -> Unit,
    deleteCamera: () -> Unit,
) {
    val live = state.status == ConnectionStatus.Live
    val controlFocus = remember { FocusRequester() }
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
                Video(state.frame, Modifier.fillMaxSize())
            }
            if (state.fullscreen) {
                Box(
                    Modifier.matchParentSize()
                        .then(if (isTv && (live || state.status == ConnectionStatus.Connecting)) Modifier.focusRequester(controlFocus) else Modifier)
                        .clickable(onClickLabel = "Salir de pantalla completa", onClick = fullscreen),
                )
            }
            if (state.status == ConnectionStatus.Connecting) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = Color.White)
                    if (state.fullscreen) Text(state.statusText, color = Color.White)
                }
            } else if (!live && (!isTv || state.fullscreen)) {
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
                    CameraAction(false, "Pantalla completa", fullscreen, enabled = live)
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
private fun Video(bitmap: Bitmap?, modifier: Modifier) {
    if (bitmap == null) {
        Box(modifier)
    } else {
        Image(
            bitmap = remember(bitmap) { bitmap.asImageBitmap() },
            contentDescription = "Imagen de la cámara",
            modifier = modifier,
            contentScale = ContentScale.Fit,
        )
    }
}
