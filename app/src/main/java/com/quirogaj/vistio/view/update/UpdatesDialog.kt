package com.quirogaj.vistio.view.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.quirogaj.vistio.BuildConfig
import com.quirogaj.vistio.view.tv.CameraAction
import com.quirogaj.vistio.viewmodel.UpdateStatus
import com.quirogaj.vistio.viewmodel.UpdatesState

@Composable
internal fun UpdatesDialog(
    state: UpdatesState,
    isTv: Boolean,
    check: () -> Unit,
    download: () -> Unit,
    cancelDownload: () -> Unit,
    install: () -> Unit,
    dismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val canCheck = state.status == UpdateStatus.IDLE || state.status == UpdateStatus.CURRENT || state.status == UpdateStatus.ERROR
    val confirm = when {
        state.status == UpdateStatus.AVAILABLE -> "Descargar"
        state.status == UpdateStatus.READY -> "Instalar actualización"
        state.status == UpdateStatus.DOWNLOADING -> "Cancelar descarga"
        state.status == UpdateStatus.ERROR && state.release != null -> "Reintentar descarga"
        canCheck -> "Buscar actualizaciones"
        else -> null
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Actualizaciones") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Versión instalada · " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelLarge)
                val status = when (state.status) {
                    UpdateStatus.NOT_CONFIGURED -> "El canal de GitHub Releases todavía no está configurado."
                    UpdateStatus.IDLE -> "Busca la última versión disponible de Vistio."
                    UpdateStatus.CHECKING -> "Buscando actualizaciones…"
                    UpdateStatus.CURRENT -> "Ya tienes la última versión disponible."
                    UpdateStatus.AVAILABLE -> "Nueva versión · " + state.release?.versionName.orEmpty()
                    UpdateStatus.DOWNLOADING -> "Descargando actualización · " + state.progress + "%"
                    UpdateStatus.READY -> "El APK se verificó y está listo para instalar."
                    UpdateStatus.ERROR -> state.message.orEmpty()
                }
                Text(status)
                if (state.status == UpdateStatus.CHECKING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (state.status == UpdateStatus.DOWNLOADING) {
                    LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
                if (state.status != UpdateStatus.ERROR) state.message?.let { Text(it) }
                val notes = state.release?.notes.orEmpty()
                if (notes.isNotBlank() && state.status == UpdateStatus.AVAILABLE) {
                    Text(notes, Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()))
                }
            }
        },
        confirmButton = {
            if (confirm != null) {
                LaunchedEffect(isTv, confirm) { if (isTv) focus.requestFocus() }
                CameraAction(isTv, confirm, {
                    when (state.status) {
                        UpdateStatus.AVAILABLE -> download()
                        UpdateStatus.DOWNLOADING -> cancelDownload()
                        UpdateStatus.READY -> install()
                        UpdateStatus.ERROR -> if (state.release == null) check() else download()
                        else -> check()
                    }
                }, Modifier.focusRequester(focus))
            }
        },
        dismissButton = {
            if (confirm == null) LaunchedEffect(isTv) { if (isTv) focus.requestFocus() }
            CameraAction(isTv, "Cerrar", dismiss, if (confirm == null) Modifier.focusRequester(focus) else Modifier)
        },
    )
}
