# Revisión de firma y personalidad

Base revisada: `5bfa6d414d9f40ff7df66983e94e27727fb0aa3a` (0.2.6-alpha, versionCode 8).

## Diagnóstico confirmado en artefactos

Se extrajo el certificado del bloque APK Signature Scheme v2 de dos APK:

| APK | SHA-256 del certificado |
| --- | --- |
| `ARIA-Mobile-Alpha (4).zip` aportado por Kura | `AB17629A5F8227D03FB72B28FD97C876559968C683B3D1C471C01B723633019F` |
| Build `35787632800`, artefacto `10721330229` (0.2.6) | `34FAC970E50CDAEB84133015358032EA846EA9525CF35C077A5D53203AA35BCC` |

El ZIP de 0.2.6 coincide con el digest publicado por GitHub: `b9c3368f54ac94aaa86f954e3fea0f3edbdf7d63260e5614f482a2f80a7a6917`.

La comprobación de keytool del build 35787632800 mostró otro certificado: `24A83E6CAE643A11B30E1102166EC4F8B4CECDD4614F7BA5A1E7CBEBC16CDE17`. La comprobación de una keystore por sí sola no validó la firma del APK final. Gradle no tenía un enlace explícito a esa keystore. No se confirmó el mecanismo exacto por el que seleccionó la otra clave.

Por tanto, no es solo un problema del número de versión: los APK examinados tienen firmas distintas. Android no acepta una actualización ordinaria entre ellos. No se sabe cuál de estos certificados tiene la app actualmente instalada por Kura.

## Corrección preparada

- Gradle usa explícitamente `ARIA_SIGNING_KEYSTORE` para firmar debug. En CI falta de esa variable produce error.
- El workflow copia la clave existente a un archivo temporal privado y comprueba su huella antes de usarla.
- Si falta la clave, no genera otra. Puede restaurarse mediante el secreto opcional `ARIA_DEBUG_KEYSTORE_BASE64`, que debe contener la MISMA keystore existente, no una clave nueva.
- Se conserva la huella esperada 24A8...DE17. No se crea una clave ni se publica material privado.
- El APK terminado se comprueba con `apksigner` antes de publicarse. También se comprueban applicationId y versión contra los metadatos de Gradle.
- Nueva versión preparada: 0.2.7-alpha / versionCode 9. La pantalla usa BuildConfig.VERSION_NAME; antes mostraba 0.2.5 incluso dentro de 0.2.6.

La caché de Actions puede desaparecer: no es un respaldo permanente de la clave. El secreto o un respaldo seguro de la keystore existente queda pendiente de configuración por el propietario. Esta revisión no accedió ni exportó la clave privada.

## Compatibilidad con instalaciones anteriores

Esto estabiliza las próximas firmas, pero no convierte un APK anterior en compatible con la clave 24A8...DE17. Para conservar una instalación antigua mediante actualización hace falta su clave privada original, o una migración autorizada compatible; el certificado público extraíble del APK no permite reconstruirla. No se recomienda desinstalar antes de respaldar el historial. Esta app aún no incluye exportación de historial.

## Personalidad

La v1.0 ya se aplica tanto al importar el modelo como al restaurarlo. Se preparó v1.1 conservando sus rasgos: corrige ejemplos que inventaban horas de ausencia o errores en código no visto, elimina el ejemplo que anunciaba un modo pese a pedir no anunciarlo y aclara la diferencia entre historial visible y contexto del modelo. La personalidad sigue siendo un prompt y necesita evaluación conversacional en el teléfono; no se garantiza consistencia perfecta.

## Validación de esta revisión

Comparación de certificados extraídos de los APK y digest del ZIP; lectura del log de firma real; revisión de Kotlin/Gradle, YAML, sintaxis Bash y diff. No se compiló, instaló ni probó una actualización en el teléfono durante esta revisión.
