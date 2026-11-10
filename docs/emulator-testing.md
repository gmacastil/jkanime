# Validación en emulador Android TV (2026-11-04)

Sin TV física disponible, toda la validación de F0–F3 se hizo contra un emulador
Android TV real (no Robolectric): AVD `jktv` (`tv_1080p`, API 36,
`android-tv x86_64`), creado y manejado por `tools/run-emulator.sh`. Este doc
registra qué se verificó, los bugs encontrados/corregidos y el estado del
bloqueo de reproducción.

## Entorno

- SDK en `/data/codigo/android-sdk` (fuera del repo); `emulator` 37.2.13 +
  `system-images;android-36;android-tv;x86_64`.
- `/dev/kvm` accesible → el emulador x86_64 corre a velocidad usable.
- `DISPLAY=:0` (X11): el emulador corre con ventana visible + `-gpu host`.
- adb: `/data/codigo/android-sdk/platform-tools/adb`.
- Navegación sin mando: `adb shell input keyevent {19=arriba,20=abajo,21=izq,22=der,23=OK,4=atrás}`.
- Captura: `adb exec-out screencap -p > archivo.png`.
- Build: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :app:assembleDebug`
  (incremental: 2–8 s).

## Qué se verificó end-to-end (con capturas adb reales)

- **HomeScreen**: datos reales de "Programación" renderizan correctamente —
  grilla de 6 columnas, badge `Ep N`, fecha relativa.
- **Tabs de sección** (Animes / Donghuas / OVAs): cambian el contenido de la
  grilla correctamente.
- **Navegación D-pad**: moverse entre tarjetas con flechas muestra el borde de
  foco (naranja) correctamente.
- **DetailScreen**: descripción, chips (tipo/estado/calidad), grilla de
  capítulos y botón "Ver último" funcionan.
- **PlayerActivity**: overlay de selección de servidor (Streamwish / Vidhide /
  VOE / Filemoon / Mixdrop, etc.) se muestra, auto-hide/show funciona, botón
  "Salir" presente.

## Bugs encontrados y corregidos

### 1. Foco inicial "muerto" en pantallas TV

**Síntoma**: al abrir Home/Detail, ninguna tarjeta/botón tenía foco visible;
el mando no reaccionaba hasta que el usuario movía el D-pad manualmente una
vez (y a veces ni así).

**Causas (dos, combinadas)**:

1. En `core/ui/.../PosterCard.kt`, el modifier `.focusRequester(...)` estaba
   **después** de `.clickable(...)` en la cadena de `Modifier`. Con ese orden
   `requestFocus()` devuelve `false` silenciosamente (no lanza excepción)
   porque ese nodo del árbol de Compose no queda registrado como destino de
   foco. **Regla**: `focusRequester()` siempre debe preceder a `.clickable()`.
2. Pedir foco una sola vez en el primer frame (`withFrameNanos {}` simple) no
   alcanza: los items de una `LazyVerticalGrid`/`LazyRow` se crean a través de
   varios frames de medición, así que la petición de foco en el frame 0 puede
   apuntar a un nodo que todavía no existe.

**Fix**: mover `focusRequester()` antes de `.clickable()` en `PosterCard.kt`,
y cambiar el `LaunchedEffect` de foco inicial a un bucle que reintenta
`requestFocus()` durante hasta ~10 frames con `withFrameNanos {}`. Aplicado en:

- `core/ui/src/main/kotlin/net/jkanime/tv/ui/component/PosterCard.kt`
- `app/src/main/kotlin/net/jkanime/tv/feature/home/HomeScreen.kt`
  (`ProgramacionGrid`, que ahora recibe `section` para resetear el scroll al
  cambiar de pestaña)
- `app/src/main/kotlin/net/jkanime/tv/feature/detail/DetailScreen.kt`
  (foco inicial en el botón "Ver último")

Verificado con capturas adb: borde de foco naranja visible en la primera
tarjeta/botón sin ninguna pulsación previa.

### 2. "Cargar más" se mostraba sin más páginas

En `DetailScreen.kt`/`DetailViewModel.kt` el botón "Cargar más" aparecía
siempre, incluso cuando `hasMore == false`. Fix: el botón solo se renderiza
si `hasMore` es `true`; se agregó estado `loadingMore` (texto "Cargando…") y
se conectó `enabled` al `TvActionButton`.

### 3. Chip "0 eps"

Se veía `Episodios: 0` en animes en emisión. Confirmado por curl que es dato
real del sitio (no bug del parser): jkanime reporta `0` como total de
episodios para animes que aún no tienen total conocido. Fix: el chip de
`totalEpisodes` se oculta cuando el valor es `0` o `null`
(`?.takeIf { it > 0 }`), en vez de mostrar "0 eps".

## Bloqueo de anuncios/overlays en el reproductor WebView (RESUELTO)

**Síntoma**: al reproducir un episodio, tras ~100-180s aparece un overlay con
imagen + botón de cierre (anuncio de contenido adulto) encima del `<video>`,
además de un falso "captcha" grande ("Confirm you're not a robot") que
aparecía antes casi siempre al cargar el embed.

**Root cause real (confirmado con diagnóstico exhaustivo)**: el falso captcha
y los trackers-iframe sí son alcanzables recorriendo el árbol DOM normal
(`document.querySelectorAll`, bajando por `shadowRoot` e `iframe.contentDocument`)
y por eso el sweep original (`sweepOverlaysInDoc`) siempre los eliminó bien.
Pero el anuncio de imagen+cierre **nunca apareció en ningún recorrido de
árbol**, por ninguna vía (ni `querySelectorAll('*')` recursivo, ni bajando
`shadowRoot`, ni `contentDocument` de iframes same-origin) a pesar de ocupar
píxeles reales y visibles en pantalla. Se descartó explícitamente la hipótesis
de "el contexto JS se destruye y hay que reinyectar el hook": un heartbeat
(`setInterval` logueando un contador creciente, con un ID de contexto fijado
una vez por ejecución) corrió sin interrupción durante 417+ segundos,
incluyendo varios ciclos de aparición/desaparición del anuncio, siempre con el
mismo ID de contexto — el script nunca se reinicia ni se pierde.

La causa real es que el nodo del anuncio se pinta en un contexto que no
expone ninguna de las vías de recorrido de árbol disponibles desde JS (muy
probablemente un shadow root `closed` o un documento generado dinámicamente
vía `srcdoc`/`about:srcdoc` fuera del alcance de `contentDocument`). La única
forma de encontrarlo fue preguntarle directamente a Chromium qué está
pintando en cada punto de la pantalla con `document.elementFromPoint(x, y)`
— lo mismo que haría un toque/click real — en vez de confiar en la estructura
del árbol.

**Fix implementado (`WebPlayer.kt`, función `HOOK_SCRIPT`)**: tres capas de
defensa, cada una complementando a la anterior:

1. **`sweepOverlaysInDoc`** (recorrido de árbol, ya existente): detecta y
   elimina el falso captcha grande y los iframes-tracker (0-2px) mediante
   heurística de ancestro `position:fixed/absolute` + tamaño/`zIndex`,
   bajando por shadow-DOM e iframes same-origin. Sigue siendo necesario para
   estos dos casos, que el hit-testing no cubre bien (son demasiado grandes
   o están fuera de la grilla de muestreo en algunos frames).
2. **Bloqueo de red** (`shouldInterceptRequest`): lista `AD_HOSTS` (incluye
   ahora `janitorprecisiontrio.com` y `subduepaler.cyou`, dominios "doorway"
   confirmados sirviendo el banner) y `AD_URL_PATTERNS` (`/sn/pr/`,
   `zoneid=`). Reduce la frecuencia de aparición pero no la elimina del todo
   porque los dominios rotan.
3. **`hitTestSweep` / `pierceElementAt`** (defensa principal, nueva): cada
   1200ms recorre una grilla de 14×10 puntos sobre el viewport. Para cada
   punto, compara el elemento "superficial" (`document.elementFromPoint`,
   sin atravesar nada) con el elemento "perforado" (`pierceElementAt`, que
   repite la consulta dentro de `shadowRoot.elementFromPoint` con
   retargeting y de `iframe.contentDocument.elementFromPoint` con traducción
   de coordenadas). Si ambos difieren (`pierced == true`), el elemento
   superficial es el contenedor top-level del anuncio (normalmente un
   `<iframe>` o `<div>` inyectado) y se elimina con `.remove()`. Esto
   encuentra el nodo real sin depender en absoluto de recorrer el árbol.

**Validación**: dos pruebas de reproducción continua ≥200s con el código
final (una de 417s con heartbeat activo de por medio) mostraron el anuncio
apareciendo, siendo eliminado automáticamente
(log `JkAdBlock: overlay eliminado: hit-pierced:BODY#...` justo tras una
petición de imagen a `subduepaler.cyou`) y **sin volver a reaparecer** en el
resto de la prueba. Audio/vídeo (buffer pool `BufferPoolAccessor2.0`, timer
de progreso JS) y la navegación de D-pad (selección de servidor) siguieron
funcionando con normalidad durante y después del fix.

