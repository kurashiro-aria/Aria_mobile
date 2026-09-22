# Reconexión del cerebro local

Alpha 0.2.8 prepara la reconexión del GGUF guardado en `filesDir/models` cuando la app vuelve a abrirse después de que Android cierre su proceso. `last_model` se guarda de forma síncrona al importar un modelo para no perder la selección si se cierra justo después.

Si el motor queda en `Error` después de haber inicializado la biblioteca nativa, ARIA limpia los recursos y vuelve a cargar el modelo y la personalidad. El botón cambia a `RECONECTAR CEREBRO` cuando hay un modelo local guardado y el motor no está listo; permite reintentar sin abrir el selector ni duplicar el GGUF. Si el modelo no existe o el motor no logra inicializarse, muestra el error y permite seleccionar un archivo nuevo.

Esto no mantiene un servicio de inferencia encendido indefinidamente en segundo plano. Android puede finalizar el proceso: al volver se recrea el motor con el archivo persistente.

El archivo y preferencias se conservan mediante una **actualización válida** de Android, cuya firma debe coincidir con la app instalada. Desinstalar ARIA borra su almacenamiento privado, incluido el GGUF copiado. La revisión de firma documenta las diferencias entre los APK ya publicados; este cambio no reconstruye una clave privada anterior ni recupera datos borrados.

Validación pendiente: compilar con firma verificable; abrir, cargar el GGUF, cerrar completamente la app, abrirla y enviar un mensaje; provocar un fallo recuperable y usar Reconectar. Antes de sugerir desinstalar una versión instalada, confirmar su certificado y disponer de copia del historial y el modelo original.

## Identificación visible

La etiqueta instalada es `ARIA Mobile 0.2.8-alpha`. El subtítulo del chat lee `BuildConfig.VERSION_NAME`. El workflow publica `ARIA-Mobile-0.2.8-alpha.apk` dentro del artefacto de nombre correspondiente, derivando el número de versión de los metadatos de compilación. Próximas versiones cambian la constante `ariaVersionName` del proyecto y ambos nombres se actualizan sin modificar la identidad interna `com.kura.aria`.

El APK 0.2.5 exacto aportado por Kura tiene certificado SHA-256 `E1AA0AE289ED945D8211B3EE991BF0879CDAEA6CAE699430CFDDA7BB3324D52D`. No coincide con las dos compilaciones 0.2.5 recuperadas de GitHub (`B776...E26B` y `ADA3...EADD`) ni con la nueva firma prevista (`24A8...DE17`). Por tanto, sin la clave privada correspondiente a `E1AA...`, no puede actualizarse esa instalación conservando datos privados. La compilación 0.2.8 anterior falló correctamente al final: el verificador buscaba el formato `Signer #1`, mientras que `apksigner` imprimió `V2 Signer`; el digest real del APK sí coincidía con la clave esperada 24A8. El patrón de verificación reconoce ambos formatos.
