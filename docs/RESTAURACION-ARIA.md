# Puntos de restauración de ARIA

Antes de probar ajustes de conversación de 0.2.26-alpha:

| Estado | Rama para recuperar código | Commit |
| --- | --- | --- |
| 0.2.24-alpha, versión que Kura reportó funcional | `aria-restore-0.2.24-working` | `faf75f902a8b04c16c44b90eff2661af79ffcd28` |
| 0.2.25-alpha con nuevas expresiones y colores de burbujas, antes de ajustar el motor | `aria-restore-before-conversation-tuning-20260923` | `d0e31b066e09963176204331023d788affcd65ae` |
| 0.2.26-alpha, antes de corregir la repetición vista en el teléfono | `aria-restore-0.2.26-before-repeat-fix` | `b5fda90efc15d3dd92d24948a83a3b3030e992e2` |
| 0.2.27-alpha, antes de integrar el plan de conversación y memoria | `aria-restore-0.2.27-before-plan-integration` | `2069f4dc35fb13bdf7c9bf2c38e4fc1e9eb1b01c` |
| 0.2.28-alpha, antes de ajustar estados de ánimo | `aria-restore-0.2.28-before-mood` | `335532ed2033bd47fd50199e4bbfdc5cb3727d56` |
| 0.2.29-alpha, antes de ajustar la expresión textual y visual | `aria-restore-0.2.29-before-expression-tuning` | `aaba37063afed5c8469975f4529cce0afdb27e8d` |
| 0.2.30-alpha, antes del estado social de varios turnos | `aria-restore-0.2.30-before-social-state` | `baa884ccbef96edd19bd80dd50d351acf8a8ed97` |
| 0.2.31-alpha, antes de la pantalla de carga | `aria-restore-0.2.31-before-loading-screen` | `7fb2f226e804e238f5e46cef90f49889cf705106` |
| 0.2.31.1-alpha, antes de corregir el indicador y añadir «Despertar» | `aria-restore-0.2.31.1-before-wake-button` | `42951d20524c9f86e32b88179949c13c5efe5e6d` |

El APK 0.2.24-alpha que pasó las comprobaciones del workflow sigue disponible en
[la ejecución 35891568373](https://github.com/kurashiro-aria/Aria_mobile/actions/runs/35891568373/artifacts/10765876814).
La segunda rama guarda código, no un APK con los colores nuevos.

Para recuperar la app después de instalar una versión más reciente, crear un nuevo commit sobre
`aria-alpha-0.2-local-ai` con el árbol del punto elegido, subir `versionCode` y `versionName`, y
ejecutar el workflow normal. Instalar el nuevo APK firmado encima del actual conserva el GGUF,
historial y recuerdos. Android puede rechazar un APK anterior con un `versionCode` menor; no
desinstalar ARIA para forzar una reversión, porque el almacenamiento privado se perdería.
