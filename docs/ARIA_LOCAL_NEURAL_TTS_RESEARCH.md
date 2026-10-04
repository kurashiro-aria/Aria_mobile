# ARIA local neural TTS research

Estado: prototipo aislado, no conectado a la lectura automática y sin APK en esta etapa.

## Candidatos comparados

| Motor/modelo | Español / voz | Tamaño de modelo | Android ARM64/offline | Expresividad real | Licencia/riesgo | Decisión |
|---|---|---:|---|---|---|---|
| sherpa-onnx + Piper `es_MX-claude-high` | Español México, 1 speaker, 22.05 kHz | 63.1 MB ONNX + JSON | Sí: sherpa publica APKs `arm64-v8a` para modelos Piper | Prosodia VITS aprendida y `speed`; no hay controles neuronales de emoción ni género/edad declarados | Modelo card Apache-2.0; revisar también licencia de runtime/modelo antes de distribuir | **Elegido para el adaptador** |
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

Se añadió `AriaSpeechEngine` y el adaptador `PiperNeuralSpeechEngine`. El
adaptador carga una sola vez, verifica el `.onnx`, `tokens.txt` y el directorio
`espeak-ng-data` del paquete sherpa-onnx, sanitiza
`*acciones*`, admite cancelación y puede recibir un backend fake en tests.

No se añadieron binarios, `.so`, AAR ni modelos al repositorio. En el estado
actual el adaptador informa `UNAVAILABLE` porque faltan el runtime sherpa-onnx
ARM64 y el modelo revisado. La selección cae explícitamente a Android TTS; no
se usa Cloud Voice, B2 ni otra aplicación.

## Bloqueo antes de una prueba acústica

Piper es una opción práctica para calidad/prosodia local, pero no cumple por
sí sola la exigencia de emoción neuronal controlable. `emotion` y
`expressionStyle` se conservan en el contrato para una futura capa compatible,
pero el backend Piper no los interpreta como emociones. No se debe afirmar que
los botones de emoción producen actuación emocional hasta medirlo en el
dispositivo.

Para continuar se necesita una decisión explícita sobre:

1. incorporar el runtime sherpa-onnx ARM64 revisado;
2. obtener y validar `es_MX-claude-high.onnx` + su JSON (63.1 MB, sin subirlo
   al repositorio si no se aprueba el tamaño/licencia);
3. implementar la unión JNI/AudioTrack y medir carga, síntesis y RAM en el
   Xiaomi 17T;
4. aceptar que Piper no ofrece emociones neuronales nativas, o seleccionar
   posteriormente otro modelo entrenado para expresividad.
