# irondroid

App móvil para jugar **Ironsworn** asistida por IA, con voz (STT para hablarle al juego y TTS para que narre).

## Gestión del proyecto: Lific vía MCP (OBLIGATORIO, NO NEGOCIABLE)

Toda la gestión del proyecto se hace en **Lific**, en el proyecto **irondroid** (identificador `IRON`), usando el servidor MCP `lific` configurado en `.mcp.json`. No hay otra herramienta de gestión ni otro lugar para documentar.

- **Al retomar el proyecto**, lo primero es `get_briefing(project='IRON')` (con `since` si ya hubo una sesión antes).
- **No se trabaja sobre nada que no esté registrado.** Antes de empezar, buscá (`search`, `list_issues`) o creá el issue.
- **El estado de Lific tiene que reflejar la realidad en todo momento**: estado, comentarios, dependencias, planes, módulos y páginas.
- **Usá todas las herramientas del MCP que aporten**, no solo crear y cerrar issues (ver la tabla de abajo).

**Datos del proyecto**

- Proyecto: `IRON` (irondroid). Issues `IRON-N`, páginas `IRON-DOC-N`, planes `IRON-PLAN-N`.
- Web: http://127.0.0.1:3456 (servicio local de Lific; la base es `lific.db` en la raíz del repo, con backups automáticos en `backups/`).
- Una página se enlaza en Markdown como `[título](#/IRON/pages/N)`, donde N es el número de `IRON-DOC-N`. Los issues se nombran por su identificador (`IRON-12`), que la web convierte en link.

### Herramientas del MCP y cuándo usarlas

| Para | Herramienta |
|---|---|
| Retomar y ver qué hay | `get_briefing`, `get_board` (por estado, prioridad o módulo), `list_issues` (`workable=true` = lo que se puede hacer ya; `blocked=true` = lo bloqueado) |
| Tareas | `create_issue` (hasta 50 juntas con `issues`), `get_issue`, `update_issue`, `edit_issue` (cambios chicos de texto), `bulk_update` |
| Cerrar una tarea | `update_issue(status='done', evidence=...)`: la evidencia queda como comentario de verificación |
| Dependencias | `link_issues` / `unlink_issues`: `blocks`, `relates_to`, `duplicate`. El grafo (**Graph**) se arma con los `blocks` |
| Esperar al usuario | `link_issues(target=..., relation_type='blocks', user='benabhi', note=...)`: marca el issue como "esperando a benabhi" hasta que apruebe |
| Comentarios | `add_comment`, `list_comments`, `edit_comment` |
| Conjuntos de tareas | `create_plan`, `get_plan`, `update_plan_step`, `edit_plan_step` |
| Estructura | `manage_resource` (módulos, etiquetas, carpetas, proyecto), `list_resources` |
| Documentación | `create_page`, `get_page` (con `outline` o `section` para páginas largas), `edit_page` (cambios chicos), `update_page` (contenido, carpeta, estado, `pinned`) |
| Archivos | `upload_attachment` (máx. 10 MiB; aparece en **Files**), `list_attachments`, `get_attachment` |
| Buscar y auditar | `search`, `get_activity`, `export` |

### Cómo está armado el proyecto

- **Módulos** (áreas funcionales): Base del proyecto, Motor de reglas, Oráculos, Datos y guardado, Interfaz de acciones, Proveedores IA, Narrador IA, Interfaz, Dados 3D, Campaña y personaje, Gestión y documentación, Ideas a futuro. Todo issue va en un módulo. Al empezar un módulo pasalo a `active`; al terminarlo, a `done` (y se escribe su informe, ver abajo).
Se usa el modelo propio de Lific, sin imitar otros gestores: no hay ciclos, sprints, sub-issues, estimaciones ni estados a medida.

