package com.quirogaj.vistio.view.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.quirogaj.vistio.R
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
    val colors = MaterialTheme.colorScheme
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    val active = state.profiles.firstOrNull { it.id == activeId } ?: state.profiles.firstOrNull()
    val first = remember { FocusRequester() }
    val primary = remember { FocusRequester() }
    val firstId = state.profiles.firstOrNull()?.id
    val initialFocus = if (firstId == null) primary else first
    LaunchedEffect(firstId, state.profilesBusy, state.profilesLoadFailed, state.editor == null) {
        if (!state.profilesBusy && state.editor == null) initialFocus.requestFocus()
    }

    Column(
        modifier.background(colors.background)
            .padding(horizontal = TvHorizontalMargin, vertical = TvVerticalMargin),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                painterResource(R.drawable.vistio_logo),
                contentDescription = "Vistio",
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
            )
            Text("Mis cámaras", color = colors.onSurface, style = MaterialTheme.typography.headlineLarge)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            LazyColumn(
                modifier = Modifier.weight(0.95f).fillMaxHeight(),
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(state.profiles, key = { it.id }) { profile ->
                    val watch = { connect(profile) }
                    TvLibraryRow(
                        title = profile.name,
                        subtitle = profile.address,
                        onClick = watch,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) activeId = profile.id }
                            .focusProperties { right = primary }
                            .then(if (profile.id == firstId) Modifier.focusRequester(first) else Modifier),
                    )
                }
                if (!state.profilesBusy && !state.profilesLoadFailed && active != null) {
                    item { TvLibraryRow("Agregar cámara", null, add) }
                }
                item {
                    TvLibraryRow(if (updateAvailable) "Actualizar Vistio" else "Actualizaciones", null, openUpdates)
                }
            }
            Column(
                Modifier.weight(1.2f).fillMaxHeight()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Brush.verticalGradient(listOf(colors.primaryContainer, colors.surface)))
                    .padding(28.dp),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.profilesBusy) {
                        CircularProgressIndicator(color = colors.primary)
                    } else {
                        Icon(
                            painterResource(R.drawable.ic_tv_camera),
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(88.dp),
                        )
                    }
                }
                if (!state.profilesBusy) {
                    val title = when {
                        state.profilesLoadFailed -> "No se pudieron cargar las cámaras"
                        active != null -> active.name
                        else -> "Agrega tu primera cámara"
                    }
                    Text(title, color = colors.onSurface, style = MaterialTheme.typography.headlineMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        active?.address ?: if (state.profilesLoadFailed) "Vuelve a cargar los perfiles guardados."
                            else "Usa la dirección que muestra Android IP Camera.",
                        modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val open = when {
                        state.profilesLoadFailed -> reload
                        active != null -> ({ connect(active) })
                        else -> add
                    }
                    val action = when {
                        state.profilesLoadFailed -> "Reintentar"
                        active != null -> "Ver en directo"
                        else -> "Agregar cámara"
                    }
                    TvActionButton(
                        label = action,
                        onClick = open,
                        modifier = Modifier.fillMaxWidth().focusRequester(primary),
                        primary = true,
                    )
                    if (active != null && !state.profilesLoadFailed) {
                        CameraAction(
                            true, "Configurar cámara", { configure(active) },
                            Modifier.padding(top = 12.dp).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvLibraryRow(title: String, subtitle: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().tvPointerActivation(onClick = onClick),
        colors = CardDefaults.colors(
            containerColor = colors.surface,
            contentColor = colors.onSurface,
            focusedContainerColor = TvFocusColor,
            focusedContentColor = TvFocusTextColor,
        ),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, TvFocusColor), inset = 3.dp)),
        scale = CardDefaults.scale(focusedScale = 1.025f),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = if (subtitle == null) 54.dp else 76.dp)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