**Pendiente/sin cubrir**: los dominios de anuncio "doorway" seguirán
rotando; cuando aparezca uno nuevo, el hit-test sweep (capa 3) lo cubre sin
cambios de código, y el bloqueo de red (capa 2) es solo un refuerzo opcional
que requiere añadir el dominio nuevo a `AD_HOSTS` si se detecta.

## Estado de cambios sin commitear

A la fecha de este doc, lo siguiente está corregido y verificado en el
emulador pero **no commiteado** (último commit: `a0ef603`):

- Fix de foco inicial: `PosterCard.kt`, `HomeScreen.kt`, `DetailScreen.kt`.
- Fix "Cargar más" / chip "0 eps": `DetailScreen.kt`, `DetailViewModel.kt`.
- Fix selección de servidor sin reaccionar a Enter/DirectionCenter:
  `PlayerScreen.kt` (`onPreviewKeyEvent` → `onKeyEvent` en el `Box` raíz).
- Bloqueo de anuncios/overlays del reproductor (ver sección arriba):
  `WebPlayer.kt` — `sweepOverlaysInDoc` (tree-walk), `hitTestSweep` +
  `pierceElementAt` (hit-testing, defensa principal), `AD_HOSTS`/
  `AD_URL_PATTERNS` ampliados con `janitorprecisiontrio.com` y
  `subduepaler.cyou`. Código de diagnóstico temporal (heartbeat, `JKDUMP`,
  `JKHIT`, logging de `imagen vista:`) ya removido — solo queda lógica de
  producción.

## F4 — Favoritos y progreso (Room) — verificado

Room 2.8.5 + KSP, DB `jk-tv.db` (tablas `favorites` y `progress`).

Verificado en emulador:

- Favorito desde el detalle: botón `☆ Añadir a favoritos` → `★ En favoritos`;
  fila insertada en `favorites` con `slug`, `title`, `last_aired_episode`,
  `status_label`.
- Progreso desde el reproductor: `JkBridge.onProgress` se persiste cada ~5 s
  de avance real (no en cada `timeupdate`) y `onEnded` marca `completed`.
  Fila observada: `(sasaki-to-pii-chan-season-2, 4, 50638 ms, 1420001 ms)`.
- Reanudación: al reabrir el episodio el `<video>` salta a la posición
  guardada (`WebPlayerController.seekTo`), gateado a que `onReady` ya haya
  enganchado el `<video>`. Verificado: al reabrir, el progreso siguió desde
  50 s → 56 s en vez de empezar en 0.
- Home: botón `★ Favoritos` en la cabecera muestra la grilla de favoritos con
  badge `+N` de episodios pendientes (`last_aired_episode - last_watched_episode`).
- Detalle: botón `Continuar Ep N` cuando el último progreso no está completado.

Pendiente para F5: el badge `+N` depende de `last_aired_episode`, que hoy se
escribe al marcar el favorito; el sync periódico de WorkManager lo refrescará
para todos los favoritos.

## F5 — Sync periódico y badges de episodios pendientes — verificado

WorkManager 2.10.5 (2.12 exige minSdk 24 y el proyecto es minSdk 23).

- `FavoritesSyncWorker` (CoroutineWorker) recorre los favoritos y pide
  `repository.detail(slug, force = true)` de cada uno, actualizando
  `last_aired_episode`, `total_episodes` y `status_label` en Room. Un fallo
  individual no aborta el resto; solo se devuelve `Result.retry()` si fallan
  todos.
- Programación: `PeriodicWorkRequestBuilder(6, HOURS)` con constraint
  `NetworkType.CONNECTED` y `ExistingPeriodicWorkPolicy.KEEP` (si no se
  reiniciara el intervalo en cada arranque, un equipo que abre la app a diario
  nunca llegaría a ejecutarlo).
- Se programa desde `JkApp.onCreate()` (`android:name=".JkApp"` en el
  manifest).

Verificado en emulador:

- `dumpsys jobscheduler` muestra el job de WorkManager con
  `TIME=+5h59m55s` → intervalo de 6 h correcto.
- Ejecución forzada del worker: `JkSync: favoritos=1 fallos=0`,
  `Worker result SUCCESS`, y `favorites.synced_at` actualizado en Room.
- Badge `+N` en la grilla de favoritos = `last_aired_episode -
  last_watched_episode` (observado `+5` con el favorito de prueba).

## F7 — Búsqueda, detalle de dos columnas y reproductor con fallback — verificado

### Búsqueda en Programación

Filtra en memoria `sections.values.flatten()` (animes + donghuas + ovas) con
`searchKey()` = NFD + sin diacríticos + minúsculas, así que "majutsu" encuentra
"Majutsushi" y "pokemon" encuentra "Pokémon". No gasta red.

**Trampa:** un `BasicTextField` de una línea consume `DirectionDown` para mover
el cursor y el foco nunca baja a la grilla. Hay que interceptarlo en el padre
con `onPreviewKeyEvent` (fase pre, de padre a hijo) y pedir el foco del primer
resultado a mano. Verificado: `DPAD_DOWN` → foco en la primera tarjeta.

**Trampa:** con el IME en pantalla, el D-pad lo consume el teclado. En pruebas
automatizadas: `input text` → `KEYCODE_BACK` (cierra el IME) → `DPAD_DOWN`.

### Detalle

- Dos columnas: póster + `Estado/Tipo/Próx. cap/Capítulos` a la izquierda,
  lista de capítulos a la derecha (una sola columna, ordenable).
- Vistos en verde (`JkGreen = 0xFF3DDC84`), empezados en naranja con barra.
  Comprobado con muestreo de píxeles del `screencap`: insignia del ep. visto
  `(61,220,132)`, ep. en curso `(255,107,53)`.
- `Continuar Ep N` toma el último episodio **empezado y sin terminar**; si se
  coge el último con progreso a secas, al terminar el ep. 4 el botón llevaba
  otra vez al 4.

### Reproductor: servidores ocultos y fallback

- `jkplayer/um` = **Desu**, `jkplayer/umv` = **Magi** (verificado en dos
  animes distintos: son las dos pestañas que renderiza la página). Desu es el
  player por defecto.
- La lista de fuentes está oculta; se abre con `Usar otro servidor` o sola si
  el vigilante de 20 s no engancha un `<video>`.

**Trampa grande:** el `WebView` pide el foco al cargar y se queda con **todo**
el D-pad (Chromium navega su propio reproductor), dejando inalcanzables los
botones del overlay. `isFocusable = false` no basta: Chromium llama
`requestFocus()` por dentro. La solución es una subclase que rechaza
`requestFocus(direction, rect)` (la sobrecarga de la que depende
`View.requestFocus()`).

**Trampa mayor:** en el puente JS→Kotlin, una propiedad y un método con el
mismo nombre colisionan. `private val onReady: () -> Unit` + `fun onReady()`
hacen que `handler.post { onReady() }` llame al **método** (recursión infinita
en el hilo principal) en vez de a la lambda. Síntoma: el vídeo reproduce
(porque `onProgress` sí resolvía a la propiedad, al no encajar `Long, Long` con
`Double, Double`), pero `playerReady` nunca pasa a `true` y sale el aviso
"No cargó". Los callbacks se llaman `onReadyEvent`/`onEndedEvent` a propósito.

- Con el servidor caído (la web devuelve `500 Server Error` dentro del iframe)
  el aviso aparece a los 20 s y la lista queda abierta con el foco en el primer
  servidor; elegir Magi recarga y el aviso desaparece al enganchar el vídeo.

## F8 — Cabecera con menú lateral, buscador del sitio e iconos Material — verificado

### Home

- La cabecera ya no tiene la fila de botones (Animes/Donghuas/OVAs/Favoritos/
  Sync) ni el texto "Programación": queda `☰` + marca + campo de búsqueda,
  siempre visible y ocupando el ancho restante. Radio de 10 dp (antes píldora
  de 26 dp) e iconos Material (`Icons.Filled.Menu` / `Search`), no emojis.
- Los filtros viven en un `SideDrawer` de 260 dp sobre un velo negro al 60 %.
  Se abre con `☰`, se cierra con `BACK` (`BackHandler`) o al elegir una entrada.
  Al abrir, el foco va al primer ítem (`Animes`); al cerrar, la grilla lo
  recupera.
- La búsqueda **pega al buscador del sitio** (`GET /buscar/{q}`, ver
  `scraping.md` §5), no filtra en memoria. Debounce de 550 ms, mínimo 2 letras,
  caché de 10 min y `searchJob.cancel()` para que una respuesta vieja no pise a
  la nueva. Mientras carga se mantienen los resultados anteriores y el contador
  de la barra dice `buscando…`.
- `BACK` con una búsqueda activa la limpia (y vuelve a la sección) en vez de
  cerrar la app.