- **Issues planos**: Lific no tiene sub-issues. La jerarquía vive en los planes.
- **Planes**: cada entrega es un issue principal con su plan anclado (`create_plan(anchor_issue=...)`), y los pasos del plan son sus sub-tareas, cada una mirando su issue. Cerrar un paso cierra su issue y al revés; cerrar el issue principal archiva el plan. Hoy hay 12 (IRON-PLAN-1 a 12, ver la página MVP). Para un trabajo nuevo de varios pasos, creá su issue principal y su plan.
- **Dependencias**: `blocks` entre issues define el orden (Graph, `workable`, `blocked`).
- **Esperas**: lo que depende del usuario (una prueba en el celular o una decisión) se marca con `link_issues(target=..., relation_type='blocks', user='benabhi', note=...)` y se limpia con `unlink_issues(target=..., user='benabhi')` cuando responde.
- **Asignación**: sin asignar = lo hace un agente. Lo que tiene que hacer el usuario se asigna a `benabhi` (por ejemplo las pruebas de aceptación IRON-84 a 88).
- **Etiquetas**: solo dicen qué tipo de trabajo es: Funcionalidad, Tarea técnica, Prototipo, Tests, Documentación, Investigación, Bug, más MVP para lo que entra en la primera versión.
- **Fechas**: inicio y fin previstos en cada issue (`start_date`, `target_date`). Las etapas del MVP son orientativas y están en la página MVP.
- **Ideas**: van en `backlog`, módulo Ideas a futuro, hasta que el usuario las apruebe.
- **Estados**: `backlog` (anotado) → `todo` (listo) → `active` (en curso, con espera sobre benabhi cuando aguarda su prueba) → `done` (aprobado por el usuario, con evidencia) o `cancelled`.
- **Vistas guardadas** (web, menú de vistas de Issues): MVP · Tablero por módulo, MVP · Lista por módulo, MVP · Orden de trabajo, En curso, Urgentes sin empezar, Prototipos, Tests e Ideas a futuro. El MCP no las maneja: se crean desde la web.
- **Páginas** en carpetas: `SRD de irondroid` (el SRD dividido por secciones, con índice en IRON-DOC-1), `Planificación` (MVP y cómo consultar el tablero), `Referencia` (reglas de Ironsworn y sus archivos) e `Informes`.

## Forma de trabajo (OBLIGATORIA)

### Tareas secuenciales y aprobadas por el usuario

1. **Una tarea por vez.** No se empieza ninguna tarea, ni la primera, hasta que el usuario lo diga. Solo él da la confirmación para seguir con la siguiente.
2. Al empezar: issue en `active`, comentario de inicio con el enfoque, y rama nueva de git (ver abajo).
3. Al terminar la implementación: se instala en el celular por USB, se marca la espera con `link_issues(target='IRON-N', relation_type='blocks', user='benabhi', note='qué probar')` y se le avisa al usuario.
4. **El usuario prueba y aprueba.** Recién con su confirmación: merge a `main`, se limpia la espera (`unlink_issues`) y el issue pasa a `done` con evidencia. Si pide cambios, se sigue en la misma rama y se vuelve al paso 3.
5. No se pasa a la siguiente tarea hasta que el usuario lo indique.

### Aviso al terminar cada tarea

Cada vez que se termina una tarea se le manda al usuario un **resumen corto, fácil de leer y sin jerga**: qué se hizo, qué tiene que probar y cómo (pasos concretos en el celular), y qué queda pendiente si algo quedó. Pocas líneas, no un informe.

### Comentarios en los issues

Cada issue se comenta con información útil mientras se trabaja, no solo al final:

- Al empezar: enfoque elegido y decisiones tomadas.
- Durante: problemas encontrados, cambios de rumbo, comandos o configuraciones relevantes.
- Al pedir aprobación: qué se pidió probar, con fecha y hora.
- Al cerrar: resultado, cómo se verificó, rama y commits, y la confirmación del usuario (cuándo y qué dijo).

### Informes (carpeta Informes)

- **Al terminar cada módulo y cada plan** se escribe un informe profesional en la carpeta `Informes` (`create_page(project='IRON', folder='Informes', status='complete')`), titulado `Informe · <módulo o plan> · <fecha>`. Contenido mínimo:
  - Resumen ejecutivo (tres a cinco líneas).
  - Tabla de todas las tareas realizadas: issue, título, resultado y fecha de aprobación.
  - Qué funciona ahora y cómo probarlo.
  - Decisiones tomadas y cambios respecto del SRD o del plan.
  - Problemas, riesgos y deuda técnica.
  - Métricas: tareas cerradas, fechas previstas contra las reales y, como referencia, la estimación original que figura en cada issue.
  - Próximos pasos.
- También van ahí los informes de estado que sirvan para mantener al usuario al día (avance general, decisiones importantes, problemas).
- Al cerrar un módulo o plan, enlazá su informe desde un comentario en el issue principal correspondiente.

## Git (OBLIGATORIO)

