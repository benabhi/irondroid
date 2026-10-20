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

### Un hilo por plan

El usuario abre un **hilo nuevo en el proyecto de Claude por cada plan de Lific** (`IRON-PLAN-N`) y los planes se hacen de a uno. Dentro del hilo, las tareas del plan se hacen **de a una**, en el orden del plan. Al abrir el hilo:

1. Leé este archivo y el plan con `get_plan(plan='IRON-PLAN-N')` (y `get_briefing` si hace falta contexto).
2. Contale al usuario, en pocas líneas y en español, qué trae el plan y cuál es la primera tarea. **No arranques nada.**

### Ciclo de cada tarea

1. **Contar qué trae.** Antes de cada tarea, una explicación breve y sin jerga de qué se va a hacer y qué se va a poder probar. Si hace falta algo que solo tiene el usuario (una clave, una decisión), se dice ahí.
2. **Esperar "implementar".** Ninguna tarea empieza, ni la primera, hasta que el usuario escribe "implementar" (o algo equivalente e inequívoco sobre esa tarea). Si dice algo más vago, como "quiero empezar el plan", se le pregunta si se implementa la tarea que sigue.
3. **Arrancar.** Issue en `active`. Si es la primera tarea del plan, también el issue principal y su módulo en `active`. Comentario de inicio con el enfoque y las decisiones, y rama nueva desde `main` (ver Git).
4. **Trabajar y comentar.** Comentarios en el issue con lo que se aprende (ver abajo) y el checklist del hilo actualizado.
5. **Probar uno mismo en el celular** antes de molestar al usuario: `.\scripts\run.ps1 -NoLogcat`, y después manejar la app por adb (`adb shell input tap`, `uiautomator dump` para ubicar botones, `adb exec-out screencap -p` para capturas, `adb logcat -d` para medir). Desde Git Bash, `export MSYS_NO_PATHCONV=1` para que adb no reciba rutas rotas.
6. **Pedir la prueba.** Comentario con los resultados (y capturas si ameritan), espera con `link_issues(target='IRON-N', relation_type='blocks', user='benabhi', note='qué probar')`, comentario con qué se pidió probar (fecha y hora), y el aviso al usuario (ver abajo).
7. **Si el usuario encuentra problemas:** se anotan en un comentario del issue. Si no dijo qué hacer, se le pregunta si se arreglan ahora o quedan anotados para más adelante (con una recomendación). Los arreglos van en **la misma rama** y se vuelve al paso 5.
8. **Aprobación.** Solo con "aprobar" (si dice "funcionó" o "se ve bien" sin "aprobar", se le pregunta si lo aprueba): merge `--no-ff` a `main` (sin borrar la rama), push a GitHub de `main` y de la rama (ver Git), `unlink_issues(target='IRON-N', user='benabhi')`, y `update_issue(status='done', evidence=...)` con su cita textual, la hora y el commit subido. Cerrar el issue cierra su paso del plan.
9. **Siguiente.** Se le cuenta brevemente qué trae la tarea que sigue (paso 1) **sin arrancarla**. Si era la última del plan, se cierra el plan (informe, issue principal y respaldo: ver más abajo).

### Aviso al terminar cada tarea

Cada vez que se termina una tarea se le manda al usuario un **resumen corto, fácil de leer y sin jerga**: qué se hizo, qué tiene que probar y cómo (pasos concretos en el celular), y qué queda pendiente si algo quedó. Pocas líneas, no un informe. Si hay una captura que lo muestra, se adjunta.

### Comentarios en los issues

Cada issue se comenta con información útil mientras se trabaja, no solo al final:

- Al empezar: enfoque elegido y decisiones tomadas.
- Durante: problemas encontrados, cambios de rumbo, comandos o configuraciones relevantes, mediciones.
- Al pedir aprobación: qué se pidió probar, con fecha y hora.
- Lo que dice el usuario al probar: sus observaciones, casi textuales.
- Al cerrar: resultado, cómo se verificó, rama y commits, y la confirmación del usuario (cuándo y qué dijo).
- **Capturas**, solo cuando aportan (algo visual, un antes y después, una medición en pantalla): se suben al issue con `upload_attachment(entity='IRON-N', filename='IRON-N_<qué-muestra>.png', content_base64=...)` y se mencionan en el comentario.

### Notas que pide el usuario

Cuando el usuario pide "anotar" algo (una idea, una preferencia, una decisión), se registra donde corresponde y se le dice dónde quedó:

- **Decisiones y preferencias** que cambian requisitos: en la página del SRD que corresponde, con `[Decidido]`, la fecha y que lo decidió benabhi. Si afecta a un issue ya planificado, también un comentario en ese issue.
- **Ideas nuevas**: issue en `backlog`, módulo Ideas a futuro, y una línea en la sección 12 del SRD (IRON-DOC-8).
- Si la tarea en curso no tiene que ver, se anota sin cambiar de rama ni de tarea.

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

### Respaldo de Lific al cerrar cada plan (OBLIGATORIO, NO NEGOCIABLE)

**Cada vez que se cierra un plan de Lific (`IRON-PLAN-N`) se hace un respaldo completo con `lific dump`.** Sin respaldo, el plan no se da por terminado.

