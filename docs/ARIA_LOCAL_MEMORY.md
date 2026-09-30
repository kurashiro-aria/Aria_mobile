# ARIA Cloud: memoria local

La fuente de verdad de memoria e historial está en el teléfono. `aria_memory.db` guarda recuerdos estructurados e indexados; `aria_history.db` guarda el historial original. Al primer arranque, los datos anteriores de `aria_memories_v2` y `history/chat.jsonl` se copian a SQLite. Los originales no se borran automáticamente.

## Esquema y crecimiento

`memories` contiene tipo, contenido, clave normalizada, creación, actualización, último uso, importancia, confianza, origen, etiquetas, entidades, conversación, versión, archivado y contador de acceso. Tiene índices por tipo, actualización, clave, conversación y contenido normalizado. `memory_fts` permite buscar un máximo acotado de candidatos sin recorrer toda la tabla. `memory_relations` y `memory_summaries` preparan relaciones y consolidación; resumir nunca elimina originales.

El historial se consulta por páginas. La conversación lee 40 mensajes recientes y la pantalla representa como máximo 200. La exportación procesa recuerdos en páginas de 250 e historial en páginas de 500, evitando cargar años de datos completos en RAM.

## Qué sale del teléfono

Por turno salen exclusivamente el mensaje actual, hasta cuatro turnos inmediatos seleccionados, hasta tres recuerdos locales relevantes, instrucciones de identidad y estilo necesarias y el estado conversacional breve. No salen la base SQLite, el historial completo, recuerdos no seleccionados, archivos internos, claves del proveedor ni backups. La aplicación no añade telemetría de conversación.

## Backup

`Datos de ARIA` permite exportar e importar un backup `ARIA_MEMORY_BACKUP_YYYY-MM-DD.aria`. El archivo usa AES-256-GCM y una clave derivada de una contraseña elegida por Kura mediante PBKDF2-HMAC-SHA256. La contraseña y la clave derivada no se guardan. El formato contiene manifiesto versionado, recuerdos e historial; valida completamente el archivo y su versión antes de importar. La importación combina datos y deduplica recuerdos e historial.

La base activa aún usa el cifrado de almacenamiento del sistema Android y el aislamiento de la aplicación; no está cifrada con SQLCipher. No se añadió SQLCipher porque aumenta el APK y requiere una evaluación separada de mantenimiento/rendimiento. Android Keystore se reservará para cifrado transparente de la base si se adopta una biblioteca mantenida. El backup portátil usa contraseña porque una clave exclusiva del Keystore del teléfono antiguo no podría restaurarse en otro teléfono.

## Límites actuales

La extracción automática solo acepta patrones locales de alta confianza: preferencias explícitas, algunos hechos personales, proyectos y experiencias fechadas. Todo mensaje continúa en el historial, pero las frases casuales y roleplay no se convierten en memoria permanente. Las tablas de relaciones y summaries están creadas; la generación automática de summaries todavía debe programarse como trabajo de mantenimiento cuando exista suficiente material.
## Escala y consolidación

La búsqueda FTS/SQLite devuelve como máximo 60 candidatos indexados antes del ranking local.
El límite se aplica en SQL y también existe como política reutilizable para futuros índices, por
lo que el crecimiento de la base no implica cargarla completa en RAM. Los lotes de 50 elementos
quedan marcados como umbral de consolidación; la tabla `memory_summaries` conserva resúmenes sin
eliminar los originales. La generación automática del texto resumido queda pendiente hasta poder
hacerla sin una inferencia Cloud adicional ni reglas que inventen contenido.
