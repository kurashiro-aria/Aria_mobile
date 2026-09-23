# Puntos de restauración de ARIA

Antes de probar ajustes de conversación de 0.2.26-alpha:

| Estado | Rama para recuperar código | Commit |
| --- | --- | --- |
| 0.2.24-alpha, versión que Kura reportó funcional | `aria-restore-0.2.24-working` | `faf75f902a8b04c16c44b90eff2661af79ffcd28` |
| 0.2.25-alpha con nuevas expresiones y colores de burbujas, antes de ajustar el motor | `aria-restore-before-conversation-tuning-20260923` | `d0e31b066e09963176204331023d788affcd65ae` |
| 0.2.26-alpha, antes de corregir la repetición vista en el teléfono | `aria-restore-0.2.26-before-repeat-fix` | `b5fda90efc15d3dd92d24948a83a3b3030e992e2` |
| 0.2.27-alpha, antes de integrar el plan de conversación y memoria | `aria-restore-0.2.27-before-plan-integration` | `2069f4dc35fb13bdf7c9bf2c38e4fc1e9eb1b01c` |

El APK 0.2.24-alpha que pasó las comprobaciones del workflow sigue disponible en
[la ejecución 35891568373](https://github.com/kurashiro-aria/Aria_mobile/actions/runs/35891568373/artifacts/10765876814).
La segunda rama guarda código, no un APK con los colores nuevos.

Para recuperar la app después de instalar una versión más reciente, crear un nuevo commit sobre
`aria-alpha-0.2-local-ai` con el árbol del punto elegido, subir `versionCode` y `versionName`, y
ejecutar el workflow normal. Instalar el nuevo APK firmado encima del actual conserva el GGUF,
historial y recuerdos. Android puede rechazar un APK anterior con un `versionCode` menor; no
desinstalar ARIA para forzar una reversión, porque el almacenamiento privado se perdería.
