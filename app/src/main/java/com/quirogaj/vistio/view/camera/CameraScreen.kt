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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.quirogaj.vistio.view.tv.CameraAction
import com.quirogaj.vistio.view.tv.TvCameraHome
import androidx.compose.ui.Modifier
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
    LaunchedEffect(state.notice) {
        state.notice?.let {
            messages.showSnackbar(it)
            noticeShown(it)
        }
    }

    Scaffold(
        contentWindowInsets = if (state.fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!state.fullscreen && (!isTv || state.selectedProfile != null)) TopAppBar(
                title = {
                    val name = state.selectedProfile?.name
                    Text(if (state.configuring) "Configuración" else name ?: "Mis cámaras", maxLines = 1)
                },
                navigationIcon = {
                    if (state.selectedProfile != null) {
                        CameraAction(isTv, "Volver", showProfiles, Modifier.padding(start = if (isTv) 24.dp else 0.dp))
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
                }
            }
            else -> PlayerCard(
                state, body, isTv, capture, { fullscreen(!state.fullscreen) }, onVideoSurface,
                reconnect = { connect(selected, false) }, disconnect = disconnect,
            )
        }
    }

    state.editor?.let {
        ProfileEditor(it, state.profilesBusy, state.editorError, dismissEditor, saveProfile, deleteProfile, isTv)
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
) {
    val live = state.status == ConnectionStatus.Live
    val controlFocus = remember { FocusRequester() }
    val dialogClosed = state.passwordPrompt == null && state.snapshot == null
    LaunchedEffect(isTv, state.fullscreen, state.status, dialogClosed) {
        if (isTv && dialogClosed) controlFocus.requestFocus()
    }
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier, color = colors.surfaceContainer) {
        Column {
            Box(
                Modifier.weight(1f).fillMaxWidth().background(Color(0xFF101312)),
                contentAlignment = Alignment.Center,
            ) {
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
                            .then(if (isTv) Modifier.focusRequester(controlFocus) else Modifier)
                            .clickable(onClickLabel = "Salir de pantalla completa", onClick = fullscreen),
                    )
                }
                if (state.status == ConnectionStatus.Connecting) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = Color.White)
                        if (state.fullscreen) Text(state.statusText, color = Color.White)
                    }
                } else if (!live) {
                    val action = if (state.status == ConnectionStatus.Error) "Reintentar" else "Conectar"
                    if (isTv) {
                        CameraAction(isTv, action, reconnect, Modifier.focusRequester(controlFocus))
                    } else {
                        OutlinedButton(onClick = reconnect) { Text(action, color = Color.White) }
                    }
                }
            }
            if (state.fullscreen) return@Column
            Text(
                state.statusText,
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = when (state.status) {
                    ConnectionStatus.Live -> colors.primary
                    ConnectionStatus.Error -> colors.error
                    else -> colors.onSurfaceVariant
                },
            )
            HorizontalDivider(color = colors.outlineVariant)
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = if (isTv) 32.dp else 8.dp, vertical = 8.dp),
                horizontalArrangement = if (isTv) Arrangement.spacedBy(16.dp, Alignment.End) else Arrangement.SpaceBetween,
            ) {
                CameraAction(
                    isTv, if (state.capturing) "Capturando…" else "Captura", capture,
                    enabled = live && !state.capturing && !state.settingsBusy,
                )
                CameraAction(
                    isTv, "Pantalla completa", fullscreen,
                    modifier = if (isTv && live) Modifier.focusRequester(controlFocus) else Modifier,
                    enabled = live,
                )
                if (live || state.status == ConnectionStatus.Connecting) {
                    CameraAction(
                        isTv, "Desconectar", disconnect,
                        modifier = if (isTv && !live) Modifier.focusRequester(controlFocus) else Modifier,
                    )
                }
            }
        }
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
        modifier.padding(horizontal = 42.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.weight(0.8f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
            CameraAction(true, "Editar perfil", editProfile, Modifier.focusRequester(focus))
            CameraAction(true, "Abrir panel", panel)
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
