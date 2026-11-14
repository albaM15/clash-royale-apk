# App Android para capturar Clash Royale y guardar imágenes en R2

## Objetivo de esta entrega

Implementa una app Android en Kotlin que, durante una sesión autorizada por el usuario, capture Clash Royale **una vez por segundo** y suba cada imagen JPEG al bucket existente **`cr-videos`** de Cloudflare R2. Usa el prefijo **`ingest/`**, sin barra inicial en la clave. La entrega termina cuando se verifica la subida desde un teléfono físico. La integración con Kinesis, SageMaker y el modelo queda para una etapa posterior.

## Repositorio y estado actual

- La app Android vive en el repositorio independiente [albaM15/clash-royale-apk](https://github.com/albaM15/clash-royale-apk), cuya carpeta local se llama `clash-royale-apk`. Usa su raíz para `AGENTS.md`, este documento y el proyecto Gradle si todavía no existe. Si ya hay un proyecto Gradle, conserva su estructura.
- El [anotador de videos](https://cr-dataset.eddux.dev/) está desplegado en el proyecto Cloudflare Pages `cr-annotator`. Su código vive en [devEddu17x/deep-learning-clash-royale](https://github.com/devEddu17x/deep-learning-clash-royale). Allí, `annotator/public/` contiene la interfaz y `annotator/functions/api/` contiene las rutas. `/api/add` registra enlaces de YouTube en D1.
- Una subida de prueba desde Kaggle confirmó que el bucket `cr-videos` recibe objetos bajo `ingest/`. Queda por configurar y probar la subida mediante Pages desde el teléfono.

## App Android

- Usa **MediaProjection**. Incluye controles de inicio y fin, solicita autorización de Android por sesión y mantén la captura activa mediante un servicio en primer plano mientras el usuario juega. Si el selector del sistema permite compartir una sola app, indica al usuario que seleccione Clash Royale. Libera los recursos de captura al detenerse la sesión o revocarse el permiso.
- Obtén un JPEG cada **1000 ms**. Mantén la proporción de la imagen. Ejecuta la subida fuera del hilo principal y evita que una subida lenta bloquee la toma de la siguiente captura. Limita la cola y muestra los fallos o descartes.
- Genera un identificador único al iniciar cada sesión y registra el timestamp en milisegundos al tomar la captura. Muestra el estado de la sesión, el número de subidas confirmadas y los errores de envío.
- Deja configurable la URL. Para el prototipo, permite que el usuario de prueba configure el token. Mantén las claves de R2 y los valores reales de los tokens fuera del código fuente y de los registros.

## Endpoint de subida

Implementa la **Pages Function** en `annotator/functions/api/ingest.js` del repositorio del anotador, `devEddu17x/deep-learning-clash-royale`. La URL prevista tras desplegarla es `https://cr-dataset.eddux.dev/api/ingest`. Usa el mismo proyecto de Pages y conserva el funcionamiento de las rutas del anotador y su base D1.

| Elemento | Contrato propuesto para implementar app y servidor |
| --- | --- |
| Método y ruta | `POST /api/ingest` |
| Autenticación | `Authorization: Bearer <token>`. Compara con el secreto de servidor `INGEST_TOKEN`, dedicado a la subida. |
| Cuerpo | Bytes del JPEG con `Content-Type: image/jpeg`. |
| Identificación | Encabezados `X-Session-Id` y `X-Capture-Timestamp`. Valida un identificador alfanumérico con guiones o guiones bajos y un timestamp entero en milisegundos. |
| Destino | La función construye la clave `ingest/<sesión>/<timestamp>.jpg` a partir de campos validados. El cliente no proporciona una clave completa ni elige el bucket. |
| Respuesta | `201` con JSON que incluya `key` cuando R2 confirme la escritura. Devuelve un error claro para token inválido, datos inválidos o fallo de almacenamiento. |

Usa un binding de R2, por ejemplo `CR_VIDEOS`, vinculado al bucket **existente** `cr-videos`. Valida que el cuerpo sea JPEG, aplica un límite de tamaño y guarda el tipo de contenido en R2. Limita esta ruta a subidas bajo `ingest/`. Mantén `ADMIN_TOKEN` para su uso en el anotador. La app solo requiere la URL y el token de subida.

## Configuración y prueba de aceptación

1. Documenta cómo vincular `CR_VIDEOS` a `cr-videos`, configurar `INGEST_TOKEN` como secreto en el proyecto Pages y desplegar la ruta. [Bindings de Cloudflare Pages](https://developers.cloudflare.com/pages/functions/bindings/#r2-buckets)
2. Prueba con un JPEG y el token de prueba. Verifica que una petición sin token o con token incorrecto se rechace y no cree un objeto.
3. Instala la app en un teléfono físico. Inicia la sesión, autoriza la captura y juega unos segundos. Detén la sesión y verifica que cesen las capturas.
4. Abre **R2 → cr-videos → Objects → ingest/**. Comprueba que los JPG de la misma sesión se pueden abrir y muestran la partida, con timestamps separados aproximadamente un segundo. El prefijo aparecerá con la primera subida.

Entrega la app Android en `clash-royale-apk`, con los pasos para compilar, configurar y probar. Si solo tienes acceso al repo Android, deja la URL configurable y entrega la función como parche para el repo del anotador o usa el endpoint si el equipo ya lo desplegó. Indica qué pruebas quedaron pendientes por acceso al anotador, a Pages o a un teléfono.
