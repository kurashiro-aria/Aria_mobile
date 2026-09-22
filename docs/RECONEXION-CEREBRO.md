# Reconexión del cerebro local

Alpha 0.2.8 prepara la reconexión del GGUF guardado en `filesDir/models` cuando la app vuelve a abrirse después de que Android cierre su proceso. `last_model` se guarda de forma síncrona al importar un modelo para no perder la selección si se cierra justo después.

Si el motor queda en `Error` después de haber inicializado la biblioteca nativa, ARIA limpia los recursos y vuelve a cargar el modelo y la personalidad. El botón cambia a `RECONECTAR CEREBRO` cuando hay un modelo local guardado y el motor no está listo; permite reintentar sin abrir el selector ni duplicar el GGUF. Si el modelo no existe o el motor no logra inicializarse, muestra el error y permite seleccionar un archivo nuevo.

Esto no mantiene un servicio de inferencia encendido indefinidamente en segundo plano. Android puede finalizar el proceso: al volver se recrea el motor con el archivo persistente.

El archivo y preferencias se conservan mediante una **actualización válida** de Android, cuya firma debe coincidir con la app instalada. Desinstalar ARIA borra su almacenamiento privado, incluido el GGUF copiado. La revisión de firma documenta las diferencias entre los APK ya publicados; este cambio no reconstruye una clave privada anterior ni recupera datos borrados.

Validación pendiente: compilar con firma verificable; abrir, cargar el GGUF, cerrar completamente la app, abrirla y enviar un mensaje; provocar un fallo recuperable y usar Reconectar. Antes de sugerir desinstalar una versión instalada, confirmar su certificado y disponer de copia del historial y el modelo original.
