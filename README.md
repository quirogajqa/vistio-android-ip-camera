# Vistio

Vistio muestra en Android y Android TV el video en directo de un teléfono que ejecuta [DigitallyRefined Android IP Camera](https://github.com/DigitallyRefined/android-ip-camera). Ambos dispositivos deben estar en la misma red local. El mismo APK detecta Android TV y adapta la interfaz al mando.

**[Descargar el APK](https://github.com/quirogajqa/vistio-android-ip-camera/releases/latest/download/vistio.apk)** · [Notas de la última versión](https://github.com/quirogajqa/vistio-android-ip-camera/releases/latest) · Android 8.0 o posterior · [Licencia GPLv3](LICENSE)

## Primeros pasos

1. Inicia **Android IP Camera** en el teléfono que hará de cámara y copia la dirección que muestra.
2. En Vistio, abre **Mis cámaras → Agregar cámara**. Introduce la dirección y, si corresponde, el usuario. Guarda el perfil.
3. Selecciona la cámara para ver el directo en pantalla completa. En el móvil, toca el video para alternar entre pantalla completa y los controles. En TV, pulsa **Atrás** para mostrar los controles. **Captura** permite guardar un JPEG.
4. Abre **Configurar cámara** para editar o eliminar el perfil, elegir la cámara frontal o trasera y cambiar resolución, rotación o espejo. Los ajustes que aplica el servidor también afectan a otros clientes conectados.

En Android 17, concede a Vistio el permiso de red local cuando se solicite.

### Android TV y configuración por QR

Usa las flechas y **OK** para moverte por **Mis cámaras**. El foco se marca en turquesa. Al salir de pantalla completa, el visor muestra un panel lateral con el estado y sus acciones. También puedes usar mouse.

Para escribir desde el celular, abre **Agregar cámara → Configurar con QR** y escanea el código. El navegador abre un formulario para enviar nombre, dirección, usuario y huella SHA-256 opcional. En la TV, revisa los datos y pulsa **Guardar perfil**. Introduce la contraseña en la TV. El enlace local usa HTTP, vence después de tres minutos y se cierra tras recibir el formulario o al salir del panel.

### Contraseñas y HTTPS

HTTP transmite el video y las credenciales sin cifrar. Si activas HTTPS en el servidor, Vistio usa la validación de Android. Para un certificado autofirmado puedes dejar la huella vacía. Vistio muestra el certificado antes de enviar las credenciales. Comprueba que corresponde a tu cámara y pulsa **Confiar y conectar** para fijar su huella en ese perfil. Si la huella cambia, Vistio bloquea la conexión hasta que verifiques el nuevo certificado. También puedes copiar una huella verificada en **Editar perfil**.

**Recordar contraseña en este dispositivo** cifra la contraseña con Android Keystore. Los perfiles se guardan localmente fuera de las copias de seguridad.

## Modos de video

| Modo | Cómo usarlo |
| --- | --- |
| **MJPEG** | Modo predeterminado. Usa el flujo HTTP o HTTPS de la cámara. |
| **H.264** | Activa **Preferir H.264** en el perfil si el servidor ofrece ese flujo. |
| **RTSPS** | Activa RTSP en el servidor y usa una dirección HTTPS en el perfil. Activa **Preferir RTSPS** e introduce el puerto indicado por la cámara, normalmente **8554**. El flujo principal usa `/live` y Media3 lo reproduce por TCP dentro de TLS. |

Vistio vuelve a MJPEG si el flujo preferido no está disponible. El visor indica el protocolo activo al salir de pantalla completa.

### Audio del directo

En los controles del visor, pulsa **Activar audio** para escuchar el micrófono de Android IP Camera y **Silenciar audio** para apagarlo. El servidor necesita permiso de micrófono. El audio arranca activado en TV y silenciado en móvil hasta que cambies la opción. Vistio recuerda la elección en cada dispositivo y detiene la reproducción al desconectar o pasar a segundo plano.

## Actualizaciones

Abre **Mis cámaras → Actualizaciones** para buscar, descargar e instalar una versión publicada en GitHub Releases. Vistio también comprueba si hay novedades cuando han pasado 14 días desde la última comprobación exitosa. Android puede pedir permiso para instalar desde Vistio y siempre muestra su propia confirmación.

## Compilar y preparar una publicación

Abre el proyecto en Android Studio con Android SDK 37. La app usa Kotlin, Jetpack Compose, Material 3 y Compose for TV. Las pantallas observan el estado del ViewModel y la capa de datos gestiona las conexiones y los perfiles.

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

Para habilitar las actualizaciones en una compilación local, añade a `local.properties` los datos del canal público:

```properties
VISTIO_UPDATE_GITHUB_OWNER=quirogajqa
VISTIO_UPDATE_GITHUB_REPOSITORY=vistio-android-ip-camera
```

Para publicar una versión, compila con un número de tres partes superior al anterior. Firma el APK con la misma clave que la última publicación y ejecuta:

```powershell
.\gradlew.bat :app:assembleRelease "-PappVersionName=1.7.3"
.\tools\Prepare-Update.ps1 -ApkPath .\vistio-firmado.apk
```

Adjunta el APK numerado y `update.json` de `build/update-release` a la Release correspondiente. Añade una copia del APK como `vistio.apk` para mantener el enlace fijo de Downloader. Antes de abrir el instalador, Vistio comprueba el tamaño, SHA-256, paquete, versión y firma.

## Licencia

El código, los scripts y la documentación propios se distribuyen bajo [GNU GPL versión 3 únicamente (GPL-3.0-only)](LICENSE). El logo de Vistio, el gato de QuirogaJ, los iconos derivados y el banner de TV se ofrecen bajo la misma licencia en la medida de los derechos que puedan concederse. Los componentes de terceros conservan sus licencias originales.
