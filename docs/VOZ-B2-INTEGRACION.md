# Voz B2 y primera salida local

Kura eligió la toma B2 mexicana, cálida, expresiva y ligeramente más juvenil. El archivo B2 es una **muestra**, no un modelo TTS: reproducirlo no permite sintetizar frases nuevas con esa misma identidad vocal. No se incluye la muestra ni pesos de Chatterbox/Qwen en la APK.

La primera salida de voz usa `LocalSpeechOutput`: se activa desde **Menú → Voz**, busca una voz española instalada que declare funcionamiento sin red, da prioridad a es-MX y lee exclusivamente la respuesta final de ARIA. Está apagada inicialmente. El menú también permite repetir la última respuesta nueva o detenerla. La reproducción se detiene al iniciar un nuevo turno o al salir de la pantalla; no lee historial restaurado ni texto provisional. Si el teléfono no tiene una voz local española, el chat de texto sigue funcionando.

`AriaVoiceDirector` recibe la emoción que ya eligió el sistema y ajusta discretamente ritmo y altura. Android TTS no garantiza timbre B2, edad vocal ni actuación emocional auténtica. La voz concreta instalada en el Xiaomi debe probarse antes de aceptarla.

Para alcanzar B2 en nuevas frases falta un backend local con clonación/condicionamiento a partir de una referencia cuya licencia permita ese uso. Probar de forma aislada un motor Qwen3-TTS Base o Chatterbox es-MX en ARM64, medir RAM, latencia y coexistencia con el GGUF cargado, y comprobar varias frases y emociones. Tras esa prueba, reemplazar solo el adaptador `LocalSpeechOutput`, manteniendo texto, emoción, memoria y GGUF separados. No activar reproducción automática para usuarios existentes ni sustituir la firma o los datos de la app.