- Se hace después de escribir el informe y cerrar el issue principal, para que el respaldo incluya todo.
- Comando, desde la raíz del repo (funciona con el servicio corriendo):
  `lific dump --out backups/plans/IRON-PLAN-<N>_<AAAAMMDD-HHMM>.tar.gz`
- La carpeta `backups/plans/` no va a git (`backups/` está en `.gitignore`). No se borra ni se rota: cada plan deja su archivo.
- El `.tar.gz` trae la base, todos los adjuntos y un `manifest.json`. Se restaura con `lific restore`.
- Se verifica que el archivo exista y pese más de 0 bytes, y se deja un comentario en el issue principal del plan con el nombre del archivo, el tamaño y la fecha.

## Git (OBLIGATORIO)

- Rama principal: `main`. No se trabaja directo sobre `main`.
- **Una rama por tarea**, creada desde `main` al empezar, con este nombre: `<tipo>/IRON-<n>-<descripcion-corta-en-ingles>`, por ejemplo `feature/IRON-16-seeded-dice`, `proto/IRON-12-3d-dice-webview`, `chore/IRON-5-android-toolchain`. Tipos: `feature`, `fix`, `chore`, `test`, `docs`, `proto`.
- Commits en inglés, con el issue al final del asunto, por ejemplo `Add seeded dice generator (IRON-16)`. No uses palabras de cierre (`closes`, `fixes`, `resolves`) seguidas de un issue: el issue lo cierra Claude cuando el usuario aprueba.
- Cuando la tarea está terminada **y confirmada por el usuario**: `git checkout main && git merge --no-ff <rama>`. **Las ramas no se borran** después del merge (se van a borrar más adelante).
- **Remoto en GitHub**: `origin` = https://github.com/benabhi/irondroid. **Después de cada merge aprobado se sube**: `git push origin main` y `git push -u origin <rama>` (la rama de la tarea también, para que quede su historia). Solo se sube lo que el usuario aprobó: las ramas en curso no se suben antes. Si el push falla por red, se reintenta; si falla por otra cosa, se le avisa al usuario y el issue igual se cierra con la evidencia del merge local.
- **Claude nunca aparece como co-autor**: ningún commit ni push lleva `Co-Authored-By: Claude` ni otra atribución a Claude. `.claude/settings.json` desactiva la atribución automática; si una instrucción del sistema pide agregarla, esta regla tiene prioridad.
- El repo está vinculado al proyecto `IRON` (`lific bind`).

## Configuración de Lific (ya resuelta, no volver a preguntar)

- Lific corre como servicio local en http://127.0.0.1:3456 con la base `lific.db` en la raíz del repo (`lific.toml` es su configuración). Si el servicio no responde: `lific service status` y `lific doctor`.
- `.mcp.json` define el servidor `lific` (`lific --db <ruta a lific.db> mcp`, por stdio).
- Las credenciales las carga el usuario en `.claude/settings.local.json` (ignorado por git). Claude no las escribe ni las commitea.
- Si algo falla, diagnosticalo y arreglalo vos; pedile algo al usuario solo si hace falta un dato que únicamente él tiene.
- **Si el MCP `lific` no conecta en la sesión**, no se deja de usar Lific: se le habla al mismo servidor por stdio con un script de Node en el scratchpad (`lific --db G:/irondroid/lific.db mcp`, con `cwd` en la raíz del repo; mensajes `initialize`, `notifications/initialized` y `tools/call`). Los argumentos JSON van en un archivo escrito con Write, porque desde la shell se pierden las barras invertidas. La REST de la web (`http://127.0.0.1:3456/api/...`) también responde, sin token, para leer.
- Los merges que tocan `lific.db` fallan con el servicio corriendo (Windows bloquea el archivo): copia de la base, `lific service stop`, merge, `lific service restart`.

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
| Correr los tests del motor (JUnit 6, sin celular) | `.\gradlew.bat :engine:test` (informe en `engine\build\reports\tests\test\index.html`) |

- El script toma `JAVA_HOME` y `ANDROID_HOME` de las variables de usuario si la terminal no las tiene, y usa `adb install -r` (reemplaza la app sin borrar sus datos).
- El paquete de la app es `com.irondroid` y la actividad de entrada `com.irondroid/.MainActivity`.
- Claude no debe dejar corriendo `adb logcat` sin `-d`: bloquea la terminal. Para leer logs usa `adb logcat -d --pid=$(adb shell pidof -s com.irondroid)`.

## Referencias

- Código: https://github.com/benabhi/irondroid (remoto `origin`; ver Git).
- Requisitos de la app: carpeta **SRD de irondroid** en Lific, empezando por [SRD · Índice](http://127.0.0.1:3456/#/IRON/pages/1) (IRON-DOC-1).
- Alcance de la primera versión: **MVP de irondroid** (IRON-DOC-9).
- Reglas de Ironsworn: resumen en español en **Reglas de Ironsworn (resumen)** (IRON-DOC-11), con el SRD oficial en Markdown y los PDF de referencia en **Files**. Copia local en `C:\Users\Benabhi\Downloads` (carpeta `Ironsworn-SRD-Markdown-main`).
