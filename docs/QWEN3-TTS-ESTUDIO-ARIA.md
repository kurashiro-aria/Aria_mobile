# ARIA: prueba técnica de voz local (28-09-2026)

**Alcance:** investigación y herramienta de laboratorio aislada. No hay TTS en la APK, ni cambio de firma, `applicationId`, GGUF, memoria, emociones o conversación. Respaldo comprobado: `backup/aria-0.2.36.9-before-local-voice` en `1e28b65bbf87ca5d151eeb238381424ee5a111df`; no se escribe en esa rama.

## Evidencia y límites

| Cuestión | Resultado | Confianza |
| --- | --- | --- |
| Español | Los cinco checkpoints oficiales 0.6B/1.7B listan español entre diez idiomas. Hay que escuchar acento, prosodia y pronunciación real en frases de Kura. | Oficial para idioma; calidad en ARIA pendiente |
| Licencia | Qwen3-TTS: Apache-2.0 en el repositorio/modelos. Comprobar licencias de runtime y códec al distribuir. | Oficial |
| Tamaño | 0.6B Base: ~1,83 GB de pesos y ~682 MB de códec; repositorio ~2,52 GB. 1.7B VoiceDesign: ~4,52 GB de repositorio. No equivalen a RAM residente. | Árbol de archivos oficial |
| RAM | Pesos 0.6B FP16 ~1,2 GB como mínimo teórico; checkpoint publicado ~1,8 GB + códec ~0,68 GB, activaciones, KV, buffers y Android. Presupuestar **3–5 GB adicionales** como hipótesis de trabajo sin cuantizar, y medir PSS/pico junto al GGUF. Q4 puede bajar pesos a ~0,4–0,8 GB, sin cuantizar necesariamente códec/activaciones; **1,5–3 GB adicionales** es hipótesis, no benchmark. | Estimación, no medición |
| Cuantización | `llama.cpp` y ports comunitarios muestran GGUF Q4/INT4 o LiteRT para partes del sistema. Deben incluir decodificación del audio y conservar calidad del español; no es checkpoint Android oficial. | Comunidad/posibilidad técnica |
| Android ARM64 | La biblioteca oficial Python/PyTorch no documenta una integración Android de producción. Existen demostraciones comunitarias; requieren empaquetado y profiling propios. | Oficial: sin soporte publicado; comunidad: prototipos |
| Offline | Sí, técnicamente, con todos los pesos/códec/runtime descargados localmente, licencias compatibles y sin llamadas de red. | Posibilidad técnica |
| Latencia y streaming | Los modelos oficiales anuncian streaming y primera emisión hasta 97 ms en condiciones de demostración; **no** es una cifra Xiaomi ni incluye carga, el GGUF, ni reproducción Android. Medir carga, primer audio y factor tiempo real en el dispositivo, con ambos modelos residentes. | Oficial para arquitectura; latencia móvil desconocida |
| Voz original | **VoiceDesign es 1.7B, no 0.6B**. 0.6B CustomVoice ofrece nueve presets; 0.6B Base clona una referencia autorizada de pocos segundos. Propuesta: diseñar voz sintética original en 1.7B, guardar referencia autorizada y probar clonarla con 0.6B Base. Identidad/acento requieren escucha. | Oficial para capacidades; transferencia por evaluar |
| Interpretación | 1.7B VoiceDesign/CustomVoice indican control por instrucciones. **0.6B Base/CustomVoice no lo documentan**. Variar el texto o hablar más bajo no prueba control emocional ni susurro auténtico. | Oficial |