**Trampa:** el `BasicTextField` consume `DirectionLeft/Right` para el cursor, así
que un botón `✕` a la derecha del campo es **inalcanzable** con el D-pad. Por eso
limpiar es `BACK`, no un icono.

**Trampa:** al elegir una entrada del menú, el ítem enfocado desaparece del
árbol y el D-pad queda muerto. `HomeScreen` lleva un `focusNonce` que se
incrementa en cada selección y entra en la clave del `LaunchedEffect` que pide
el foco de la primera tarjeta (si no, con las mismas tarjetas el efecto no se
relanza). Si el contenido queda vacío (favoritos sin nada) el foco cae al botón
`☰`, que siempre existe.

**Trampa:** los resultados llegan por red con retraso. Si el usuario pulsa `▼`
antes de que existan, `pendingGridFocus` recuerda la intención y el foco baja a
la primera tarjeta en cuanto aparecen.

**Trampa:** el velo del menú **no** bloquea el foco geométricamente. Con el
menú abierto, `DPAD_RIGHT` desde un ítem puede aterrizar en una tarjeta de la
grilla de detrás; para salir del menú está `BACK` o elegir una entrada.

### Detalle

- El botón de orden pasó a flechas Material apiladas
  (`Icons.Filled.KeyboardArrowUp` + `KeyboardArrowDown`; `SwapVert` solo existe
  en `material-icons-extended`, que pesaría decenas de MB) pegadas al texto
  `Capítulos (N)`. El estado se lee en el color (naranja = descendente) y en la
  etiqueta `Nuevos primero` / `Antiguos primero`.
- La estrella de favoritos es `Icons.Filled.Star` en un círculo de 48 dp:
  gris si no está, naranja si está. `material-icons-core` no trae `StarBorder`.
- El `LaunchedEffect` que pide el foco inicial **no** incluye el orden en su
  clave: si lo incluyera, al tocar el icono el foco saltaba a la primera fila y
  no se podía volver a invertir sin navegar de nuevo.

Verificado en emulador: `☰` → menú con foco en `Animes` → `Donghuas` cierra y
recarga la grilla con foco en la primera tarjeta; `BACK` cierra el menú sin
cambiar de sección; `Favoritos` vacío deja el foco en `☰`; `Sync` abre la
pantalla de sincronización. Buscador: `naruto` → **23** fichas del sitio
(Boruto, Boruto the Movie, Naruto, Naruto Shippuden…), `shingeki` → 18,
`one` → 30 (el tope del sitio). `▼` baja a la primera tarjeta y `ENTER` abre el
detalle de un resultado remoto. `BACK` con búsqueda activa la limpia.

## F10 — Foco en el último capítulo visto y pantalla Directorio — verificado

### Detalle: foco inicial en el último episodio visto/en curso

- `Content()` en `DetailScreen.kt` ya no enfoca siempre `sorted.first()`.
  Calcula `initialFocusIndex`:
  1. Si hay `resumeEpisode` (empezado y sin terminar), ese episodio.
  2. Si no, el de número **más alto** entre los que tengan `completed == true`
     o `positionMs > 0` en `progressByEpisode`.
  3. Si no hay ninguno con progreso, el índice `0` (comportamiento anterior).
- El `FocusRequester` se asigna por índice dentro del `itemsIndexed` de la
  `LazyColumn` (`index == initialFocusIndex`), no solo a `index == 0`.
- La clave del `LaunchedEffect` incluye `initialFocusIndex` para que, si el
  progreso cambia (p. ej. tras ver un capítulo y volver), el foco se
  recalcule sin salir de la pantalla.

**Verificado con datos inyectados en Room** (ep. 9 `completed=true`, ep. 10
`positionMs=300000/1200000` ≈ 25 %, sobre un anime de 12 capítulos ordenado
"Nuevos primero" con el 12 arriba): el foco cae en **Episodio 10** (borde
naranja, barra de progreso al 25 %), no en el Episodio 12. El botón
"Continuar Ep 10" aparece correctamente junto al título de la sección.
Confirmado con captura (`screencap`, ya que Compose for TV no siempre marca
`focused="true"` en el dump de `uiautomator` aunque el resaltado visual sí
esté presente).

### Directorio: nueva entrada en el menú, filtros reales del sitio

- `SideDrawer` (`HomeScreen.kt`) ya no tiene las entradas `Donghuas`/`OVAs`:
  queda solo `Animes` (quick filter de Programación) + una entrada nueva
  `Directorio` (icono `Icons.Filled.List`) que navega a `DirectorioScreen` vía
  `Routes.DIRECTORIO` en `JkNavHost`.
- `DirectorioScreen` + `DirectorioViewModel` cargan `GET /directorio` (ver
  `scraping.md` §6): panel de filtros a la izquierda (`FiltersPanel` con un
  `TvDropdown` por cada `<select>` del sitio — Ordenar por, Género, Letra,
  Demografía, Categoría, Tipo, Estado, Año, Temporada, Dirección) y grilla de
  resultados a la derecha con paginación (`Anterior`/`Siguiente`).
- Cualquier cambio de filtro reinicia la paginación a la página 1
  (`setFilter` → `loadPage(1)`); `clearFilters()` vacía todos los filtros.
- `TvDropdown` es un desplegable D-pad-friendly: se expande al recibir foco
  (`onFocusChanged`), cada opción es un ítem clickeable con check (`Icons.
  Filled.Check`) en la seleccionada.

Verificado en emulador: `☰` → `Directorio` abre la pantalla con los 10
filtros del sitio y una grilla con animes reales (`Cardfight!! Vanguard:
Divinez…`, `Demons' Crest`, `Watashi no Shiawase na Kekkon Special`…) con su
tipo (Serie/ONA) y estado (En espera/En emisión/Concluido) tal como vienen
del sitio.

## Emparejamiento Supabase (pendiente de F6) — verificado 2026-11-08

Los dos pendientes de Supabase quedaron cerrados:

1. **Bloque SQL de emparejamiento ejecutado** en el SQL Editor del proyecto
   `imbquhejcckjgyxtgurw` (`sync_pairings` + `sync_new_pairing` + `sync_join`
   + grants, líneas 248-342 de `docs/supabase/schema.sql`). Verificado con
   curl: `sync_join` con código inválido → `invalid_pairing_code`;
   `sync_new_pairing` con credenciales malas → `invalid_credentials` (antes
   ambos devolvían `PGRST202`).
2. **Clave publishable** (`sb_publishable_…`) reemplazada en
   `local.properties` — antes había una `sb_secret_…`, que salta RLS y no
   debe viajar en el APK. `:app:assembleDebug` recompilado con la nueva.

Flujo completo probado en el emulador (Sync):

- **`sync_new_pairing` desde la app**: "Vincular otro dispositivo" →
  `pairing ok: 54845918` (código de 8 dígitos, caduca en 15 min).
- **`sync_join` vía curl**: con `54845918` devolvió
  `{"code":"07951576","token":"3d3c…"}` (el espacio correcto); un segundo
  uso del mismo código → `invalid_pairing_code` (un solo uso ✓).
- **`sync_join` desde la app**: "Desvincular" → modo "¿Primer dispositivo?"
  → "Ya tengo un código" → teclado en pantalla → `84076138` →
  **"Dispositivo vinculado"** con el espacio `0795 1576` recuperado.
  (Ojo: `input text` no llega a este formulario — hay que teclear con taps
  en el keypad numérico de la pantalla.)
- **Sync post-re-vinculación**: "Sincronizar ahora" →
  `Sincronizado: 6 favoritos, 7 progresos`.

### Trampa: bounds de texto obsoletos en `uiautomator dump`

En la pantalla Sync, tras cambiar el estado (aparece/desaparece la fila del
código de emparejamiento), los `bounds=` de los nodos de **texto** no
enfocados quedaron desfasados ~115 px respecto a la posición real (decían
`y=468` para "Sincronizar ahora" cuando el botón estaba en `y=575-671`), y
los taps en esas coordenadas no caían en el botón. El `bounds` del nodo
**contenedor con foco** sí era correcto. Regla: si un tap "no hace nada",
validar la posición real con `screencap` + recorte PIL de la franja antes
de reintentar.

---

## F11 — Perfiles locales tipo Netflix (multiusuario) — verificado 2026-11-08

Varios usuarios en la MISMA TV: cada perfil es un `owner` de su biblioteca
en Room y de su propio sync space. Sin servicios externos (Google Cloud
quedó descartado por ahora; el login por cuenta se puede añadir después
sobre este mismo sistema).

### Cambios

- **Room v2 → v3** (`MIGRATION_2_3`): SQLite no permite cambiar la PK con
  ALTER TABLE, así que `favorites`/`progress` se **recrean** con `owner` como
  primera columna de la PK (`PRIMARY KEY(owner, slug)` / `(owner, slug,
  episode)`); las filas existentes quedan como `owner = 0` (modo legacy) y
  se crea la tabla `profiles` (name, pin_hash, space_code, space_token,
  created_at, last_used_at).
