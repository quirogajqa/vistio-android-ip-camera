package com.quirogaj.vistio.view.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.quirogaj.vistio.BuildConfig
import com.quirogaj.vistio.data.CameraProfile
import com.quirogaj.vistio.viewmodel.CameraUiState

@Composable
internal fun TvCameraHome(
    state: CameraUiState,
    modifier: Modifier,
    connect: (CameraProfile) -> Unit,
    configure: (CameraProfile) -> Unit,
    add: () -> Unit,
    reload: () -> Unit,
    updateAvailable: Boolean,
    openUpdates: () -> Unit,
) {
    val first = remember { FocusRequester() }
    val addFocus = remember { FocusRequester() }
    val retry = remember { FocusRequester() }
    val empty = state.profiles.isEmpty()
    val canAdd = !state.profilesBusy && !state.profilesLoadFailed
    LaunchedEffect(state.profilesBusy, state.profilesLoadFailed, empty, state.editor == null) {
        if (!state.profilesBusy && state.editor == null) {
            if (state.profilesLoadFailed) retry.requestFocus()
            else if (empty) addFocus.requestFocus()
        }
    }
    Column(
        modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vistio · v" + BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                Text("Mis cámaras", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineLarge)
            }
            OutlinedButton(
                onClick = openUpdates,
                modifier = Modifier.padding(end = 16.dp).tvPointerActivation(onClick = openUpdates),
            ) {
                Text(if (updateAvailable) "Actualizar" else "Actualizaciones")
            }
            Button(
                onClick = add,
                enabled = canAdd,
                modifier = Modifier.focusRequester(addFocus).tvPointerActivation(canAdd, add),
            ) {
                Text("Agregar cámara")
            }
        }
        when {
            state.profilesBusy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.profilesLoadFailed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No se pudieron leer los perfiles guardados.", color = MaterialTheme.colorScheme.onSurface)
                OutlinedButton(
                    onClick = reload,
                    modifier = Modifier.focusRequester(retry).tvPointerActivation(onClick = reload),
                ) { Text("Reintentar") }
            }
            empty -> Text(
                "Usa la dirección que muestra Android IP Camera para agregar tu primera cámara.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(238.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                itemsIndexed(state.profiles, key = { _, profile -> profile.id }) { index, profile ->
                    if (index == 0) {
                        LaunchedEffect(state.editor == null) { if (state.editor == null) first.requestFocus() }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val watch = { connect(profile) }
                        val settings = { configure(profile) }
                        Card(
                            onClick = watch,
                            modifier = Modifier.fillMaxWidth().then(
                                if (index == 0) Modifier.focusRequester(first) else Modifier,
                            ).tvPointerActivation(onClick = watch),
                        ) {
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                            ) {
                                Text(profile.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(profile.address, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        OutlinedButton(
                            onClick = settings,
                            modifier = Modifier.tvPointerActivation(onClick = settings),
                        ) { Text("Configuración") }
                    }
                }
            }
        }
    }
}
