# ARIA Mobile

**Adaptive Reasoning & Interactive Assistant**

Aplicación Android experimental con personalidad inicial y modelo GGUF local.

La rama `aria-alpha-0.2-local-ai` usa Kotlin y el ejemplo Android de llama.cpp fijado a `26394b4e6749a41c3633db040e0987500a5f7013`, con correcciones locales en `patches/llama-android.patch`.

- Chat y selección/importación de GGUF.
- Inferencia local; sin permiso de Internet en la aplicación.
- Diagnóstico nativo y recuperación tras errores de carga.
- Historial local persistente, recuerdos explícitos editables y retratos de expresiones. La voz sigue pendiente.
- Al cargar el GGUF, ARIA retoma hasta diez mensajes recientes y hasta dos intercambios anteriores relacionados. El contexto anterior son extractos literales, no una síntesis generada.
- En cada mensaje se inyectan hasta tres recuerdos explícitos relacionados. Si la pregunta es elíptica, se usa el tema de los últimos dos mensajes del usuario para encontrarlos; no se crean recuerdos permanentes automáticamente.
- En Qwen3 se pide una respuesta directa en cada turno (`/no_think`). Si una generación solo produjo razonamiento oculto, ARIA intenta una vez más obtener una respuesta visible antes de mostrar el error.

Consulta [la revisión estática](docs/REVISION-ALPHA-0.2.1.md) y [la personalidad](docs/PERSONALIDAD-ALPHA-0.2.2.md) para ver el desarrollo inicial. Los extractos de historial son limitados por tamaño y coincidencia de palabras; no equivalen a la comprensión ni la capacidad de memoria de un modelo remoto grande.

El workflow `Build ARIA Android` descarga la revisión fijada del motor, prepara sus dependencias y aplica el parche antes de compilar. Puede iniciarse manualmente con `workflow_dispatch` seleccionando la rama Alpha. Un checkout del repositorio por sí solo no incluye el motor descargado por ese workflow.
