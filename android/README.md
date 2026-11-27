# Captura Clash · Android público

Abre **`clash-royale-apk/android/`** en Android Studio. Requiere Android 8.0 (API 26) o posterior.

El usuario pulsa **Iniciar sesión**, acepta las notificaciones y el consentimiento de captura, y selecciona Clash Royale. **No introduce ningún token ni crea una cuenta.** La URL de producción ya está configurada; solo se muestra en Configuración avanzada para pruebas. El consentimiento de MediaProjection se pide en cada sesión.

## Compilar e instalar

Kotlin integrado en AGP 9.1.1, Gradle Wrapper 9.3.1, compile SDK 37, target SDK 36, versión de app 1.2 (código 3). Instala SDK Platform 37 y Build Tools 36.0.0. Los criterios del daemon en `gradle/gradle-daemon-jvm.properties` solicitan JVM 25; usa el JBR 25 de Android Studio. El bytecode de la app tiene destino Java 17.

Desde `android/`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Comando usado en este ordenador:

```sh
JAVA_HOME=/opt/android-studio/jbr GRADLE_USER_HOME=/tmp/clash-gradle-home ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK: **`app/build/outputs/apk/debug/app-debug.apk`**. Android Studio configura `local.properties`, ignorado por Git, con tu ruta del SDK.

Conecta el teléfono con Depuración USB activada, acepta su autorización y pulsa Run en Android Studio. Alternativamente:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Si Xiaomi bloquea la instalación USB, copia el APK a Descargas y ábrelo en el teléfono para confirmar la instalación. No necesitas depuración USB para instalar manualmente un APK.

## Probar ahora sin Cloudflare

La versión 1.2 incluye **Probar captura sin conexión**. No necesita API, Internet, token ni configuración del servidor.

1. Pulsa **Probar captura sin conexión** y autoriza compartir Clash Royale. Si Android pide notificaciones, permítelas y vuelve a pulsar el botón.
2. Abre el juego y juega entre 10 y 15 segundos.
3. Pulsa **Detener** en la notificación o **Detener sesión** en la app.
4. Pulsa **Ver capturas locales**. Usa Anterior/Siguiente para revisar los JPEG, sus timestamps y el intervalo entre imágenes de la misma sesión (aproximadamente 1000 ms).

Se guardan **todas las capturas de partida de la sesión** en el almacenamiento privado de la app (menús, cofres y pantallas de inicio/fin se omiten). Al iniciar una prueba local nueva se borran las de la sesión anterior. Hay un tope de seguridad de 1200 imágenes (unos 20 minutos de partida). No se envían al servidor. El contador Guardadas localmente indica los guardados acumulados durante esa sesión; el visor muestra las imágenes retenidas. El visor solo se abre con la captura detenida. Puedes repetir la prueba sin conexión. Esta comprobación valida captura, consentimiento, parada, legibilidad y cadencia; la subida real sigue requiriendo el backend desplegado.

## Configuración del servidor — solo el administrador

El cambio a público sustituye el contrato inicial de token compartido introducido por el usuario, a petición del propietario de la app. **No se incluye ninguna clave privada en el APK.** La app pide automáticamente una credencial temporal al servidor y la conserva únicamente en memoria.

Los tres archivos para `devEddu17x/deep-learning-clash-royale` están en `server/annotator/functions/`. Se entrega `server/ingest.patch` para un checkout sin la ruta anterior. Si ya aplicaste la primera versión del parche, sustituye su `api/ingest.js` y añade `_ingest-auth.js` y `api/ingest-session.js`; no apliques el parche de archivos nuevos encima de archivos existentes.

1. En Cloudflare → Workers & Pages → **cr-annotator** → Settings → Bindings, añade **`CR_VIDEOS`** apuntando al bucket existente **`cr-videos`**. Conserva los bindings D1 y demás configuración del anotador.
2. En Variables and Secrets configura **`INGEST_SIGNING_KEY` como secreto** de producción, con una cadena aleatoria de al menos 32 caracteres. Puedes generarla privadamente con `openssl rand -hex 32`. Esta clave solo se configura en Cloudflare: **no se entrega al público ni se introduce en el teléfono**. Conserva `ADMIN_TOKEN` del anotador. La nueva ruta no usa `INGEST_TOKEN`.
3. Despliega el proyecto incluyendo los tres archivos. Asegúrate de que `/api/ingest-session` y `/api/ingest` llegan a las funciones y no a la página estática ni a middleware que exija `ADMIN_TOKEN`. No borres, recrees ni migres D1 para esta entrega.
4. Configura límites de peticiones en Cloudflare para las dos rutas públicas y una política de consumo/retención en R2 antes de distribuir a gran escala. La credencial temporal limita la sesión y su duración; la emisión anónima no acredita identidad/dispositivo ni implementa cuotas por usuario. No se presenta como un sistema de autenticación de usuarios o de protección contra abuso.

No se han desplegado ni configurado secretos remotos desde este entorno. La URL prevista es `https://cr-dataset.eddux.dev/api/ingest`. La app requiere que las dos rutas estén desplegadas para iniciar una sesión de captura.

