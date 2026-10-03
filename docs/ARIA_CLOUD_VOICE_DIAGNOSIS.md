# ARIA Cloud Voice: diagnóstico de 429

## Síntoma

En Xiaomi 17T las primeras respuestas podían reproducirse y después la app mostraba que el proveedor estaba temporalmente limitado.

## Evidencia y causa

El código anterior tenía dos fuentes distintas de `429`, pero Android las convertía en el mismo error: el límite del Gateway (`rate_limited`) y el límite devuelto por Gemini TTS (`provider_rate_limited`). Por ello el mensaje no permitía identificar el origen. Además, Brain y Voice compartían el mismo bucket de límite por IP. La causa concreta de una ejecución desplegada requiere conservar los headers/logs diagnósticos del proveedor; no se debe afirmar que todo 429 es de Gemini sin esa evidencia.

## Modelo y endpoint

El Worker usa `ARIA_TTS_MODEL` con valor predeterminado `gemini-3.8-flash-lite-tts` y `POST /v1beta/interactions`, con `response_format` `audio/wav`, `sample_rate` 24000 y `generation_config.speech_config` para la voz. La documentación oficial actual de Gemini confirma ese modelo, el endpoint Interactions y la respuesta de audio WAV. Leda es una voz oficial y el servicio documenta español.

## Cambios aplicados

- Se separaron los buckets Gateway de Brain y Voice (`ARIA_VOICE_RATE_LIMIT_PER_MINUTE` opcional).
- El límite del Gateway devuelve `gateway_rate_limited`; un 429 del proveedor permanece como `provider_rate_limited`.
- El Worker hace como máximo la solicitud inicial más dos reintentos para 429/502/503/504, con backoff de 750 ms y 1500 ms y respeto de `Retry-After`.
- El `requestId` lógico no cambia durante los reintentos. Los fallos finales liberan el identificador para permitir una repetición legítima; las solicitudes completadas siguen protegidas contra duplicados.
- Se añadió un circuit breaker en memoria del isolate únicamente para Voice. No afecta Brain y no pretende ser un contador global entre isolates de Cloudflare.
- Android dejó de reintentar con otro `requestId`; realiza una sola solicitud lógica y distingue un 429 del Gateway de uno del proveedor.

## Pendientes

La confirmación del origen exacto de un 429 real requiere una prueba contra el Worker desplegado y los datos seguros de diagnóstico (`X-ARIA-*`, código JSON y `Retry-After`), sin exponer secretos. No se desplegó el Worker en esta tarea y no se generó APK.
