# Vistio

Cliente Android para ver una cámara de DigitallyRefined Android IP Camera en la red local. Requiere Android 8.0 o posterior.

1. Abre este proyecto en Android Studio y ejecuta el módulo `app` en el dispositivo Android que usará Vistio.
2. Conecta ambos dispositivos a la misma red e inicia Android IP Camera. En **Mis cámaras**, pulsa **Agregar cámara**, escribe la dirección que muestra el teléfono y guarda el perfil. En Android 17 concede el permiso de red local en la primera conexión.
3. Toca la tarjeta del perfil para abrir el video en pantalla completa. Usa **Volver** para regresar a tus perfiles. **Configuración**, en la tarjeta, abre los ajustes y permite editar o eliminar el perfil.
4. Toca el video o pulsa Atrás para salir de pantalla completa. Usa **Pantalla completa** para volver a ampliarlo. **Captura** obtiene un JPEG que puedes guardar con el selector de archivos. **Abrir panel**, dentro de **Configuración**, abre los controles originales. Vistio cierra la transmisión al pasar a segundo plano.

En una cámara protegida, activa **Recordar contraseña en este dispositivo** para conectar con un toque. La contraseña se cifra con una clave de Android Keystore y los perfiles se guardan localmente fuera de las copias de seguridad. Al editar el mismo perfil, la contraseña recordada ya aparece cargada y oculta. Si dejas esa opción desactivada, Vistio pide la contraseña al conectar.

El cliente solicita `/video/mjpeg` y `/video/snapshot` con autenticación HTTP Basic. HTTP transmite el video y las credenciales sin cifrar. HTTPS usa la confianza de Android y exige que el certificado corresponda a la dirección. Si usas un certificado autofirmado, obtén su huella SHA-256 desde una fuente de confianza, por ejemplo el archivo de certificado de la cámara. En **Editar perfil**, usa la dirección HTTPS y guarda esa huella. Vistio verificará el certificado exacto de ese perfil y rechazará los cambios hasta que verifiques y actualices la huella.

## Android TV

El mismo APK incluye una entrada en el lanzador de Android TV y una interfaz en horizontal con Compose for TV. En **Mis cámaras**, usa las flechas para enfocar una cámara y **OK** para abrir el directo en pantalla completa. Pulsa **OK** o **Atrás** en el directo para ver los controles. Desde **Configuración** puedes editar el perfil y ajustar la cámara. Las tarjetas y los botones de TV también aceptan clics de mouse y toques enviados por scrcpy.

Los perfiles se guardan en cada dispositivo. Usa **Agregar cámara** para configurar en el televisor la dirección local, las credenciales y la huella SHA-256 cuando corresponda. Los modos MJPEG, H.264 y RTSPS se comparten con el cliente móvil. En el Mortal T1 se verificaron el inicio de la interfaz TV, el foco inicial y la apertura y cierre del formulario mediante OK y Atrás. En 1.6.1 se verificaron clics de mouse y toques simulados por ADB. Con un clic en un perfil de prueba se abrió el directo MJPEG en el Mortal T1 y otro clic en el video mostró los controles.

## Ajustes de la cámara

Pulsa **Configuración** en la tarjeta para abrir **Ajustes de la cámara**. **Cámara y lente** muestra las opciones que informa el teléfono remoto. En **Resolución y relación de aspecto**, elige un tamaño disponible para esa lente, como **1280×720 · 16:9** o **640×480 · 4:3**, o deja que el teléfono decida con **Automática**. La resolución, la rotación y el espejo modifican la configuración de Android IP Camera para la lente seleccionada y también afectan a otros clientes de esa cámara. **Actualizar** vuelve a leer la selección y los valores del servidor.

## RTSPS

Activa RTSP en Android IP Camera. En **Editar perfil**, conserva la dirección HTTPS habitual para los ajustes y las capturas. Activa **Preferir RTSPS** e indica el puerto que muestra el servidor, normalmente **8554**. Vistio usa el flujo principal `/live`, transporta el video por TCP dentro del canal TLS y reutiliza el usuario, la contraseña y la huella verificada del mismo perfil. Si el flujo no está disponible, intenta MJPEG por HTTPS. El visor muestra el protocolo activo al salir de pantalla completa.

