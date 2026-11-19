package com.quirogaj.vistio.view.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.quirogaj.vistio.data.CameraControl
import com.quirogaj.vistio.view.tv.CameraAction
import com.quirogaj.vistio.view.tv.tvFocusOutline
import com.quirogaj.vistio.viewmodel.CameraUiState
import com.quirogaj.vistio.viewmodel.ConnectionStatus

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CameraSettingsCard(
    state: CameraUiState,
    reload: () -> Unit,
    change: (CameraControl) -> Unit,
    isTv: Boolean = false,
) {
    val settings = state.settings
    val loading = state.settingsBusy || state.status == ConnectionStatus.Connecting
    val enabled = !loading && state.passwordPrompt == null
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ajustes de la cámara", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                CameraAction(isTv, "Actualizar", reload, enabled = enabled)
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (settings == null || settings.lenses.isEmpty()) return@Column

            SettingsDropdown(
                label = "Cámara y lente",
                placeholder = "Selecciona una lente",
                selection = settings.selected?.id,
                options = settings.lenses.map { it.id to it.displayName },
                enabled = enabled,
                select = { change(CameraControl.Lens(it)) },
                isTv = isTv,
            )
            settings.selected?.let { lens ->
                SettingsDropdown(
                    label = "Resolución y relación de aspecto",
                    placeholder = "Selecciona una resolución",
                    selection = settings.resolution,
                    options = listOf("auto" to "Automática") + lens.sizes.map { it.value to it.displayName },
                    enabled = enabled,
                    select = { change(CameraControl.Resolution(it)) },
                    isTv = isTv,
                )
            }
            if (settings.rotation != null || settings.selected != null) {
                Text(
                    settings.rotation?.let { "Rotación · $it°" } ?: "Rotación",
                    style = MaterialTheme.typography.labelLarge,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (degrees in listOf(0, 90, 180, 270)) {
                        FilterChip(
                            selected = settings.rotation == degrees,
                            onClick = { change(CameraControl.Rotation(degrees)) },
                            enabled = enabled,
                            label = { Text("$degrees°") },
                            modifier = Modifier.tvFocusOutline(isTv),
                        )
                    }
                }
            }
            settings.mirror?.let { mirror ->
                Row(
                    Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Espejo", style = MaterialTheme.typography.labelLarge)
                    Switch(
                        checked = mirror,
                        onCheckedChange = { change(CameraControl.Mirror(it)) },
                        enabled = enabled,
                        modifier = Modifier.tvFocusOutline(isTv).semantics { contentDescription = "Espejo" },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    placeholder: String,
    selection: String?,
    options: List<Pair<String, String>>,
    enabled: Boolean,
    select: (String) -> Unit,
    isTv: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    val display = options.firstOrNull { it.first == selection }?.second ?: selection.orEmpty()
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display,
            onValueChange = {},
            modifier = Modifier.fillMaxWidth().tvFocusOutline(isTv).menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
            readOnly = true,
            singleLine = true,
            enabled = enabled,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded && enabled) },
        )
        ExposedDropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    modifier = Modifier.tvFocusOutline(isTv),
                    onClick = {
                        expanded = false
                        select(value)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}
