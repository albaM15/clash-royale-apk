# Verificación · versión 1.2 con prueba local

Cambio solicitado: el público inicia captura sin introducir tokens. La URL de producción está configurada; el selector de URL queda en configuración avanzada. La app obtiene una credencial temporal automáticamente y la conserva solo en memoria. La clave de firma es configuración exclusiva del servidor.

- Gradle: `JAVA_HOME=/opt/android-studio/jbr GRADLE_USER_HOME=/tmp/clash-gradle-home ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` → **BUILD SUCCESSFUL**.
- Lint: **0 errores, 7 avisos**: dos de versiones fijadas (SDK objetivo y Gradle), cinco de textos del visor que actualmente están solo en español. Informe en `app/build/reports/lint-results-debug.html`.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, versión 1.2, código 3, 881070 bytes.
- SHA-256: `69cc87a048ccbb20cb68958117b3939f9ef78c201f7ff1a3c47c5ad9a84b38af`.
- Pruebas locales JVM: **3 aprobadas**, sin errores ni omitidas. Comprueban bytes/timestamps originales, retención global de 30 JPEG entre sesiones y rechazo de rutas fuera del almacenamiento privado.
- La prueba local omite completamente la autorización de red y los envíos. Guarda los JPEG en almacenamiento privado y permite revisarlos con sus timestamps e intervalos al detener la sesión. No valida la subida a R2.

- Servidor: `node --test --test-isolation=none server/tests/ingest.test.mjs` → **16 pruebas aprobadas**. Cubre sesión anónima, renovación, integración sesión-subida, caducidad, firma alterada, rechazo de otra sesión, autenticación ausente, JPEG, límites de tamaño, clave estable al reintentar, configuración y fallo R2. R2 simulado.
- El parche actualizado añade tres archivos a un checkout sin la versión anterior y pasa `git apply --check`. Las funciones se actualizaron también en el repositorio local del anotador. No se ha publicado ni configurado Cloudflare remoto.
- Instalación física de 1.2: `adb install -r app/build/outputs/apk/debug/app-debug.apk` devolvió **Success** en el Xiaomi conectado. Se abrió la actividad principal con `adb shell am start -n dev.eddux.clashcapture/.MainActivity`. No se autorizó ni ejecutó una sesión de MediaProjection durante esta comprobación.
- El nuevo contrato sustituye el token compartido del prototipo por autorización temporal automática. Se documenta completo en README.md.

## Pendiente

Configurar `INGEST_SIGNING_KEY`, binding `CR_VIDEOS`, límites de tráfico de las rutas públicas y desplegar Pages. La emisión es anónima: las credenciales temporales no son identidad de usuario/dispositivo ni cuotas de consumo. No se realizó ninguna subida real a R2.

Completar las pruebas de aceptación de README.md con teléfono y backend desplegado: autorización Android, JPEG legibles de Clash Royale, timestamps aproximadamente cada 1000 ms, parada/revocación, rotación, desconexión/reconexión y renovación de sesión. La prueba de compilación y el servidor simulado no sustituyen esas comprobaciones.

Para la prueba inmediata en teléfono: Probar captura sin conexión → autorizar Clash Royale → jugar 10–15 s → Detener → Ver capturas locales. No se confirmó aún una captura real del juego ni su legibilidad: requiere la autorización y la acción del usuario en el teléfono. El backend no interviene en esta prueba.
