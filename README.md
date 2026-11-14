# Captura Clash

App Android en Kotlin para capturar Clash Royale mediante MediaProjection y subir JPEG a Cloudflare R2.

Abre **`android/`** en Android Studio. Consulta [la guía de instalación y prueba](android/README.md), [el estado y los pendientes](android/AGENTS.md) y [los resultados de verificación](android/VERIFICACION.md).

La app incluye una prueba local sin conexión: conserva los últimos 30 JPEG para revisar la captura y la cadencia. La subida real requiere configurar y desplegar las funciones de Cloudflare Pages incluidas en `android/server/`.

La rama predeterminada de integración es **`dev`**. Los cambios de esta entrega se separan en las ramas `feat/android-app`, `feat/pages-ingest` y `docs/entrega-y-verificacion`.
