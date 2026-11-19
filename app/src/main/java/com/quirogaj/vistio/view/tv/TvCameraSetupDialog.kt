package com.quirogaj.vistio.view.tv

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.quirogaj.vistio.data.CameraSetup
import com.quirogaj.vistio.data.CameraSetupServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun TvCameraSetupDialog(onReceived: (CameraSetup) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val receive by rememberUpdatedState(onReceived)
    val permission = "android.permission.ACCESS_LOCAL_NETWORK"
    var allowed by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
    }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var message by remember { mutableStateOf("Preparando el código QR…") }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = it
        if (!it) message = "Concede acceso a la red local para recibir el formulario."
    }
    LaunchedEffect(allowed, lifecycle) {
        if (!allowed) {
            requestPermission.launch(permission)
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            qr = null
            message = "Preparando el código QR…"
            try {
                val result = withContext(Dispatchers.IO) {
                    CameraSetupServer(context.applicationContext).use { server ->
                        val bits = QRCodeWriter().encode(server.url, BarcodeFormat.QR_CODE, 480, 480)
                        val pixels = IntArray(bits.width * bits.height) { index ->
                            if (bits[index % bits.width, index / bits.width]) android.graphics.Color.BLACK
                            else android.graphics.Color.WHITE
                        }
                        val bitmap = Bitmap.createBitmap(pixels, bits.width, bits.height, Bitmap.Config.ARGB_8888)
                        withContext(Dispatchers.Main) { qr = bitmap }
                        server.receive()
                    }
                }
                qr = null
                if (result == null) message = "El código venció. Cierra este panel y vuelve a abrirlo."
                else receive(result)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                qr = null
                message = "No se pudo abrir el formulario. Comprueba la conexión Wi-Fi o Ethernet de la TV."
            }
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        val closeFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) { closeFocus.requestFocus() }
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Configurar desde el celular", style = MaterialTheme.typography.headlineSmall)
                val bitmap = qr
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), "Código QR del formulario de cámara", Modifier.size(220.dp))
                    Text("Escanea con la cámara de tu celular conectado a la misma red.")
                } else {
                    Text(message)
                }
                TvActionButton("Cerrar", onDismiss, Modifier.fillMaxWidth().focusRequester(closeFocus))
            }
        }
    }
}