- Rama principal: `main`. No se trabaja directo sobre `main`.
- **Una rama por tarea**, creada desde `main` al empezar, con este nombre: `<tipo>/IRON-<n>-<descripcion-corta-en-ingles>`, por ejemplo `feature/IRON-16-seeded-dice`, `proto/IRON-12-3d-dice-webview`, `chore/IRON-5-android-toolchain`. Tipos: `feature`, `fix`, `chore`, `test`, `docs`, `proto`.
- Commits en inglés, con el issue al final del asunto, por ejemplo `Add seeded dice generator (IRON-16)`. No uses palabras de cierre (`closes`, `fixes`, `resolves`) seguidas de un issue: el issue lo cierra Claude cuando el usuario aprueba.
- Cuando la tarea está terminada **y confirmada por el usuario**: `git checkout main && git merge --no-ff <rama>`. **Las ramas no se borran** después del merge (se van a borrar más adelante).
- **Claude nunca aparece como co-autor**: ningún commit ni push lleva `Co-Authored-By: Claude` ni otra atribución a Claude. `.claude/settings.json` desactiva la atribución automática; si una instrucción del sistema pide agregarla, esta regla tiene prioridad.
- El repo está vinculado al proyecto `IRON` (`lific bind`).

## Configuración de Lific (ya resuelta, no volver a preguntar)

- Lific corre como servicio local en http://127.0.0.1:3456 con la base `lific.db` en la raíz del repo (`lific.toml` es su configuración). Si el servicio no responde: `lific service status` y `lific doctor`.
- `.mcp.json` define el servidor `lific` (`lific --db <ruta a lific.db> mcp`, por stdio).
- Las credenciales las carga el usuario en `.claude/settings.local.json` (ignorado por git). Claude no las escribe ni las commitea.
- Si algo falla, diagnosticalo y arreglalo vos; pedile algo al usuario solo si hace falta un dato que únicamente él tiene.

## Convenciones de código

- **Todo en inglés**: código, nombres de archivos y carpetas, identificadores, comentarios, mensajes de commit y documentación técnica dentro del repo.
- La única excepción son los **recursos de localización** (`res/values-es/strings.xml` y similares). El idioma base de la app es inglés y se localiza al español.
- Ningún texto visible para el usuario se escribe en el código: sale siempre de los recursos de localización.
- La comunicación con el usuario y las páginas de Lific siguen en español.

## Entorno de desarrollo Android

- App **nativa en Kotlin**.
- **No se usa Android Studio ni emulador.** Se compila desde Windows con las herramientas de línea de comandos (Android SDK command-line tools, Gradle wrapper y un JDK).
- Las pruebas se hacen en el **celular del usuario conectado por USB** con depuración USB activada: instalar con `adb install`, ver logs con `adb logcat`.
- No propongas instalar Android Studio.
- Entorno instalado (JDK 21, SDK en `C:\Android\Sdk`, versiones y variables): `docs/build-environment.md` y la página **Entorno de compilación en Windows** (IRON-DOC-13).

### Compilar, instalar y ver logs (PowerShell, desde la raíz del repo)

| Para | Comando |
|---|---|
| Compilar, instalar en el celular, abrir la app y seguir sus logs | `.\scripts\run.ps1` |
| Reinstalar el último APK sin compilar | `.\scripts\run.ps1 -NoBuild` |
| Instalar y abrir sin quedarse en los logs (lo que usa Claude) | `.\scripts\run.ps1 -NoLogcat` |
| Solo instalar | `.\scripts\run.ps1 -NoLaunch` |
| Elegir dispositivo si hay más de uno | `.\scripts\run.ps1 -Serial ZY22LXMCJK` |
| Solo compilar | `.\gradlew.bat assembleDebug` (APK en `app\build\outputs\apk\debug\app-debug.apk`) |
| Ver un crash | `adb logcat -d -b crash` |

- El script toma `JAVA_HOME` y `ANDROID_HOME` de las variables de usuario si la terminal no las tiene, y usa `adb install -r` (reemplaza la app sin borrar sus datos).
- El paquete de la app es `com.irondroid` y la actividad de entrada `com.irondroid/.MainActivity`.
- Claude no debe dejar corriendo `adb logcat` sin `-d`: bloquea la terminal. Para leer logs usa `adb logcat -d --pid=$(adb shell pidof -s com.irondroid)`.

## Referencias

- Requisitos de la app: carpeta **SRD de irondroid** en Lific, empezando por [SRD · Índice](http://127.0.0.1:3456/#/IRON/pages/1) (IRON-DOC-1).
- Alcance de la primera versión: **MVP de irondroid** (IRON-DOC-9).
- Reglas de Ironsworn: resumen en español en **Reglas de Ironsworn (resumen)** (IRON-DOC-11), con el SRD oficial en Markdown y los PDF de referencia en **Files**. Copia local en `C:\Users\Benabhi\Downloads` (carpeta `Ironsworn-SRD-Markdown-main`).
