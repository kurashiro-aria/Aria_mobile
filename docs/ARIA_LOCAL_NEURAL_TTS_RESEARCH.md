# ARIA local neural TTS research

Estado: prototipo acústico aislado, no conectado a la lectura automática y sin APK en esta etapa.

## Candidatos comparados

| Motor/modelo | Español / voz | Tamaño de modelo | Android ARM64/offline | Expresividad real | Licencia/riesgo | Decisión |
|---|---|---:|---|---|---|---|
| sherpa-onnx + Piper `es_MX-claude-high` | Español México, 1 speaker, 22.05 kHz | 62,949,322 bytes ONNX; 67,207,890 bytes `.tar.bz2` | Sí: runtime oficial Android/JitPack y APKs `arm64-v8a` | Prosodia VITS aprendida y `speed`; no hay controles neuronales de emoción ni género/edad declarados | Modelo card Apache-2.0; runtime Apache-2.0 | **Elegido para la prueba acústica** |
| sherpa-onnx + Piper `es_AR-daniela-high` | Español Argentina, 1 speaker, 22.05 kHz | 114 MB ONNX + JSON | Sí, mismo runtime | Igual limitación de emoción; el nombre no es metadato de género | Dataset del model card CC BY-SA 4.0; requiere atribución y revisión antes de distribución | No elegido por tamaño/licencia |
| Kokoro-ONNX `kokoro-v1.0` + voces | Multilingüe, incluye ruta de fonemización española | ~326 MB FP32; ~80 MB cuantizado según proyecto | ONNX sí, pero no hay una integración Android oficial equivalente en este repo | Varias voces; no se demuestra control nativo de emoción para este prototipo | Wrapper MIT/modelo Apache-2.0; tamaño y G2P nativo elevan riesgo | No elegido para V1 |

Fuentes oficiales consultadas:

- Sherpa-onnx Android/TTS: <https://k2-fsa.github.io/sherpa/onnx/tts/apk.html>
- Sherpa-onnx modelo Piper español: <https://k2-fsa.github.io/sherpa/onnx/tts/all/Spanish/vits-piper-es_MX-claude-high.html>
- Piper model card: <https://huggingface.co/rhasspy/piper-voices/blob/main/es/es_MX/claude/high/MODEL_CARD>
- Piper config/model: <https://huggingface.co/rhasspy/piper-voices/blob/main/es/es_MX/claude/high/es_MX-claude-high.onnx.json>
- Kokoro ONNX README: <https://github.com/thewh1teagle/kokoro-onnx/blob/main/README.md>
- Kokoro español: <https://github.com/thewh1teagle/kokoro-onnx/blob/main/examples/spanish.py>

## Implementación

sherpa-onnx v1.13.8 AAR incluye todas las ABI (~127.9 MB comprimidos en el
artefacto); `arm64-v8a` aporta aproximadamente 31.9 MB de bibliotecas nativas
antes de la compresión final del APK. Gradle filtra el APK de ARIA a
`arm64-v8a`, que es la ABI objetivo del Xiaomi 17T.

`AriaSpeechEngine` conserva el límite desacoplado y
`PiperNeuralSpeechEngine` usa ahora `SherpaPiperBackend` cuando se crea con
`forAndroid()`. El backend llama a la API oficial `OfflineTts` de
sherpa-onnx 1.13.8, configura el modelo VITS/Piper con `model.onnx`,
`tokens.txt` y `espeak-ng-data`, y convierte el `FloatArray` devuelto a PCM16
mono para `AudioTrack`. El runtime se obtiene como el artefacto Android
oficial `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8`; el APK se limita a
`arm64-v8a` en el dispositivo objetivo.

`PiperModelStore` descarga únicamente el archivo oficial fijado, verifica su
SHA-256 (`ec33fb689c248fe64810aab564cba97babf0f506672cfd404928d46e751a4721`), extrae con Apache Commons Compress en un directorio
temporal, valida el hash del ONNX (`6b7a54f5fcc8c9ce3788cd308a26cfa429ad025cdff4a7a6c34d025d0d229341`) y realiza la instalación de
   forma atómica. No se suben binarios ni el modelo al repositorio, y no se
   descarga de nuevo si la instalación validada ya existe.

El laboratorio de Ajustes solo muestra `NO INSTALADO`, `DESCARGANDO`,
`CARGANDO`, `LISTO` o `ERROR`, ofrece **Preparar voz neuronal** y después
**Probar voz neuronal** con la frase neutral fija. La ruta automática sigue
usando Android TTS `es-us-x-esc-local`; Piper no afecta Cloud Voice, B2 ni el
chat.

## Limitaciones de la prueba acústica

Piper es una opción práctica para calidad/prosodia local, pero no cumple por
sí sola la exigencia de emoción neuronal controlable. `emotion` y
`expressionStyle` se conservan en el contrato para una futura capa compatible,
pero el backend Piper no los interpreta como emociones. No se debe afirmar que
los botones de emoción producen actuación emocional hasta medirlo en el
dispositivo.

La prueba debe ejecutarse en Xiaomi 17T para obtener `T_download`,
`T_model_load`, `T_first_audio`, `T_synthesis` y `T_total`. Piper no ofrece
emociones neuronales nativas: `emotion`/`expressionStyle` se conservan en el
contrato, pero esta etapa solo sintetiza neutral y no pretende demostrar
actuación emocional.
