# Preparación de voz para ARIA

Estado: diseño para una versión posterior a 0.2.36.9. La compilación actual sigue siendo solo texto.

## Objetivo y límites

- Mantener el GGUF, Identity Core, memoria, emoción y ExpressionStyle como fuente de la respuesta. La voz pronuncia la respuesta visible que ya aprobó `VisibleReplyFilter` y pasó por `ReplyQuality`; no genera otra respuesta ni lee razonamiento oculto.
- Dictado iniciado por Kura con un botón. Mostrar el texto reconocido en el editor antes de enviarlo; no grabar continuamente ni enviar una transcripción parcial al modelo.
- Experiencia local por defecto. No utilizar un reconocedor del sistema que pueda enviar audio a un servidor. Si el reconocimiento local no está disponible en el teléfono, informar y conservar el chat de texto.
- No cargar simultáneamente otro modelo grande de voz y el GGUF sin medir memoria y latencia en el dispositivo.

## Puntos de integración

| Componente | Entrada | Salida / ciclo de vida |
| --- | --- | --- |
| `SpeechInput` (interfaz futura) | Pulsar micrófono, idioma español, permiso de audio | Parciales solo en el editor; resultado final editable. Se cancela al salir y se destruye el reconocedor. |
| `MainActivity.sendMessage()` | Texto revisado por Kura, escrito o dictado | Una sola ruta de contexto, historial y memoria; registrar origen de voz solo si se requiere para diagnóstico de sesión. |
| `SpeechOutput` (interfaz futura) | Respuesta final visible de ARIA | Reproducir y detener sin volver a invocar el GGUF. Limpiar recursos al cerrar la actividad; no repetir al restaurar historial. |
| `VoiceDirection` (dato futuro) | `AriaEmotion` + `ExpressionState` del turno | Matices suaves de velocidad, pausas y energía; nunca reescribe la frase ni asigna una emoción al usuario. |

La implementación inicial puede usar `TextToSpeech` de Android seleccionando una voz española instalada que no requiera red (`Voice.isNetworkConnectionRequired() == false`). Debe esperar `OnInitListener`, comprobar disponibilidad de idioma y voz, escuchar eventos de pronunciación, permitir parar y ejecutar `shutdown()` al terminar. No todas las voces admiten los mismos matices; ARIA debe seguir respondiendo por texto si no hay voz local compatible.

Para entrada, usar `SpeechRecognizer.isOnDeviceRecognitionAvailable()` y luego `createOnDeviceSpeechRecognizer()` si el servicio local está disponible. Pedir `RECORD_AUDIO` solo al pulsar micrófono, declarar la visibilidad del servicio de reconocimiento en el manifest, iniciar/terminar en el hilo principal, esperar `onResults`/`onError` antes de otra sesión y llamar `destroy()`. Revisar soporte de español en el dispositivo; no recurrir silenciosamente al reconocedor predeterminado, que puede usar red. La aplicación actual no solicita permiso de micrófono.

## Secuencia de entrega

1. Prototipo en teléfono real: comprobar voz española local, reconocedor local, consumo de RAM con el GGUF residente y tiempo desde pulsar hasta transcripción/reproducción. Sin cambio de firma, applicationId ni datos existentes.
2. Añadir interfaces y un botón de micrófono opcional. Dictado local a texto editable, permisos en contexto, cancelación y mensajes de error claros. Probar permiso denegado, servicio ausente, silencio, interrupción y cambio de actividad.
3. Añadir reproducción opcional de respuestas finales con parada manual. Evitar leer el texto provisional, las acciones de Kura, citas del historial o reintentos descartados. Pausar/reducir reproducción mientras escucha el micrófono para impedir que ARIA se transcriba a sí misma.
4. Usar `VoiceDirection` para variaciones discretas según emoción/estilo, calibradas en español y verificadas con varias voces instaladas. Mantener texto, imagen y voz sincronizados por ID de turno, cancelando eventos tardíos de un turno anterior.
5. Solo si la calidad local del sistema es insuficiente, evaluar motores de voz empaquetados o descargables con licencia, tamaño y memoria medidos. No acoplar identidad ni recuerdos a esos motores.

## Criterios de aceptación

- Dictar, editar y enviar conserva exactamente el flujo conversacional actual; nada se almacena antes de confirmar el envío.
- El modo local no transmite audio ni texto a servicios de red; si falta soporte, el chat escrito sigue operativo.
- La voz reproduce exclusivamente la respuesta final de ARIA y se puede interrumpir; cambiar de app o de turno no deja micrófono ni reproducción activos.
- Un turno alegre, serio o reconfortante puede variar sutilmente la forma de hablar sin forzar frases ni alterar la imagen correspondiente.

Referencias: documentación oficial de Android `TextToSpeech`, `Voice`, `SpeechRecognizer`, `RecognitionListener` y permisos en tiempo de ejecución.
