# Personalidad ARIA - Alpha 0.2.2

`AriaPersonality` reúne el prompt del sistema y los saludos de la aplicación. Define identidad propia, relación con Kura y Sol, español, calidez, curiosidad, humor contextual, sarcasmo moderado, afecto y capacidad de discrepar. Ante temas serios pide sensibilidad. No promete memoria persistente ni capacidades que aún no existen.

`VisibleReplyFilter` oculta los bloques `<think>...</think>` durante el streaming, incluso con etiquetas repartidas entre fragmentos, mayúsculas, bloques anidados o incompletos. Conserva la respuesta final. Este filtro no desactiva el razonamiento del modelo ni reduce su tiempo de generación. No es un detector de análisis sin etiquetas. Si termina sin una respuesta visible, la interfaz muestra un aviso en español.

Hay seis pruebas unitarias para fragmentación de etiquetas, bloques anidados, texto normal, bloques incompletos y aislamiento entre turnos. El workflow las ejecuta antes de empaquetar el APK.

La personalidad es una instrucción al modelo, no una garantía de que todas sus respuestas sean correctas o mantengan siempre el tono. La prueba real sigue siendo en el teléfono con el GGUF de Kura. Cargar el modelo aplica el nuevo prompt.

Pruebas manuales sugeridas: saludo breve; preguntar quiénes son Kura y Sol; expresar un problema serio; pedir recordar algo para mañana y comprobar que no afirme guardarlo; enviar dos mensajes consecutivos y comprobar que no aparezcan etiquetas ni texto del bloque de razonamiento.