Referencias: [bindings y secretos de Pages](https://developers.cloudflare.com/pages/functions/bindings/), [HMAC en Web Crypto de Cloudflare](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/).

## Contrato público coordinado

- `POST /api/ingest-session`, sin cuerpo ni credenciales para una sesión nueva. Responde 201 con `sessionId` aleatorio generado por el servidor, `token` temporal, `expiresAt` (ms de servidor) y `expiresIn` (3600 segundos). No acepta que el cliente elija una sesión ajena.
- La misma ruta con `Authorization: Bearer <credencial temporal vigente>` renueva la misma sesión. La app renueva automáticamente antes de caducar, con un reloj monotónico. Si caduca estando sin red, debe iniciarse una nueva sesión.
- `POST /api/ingest` conserva cuerpo JPEG, `Content-Type: image/jpeg`, `X-Session-Id` y `X-Capture-Timestamp` originales. `Authorization` usa la credencial temporal obtenida automáticamente. HMAC-SHA256 verifica firma, duración y pertenencia a la sesión; no acepta el antiguo token estático.
- Límite: 2 MiB por JPEG. Se validan marcadores, longitudes y dimensiones JPEG, sin decodificar píxeles. Se escribe exclusivamente `ingest/<sesión>/<timestamp>.jpg` en `CR_VIDEOS`, con tipo `image/jpeg`. Respuesta 201 con `key` tras confirmación de R2. Los reintentos usan la misma clave.
- Errores: 400 datos inválidos, 401 credencial inválida/caducada, 403 sesión distinta, 405 método, 413 tamaño, 415 tipo, 502 fallo R2 y 503 configuración incompleta. Las respuestas con credenciales usan `Cache-Control: no-store`. No se registran claves ni credenciales.

## Prueba de aceptación en teléfono

1. Con el servidor configurado y desplegado, abre Captura Clash y pulsa Iniciar sesión. No debe aparecer un campo de token.
2. Permite notificaciones y autoriza la captura de Clash Royale. Si compartes toda la pantalla, abre el juego tras el diálogo. La app conecta automáticamente y empieza a capturar cuando el servidor concede la sesión.
3. Juega entre 15 y 30 segundos. Detén desde la notificación mientras el juego sigue abierto. También puedes detener desde la app.
4. Revisa capturas, subidas confirmadas, errores, descartes y timestamps en la app. En R2 → cr-videos → ingest/ → sesión mostrada, abre JPEG legibles de la partida y compara los nombres: diferencias aproximadas de 1000 ms. Verifica que no haya nuevas capturas al detener; una petición ya recibida por el servidor puede terminar de guardarse.
5. Prueba otra sesión, rotación, revocación desde Android y desconexión/reconexión de red. Cada sesión nueva pide consentimiento e ID nuevo. Las demoras de subida no deben alterar la toma de imágenes.

Captura en hilo propio cada 1000 ms con reloj monotónico, lado mayor hasta 1280 px, proporción original y JPEG calidad 85. Consume buffers continuamente; para contenido estático reutiliza la imagen más reciente. Android no garantiza tiempo real estricto. La red usa otro hilo, ocho imágenes en cola más una en envío y hasta tres intentos por imagen con pausas de 1 y 2 s. Se renueva la credencial manteniendo la sesión y los timestamps. Una sesión se autoriza por red antes de comenzar la captura; tras comenzar, captura y subida son independientes. Detener cancela los envíos y descarta lo pendiente; no hay persistencia de imágenes ni captura de audio.

## Verificación

```sh
node --test --test-isolation=none server/tests/ingest.test.mjs
```

Tres pruebas JVM del almacenamiento local (bytes/timestamps originales, conservación de la sesión completa, borrado de sesiones anteriores y tope de seguridad y validación de rutas) y 16 pruebas del servidor con un JPEG real y R2 simulado, incluidas creación anónima, renovación, subida automática, expiración, firmas alteradas y aislamiento entre sesiones. Resultados de compilación y límites en `VERIFICACION.md`. Quedan pendientes el despliegue/configuración real de Cloudflare y las pruebas completas de captura/subida en teléfono. AWS e inferencia quedan fuera del alcance.