- **`ProfileManager`** (nuevo): perfil activo en SharedPreferences
  (`jk-profiles` / `active_profile_id`), `create()` con `claimLegacy()` del
  primer perfil, `activate()`, `delete()` (borra solo datos locales y
  activa el siguiente perfil si borraba el activo), `setSpace/clearSpace`.
- **`LibraryRepository`**: todas las lecturas son
  `profiles.activeId.flatMapLatest { dao...(owner) }` → cambiar de perfil
  re-emite Home/Detalle/Sync sin tocar ViewModels. Escrituras con
  `profiles.owner()`; `merge*` hace `remote.copy(owner = …)` (el JSON
  remoto llega con `owner=0` por defecto en las entities).
- **`SyncEngine`**: credenciales por perfil (`profiles.setSpace`); el
  primer perfil **hereda** el espacio legacy del dispositivo y limpia
  `jk-sync.xml`. `currentCredentials()` ahora es `suspend`.
- **UI**: entrada "Perfiles" en el drawer (icono `AccountBox`), ruta
  `Routes.PROFILES`, pantalla `ProfilesScreen` (crear/activar/eliminar con
  confirmación).

### Verificación en emulador (sobre DB v2 con datos reales)

1. **Migración**: instalación sobre la DB v2 → `PRAGMA user_version = 3`,
   7 favoritos + 7 progresos conservados con `owner = 0`, tabla `profiles`
   creada, app arranca sin crash.
2. **Primer perfil ("Alejoa")**: al crearlo reclamó los 7+7 filas
   (`owner=1`, `owner=0` → 0) y, al abrir Sync, `currentCredentials()`
   migró el espacio `07951576` al perfil (`profiles.space_code` seteado,
   `jk-sync.xml` sin code/token).
3. **Sync por perfil real**: "Sincronizar ahora" → `last_sync_at`
   actualizado con el espacio del perfil.
4. **Segundo perfil ("Luis")**: biblioteca vacía ("Aún no tienes
   favoritos"), Sync muestra "Crear espacio de sync / Ya tengo un código"
   (sin vincular).
5. **Cambio en caliente**: activar Alejoa desde su fila → sus 7
   favoritos reaparecen en Home sin recargar (flatMapLatest).
6. **Borrar perfil**: eliminar → confirmar → el siguiente queda activo
   (bug encontrado y corregido: antes quedaba `active=0` y la biblioteca
   se veía vacía).

### Trampas nuevas de F11

- **`Modifier.focusRequester()` DEBE ir ANTES de `clickable`** (del focus
  target) en la cadena de modificadores. Si va después,
  `requestFocus()` devuelve `false` silenciosamente y el foco nunca llega
  a la fila: el D-pad queda "atrapado". Síntoma en logcat
  (`JkProfiles`): `no se pudo enfocar el objetivo` en el `LaunchedEffect`
  de entrada.
- **Al enfocar el `BasicTextField` el IME se abre solo** y se traga
  DPAD_DOWN/CENTER (trampa ya conocida). Solución en `ProfilesScreen`:
  `LocalSoftwareKeyboardController.hide()` cuando el foco vuelve a la
  lista, e `onPreviewKeyEvent` en el campo que intercepta
  `DirectionUp` (→ primera fila) y `DirectionDown` (→ botón Crear, o a la
  lista si el botón está deshabilitado).
- **Race al entrar**: `LaunchedEffect(profiles.isEmpty())` arranca con la
  lista vacía (el `initialValue` de `collectAsStateWithLifecycle`), enfoca
  el campo, y Room emite después → el efecto se relanza con la clave
  cambiada y debe poder mover el foco a la fila (si no puede, ver arriba).
- **`collectAsStateWithLifecycle` sobre un `Flow` que no es `StateFlow`**
  exige `initialValue` explícito.
- La UI de la app usa **`androidx.tv.material3`** (`MaterialTheme`, `Text`),
  no `androidx.compose.material3` — importar el equivocado da
  `Unresolved reference` en cadena.

## Puerta de entrada de perfiles (gate tipo Netflix) — verificado 2026-11-08

Extensión de F11: al arrancar, si no hay perfil activo (`active_profile_id
== 0`) la app no entra directo a Home — pide/selecciona perfil primero,
igual que Netflix.

### Cambios

- **`ProfileGateScreen`** (nuevo, `feature/profiles/`): 3 modos internos
  (`LOADING`, `CHOOSER`, `CREATE`), decididos al observar
  `profiles.profiles` (lista de `ProfileManager`):
  - **0 perfiles** → `CREATE`: formulario "Crea tu perfil para empezar" /
    "El primer perfil tomará tus favoritos y progreso actuales." (reutiliza
    `ProfileManager.create()`, que hace `claimLegacy()` del `owner=0` +
    hereda el espacio de sync del dispositivo).
  - **1 perfil** → autoactivación silenciosa (`activate()` sin UI) y
    navegación directa a Home.
  - **2+ perfiles** → `CHOOSER`: "¿Quién está viendo?" con tiles (inicial
    del nombre en círculo) + tile "+ Nuevo perfil" al final; elegir uno
    llama `activate()` y navega a Home.
- **`JkNavHost`**: nueva ruta `Routes.PROFILE_GATE`; el `startDestination`
  ya no es fijo — se calcula leyendo
  `Graph.profiles(context).activeId.value` de forma síncrona al componerse:
  `PROFILE_GATE` si es `0`, `HOME` en otro caso.
- Mismo patrón de foco que `ProfilesScreen`: `focusRequester()` antes de
  `clickable` en cada tile; IME con `hide()` + `onPreviewKeyEvent` en el
  formulario de creación.

### Verificación en emulador (3 escenarios, manipulando `profiles` +
`jk-profiles.xml` directamente en la DB del paquete)

- **Test B — 2 perfiles, ninguno activo**: selector "¿Quién está viendo?"
  con tiles "L"/"A"/"+ Nuevo perfil" renderizado correctamente; elegir
  "Alejoa" activa el perfil y entra a Home con sus 7 favoritos.
- **Test C — 1 perfil, ninguno activo**: autoactivación sin preguntar nada,
  entra directo a Home con la biblioteca correcta del único perfil.
- **Test D — 0 perfiles**: aparece el formulario `CREATE`. Flujo completo
  con D-pad: `input text` para escribir el nombre, `KEYCODE_BACK` para
  cerrar el IME sin perder el texto, `DPAD_DOWN` mueve el foco al botón
  "Crear perfil" (confirmado con `uiautomator dump`, no solo visualmente),
  `DPAD_CENTER` crea el perfil y navega a Home automáticamente.

### Trampa nueva encontrada en Test D

- **`input text` puede concatenar sobre texto previo del IME** (sugerencias
  de autocompletar u otro estado residual del campo) en vez de reemplazarlo:
  en la prueba apareció `"macastilTestD"` en lugar de `"TestD"` al escribir
  sin limpiar el campo primero. No es un bug de la app — es el
  comportamiento de `adb shell input text` sobre un `BasicTextField` con
  contenido/sugerencias previas. Mitigación en pruebas: limpiar el campo
  con varios `KEYCODE_DEL` (o `KEYCODE_BACK`+reabrir) y confirmar con
  `screencap` que quedó vacío antes de `input text`.
- **"Claim legacy" solo reclama `owner = 0`.** Si se simula "0 perfiles"
  vaciando la tabla `profiles` de una DB que ya tenía un perfil real
  (datos en `owner = 1`, no `owner = 0`), el perfil nuevo creado por el
  gate arranca con biblioteca **vacía** — no es un bug, el legacy real de
  una instalación fresca sí vive en `owner = 0`. Para simular "0 perfiles"
  de forma fiel a una instalación nueva, los favoritos/progreso de prueba
  también deben estar en `owner = 0`, no solo vaciar `profiles`.

## Sync automático al entrar a un perfil (F11.2) — verificado 2026-11-08

Hueco encontrado tras F11.1: entrar a un perfil desde el gate
(autoactivación, elegir en el chooser, o crear uno nuevo) no disparaba
ningún sync — el único pull/push automático era el de `JkApp.onCreate()`,
que corre **antes** de que el gate decida nada (con el `activeId` que
hubiera en ese instante, normalmente `0`). El resultado: el perfil podía
quedarse mostrando solo lo que había en Room local hasta sincronizar a
mano o esperar el ciclo de 6 h del worker periódico — no es el
comportamiento esperado tipo Netflix ("elijo perfil → veo lo último").

### Decisión

Auto-sync en background sin bloquear la navegación: se dispara
`syncNow()` justo después de `activate()`/`create()`, pero se navega a
Home de inmediato (no se espera el resultado). Si hay cambios remotos,
los favoritos/progreso se actualizan solos en pantalla gracias al
`flatMapLatest` ya existente de `LibraryRepository` — no hace falta
ningún estado de "sincronizando" en el gate.

### Cambios