La reproducción RTSPS usa AndroidX Media3.

## Actualizaciones por GitHub Releases

En **Mis cámaras**, abre **Actualizaciones** para buscar, descargar e instalar una versión. Al abrir la pantalla principal, Vistio busca automáticamente si pasaron 14 días desde la última comprobación exitosa. La descarga y la instalación se inician desde la pantalla de actualizaciones. Android puede pedir permiso para instalar desde Vistio y luego muestra su confirmación.

### Configurar el canal

Para usar el canal de Releases de Vistio, añade estos valores a local.properties:

    VISTIO_UPDATE_GITHUB_OWNER=quirogajqa
    VISTIO_UPDATE_GITHUB_REPOSITORY=vistio-android-ip-camera

Si el repositorio es público, deja el token vacío. En un canal privado de uso personal se puede configurar VISTIO_UPDATE_GITHUB_TOKEN con un token de solo lectura limitado al repositorio. Ese token queda incorporado en el APK y puede extraerse, así que distribuye ese APK únicamente a sus destinatarios. Los valores se leen al compilar y local.properties está excluido de Git.

Con el repositorio configurado, instala la primera compilación con este gestor en cada dispositivo mediante ADB o Android Studio. Las versiones posteriores deben usar el mismo paquete y la misma clave de firma.

### Preparar una versión

Compila con appVersionName en formato MAYOR.MENOR.PARCHE. Firma el APK de release con la misma clave que la versión anterior y pásalo al script:

    .\gradlew.bat :app:assembleRelease "-PappVersionName=1.7.1"
    .\tools\Prepare-Update.ps1 -ApkPath .\vistio-firmado.apk

El script genera el APK con el nombre de la versión y update.json en build/update-release. Adjunta ambos a una Release estable de GitHub. El gestor valida el tamaño, SHA-256, paquete, versión y firma del APK antes de abrir el instalador. Incrementa la versión al preparar cada actualización.

En 1.7.0 se verificaron el APK firmado, el SHA-256 de update.json y el menú Actualizaciones en el Mortal T1. El canal público de actualizaciones utiliza los APK y metadatos de las Releases de este repositorio.

## Arquitectura

La aplicación está escrita en Kotlin y usa Jetpack Compose con Material 3. La estructura sigue la de SmartCompra.

- `view/camera` muestra el estado de la cámara y envía las acciones de la pantalla.
- `viewmodel` expone un `StateFlow` de solo lectura y ejecuta las operaciones de red con corrutinas de `viewModelScope`.
- `data` implementa el acceso HTTP/HTTPS y la lectura de MJPEG.
- `ui/theme` aplica el tema Material 3 con modo claro y oscuro.

## Verificación

La consulta al [código de Android IP Camera](https://github.com/DigitallyRefined/android-ip-camera/blob/main/app/src/main/kotlin/com/github/digitallyrefined/androidipcamera/helpers/StreamingServerHelper.kt) confirmó las rutas, el multipart MJPEG y la autenticación Basic. Las pruebas unitarias usan un flujo multipart simulado para comprobar la separación de fotogramas con y sin longitud declarada, el cierre y el límite de tamaño. Queda pendiente probar en Android el guardado de perfiles al cerrar y volver a abrir Vistio, la contraseña recordada y la conexión por perfil con una cámara real.
`assembleDebug` y `testDebugUnitTest` finalizaron correctamente. El APK de pruebas quedó en `app/build/outputs/apk/debug/app-debug.apk`.

## Licencia

El código, los scripts y la documentación propios de Vistio se distribuyen bajo la GNU General Public License, versión 3 únicamente (GPL-3.0-only). Consulta el texto completo en [LICENSE](LICENSE).

El logo de Vistio, sus iconos derivados y el banner de Android TV se ofrecen bajo GPL-3.0-only en la medida de los derechos que puedan concederse. Identifica claramente las versiones modificadas que redistribuyas. Los componentes de terceros conservan sus licencias originales.

Vistio es un cliente independiente para [DigitallyRefined/android-ip-camera](https://github.com/DigitallyRefined/android-ip-camera).
