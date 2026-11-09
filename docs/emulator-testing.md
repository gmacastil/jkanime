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