- **`Graph.appScope`** (nuevo): `CoroutineScope(SupervisorJob() +
  Dispatchers.IO)` a nivel de aplicación, para que el sync no se cancele
  cuando la pantalla que lo dispara (p. ej. `ProfileGateScreen`) sale de
  composición al navegar. `Graph.syncInBackground(context)`: azúcar sobre
  `appScope.launch { runCatching { sync(context).syncNow() } }`.
- **`JkApp.onCreate()`**: simplificado para reutilizar
  `Graph.syncInBackground(this)` en vez de tener su propio
  `CoroutineScope` duplicado.
- **`ProfileGateScreen`**: los 3 flujos de entrada (autoactivación con 1
  perfil, elegir uno en el chooser, crear uno nuevo) llaman
  `Graph.syncInBackground(context)` justo después de
  `activate()`/`create()` y antes de `onReady()`.
- **`ProfilesScreen`**: por consistencia, cambiar de perfil manualmente
  desde la pantalla de gestión también dispara `syncInBackground()`.

### Verificación en emulador

Escenario "1 perfil, sin activo" (prefs con `active_profile_id=0`,
perfil "Alejoa" real con espacio `07951576`): se leyó `last_sync_at` de
`shared_prefs/jk-sync.xml` antes y después de reiniciar la app — cambió
de `1794183999754` a `1794185823408`, confirmando que `syncNow()` corrió
automáticamente al autoactivar el perfil. La app entró a Home sin demora
visible (navegación no bloqueada por el sync).

## Tarjetas Material Design 3 en el gate de perfiles — verificado 2026-11-08

Pedido explícito del usuario: mostrar y crear perfiles con tarjetas
estilo Material Design 3, en vez de los `Box` + `background` + `border` +
`clickable` manuales de F11/F11.1.

### Cambios

- **`ProfileTile`/`NewProfileTile`** (`ProfileGateScreen.kt`): reescritos
  sobre `androidx.tv.material3.Card` (no `ClassicCard`/`CompactCard` —
  los tiles solo muestran una inicial o "+", no una imagen). `Card` recibe
  `onClick`/`interactionSource` directamente, así que ya no hace falta
  `.clickable(...)` aparte. Estilo vía:
  - `CardDefaults.shape(shape = RoundedCornerShape(12.dp))`
  - `CardDefaults.colors(containerColor = JkSurface, contentColor = ...,
    focusedContainerColor = JkSurfaceHigh, focusedContentColor = JkOrange)`
  - `CardDefaults.scale(focusedScale = 1.05f)`
  - `CardDefaults.border(focusedBorder = Border(border =
    BorderStroke(3.dp, JkOrange), shape = RoundedCornerShape(12.dp)))`
  - `CardDefaults.glow(focusedGlow = Glow(elevationColor = JkOrange,
    elevation = 8.dp))`
  - El `focusRequester()` del tile `Alejoa`/primer perfil sigue yendo
    **antes** del `Card` en la cadena de modificadores (misma trampa F11:
    después de un componente clickable, `requestFocus()` falla en
    silencio).
- **Formulario `Mode.CREATE`**: envuelto en un `androidx.tv.material3.Surface`
  (variante no interactiva, vía `SurfaceDefaults.colors(containerColor =
  JkSurface, contentColor = JkTextPrimary)`, `shape = RoundedCornerShape(16.dp)`,
  `modifier = Modifier.fillMaxWidth(0.6f)`), reemplazando el `Box` +
  `background` suelto de antes. El `Column` interior pasa a
  `padding(32.dp)`; el fondo del `BasicTextField` cambia de `JkSurface` a
  `JkSurfaceHigh` para que se note dentro de la nueva `Surface`; el ancho
  del campo pasa de `fillMaxWidth(0.5f)` a `fillMaxWidth()` (ya es relativo
  al ancho de la `Surface`, no al de toda la pantalla).
- API de `tv-material3` 1.1.0 confirmada por `javap` sobre el aar
  decompilado y por `developer.android.com`:
  `Border(border: BorderStroke, inset: Dp, shape: Shape)` (constructor
  secundario público) y `Glow(elevationColor: Color, elevation: Dp)`
  (constructor público añadido en 1.1.0) — ambos usados directamente, no
  vía `.None`.

### Verificación en emulador

Con un segundo perfil de prueba ("TestCard") insertado directo en
`jk-tv.db` y `active_profile_id=0` (fuerza el chooser con 2+ perfiles):