Fuente principal: [Qwen3-TTS oficial](https://github.com/QwenLM/Qwen3-TTS), [0.6B Base](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base/tree/main), [1.7B VoiceDesign](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign/tree/main). Para Android, [llama.cpp TTS](https://github.com/ggml-org/llama.cpp/blob/master/docs/tts.md) y [demo comunitaria Qwen3 Android](https://github.com/Danmoreng/qwen3-tts-android) son pistas, no certificación de esta APK. La versión de llama.cpp usada por ARIA está fijada a otra revisión: no asumir compatibilidad directa.

Xiaomi 11T Pro: Snapdragon 888, variantes 8/12 GB RAM según [Xiaomi](https://www.mi.com/mx/product/xiaomi-11t-pro/specs/). La memoria libre es menor; GGUF y TTS simultáneos pueden causar expulsión del proceso, calor o latencia alta, especialmente con 8 GB. Tomar medidas con el teléfono real antes de elegir motor.

## Voz propia e interpretación

Bella (`af_bella`) de Kokoro es una **referencia estética de timbre**, una voz estadounidense de inglés; no es un objetivo de copia ni una voz española nativa. Diseñar una voz nueva adulta, suave, cálida, cercana y expresiva en español mediante 1.7B VoiceDesign o una grabación propia con consentimiento y licencia clara. Conservar la misma semilla/referencia y evaluar estabilidad de identidad entre cinco actuaciones. No utilizar grabaciones de Bella ni de una persona ajena para clonación.

El susurro solo se acepta si una escucha y análisis acústico muestran fonación susurrada real, no reducción del volumen. **No hay muestra susurrada ni soporte 0.6B verificado**. Alegría, tristeza, cariño y picardía se pueden solicitar por instrucciones a 1.7B; el resultado efectivo debe escucharse. En 0.6B la identidad se puede fijar, pero el control independiente de emoción queda sin demostrar.

## AriaVoiceDirector: contrato futuro, sin código de producción

`GGUF → Conversation Brain → texto final de ARIA → AriaEmotion + ExpressionState + intención vocal → AriaVoiceDirector → motor TTS → audio`

`AriaVoiceDirector` recibe estado ya resuelto y el ID del turno. Devuelve `VoiceDirection(voiceId, language=es, styleHint?, paceHint?, energyHint?, deliveryHint?, turnId)` mediante un adaptador por motor. Nunca lee texto para *decidir* la emoción ni modifica la respuesta del GGUF. Si un motor ignora un parámetro, informa capacidad no soportada; no fabrica otro sentimiento. La voz/portrait se sincronizan por turno y las respuestas tardías se cancelan. No persistir automáticamente estilos transitorios como preferencias permanentes.

| Estado existente | Intención vocal objetivo | Alcance demostrado |
| --- | --- | --- |
| NEUTRAL/THINKING/SERIOUS | natural, pausado o concentrado | Como instrucción 1.7B; 0.6B no garantizado |
| HAPPY/AMUSED/EXCITED | luminosa, con energía moderada | Como instrucción 1.7B; escuchar |
| SAD/TIRED | menor energía, ritmo reposado | Como instrucción 1.7B; escuchar |
| ANGRY/ANNOYED | firme, sin gritos artificiales | Como instrucción 1.7B; escuchar |
| AFFECTIONATE/EMBARRASSED | cálida, cercana o tímida | Como instrucción 1.7B; escuchar |
| SURPRISED/CONFUSED | mayor variación o duda | Como instrucción 1.7B; escuchar |
| PLAYFUL + FLIRTY/TEASING | juguetona, picardía suave | El estilo no reemplaza la emoción; escuchar |
| WHISPER | susurro fonético real | No es emoción existente de ARIA; capacidad no verificada |

## Comparación para decidir

| Motor | Ventaja | Límite para ARIA Mobile |
| --- | --- | --- |
| Qwen3-TTS 0.6B | Español, clonación autorizada o presets, streaming; ecosistema comunitario ARM | Peso/códec y RAM junto al GGUF, Android no oficial, instrucciones expresivas no documentadas |
| Kokoro 82M/Bella | Muy ligero y Apache-2.0; buena referencia de calidez | Bella es inglés de EE. UU.; existen otras voces españolas, pero no son Bella; control expresivo/susurro limitado |
| Chatterbox Multilingual 500M | Español y expresividad, voz de referencia; MIT | Entorno PyTorch de escritorio, sin ruta Android oficial comprobada; medir RAM/latencia |
| Fish Speech S2-Pro | Expresividad/instrucciones y streaming prometedores para ARIA PC | Pesos y códec ~11 GB; licencia Fish Audio Research (ver restricciones antes de distribución); inviable como primera ruta móvil |

Fuentes: [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M), [Chatterbox](https://github.com/resemble-ai/chatterbox), [Fish Speech](https://github.com/fishaudio/fish-speech), [Xiaomi](https://www.mi.com/mx/product/xiaomi-11t-pro/specs/).

## Prueba reproducible, fuera de la APK

Herramienta: `scripts/voice_qwen3_probe.py`. Usa exactamente `Hola, Kura. Estoy aquí contigo. Cuéntame cómo te fue hoy.` en todos los WAV. En PC aislado con Python 3.12, instalar `qwen-tts`, `torch`, `soundfile` y descargar el checkpoint oficial *antes* de la prueba; ejecutar con pesos locales y salida fuera del repositorio:

```bash
python scripts/voice_qwen3_probe.py --mode custom-06 --model /modelos/Qwen3-TTS-12Hz-0.6B-CustomVoice --output /tmp/aria-qwen-06
python scripts/voice_qwen3_probe.py --mode base-06 --model /modelos/Qwen3-TTS-12Hz-0.6B-Base --reference /ruta/aria-original-autorizada.wav --reference-text 'transcripción literal de esa grabación' --output /tmp/aria-qwen-clon
python scripts/voice_qwen3_probe.py --mode custom-17 --model /modelos/Qwen3-TTS-12Hz-1.7B-CustomVoice --output /tmp/aria-qwen-estilos
```

0.6B entrega **neutral solamente** por falta de control expresivo oficial. 1.7B genera neutral/feliz/triste/cariñosa/juguetona con el mismo preset `Serena`; este preset no es todavía la voz propia de ARIA. La herramienta guarda WAV y manifiesto con latencia de carga y síntesis total, no latencia de primer fragmento. No genera susurro. Escuchar en orden aleatorio, puntuar naturalidad en español, identidad consistente, emoción percibida y artefactos. Después repetir en Xiaomi midiendo PSS, pico, tiempo hasta primer audio, tiempo total y temperatura con GGUF cargado. No confundir un resultado PC con rendimiento ARM64.

**Estado de ejecución aquí:** preflight del script y análisis estático, sin audio generado: el entorno no tiene `torch`, `qwen-tts`, `soundfile` ni checkpoints de varios GB disponibles. No se descargaron pesos ni se añadió dependencia Android. Siguiente experimento: escuchar 1.7B CustomVoice como control de expresividad y 0.6B Base con referencia sintética original autorizada; si la voz 0.6B no conserva matices, comparar Kokoro español o ruta más potente para PC antes de integrar.
