# ARIA Mobile

**Adaptive Reasoning & Interactive Assistant**

Aplicación Android experimental con personalidad inicial y modelo GGUF local.

La rama `aria-alpha-0.2-local-ai` usa Kotlin y el ejemplo Android de llama.cpp fijado a `26394b4e6749a41c3633db040e0987500a5f7013`, con correcciones locales en `patches/llama-android.patch`.

- Chat y selección/importación de GGUF.
- Inferencia local; sin permiso de Internet en la aplicación.
- Al reconectar un GGUF guardado, ARIA aparece dormida a pantalla completa antes del chat. La barra sigue el avance de carga que comunica llama.cpp; durante la preparación del contexto y la personalidad, donde no hay una medida de avance, muestra una animación de espera. Al terminar aparece «Despertar» y Kura abre el chat al tocarlo. Se muestra el tiempo transcurrido sin porcentaje ni estimación ficticia. Si no hay cerebro guardado o falla la carga, se muestra el chat para elegir o reconectar uno.
- Diagnóstico nativo y recuperación tras errores de carga.
- Historial local persistente, recuerdos explícitos que se pueden guardar, corregir u olvidar, y retratos de expresiones. La voz sigue pendiente.
- La personalidad de ARIA se carga con el GGUF. Cuando el mensaje continúa el hilo, se consideran hasta seis mensajes recientes; el modelo recibe las palabras pertinentes de Kura y, si la respuesta es elíptica, solo la pregunta o afirmación necesaria de ARIA. También puede recibir hasta cinco recuerdos explícitos relacionados, fragmentos anteriores del usuario y un tema resumido de sus propias palabras. Un saludo o tema nuevo no arrastra la conversación anterior.
- El estado de conversación guarda el tema y una pregunta pendiente, además de hasta seis temas anteriores. Es local, limitado y se actualiza después de una respuesta; no convierte cada frase en un recuerdo permanente. Para corregir un recuerdo: `ARIA, corrige el recuerdo #7: nuevo texto`.
- El tono del mensaje actual orienta una instrucción breve por turno y la expresión del retrato: alegría, humor, cansancio, frustración, vulnerabilidad o urgencia. Una respuesta corta puede seguir el tono previo; un tema nuevo lo reinicia. Esta señal no se guarda como diagnóstico ni retrasa la carga del modelo.
- El retrato reacciona al mensaje de Kura y vuelve a ajustarse cuando ARIA comienza a escribir y al terminar. En una conversación triste puede pasar de preocupación a ternura; una broma permite que ARIA muestre sorpresa, diversión o molestia teatral según sus palabras. Los cambios tienen una transición breve y no alteran el tamaño de la imagen.
- Desde 0.2.31 el estado social se guarda junto con el tema: puede sostener una broma, curiosidad o concentración durante respuestas breves y recibe señales de las palabras de ARIA. Caduca tras una pausa de treinta minutos o varios turnos neutros; un asunto urgente siempre tiene prioridad. El estado orienta el texto y el retrato sin guardar una etiqueta emocional permanente sobre Kura.
- La iniciativa se puede activar en el menú. Solo propone retomar una pregunta pendiente al volver después de dos horas, entre las 8:00 y las 22:59, con seis horas de espera entre propuestas. Está desactivada por defecto y no carga el modelo ni genera texto adicional.
- Si la pregunta hace una referencia breve al turno anterior, la búsqueda de recuerdos puede usar el tema de los últimos dos mensajes del usuario. Las palabras genéricas no recuperan recuerdos por sí solas. No se crean recuerdos permanentes automáticamente.
- En Qwen3 se pide una respuesta directa en cada turno (`/no_think`). Si una generación solo produjo razonamiento oculto, ARIA intenta una vez más obtener una respuesta visible antes de mostrar el error.
- El motor limpia el contexto de generación entre turnos y conserva la personalidad cargada. El contexto reciente se entrega una vez por respuesta; Qwen3 usa su plantilla con razonamiento desactivado cuando está disponible.
- Para Qwen3 en modo directo se usan temperatura 0,7, top-p 0,8, top-k 20 y min-p 0. El menú Rendimiento muestra la duración de carga, el modelo, el tamaño del último contexto, los recuerdos recuperados y los contadores de repeticiones y fallos. Estas medidas se conservan solo durante la sesión.
- El filtro elimina un prefijo `ARIA:` generado por el modelo; si la nueva respuesta repite un tramo largo de la anterior, se reintenta una vez con contexto mínimo antes de guardarla.

Para volver a un estado anterior, consulta [los puntos de restauración](docs/RESTAURACION-ARIA.md). Una actualización firmada conserva el modelo y el historial; desinstalar la app borra sus datos privados.

Consulta [la revisión estática](docs/REVISION-ALPHA-0.2.1.md) y [la personalidad](docs/PERSONALIDAD-ALPHA-0.2.2.md) para ver el desarrollo inicial. Los extractos de historial son limitados por tamaño y coincidencia de palabras; no equivalen a la comprensión ni la capacidad de memoria de un modelo remoto grande.

El workflow `Build ARIA Android` descarga la revisión fijada del motor, prepara sus dependencias y aplica el parche antes de compilar. Puede iniciarse manualmente con `workflow_dispatch` seleccionando la rama Alpha. Un checkout del repositorio por sí solo no incluye el motor descargado por ese workflow.
