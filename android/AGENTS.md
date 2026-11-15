# AGENTS.md · App Android

## Alcance

La app pertenece al repositorio [`albaM15/clash-royale-apk`](https://github.com/albaM15/clash-royale-apk). Trabaja en **`clash-royale-apk/android/` como raíz del proyecto Gradle**, donde están este archivo y `settings.gradle.kts`. Esta es también la carpeta que se abre en Android Studio.

Consulta `../contexto-codex-android-clash-royale.md`, `README.md` y `VERIFICACION.md`. El contexto original describe un prototipo con token manual; el usuario cambió después el requisito a una **app para público abierto, sin introducir tokens**. Conserva ese requisito actualizado.

## Reglas para trabajar en la app

- Usa Kotlin y conserva la estructura y las versiones del proyecto Android si ya existe. Mantén la interfaz en español.
- Usa `MediaProjection` con consentimiento del usuario en cada sesión. Permite detener la captura mientras el juego está abierto. Libera los recursos cuando termine la sesión o Android revoque el permiso.
- Revisa la pantalla cada 1000 ms durante la sesión y guarda o sube solo las capturas de partida en curso (`GameplayDetector`: barra de elixir magenta en el 15 % inferior, misma regla que el notebook 02). Menús, cofres, "VS" y animaciones de inicio/fin se omiten y se cuentan como "Fuera de partida". Mantén la captura independiente de las demoras de red y conserva el identificador de sesión y la hora original de cada imagen al reintentar un envío.
- No pidas tokens, contraseñas de infraestructura ni configuración de Cloudflare al público. La app obtiene y renueva una credencial temporal automáticamente. La URL de producción viene configurada y puede cambiarse en Configuración avanzada para pruebas.
- Mantén las claves privadas fuera del repositorio, del APK y de los registros. `INGEST_SIGNING_KEY` es un secreto exclusivo del servidor; las credenciales temporales de sesión viven solo en memoria. No vuelvas a introducir el token estático manual del prototipo.
- Coordina cualquier cambio del contrato de subida con la Pages Function del anotador, que vive en el repositorio `devEddu17x/deep-learning-clash-royale`. Mantén el destino `cr-videos` con el prefijo `ingest/`. El alcance de Android en esta entrega es captura y subida. AWS e inferencia corresponden a una etapa posterior.
- Conserva la prueba local sin conexión, diferenciada del modo de subida. Captura hasta que se detenga la sesión, guardando **todas las capturas de partida de la sesión actual** (`LocalCaptureStore`). Al iniciar una prueba local nueva se borran las sesiones locales anteriores. El único límite es un tope de seguridad de 1200 imágenes por si la sesión se deja abierta durante horas.

## Hecho

- Proyecto Kotlin y Gradle Wrapper en `android/`, interfaz en español. Versión actual **1.4**, `versionCode = 5`, paquete `dev.eddux.clashcapture`.
- AGP 9.1.1, Gradle 9.3.1, Kotlin integrado en AGP, mínimo Android 8/API 26, compile SDK 37 y target SDK 36. Wrapper con comprobación SHA-256 de la distribución.
- Captura implementada con `MediaProjection`, consentimiento por sesión, servicio en primer plano, parada desde la notificación y liberación de recursos al detener/revocar.
- JPEG cada 1000 ms con reloj monotónico, proporción original, lado mayor máximo de 1280 px y calidad 85. Adaptación al tamaño compartido y rotación.
- Subida en otro hilo, cola de ocho imágenes más una en envío, hasta tres intentos por imagen y conservación de sesión/timestamp al reintentar. Contadores de capturas, subidas confirmadas, pendientes, errores y descartes.
- Eliminado el campo de token del APK. Inicio de sesión anónimo y renovación automática de la autorización implementados en cliente y servidor.
- Botón **Probar captura sin conexión**: funciona sin API ni Internet, guarda JPEG en almacenamiento privado y no realiza subidas. Botón **Ver capturas locales** con visor, timestamps e intervalo entre imágenes; disponible después de detener la captura.
- Funciones entregadas en `server/annotator/functions/` y actualizadas también en el repositorio local `/home/alba/dev/deep-learning-clash-royale/annotator/functions/`:
  - `_ingest-auth.js`: firma y verificación de credenciales temporales mediante HMAC-SHA256.
  - `api/ingest-session.js`: creación anónima y renovación de sesiones.
  - `api/ingest.js`: validación y subida del JPEG a R2.
- Parche actualizado en `server/ingest.patch`. Añade los tres archivos en un checkout sin la versión anterior; si ya existen, revisar e integrar en lugar de aplicar el parche de archivos nuevos encima.
- Versión **1.3**: filtro de partida en `GameplayDetector.kt`, aplicado antes de comprimir el JPEG, en la prueba local y en la subida. Pruebas en `GameplayDetectorTest.kt` con capturas reales (partida, "VS", cofre e inicio sin cartas) y el caso de 0 de elixir.
- Versión **1.4**: la prueba local guarda todas las capturas de partida de la sesión (ya no solo las últimas 30) y borra las sesiones locales anteriores al iniciar una nueva.
- Documentados compilación, instalación, configuración administrativa y pruebas en `README.md`; resultados detallados en `VERIFICACION.md`.

## Contrato actual de subida

- `POST /api/ingest-session` sin credenciales crea una sesión con ID aleatorio del servidor y devuelve `sessionId`, `token`, `expiresAt` y `expiresIn` (3600 segundos).
- La misma ruta con la credencial temporal vigente en `Authorization: Bearer ...` renueva la misma sesión. La app lo gestiona automáticamente.
- `POST /api/ingest` recibe bytes JPEG, `Content-Type: image/jpeg`, `X-Session-Id`, `X-Capture-Timestamp` y la credencial temporal en `Authorization`.
- Valida firma, caducidad, pertenencia a la sesión, identificación, estructura JPEG y tamaño máximo de 2 MiB. Construye `ingest/<sesión>/<timestamp>.jpg` y responde `201` con `key` tras confirmar la escritura en R2.
- Configuración administrativa: binding **`CR_VIDEOS` → bucket existente `cr-videos`** y secreto **`INGEST_SIGNING_KEY`** de al menos 32 caracteres. La nueva ruta no usa `INGEST_TOKEN`; conserva `ADMIN_TOKEN` y D1 del anotador.
- El modo de subida requiere autorización inicial del servidor antes de capturar. Durante la captura, las demoras de red no bloquean las nuevas imágenes. Para probar sin backend, usa el modo local.
- La emisión de sesiones es anónima: la credencial temporal limita sesión/duración, pero no acredita identidad de usuario/dispositivo ni implementa cuotas de consumo.

## Verificado

- Compilación de depuración y Lint: **BUILD SUCCESSFUL**, **0 errores y 7 avisos** de Lint (dos de versiones y cinco de textos del visor solo en español).
- **Pruebas JVM del almacenamiento local**: bytes/timestamps originales, conservación de la sesión completa, borrado de sesiones anteriores, tope de seguridad y rechazo de rutas fuera del almacenamiento privado.
- **16 pruebas del servidor aprobadas**, con JPEG real y R2 simulado: sesión anónima, renovación, integración sesión-subida, caducidad, firma alterada, aislamiento de sesiones, validación, tamaño y errores de almacenamiento.
- `git apply --check` del parche actualizado aprobado sobre una carpeta sin las funciones anteriores.
- APK 1.2 instalado correctamente en el Xiaomi conectado mediante ADB y actividad principal abierta. **Esto no confirma una sesión real de captura ni una subida a R2.**

## Pendiente

- Configurar en Cloudflare Pages `INGEST_SIGNING_KEY` y `CR_VIDEOS`, conservando D1, `ADMIN_TOKEN` y las rutas del anotador.
- **Desplegar las funciones actualizadas** en `cr-annotator`. Se modificó el repositorio local, pero no se realizó despliegue remoto. Comprobar que las dos rutas llegan a las funciones y no al HTML del anotador ni a un middleware con autenticación administrativa.
- Configurar límites de tráfico para las rutas públicas y una política de consumo/retención en R2 antes de distribuir a gran escala. No se implementaron cuotas por usuario ni acreditación de dispositivo.
- Completar la prueba física local: consentimiento, Clash Royale abierto, JPEG legibles, intervalos cercanos a 1000 ms, parada desde la notificación, revocación por Android, rotación y conservación de todas las capturas de partida de la sesión. No consta una verificación de estas capturas por el agente.
- Completar la prueba con Pages/R2: inicio automático sin token manual, subidas confirmadas, JPEG reales de la partida en `cr-videos/ingest/<sesión>/`, cadencia, reintentos tras desconexión, renovación y rechazo de credenciales inválidas sin crear objetos.
- No se ha realizado ninguna subida real a R2 desde este entorno. La entrega completa sigue pendiente de esas pruebas de aceptación.
- AWS, inferencia y modelo siguen fuera del alcance de esta entrega.

## Verificación

- Ejecuta la compilación de depuración con Gradle y documenta el comando usado para generar el APK.
- Prueba en un teléfono físico el inicio y fin de sesión, la captura con Clash Royale abierto y la aparición de JPEG legibles en `cr-videos/ingest/`. Comprueba la cadencia mediante los timestamps de captura.
- Indica cuáles pruebas ejecutaste y cuáles requieren acceso a Cloudflare Pages o a un teléfono.

Desde `android/`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
node --test --test-isolation=none server/tests/ingest.test.mjs
```

En este ordenador se usó `JAVA_HOME=/opt/android-studio/jbr GRADLE_USER_HOME=/tmp/clash-gradle-home` para ejecutar Gradle. APK: `app/build/outputs/apk/debug/app-debug.apk`.

Prueba inmediata sin Cloudflare: **Probar captura sin conexión → autorizar Clash Royale → jugar 10–15 s → Detener → Ver capturas locales**. Distingue siempre esta prueba de la subida real a R2.