- `screencap` confirmó las 3 `Card` del chooser (Alejoa, TestCard, "+
  Nuevo perfil") con esquinas redondeadas; la tarjeta con foco muestra
  borde naranja + glow, las demás quedan en `JkSurface` plano.
- `DPAD_RIGHT` repetido movió el foco correctamente entre las 3 `Card`
  (confirmado por screencap en cada paso).
- `DPAD_CENTER` sobre "+ Nuevo perfil" abrió el formulario dentro de la
  nueva `Surface` — panel elevado y redondeado, visualmente distinto del
  fondo, en vez del bloque suelto anterior.
- Escrito el nombre "CardMD3", `KEYCODE_BACK` cerró el IME sin perder el
  texto, y `DPAD_DOWN` bajó el foco al botón "Crear perfil" — confirmado
  con `uiautomator dump` (`focused="true"` sobre el nodo del botón).
- `DPAD_CENTER` sobre "Crear perfil" creó el perfil y navegó a Home de
  inmediato (sin regresión del auto-sync de F11.2).
- `./gradlew :app:assembleDebug` y `./gradlew test` → `BUILD SUCCESSFUL`.
- Restauración: DB y `shared_prefs/jk-profiles.xml` del emulador
  restaurados desde `jk-tv-backup.db` (perfil "Alejoa" único, 7
  favoritos, espacio `07951576`) — confirmado con `screencap` y lectura
  directa de la DB restaurada.

---

## Fix MD3 del panel de filtros en Directorio — verificado 2026-11-08

Reporte del usuario: "el menú de buscar animes de directorio se ve mal".
Se auditaron visualmente (vía `screencap`) todas las pantallas/menús
principales — Home, cabecera + buscador, `SideDrawer`, Detail, Sync — y
**solo Directorio** tenía un problema real.

### Diagnóstico

`JkSurface` (`#17171C`) y `JkBackground` (`#0E0E11`) son tonos casi
idénticos: el `FiltersPanel` (`Box` + `background(JkSurface)` +
`border(1.dp, JkSurfaceHigh)`) se fundía visualmente con el fondo general,
sin relieve. Además el trigger de `TvDropdown` solo pintaba borde cuando
tenía foco (`width = if (buttonFocused) 2.dp else 0.dp`): en reposo se
veía como texto suelto con una flechita, no como un control.

### Fix

Mismo patrón MD3 ya validado en F11.3 (`ProfileGateScreen.kt`):

- `FiltersPanel`: el `Column` con `background`/`border` manual pasó a
  `androidx.tv.material3.Surface` con
  `SurfaceDefaults.colors(containerColor = JkSurfaceHigh, ...)` — token
  más claro que `JkSurface`, así el panel se eleva del fondo con
  contraste real, no solo con un borde de 1dp invisible.
- `TvDropdown` (trigger): el `Box` + `clickable` pasó a
  `androidx.tv.material3.Card` con `CardDefaults.border(border = ...,
  focusedBorder = ...)` — a diferencia de F11.3 (que solo definía
  `focusedBorder`), aquí se definió también el `border` de reposo
  (`Border(BorderStroke(1.dp, JkSurfaceHigh), ...)`) para que el control
  se vea como tal incluso sin foco. El foco sigue resaltando con borde
  naranja de 2dp + texto/icono naranja, igual que antes.
- La lista de opciones expandida (`Column` con fondo `JkSurfaceHigh` +
  borde naranja) no cambió: ya tenía buen contraste.

### Verificación en emulador

- `./gradlew :app:assembleDebug` y `./gradlew test` → `BUILD SUCCESSFUL`.
- `screencap` antes/después del `FiltersPanel`: el panel pasó de verse
  plano/fundido con el fondo a tener un contenedor `JkSurfaceHigh`
  claramente diferenciado, con los 5 dropdowns (`Ordenar por`, `Género`,
  `Letra`, `Demografía`, `Categoría`) visibles como controles reales.
- `DPAD_LEFT` sobre el primer dropdown (`Ordenar por`): borde naranja +
  texto naranja en foco, lista expandida (`Por fecha`/`Por nombre`/`Por
  popularidad`) con el ítem seleccionado resaltado — sin regresión del
  comportamiento de expandir/colapsar al ganar/perder foco.
- `DPAD_DOWN` + `DPAD_CENTER` sobre una opción: seleccionó y colapsó el
  dropdown correctamente (misma lógica de `onSelect`/`expanded.value`, sin
  cambios funcionales, solo visuales).

---

## F12 — Avatares de perfil en Home + selector reentrante con borrado — verificado 2026-11-09

Pedido explícito del usuario: identificar perfiles con avatares, mostrar el
avatar del perfil activo en Home, sacar "Perfiles" del menú lateral de Home
(el usuario debe salir de Home para cambiar/crear perfil) y permitir borrar
perfiles desde el propio chooser de tarjetas.

### Cambios

- **`ProfileAvatar.kt`** (nuevo, `feature/profiles/`): `avatarColorFor(profileId)`
  devuelve un color determinista de una paleta fija según el id del perfil
  (sin cambio de esquema en `ProfileEntity`, sin migración de Room — el
  color se deriva en cada render). `ProfileAvatar(name, profileId, modifier,
  size)` composable circular con la inicial del nombre, reutilizado en el
  header de Home y en las tarjetas del chooser.
- **`HomeScreen.kt`**: "Perfiles" eliminado del `SideDrawer`. El `Header`
  ahora muestra un `ProfileAvatarButton` (44.dp) junto al campo de búsqueda
  con el avatar del perfil activo; al pulsarlo navega a `PROFILE_SWITCH`.
- **`ProfileGateScreen.kt`**: pasa a ser de doble propósito —
  - gate inicial de arranque (`startInChooser = false`, comportamiento de
    F11/F11.1 sin cambios: 0 perfiles → formulario, 1 perfil → autoactivar,
    2+ → chooser).
  - pantalla reentrante de cambio de perfil desde Home
    (`startInChooser = true`, con `onBack: () -> Unit` para volver).
  - nuevo modo edición (`editMode`/`confirmDeleteId`): botón lápiz/check en
    la cabecera; en modo edición cada `ProfileTile` muestra una insignia "X"
    roja (`JkRed`, nuevo color en `Theme.kt`) en la esquina; al tocar la
    tarjeta se reemplaza su contenido por una confirmación inline
    ("¿Eliminar "<nombre>"?" + botones Eliminar/Cancelar) sin salir de la
    pantalla. Si se borra el último perfil, cae automáticamente a
    `Mode.CREATE`.
- **`ProfilesScreen.kt` eliminado** — completamente reemplazado por
  `ProfileGateScreen`.
- **`JkNavHost.kt`**: nueva ruta `Routes.PROFILE_SWITCH` sustituye a la
  antigua `Routes.PROFILES`; `onOpenProfiles` de `HomeScreen` navega ahí.

### Bug encontrado y corregido: insignia de borrado invisible

La "X" roja de borrado no se veía en pantalla aunque el nodo existía en el
árbol de UI (bounds correctos) y el color de fondo era el esperado por
debajo del `Card`. Causa: el `Card` de `tv-material3` con `Glow` (elevación
de foco) se repinta por encima de hermanos dentro del mismo `Box` padre,
tapando el badge. Fix: `.zIndex(1f)` en el `Box` del badge (import
`androidx.compose.ui.zIndex`), confirmado visualmente tras rebuild — mismo
patrón a vigilar en cualquier otro overlay sobre un `Card` con `Glow`.

### Verificación en emulador

- Avatar en el header de Home confirmado visible (círculo verde "A"),
  bounds `[1780,32][1876,128]`; `input tap` en el centro navega siempre a
  `PROFILE_SWITCH` ("¿Quién está viendo?").
- `SideDrawer` confirmado sin "Perfiles": solo `Animes`, `Directorio`,
  `Favoritos`, `Sync`.
- Modo edición: tap en el lápiz muestra la insignia roja en cada tarjeta y
  cambia el texto de ayuda a "Elige un perfil para eliminarlo.".
- Tap en una tarjeta en modo edición → confirmación inline con nombre real
  del perfil + Eliminar/Cancelar.
- "Cancelar" restaura la tarjeta sin borrar nada.
- "Eliminar" confirmado end-to-end sobre un perfil de prueba ("TestD",
  creado durante esta misma sesión de pruebas): tras confirmar, el perfil
  desaparece del chooser y solo queda el perfil original ("Alejoa").
- Salir del modo edición (tap en el check) hace desaparecer la insignia y
  vuelve a mostrarse la tarjeta "+ Nuevo perfil".
- Creación de perfil desde el chooser reentrante verificada end-to-end:
  "+ Nuevo perfil" → formulario `Mode.CREATE` → `input text` + `ENTER` →
  perfil creado y vuelta directa a Home con el nuevo avatar activo.
- `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL` en cada iteración
  (incluida la del fix de `zIndex`).
- Estado final del emulador restaurado a un solo perfil ("Alejoa", avatar
  verde "A") tras borrar el perfil de prueba usado en la verificación.

### Detalle menor de foco (no bloqueante)

Tras confirmar o cancelar un borrado, el foco del D-pad no vuelve a la
tarjeta de origen sino al botón lápiz/check de la cabecera (navegable con
`DPAD_DOWN`/`DPAD_LEFT`/`DPAD_RIGHT` igual, solo cambia de dónde parte).
No afecta la funcionalidad; pendiente de pulir si se quiere exactitud de
foco perfecta.

## F13 — Sync al crear perfil + menú del avatar en Home — verificado 2026-11-09

Pedido explícito del usuario: al crear un perfil preguntar si se quiere
sincronizar (crear espacio nuevo o entrar un código existente), y mover todas
las acciones de sync al menú que abre el avatar de Home (ver código, vincular,
desvincular, re-sincronizar), con iconos. La pantalla `Sync` del menú lateral
desaparece.

### Cambios

- **`ProfileGateScreen.kt`** — nuevo `Mode.SYNC_CHOICE`, al que se pasa
  **después** de crear un perfil (antes se llamaba `onReady()` de inmediato).
  Contenido: "¿Quieres sincronizar este perfil?" con `Crear espacio de sync`
  (primario), `Tengo un código de vinculación`, `Ahora no` (`onSkip` →
  `onReady()`) y `Atrás` (vuelve al formulario `Mode.CREATE`).
  - Sub-modo interno `joining` (estado local `var joining by remember`): al
    pulsar "Tengo un código" se muestra el teclado numérico en la misma
    pantalla. Antes la condición era `syncCode.isNotBlank()`, así que el
    teclado **nunca aparecía**.
  - `syncLinked` (estado del padre): tras crear espacio o vincular con éxito
    la pantalla cambia a "Perfil sincronizado" + `Continuar`, mostrando el
    código generado para que el usuario lo apunte.
  - `SyncKeypad` acepta `focusRequester` (se aplica a la tecla "1") y
    `onCancel`.
- **`HomeScreen.kt`** — menú del avatar (`AvatarMenu`) con
  `AvatarMenuMode { MAIN, JOIN_CODE }`:
  - **Con espacio**: código en grande (`0795  1576`) + `Sincronizar ahora`,
    `Generar código de vinculación`, `Desvincular`, `Cambiar perfil`,
    `Cerrar`.
  - **Sin espacio**: "Este perfil no está vinculado a la nube." +
    `Crear espacio de sync` / `Tengo un código de vinculación` + `Cambiar
    perfil` / `Cerrar`. Es el mismo estado al que se cae **después de
    desvincular**, que es justo lo pedido: al desvincular se vuelve a
    preguntar si crear código nuevo o ingresar uno existente.
  - **`JOIN_CODE`**: teclado numérico + `Vincular` / `Volver` (código de
    vinculación de 8 dígitos, no el código del espacio).
  - `Sync` eliminado del `SideDrawer`; el `Header` ya no recibe `onOpenSync`
    ni `onOpenProfiles` (solo `onOpenAvatarMenu`).
- **`JkNavHost.kt`**: eliminadas `Routes.SYNC` y su `composable`;
  `HomeScreen` recibe `onOpenDirectorio` + `onOpenProfileSwitch`.
- **`feature/sync/SyncScreen.kt` eliminado**: quedó inalcanzable y su
  funcionalidad vive ahora en el menú del avatar.

### Bugs encontrados y corregidos (todos vistos en emulador)

1. **El overlay no cubría la cabecera**: el menú se renderizaba dentro del
   `Header`, dentro de la `Column`, así que se recortaba y "Cerrar" quedaba
   fuera. Fix: moverlo al `Box` raíz de `HomeScreen`.
2. **El D-pad se escapaba al fondo**: con el menú abierto, `DPAD_DOWN` viajaba
   al botón hamburguesa de detrás y el menú quedaba inutilizable. Un `Box`
   overlay **no** confina el foco en TV. Fix: envolver en
   `androidx.compose.ui.window.Dialog` con
   `DialogProperties(usePlatformDefaultWidth = false)`. Verificado: 3×
   `DPAD_DOWN` dejan el foco en "Cerrar" dentro del diálogo.
3. **Panel ilegible sobre la grilla**: sin fondo sólido el texto se mezclaba
   con los pósters. Fix: `androidx.tv.material3.Surface` con
   `SurfaceDefaults.colors(containerColor = JkSurface)` sobre scrim
   `Color.Black.copy(alpha = 0.82f)`, `RoundedCornerShape(16.dp)`.
4. **Botones aplastados en 1080p**: en `SyncChoiceScreen` y en el teclado del
   menú, "Vincular"/"Volver" quedaban con 5 px de alto (texto recortado) y
   "Ahora no"/"Atrás" fuera del panel. Causa: el `Column` desbordaba el alto
   disponible (1080p @ density 2 = 540 dp). Fix: `Vincular` + `Volver` en una
   sola `Row` (solo aparece `Vincular` con 8 dígitos), panel `0.55f` de ancho,
   paddings 24 dp, y en el menú del avatar **se oculta el bloque
   avatar+nombre mientras se teclea** (`mode != JOIN_CODE`).
5. **Mensaje de estado fuera del panel**: el `syncStatus` iba al final del
   `Column`, así que con el teclado visible el error
   (`invalid_pairing_code`) no se veía. Fix: en modo `joining` el estado se
   pinta justo debajo del código.
6. **Estado obsoleto al reabrir el teclado**: el menú seguía mostrando
   "Desvinculado" encima del teclado. Fix: `onModeChange` limpia
   `avatarMenuStatus` al entrar en `JOIN_CODE`.
7. **Botones alineados a la izquierda**: los `Column` de cada modo no tenían
   `horizontalAlignment`, así que las filas de botones arrancaban pegadas al
   borde del panel. Fix: `fillMaxWidth()` +
   `Alignment.CenterHorizontally` en cada bloque.

### Trampas de prueba confirmadas (ya conocidas, re-confirmadas aquí)

- **El código del espacio NO sirve para vincular**: `joinSpace` espera un
  **código de vinculación** de un solo uso y 15 min (`createPairingCode` →
  `sync_new_pairing`). Meter `0795 1576` devuelve
  `No se pudo vincular: invalid_pairing_code` — comportamiento correcto, pero
  de ahí que las etiquetas digan "código de vinculación".
- **`uiautomator dump` no es fiable para el foco en Compose for TV**: marca
  `focused="true"` en un nodo que no es el que realmente tiene el foco de
  Compose. Guiarse por la captura (borde naranja) y no por el dump.
- **"Touch mode" de Android**: tras un `input tap`, el **primer** evento de
  D-pad solo saca la ventana del modo táctil y no se entrega al componente.
  En pruebas con adb hay que enviar una tecla "de más" (o pulsar dos veces)
  antes de dar por fallido un botón. Con un mando real no ocurre.
- **Los taps sobre botones de TV a veces no disparan `onClick`**; `DPAD_*` +
  `DPAD_CENTER` sobre el foco confirmado es más fiable.
- **Las coordenadas cambian al aparecer la línea de estado**: el panel crece y
  todo se desplaza ~56 px; recalcular bounds después de cada acción.

### Verificación end-to-end en emulador

- Crear perfil → aparece "¿Quieres sincronizar este perfil?" con las 4
  acciones completas y visibles (captura con `Ahora no` y `Atrás` dentro del
  panel).
- "Tengo un código" → teclado numérico (`Introduce el código`, `····  ····`),
  foco en la tecla "1"; los 8 dígitos pintan `0795  1576` y aparecen
  `Vincular` + `Volver` en la misma fila con texto legible.
- "Vincular" con el código del espacio → `No se pudo vincular:
  invalid_pairing_code` (mensaje visible bajo el código).
- Avatar de Home → menú modal con avatar, nombre y código `6736 9973`.
- "Crear espacio de sync" desde el menú → espacio creado y código mostrado en
  el acto (`Espacio creado: 6736 9973`) sin reiniciar (el
  `activeProfile` Flow de `ProfileManager` re-emite).
- "Sincronizar ahora" → `Sincronizado: 0 favoritos, 0 progresos`.
- "Desvincular" → `Desvinculado` y el menú cae al estado sin espacio con
  `Crear espacio de sync` / `Tengo un código de vinculación` (misma lógica que
  al crear el perfil).
- "Tengo un código de vinculación" en el menú → teclado con `Código de
  vinculación`, `Vincular` y `Volver` completos (panel más ancho y sin el
  bloque de avatar).
- Vincular con un código de vinculación real generado antes →
  `Dispositivo vinculado` y el menú vuelve a `MAIN` mostrando el código del
  espacio.
- `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL` en cada iteración.
- Emulador restaurado a un solo perfil ("Alejoa", espacio `07951576`, 7
  favoritos y 7 progresos intactos) tras borrar `Test3`, `Test2a` y
  `SyncTest` en el modo edición del chooser.

## F14 — Rediseño de controles + paginado del Directorio — verificado 2026-11-09

Pedido explícito del usuario: "mejora el diseño de los botones, o la
distribución, propón algo más moderno para perfiles y paginado". Se acordó
alcance **global** (un solo lenguaje visual de botón en toda la app) y
paginado del Directorio con **chips de página + «Ir a página»** por teclado
numérico.

### Cambios

- **`Theme.kt`** — nuevo token `JkOrangeBright = 0xFFFF8A5C`, usado para el
  foco de los botones primarios (antes el foco sobre naranja no se distinguía).
- **`core/ui/.../TvControls.kt`** (afecta a Home, Detalle, menú del avatar,
  perfiles y Directorio):
  - `TvActionButton`: alto mínimo 52 dp (`compact` = 42 dp), radio 12/10 dp,
    escala 1.04 al foco, `Arrangement.spacedBy(10.dp, CenterHorizontally)`,
    y tres parámetros nuevos: `danger` (rojo), `compact` (chips/teclados) e
    `icon: ImageVector?`.
    - **Secundario**: en reposo lleva borde 1 dp `JkSurfaceHigh`; al enfocar,
      borde 2 dp naranja y texto naranja (antes era solo un relleno más
      claro, indistinguible del fondo).
    - **Primario**: relleno naranja y, al enfocar, fondo `JkOrangeBright` +
      borde 2 dp blanco (visible sobre el naranja).
  - `TvIconButton`: 48 dp, radio 12 dp, borde 1 dp tenue en reposo, 2 dp
    naranja al enfocar, y `enabled` nuevo (flechas del paginado).
  - `TvChipButton` (nuevo): 52×42 dp, radio 10 dp; `selected` = relleno
    naranja; al enfocar, borde 2 dp (blanco si está seleccionado, naranja si
    no) y escala 1.06.
  - `TvNumberPad` (nuevo): teclado numérico de 4 filas compartido. Reemplaza
    las dos copias locales (`HomeScreen.SyncKeypad` y
    `ProfileGateScreen.SyncKeypad`), que ahora solo añaden la fila de
    acciones (`Vincular` / `Volver`).
- **`DirectorioScreen.kt`**:
  - `Row(fillMaxSize)` → `Row(fillMaxWidth().weight(1f))`: la fila pedía toda
    la altura y **empujaba la barra de paginado fuera de la pantalla** (se
    veía recortada contra el borde inferior).
  - La barra de paginado sale del `when` del estado y vive en un
    `PaginationFooter` que recuerda la última `DirectorioPage` conocida: así
    no se desmonta durante el `Loading` de cada salto y **no se pierde el
    foco del D-pad**.
  - `PaginationBar` nueva: `‹` + ventana de chips (`pageWindow(current,
    last, radius = 2)`: primera, última y ±2 alrededor de la actual, con
    `null` = «…») + `›` + botón compacto `Ir a página`.
  - `JumpToPageDialog`: `Dialog` con scrim, campo de solo lectura, el
    `TvNumberPad` compartido y `Cancelar` / `Ir` (deshabilitado sin dígitos).
    Máx. 4 dígitos y se ignoran valores mayores que `lastPage`; al confirmar
    hace `coerceIn(1, lastPage)`. Foco inicial en la tecla "1"; al cerrar, el
    foco vuelve al botón `Ir a página`.
  - `ResultsGrid`: `LaunchedEffect(page.page, page.filters) {
    gridState.scrollToItem(0) }` para no heredar el scroll de la página
    anterior.
- **`ProfileGateScreen.kt`**:
  - `ProfileTile`: nombre debajo de la tarjeta (naranja al enfocar, elipsis a
    168 dp) + indicador de sync: punto verde + "Sincronizado" si
    `spaceCode != null`, punto gris + "Solo local" si no.
  - `NewProfileTile`: tarjeta con borde 2 dp tenue en reposo y 3 dp naranja
    al enfocar, con un círculo de 64 dp y el icono `Add` (antes un "+" de
    texto suelto).
  - **Confirmación de borrado movida a `DeleteProfileDialog`**: los dos
    botones inline estiraban una tarjeta y desalineaban toda la fila. El
    diálogo muestra el aviso + `Cancelar` (foco inicial, para que un ENTER de
    más nunca borre) y `Eliminar` en `danger`.

### Trampas nuevas

- **`Modifier.weight` solo existe dentro del `RowScope`/`ColumnScope` de quien
  lo declara**: no se puede pasar `weight` a una función `@Composable` que
  devuelve `Modifier` y aplicarlo dentro (no compila). Se pasa el modificador
  desde el llamador (`Modifier.weight(1f).fillMaxHeight()`) y la función usa
  `modifier.fillMaxSize()`.
- **`Modifier.padding(horizontal = , top = , bottom = )` no existe**: hay que
  usar la sobrecarga con los cuatro lados nombrados.
- **Chips de paginado con ventana fija**: el número de hijos de la `Row` debe
  mantenerse constante en el rango medio (1 … 3 4 5 6 7 … 169 = 9 hijos), si
  no, al cambiar de página el índice del foco se corre y se pierde el foco.

### Verificación en emulador

- Menú del avatar: `Sincronizar ahora` en naranja con borde blanco al enfocar
  y los secundarios con borde de 1 dp; todo cabe en el panel de 540 dp de
  alto.
- Perfiles: "Alejoa" con punto verde y "Sincronizado"; "Nuevo perfil" con
  borde tenue y círculo con `+`; modo edición con `X` roja circular.
- Borrado: la tarjeta **no** cambia de tamaño; el diálogo aparece centrado con
  foco en `Cancelar` y `Eliminar` en rojo.
- Directorio (169 páginas): barra completa y centrada
  `‹ [1] 2 3 … 169 › [Ir a página]`; chip actual en naranja y foco con borde
  blanco; `›` deshabilitada en la 169.
- Salto directo: `Ir a página` → `169` con el teclado → carga la página 169 y
  la ventana pasa a `1 … 167 168 [169]`.
- Foco: `▼` desde la grilla llega a la barra, `◀/▶` mueven entre chips y el
  foco **sobrevive** a la carga de la página siguiente (chip enfocado con
  borde blanco tras el `Loading`).
- Cambiar un filtro (Género = Comedia) reinicia a página 1 y la ventana se
  recalcula a 68 páginas.
- `./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL` tras cada paso.

## F15 — Menú lateral del avatar y orden de capítulos — verificado 2026-11-09

- El menú del avatar dejó de ser un panel centrado con filas de botones y ahora
  es un cajón lateral derecho de 420 dp, con scrim, cabecera del perfil,
  estado de sincronización y opciones verticales (`Sincronizar ahora`, generar
  vínculo, desvincular y cambiar perfil).
- El modo de código conserva el mismo cajón y usa el teclado numérico dentro
  del menú; `BACK`/la flecha regresan a las opciones principales y `X` cierra.
- El botón de ordenar capítulos recibió un `FocusRequester` estable y rutas
  explícitas: `DPAD_UP` desde el primer capítulo llega al ordenamiento y
  `DPAD_DOWN` desde el ordenamiento vuelve al primer capítulo. Después de
  invertir la lista el foco se conserva en el botón, permitiendo alternar varias
  veces seguidas.
- Se corrigió además el foco inicial del detalle: ahora se solicita
  explícitamente el capítulo inicial en vez de dejar el foco en la estrella.
- Verificado en emulador: orden descendente `6..1`, alternancia ascendente
  `1..6`, foco visible en el control y navegación de ida y vuelta con D-pad.

## F16 — Vinculación compacta y controles del reproductor

- La pantalla de vinculación usa panel, tipografías, teclado y botones compactos
  para que las acciones inferiores quepan también en una TV real de 540 dp.
- El texto diferencia el **código temporal de vinculación** del código del
  espacio. `invalid_pairing_code` ahora explica que `07951576` es el código del
  espacio y que debe generarse un pairing temporal desde el dispositivo ya
  vinculado.
- El reproductor mantiene el WebView acelerado por hardware y fuerza visible
  la superficie `<video>`; esto cubre TVs donde se escuchaba el audio pero no
  se pintaba la imagen.
- El overlay añade barra de progreso navegable con D-pad: izquierda/derecha
  desplazan 10 segundos, además de pausa/reproducción. Las fuentes se muestran
  en un cajón vertical izquierdo colapsable.

## F17 — Reproductor estilo web y servidor flotante

- El estado normal del reproductor deja libre el vídeo y muestra únicamente
  una barra inferior con tiempo, seek, `-10 s`, pausa/reproducción y `+10 s`.
- La barra responde a izquierda/derecha del D-pad en saltos de 10 segundos y
  también conserva el arrastre lógico mediante el control enfocable.
- Desu continúa siendo la fuente inicial. Si no aparece `<video>` tras el
  tiempo de vigilancia, se muestra `Cambiar servidor` como botón flotante; el
  drawer de fuentes solo se abre al solicitarlo y ya no tapa automáticamente
  el reproductor.
- La pantalla se basa en el flujo observado en `jkanime.net/psyren/5/`: Desu
  por defecto, fuentes secundarias bajo demanda y controles concentrados abajo.

## F18 — Corrección 500 en Desu

- La web actual genera tokens `e=` de jkplayer con caracteres de base64url
  (`_`, `-` y `.`). El parser antiguo los truncaba y Desu respondía `500 | Server
  Error`.
- La expresión de extracción ahora conserva el token completo hasta la comilla
  del iframe. Se añadió prueba regresiva para `jkplayer/um` con esos caracteres.
- Validado con `:data:catalog:test` y `:app:assembleDebug`.

## Ajustes de UI + i18n completo (6 ajustes) — verificado 2026-11-09

Commits `693f142` (ajustes visuales), `2c9fe25` (i18n) y `f78a85b`
(selector de idioma + fix de foco).

### Ajustes (Fase A)
- **Directorio**: solo dos filtros (`VISIBLE_FILTERS = {"filtro","genero"}`)
  en un panel `Surface` de 230 dp con `verticalScroll` y opciones como
  filas visibles (`FilterOptionRow`, sin `TvDropdown` — se eliminó). Grilla
  de 6 columnas. Verificado con capturas: panel con scroll y foco en fila
  de género seleccionada.
- **Drawer Home**: "Animes" → "Recientes" con `Icons.Filled.DateRange`
  (`Whatshot` no existe en `material-icons-core`).
- **Favoritos**: badge `+N` (pendientes) / `Al día` en verde (`JkGreen`,
  `PosterCard.badgeColor`) / `Ep N` de respaldo.
- **Hamburguesa**: usa `painterResource(R.drawable.ic_launcher)` (naranja
  sobre negro) vía la sobrecarga de `TvIconButton(painter: Painter, …)` y
  `TvIconButtonBox`. `Painter` está en
  `androidx.compose.ui.graphics.painter.Painter` (**singular**, no
  `painting`).

### i18n (Fase B) y selector de idioma (Fase C)
- `values/` = español (default; literales byte-a-byte idénticos a los
  pre-i18n, así los dumps con `uiautomator` en español siguen válidos) y
  `values-en/` = inglés: 136 claves (130 app + 6 core/ui), 17 archivos
  Kotlin migrados a `stringResource`/`getString`.
- **Mecanismo de idioma**: `AppLocale.wrap()` en
  `MainActivity.attachBaseContext` (`createConfigurationContext` desde
  SharedPreferences `jk-profiles`/`lang`: `system|es|en`). No hay
  appcompat ni per-app locales: para aplicar un cambio,
  `ProfileManager.setLang(code)` + `(context as? Activity)?.recreate()`.
- **Selector**: `feature/profiles/LanguagePicker.kt`
  (`LanguageOptions` filas con check + `LanguagePickerDialog`). Entrada
  "Idioma" (icono `Settings`) en la sección `LANGUAGE` del cajón del
  avatar (tras "Cambiar perfil") y botón "Idioma" en el `CHOOSER` del
  gate. Strings `lang_title/lang_section/lang_hint/lang_system/lang_es/
  lang_en` (`Español`/`English` con `translatable="false"`).

### Trampas nuevas
- **El emulador está en inglés de sistema**: con `lang=system` la UI se
  ve en INGLES y los dumps devuelven textos EN. Para pruebas en español,
  elegir "Español" en el picker (queda persistido; el emulador ya quedó
  en `es`) o leer `values/` directamente. En un dispositivo en español,
  "Sistema" muestra español.
- **`DPAD_DOWN` desde el campo de búsqueda estaba atrapado en modo
  normal**: el fallback del `pendingGridFocus` solo existía cuando había
  resultados de búsqueda (con `search.hits` vacío el `LaunchedEffect` no
  hacía nada). Fix en `f78a85b`: si `!searching` se hace `focusNonce++`
  para re-enfocar la primera tarjeta (o el primer favorito). Verificado:
  campo → DOWN → primera tarjeta.
- **El `BasicTextField` del buscador abre el IME** al ganar foco en el
  emulador: con el teclado en pantalla el D-pad es del IME. `BACK`
  cierra el IME y conserva el foco en el campo.
- **Ruta al avatar del header** (menú con "Idioma"): primera tarjeta →
  `RIGHT`×5 (columna 6) → `UP` → avatar (`[1780,32][1876,128]`) →
  `CENTER`. El foco inicial del picker de idioma cae en la opción actual
  (el `FocusRequester` externo del cajón se engancha a la fila
  `selectedIndex`).
- Verificado end-to-end en `emulator-5554`: cajón → "Idioma" → filas
  "System (default)/Español/English" → elegir Español → recreate → UI en
  español ("Menú", "Buscar anime en jkanime…", "¿Quién está viendo?") →
  gate con botón "Idioma" → diálogo con check + foco en "Español".
