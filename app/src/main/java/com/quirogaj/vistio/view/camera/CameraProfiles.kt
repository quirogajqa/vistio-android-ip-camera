package com.quirogaj.vistio.view.camera

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.quirogaj.vistio.view.tv.CameraAction
import com.quirogaj.vistio.view.tv.tvFocusOutline
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.quirogaj.vistio.R
import com.quirogaj.vistio.data.CameraProfile
import com.quirogaj.vistio.viewmodel.CameraForm
import com.quirogaj.vistio.viewmodel.CameraUiState

@Composable
internal fun CameraProfiles(
    state: CameraUiState,
    modifier: Modifier,
    connect: (CameraProfile) -> Unit,
    configure: (CameraProfile) -> Unit,
    edit: (CameraProfile?) -> Unit,
    reload: () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.profilesBusy && state.editor == null -> item {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.profilesLoadFailed -> item {
                    Text("No se pudieron leer los perfiles guardados.")
                    TextButton(onClick = reload) { Text("Reintentar") }
                }
                else -> {
                    item {
                        if (state.profiles.isEmpty()) {
                            Text("Agrega tu primera cámara", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "Usa la dirección que muestra Android IP Camera.",
                                Modifier.padding(top = 4.dp, bottom = 12.dp),
                            )
                        }
                        Button(onClick = { edit(null) }) { Text("Agregar cámara") }
                    }
                    items(state.profiles, key = { it.id }) { profile ->
                        Card(onClick = { connect(profile) }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        profile.address, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { configure(profile) }) { Text("Configuración") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProfileEditor(
    initial: CameraForm,
    busy: Boolean,
    error: String?,
    dismiss: () -> Unit,
    save: (CameraForm) -> Unit,
    delete: (String) -> Unit,
    isTv: Boolean = false,
) {
    var form by remember(initial) { mutableStateOf(initial) }
    var confirmDelete by remember(initial) { mutableStateOf(false) }
    val editing = initial.id != null
    val initialFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (editing) "Editar cámara" else "Agregar cámara") },
        text = {
            LaunchedEffect(isTv) { if (isTv) initialFocus.requestFocus() }
            Column(
                Modifier.imePadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = form.name, onValueChange = { form = form.copy(name = it) },
                    modifier = Modifier.fillMaxWidth()
                        .then(if (isTv) Modifier.focusRequester(initialFocus) else Modifier).tvFocusOutline(isTv),
                    enabled = !busy, singleLine = true,
                    label = { Text("Nombre (opcional)") },
                    placeholder = { Text("Ej. Entrada") },
                )
                OutlinedTextField(
                    value = form.address,
                    onValueChange = { form = form.copy(address = it, fingerprint = "", password = "") },
                    modifier = Modifier.fillMaxWidth().tvFocusOutline(isTv), enabled = !busy, singleLine = true,
                    label = { Text("Dirección de la cámara") },
                    placeholder = { Text("http://192.168.1.10:4444") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = form.username, onValueChange = { form = form.copy(username = it, password = "") },
                    modifier = Modifier.fillMaxWidth().tvFocusOutline(isTv), enabled = !busy, singleLine = true,
                    label = { Text("Usuario (opcional)") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                )
                if (form.username.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = form.rememberPassword,
                            onCheckedChange = { form = form.copy(rememberPassword = it, password = "") },
                            enabled = !busy,
                            modifier = Modifier.tvFocusOutline(isTv),
                        )
                        Text("Recordar contraseña en este dispositivo")
                    }
                    if (form.rememberPassword) {
                        PasswordField(form.password, { form = form.copy(password = it) }, !busy, isTv = isTv)
                        Text(
                            if (initial.password.isNotEmpty() && form.address == initial.address && form.username == initial.username)
                                "Contraseña guardada. Usa el ojo para verla o escribe otra para cambiarla."
                            else "La contraseña se guarda cifrada.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (form.address.trim().startsWith("http://", ignoreCase = true)) {
                        Text("Al conectar por HTTP, el video y las credenciales viajan sin cifrar.")
                    }
                }
                if (form.address.trim().startsWith("https://", ignoreCase = true)) {
                    OutlinedTextField(
                        value = form.fingerprint,
                        onValueChange = { form = form.copy(fingerprint = it) },
                        modifier = Modifier.fillMaxWidth().tvFocusOutline(isTv),
                        enabled = !busy,
                        singleLine = true,
                        label = { Text("Huella SHA-256 (opcional)") },
                        supportingText = { Text("Para un certificado autofirmado, pega la huella verificada del certificado de la cámara.") },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusOutline(isTv).toggleable(
                        value = form.preferH264,
                        enabled = !busy,
                        role = Role.Switch,
                        onValueChange = { form = form.copy(preferH264 = it) },
                    ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Preferir H.264", modifier = Modifier.weight(1f))
                    Switch(checked = form.preferH264, onCheckedChange = null, enabled = !busy)
                }
                if (form.address.trim().startsWith("https://", ignoreCase = true)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).tvFocusOutline(isTv).toggleable(
                            value = form.preferRtsp,
                            enabled = !busy,
                            role = Role.Switch,
                            onValueChange = { form = form.copy(preferRtsp = it) },
                        ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Preferir RTSPS", modifier = Modifier.weight(1f))
                        Switch(checked = form.preferRtsp, onCheckedChange = null, enabled = !busy)
                    }
                    if (form.preferRtsp) {
                        OutlinedTextField(
                            value = form.rtspPort,
                            onValueChange = { form = form.copy(rtspPort = it) },
                            modifier = Modifier.fillMaxWidth().tvFocusOutline(isTv),
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("Puerto RTSPS") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            supportingText = { Text("Usa el puerto que muestra Android IP Camera. El habitual es 8554.") },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (editing && !confirmDelete) {
                    CameraAction(isTv, "Eliminar perfil", { confirmDelete = true }, enabled = !busy)
                }
                if (confirmDelete) {
                    Text("¿Eliminar este perfil del dispositivo?")
                    CameraAction(isTv, "Confirmar eliminación", { initial.id?.let(delete) }, enabled = !busy)
                }
            }
        },
        confirmButton = { CameraAction(isTv, "Guardar perfil", { save(form) }, enabled = !busy) },
        dismissButton = { CameraAction(isTv, "Cancelar", dismiss, enabled = !busy) },
    )
}

@Composable
internal fun ProfilePasswordDialog(
    profile: CameraProfile,
    submit: (String) -> Unit,
    dismiss: () -> Unit,
    isTv: Boolean = false,
) {
    var password by remember(profile.id) { mutableStateOf("") }
    val initialFocus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Contraseña de la cámara") },
        text = {
            LaunchedEffect(isTv) { if (isTv) initialFocus.requestFocus() }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(profile.username)
                PasswordField(
                    password, { password = it }, true,
                    modifier = if (isTv) Modifier.focusRequester(initialFocus) else Modifier,
                    isTv = isTv,
                )
                if (profile.address.startsWith("http://")) Text("HTTP envía las credenciales sin cifrar.")
            }
        },
        confirmButton = { CameraAction(isTv, "Conectar", { submit(password) }) },
        dismissButton = { CameraAction(isTv, "Cancelar", dismiss) },
    )
}

@Composable
private fun PasswordField(
    value: String,
    change: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    isTv: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = change, modifier = modifier.fillMaxWidth().tvFocusOutline(isTv),
        enabled = enabled, singleLine = true,
        label = { Text("Contraseña") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.tvFocusOutline(isTv)) {
                Icon(
                    painter = painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility),
                    contentDescription = if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                )
            }
        },
    )
}
