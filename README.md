# ARIA Mobile

**Adaptive Reasoning & Interactive Assistant**

Aplicación Android experimental con personalidad inicial y modelo GGUF local.

La rama `aria-alpha-0.2-local-ai` prepara Alpha **0.2.2**. Usa Kotlin y el ejemplo Android de llama.cpp fijado a `26394b4e6749a41c3633db040e0987500a5f7013`, con correcciones locales en `patches/llama-android.patch`.

- Chat y selección/importación de GGUF.
- Inferencia local; sin permiso de Internet en la aplicación.
- Diagnóstico nativo y recuperación tras errores de carga.
- Memoria persistente, voz y avatar todavía pendientes. `AriaMemory` solo conserva datos en RAM y no está conectado al chat.

Consulta [la revisión estática](docs/REVISION-ALPHA-0.2.1.md) para ver correcciones, hipótesis y límites. La Alpha 0.2.1 compiló y Kura confirmó carga y respuesta en su teléfono. La Alpha 0.2.2 añade [personalidad y filtrado de respuestas](docs/PERSONALIDAD-ALPHA-0.2.2.md), pendientes de validación en el dispositivo.

El workflow `Build ARIA Android` descarga la revisión fijada del motor, prepara sus dependencias y aplica el parche antes de compilar. Puede iniciarse manualmente con `workflow_dispatch` seleccionando la rama Alpha. Un checkout del repositorio por sí solo no incluye el motor descargado por ese workflow.
