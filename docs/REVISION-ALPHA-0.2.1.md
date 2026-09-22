# Revisión estática de ARIA Alpha 0.2.1

Base: `5a7426eda19969aa8e8259dd3f137d5fc517025b`, rama `aria-alpha-0.2-local-ai`.
Motor fijado: llama.cpp `26394b4e6749a41c3633db040e0987500a5f7013`.

## Conclusión

La causa probable del fallo al cargar el GGUF es el empaquetado de bibliotecas de CPU. El motor usa `ggml_backend_load_all_from_path(nativeLibraryDir)`, que enumera archivos físicos; la app no configuraba extracción de bibliotecas. El ejemplo Android del motor sí declara `extractNativeLibs=true`. ARIA ahora usa el ajuste Gradle `jniLibs.useLegacyPackaging=true` para extraerlas. Es una hipótesis fundamentada en código, no una confirmación obtenida del teléfono.

El código 1 de `load` significa que `llama_model_load_from_file` devolvió null. No identifica por sí solo arquitectura incompatible ni falta de RAM. Ahora se conserva un registro acotado de avisos/errores nativos y se comunica la falta de backend CPU de forma explícita.

## Errores corregidos por inspección

- Recargar después de `ModelReady` esperaba un estado `Initialized` que nadie producía. Ahora se descarga el modelo anterior y se prepara el motor para reintentar.
- Un estado `Error` no liberaba recursos de una carga parcial. La limpieza nativa ahora tolera recursos nulos y reinicia sus punteros; no se intenta reiniciar como válido un motor cuya inicialización falló.
- Se podía sobrescribir el archivo de un modelo todavía mapeado en memoria. Ahora se descarga primero, se importa a un archivo temporal, se comprueba su cabecera y se renombra.
- Carga y generación podían superponerse desde la interfaz. Ahora se bloquean ambos controles durante operaciones.
- Los fallos de inicialización podían dejar la app esperando indefinidamente. Se publica el estado de error y se limita la espera de inicialización a 30 segundos (no la carga del modelo).
- Un error nativo al procesar el prompt finalizaba el flujo silenciosamente y dejaba el motor ocupado. Ahora se propaga como error.
- La generación contaba dos veces los tokens del usuario para calcular cuándo detenerse. Ahora utiliza un presupuesto de tokens independiente de la posición del contexto.
- Al desplazar el contexto se seguían usando posiciones antiguas para los lotes siguientes. Ahora se actualiza la posición después de cada lote y desplazamiento.
- Los mensajes demasiado largos se truncaban manteniendo la longitud original en el contador. Ahora se rechazan explícitamente.
- Un fallo al generar un token se confundía con fin normal. Ahora se comunica como excepción.
- «GGUF válido» solo comprobaba la firma. Ahora se revisan cabecera mínima, versión y conteos básicos, y se aclara que eso no valida tensores ni integridad completa.

## Forma de aplicar los cambios del motor

`patches/llama-android.patch` se aplica a la revisión fijada del motor. El workflow ejecuta `git apply --check` antes de aplicarlo. Sustituye el reemplazo Python incrustado en YAML. No se modifican archivos en el repositorio original de llama.cpp.

La versión de ARIA pasa a `0.2.1-alpha` / código 3 para distinguir el próximo APK.

## Lo que aún no está implementado o verificado

- `AriaMemory` es una lista en RAM y no está conectada al chat. No hay memoria persistente ni sincronización con PC.
- La personalidad está en el prompt del sistema; voz y avatar no están integrados.
- No se encontró el texto de error «Prompt is too long, max prompt size is 256 tokens» en esta revisión del código Android del motor. `predictLength` controla generación y no demuestra que se haya corregido un límite de entrada.
- No se ha compilado ni ejecutado ARIA en esta revisión. No se probó el GGUF del usuario ni se midió la RAM del teléfono.
- El contexto de 8192 tokens se conserva. No se atribuye el fallo de carga a ese tamaño: el código 1 mostrado se origina antes de preparar el contexto.
- Las excepciones C++ no controladas en todas las rutas de plantillas/asignación y el historial de chat sin límite merecen una revisión posterior si se observan cierres o consumo creciente. Esta revisión no garantiza ausencia de otros errores.

## Comprobaciones realizadas

Lectura cruzada de Kotlin, Gradle, workflow y C++ de la revisión fijada; revisión del diff; comprobación de aplicación del parche sobre los archivos originales; parseo de YAML/XML. Sin build, emulador ni pruebas de inferencia.

## Próxima comprobación en el teléfono

Compilar manualmente la rama cuando se decida continuar, instalar Alpha 0.2.1 y comprobar: inicio del motor, carga GGUF, mensaje corto, segundo mensaje y recarga del modelo. Si falla, conservar el detalle nativo completo del chat. Una compilación correcta no equivale a una inferencia correcta.
