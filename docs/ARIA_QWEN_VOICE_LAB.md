# ARIA Qwen Voice Lab — 0.2.47

Laboratorio aislado para medir en Android ARM64 la clonación real de
ARIA-B5C con Qwen3-TTS 0.6B Base. No forma parte de la lectura automática y no
reemplaza Piper.

## Componentes fijados

- Runtime: `Danmoreng/qwen3-tts.cpp` commit
  `4562731dc612cb87b4cd1eedac275a17d1078773`.
- Modelo: `qwen-talker-0.6b-base-Q8_0.gguf`, SHA-256
  `d54dbaf10591421fa764ed630d764efa717ae40cd959bd48c66d4eb1af226426`.
- Códec/tokenizer: `qwen-tokenizer-12hz-Q8_0.gguf`, SHA-256
  `1883beeed99348fc35e23dd225e9082f93f6f8c109330a33d935baa8acdbfd94`.
- Revisión de modelos: `Serveurperso/Qwen3-TTS-GGUF` commit
  `b7ee2e8c7459c3bea99da23e3d178125a7d1713c`.
- Descarga total: 1,283,766,112 bytes. Los pesos no están dentro del APK.
- Perfil: `aria_voice_B5C_MASTER.wav`, SHA-256
  `b2bc79dc5a7504fe531d5fbd729a4b946423585efb1afff2ef9ab5a058f0a6b5`.

El MASTER es PCM24 mono a 24 kHz y permanece bit-identical. ARIA aplica al
runtime fijado un parche mínimo para leer PCM24 directamente; no convierte ni
sobrescribe la referencia y no cambia los pesos descargados.

## Ruta real

`Qwen Voice Lab` descarga al almacenamiento privado, reanuda archivos
parciales, comprueba tamaño y SHA-256 y escribe un marcador solo después de
validar todo. En la primera carga, el encoder del runtime convierte el WAV
maestro y su transcripción exacta en un prompt ICL que contiene la identidad,
los tokens del texto de referencia y sus códigos acústicos. Las cargas
posteriores reutilizan ese prompt.

El puente JNI de ARIA expone
`qwen3_tts_synthesize_with_icl_prompt_streaming`. Los chunks PCM proceden del
decoder mientras la generación sigue activa y se escriben directamente en
`AudioTrack`; no se corta un WAV terminado para aparentar streaming.

El motor vive en el proceso `:qwen_voice`. Un aborto nativo u OOM de ese
proceso no termina el proceso principal de ARIA. La descarga, el perfil y los
pesos sobreviven a actualizaciones compatibles de la aplicación. `release()`
libera modelo y AudioTrack sin borrar los archivos validados.

## Métricas

La pantalla registra descarga, verificación, carga, perfil, primer audio,
generación, total, RAM/PSS aproximada, duración y RTF. La primera y segunda
inferencia se numeran para comparar frío contra caliente. `T_first_audio` se
toma al escribir el primer chunk PCM válido en un `AudioTrack` que ya está en
reproducción.

La validación de escritorio Q8 observó aproximadamente 3.83 GiB de RSS pico;
no es una medición Android. Latencia, RAM y tamaño final del APK requieren la
APK 0.2.46 instalada en el Xiaomi 17T.
