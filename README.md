# ARIA Mobile

**Adaptive Reasoning & Interactive Assistant**

Aplicación Android experimental con personalidad inicial y modelo GGUF local.

La rama `aria-alpha-0.2-local-ai` usa Kotlin y el ejemplo Android de llama.cpp fijado a `26394b4e6749a41c3633db040e0987500a5f7013`, con correcciones locales en `patches/llama-android.patch`.

- Chat y selección/importación de GGUF.
- Inferencia local; sin permiso de Internet en la aplicación.
- Diagnóstico nativo y recuperación tras errores de carga.
- Historial local persistente, recuerdos explícitos editables y retratos de expresiones. La voz sigue pendiente.
- La personalidad de ARIA se carga con el GGUF. Cuando el mensaje continúa el hilo, recibe hasta cuatro turnos recientes del usuario; ante una respuesta elíptica, solo se añade la última pregunta o afirmación necesaria de ARIA, sin copiar toda su respuesta anterior. También puede recibir hasta tres recuerdos explícitos relacionados y fragmentos anteriores del usuario relacionados. Un saludo o tema nuevo no arrastra la conversación anterior.
- Si la pregunta hace una referencia breve al turno anterior, la búsqueda de recuerdos puede usar el tema de los últimos dos mensajes del usuario. Las palabras genéricas no recuperan recuerdos por sí solas. No se crean recuerdos permanentes automáticamente.
- En Qwen3 se pide una respuesta directa en cada turno (`/no_think`). Si una generación solo produjo razonamiento oculto, ARIA intenta una vez más obtener una respuesta visible antes de mostrar el error.
- El motor limpia el contexto de generación entre turnos y conserva la personalidad cargada. El contexto reciente se entrega una vez por respuesta; Qwen3 usa su plantilla con razonamiento desactivado cuando está disponible.
- Para Qwen3 en modo directo se usan temperatura 0,7, top-p 0,8, top-k 20 y min-p 0. El menú Rendimiento muestra la duración de carga y las medidas aproximadas de la última respuesta. Estas medidas se conservan solo durante la sesión.
- El filtro elimina un prefijo `ARIA:` generado por el modelo; si la nueva respuesta repite un tramo largo de la anterior, se reintenta una vez con contexto mínimo antes de guardarla.

Para volver a un estado anterior, consulta [los puntos de restauración](docs/RESTAURACION-ARIA.md). Una actualización firmada conserva el modelo y el historial; desinstalar la app borra sus datos privados.

Consulta [la revisión estática](docs/REVISION-ALPHA-0.2.1.md) y [la personalidad](docs/PERSONALIDAD-ALPHA-0.2.2.md) para ver el desarrollo inicial. Los extractos de historial son limitados por tamaño y coincidencia de palabras; no equivalen a la comprensión ni la capacidad de memoria de un modelo remoto grande.

El workflow `Build ARIA Android` descarga la revisión fijada del motor, prepara sus dependencias y aplica el parche antes de compilar. Puede iniciarse manualmente con `workflow_dispatch` seleccionando la rama Alpha. Un checkout del repositorio por sí solo no incluye el motor descargado por ese workflow.
