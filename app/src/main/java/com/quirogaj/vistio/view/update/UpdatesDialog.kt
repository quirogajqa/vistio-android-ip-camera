package com.quirogaj.vistio.view.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.quirogaj.vistio.BuildConfig
import com.quirogaj.vistio.view.tv.TvActionButton
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
    val confirm = when (state.status) {
        UpdateStatus.AVAILABLE -> "Descargar" to download
        UpdateStatus.READY -> "Instalar actualización" to install
        UpdateStatus.DOWNLOADING -> "Cancelar descarga" to cancelDownload
        UpdateStatus.ERROR -> if (state.release == null) "Buscar actualizaciones" to check else "Reintentar descarga" to download
        UpdateStatus.IDLE, UpdateStatus.CURRENT -> "Buscar actualizaciones" to check
        else -> null
    }

    if (isTv) {
        val focus = remember { FocusRequester() }
        Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(0.86f),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text("Actualizaciones", style = MaterialTheme.typography.headlineMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        UpdateDetails(state, Modifier.weight(1.35f).heightIn(max = 280.dp).verticalScroll(rememberScrollState()), isTv = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            confirm?.let { (label, action) ->
                                TvActionButton(label, action, Modifier.fillMaxWidth().focusRequester(focus), primary = true)
                            }
                            TvActionButton(
                                "Cerrar", dismiss,
                                Modifier.fillMaxWidth().then(if (confirm == null) Modifier.focusRequester(focus) else Modifier),
                            )
                        }
                    }
                }
                LaunchedEffect(confirm?.first) { focus.requestFocus() }
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Actualizaciones") },
        text = { UpdateDetails(state) },
        confirmButton = {
            confirm?.let { (label, action) ->
                androidx.compose.material3.TextButton(onClick = action) { Text(label) }
            }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = dismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun UpdateDetails(state: UpdatesState, modifier: Modifier = Modifier, isTv: Boolean = false) {
    val typography = MaterialTheme.typography
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (isTv) 16.dp else 8.dp)) {
        Text(
            "Versión instalada · " + BuildConfig.VERSION_NAME,
            style = if (isTv) typography.titleMedium else typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            status,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = if (isTv) typography.bodyLarge else typography.bodyMedium,
        )
        if (state.status == UpdateStatus.CHECKING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (state.status == UpdateStatus.DOWNLOADING) {
            LinearProgressIndicator(progress = { state.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        if (state.status != UpdateStatus.ERROR) state.message?.let { Text(it) }
        val notes = state.release?.notes.orEmpty()
        if (notes.isNotBlank() && state.status == UpdateStatus.AVAILABLE) {
            Text(
                notes,
                if (isTv) Modifier else Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                style = if (isTv) typography.bodyLarge else typography.bodyMedium,
            )
        }
    }
}
