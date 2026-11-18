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

## F19 — 15 ajustes de UX y paginado real de capítulos (1.4.0) — verificado 2026-11-11

Commits `fd1aac5` (ajustes), `f7e339e` (paginado) y `08a6dcd` (`1.4.0`),
tag `v1.4.0`. Todo con APK de release firmada instalada en `emulator-5554`
(paquete `com.macastil.tv`).

### Cambios

**Reproductor**
1. Adelantar/retroceder con ◀ ▶ muestra los controles y reinicia el
   temporizador: se ocultan solos ~9 s después del último seek.
2. El auto-ocultar solo actúa mientras reproduce; en pausa, con aviso de
   servidor o con el overlay enfocado se queda visible.
3. Fuera los botones de salto ±10 s y el de salir (se sale con `BACK`).
4. Pausa/play de 96 dp centrado sobre el vídeo; desaparece al reanudar.
   El icono sale del estado real del player, no de un flip manual.

**Home / tarjetas**

5. OK sostenido en una tarjeta añade/quita el favorito sin entrar al detalle.
   Si la tarjeta no traía datos de emisión, `completeFavorite()` pide el
   detalle justo después de insertar para poder calcular pendientes.
6. Avatar y nombre del perfil pasan al menú lateral; la cabecera queda solo
   con menú + buscador.
7. Animación del póster con `tween(110)` y escala 1.06. **Ojo:** se cambió
   del spring por defecto a `tween` creyendo que así se quitaba el temblor de
   la grilla. No era cierto: el temblor seguía ahí y la causa era otra (ver
   *F20 — el temblor de la grilla* más abajo).
8. Estado con color: naranja "En emisión", verde "Concluido"
   (`airingStatusColor`), igual en Home, búsqueda, Directorio, detalle y
   favoritos.
9. Títulos a una línea en la grilla de búsqueda (`titleMaxLines`).
10. Favoritos sin datos de emisión (`pendingEpisodes == null`) dejan de
    clasificarse como "Al día": van a Pendientes y sin badge verde.

**Detalle**

11. OK sostenido marca/desmarca el capítulo visto conservando el foco
    (`markWatched`/`unmarkWatched`; `bumpLastWatched` usa `MAX(...)` para no
    bajar `last_watched_episode`).
12. Botón "Todo visto" (`markAllWatched`).
13. Ordenar capítulos ya no roba el foco ni salta a mitad de lista.
14. "Cargar más" con paginado real y foco en el primer capítulo cargado.

**Directorio**

15. "Ir a página" pasa al final del panel de filtros y el diálogo pierde
    Cancelar (se cierra con `BACK`, devolviendo el foco al botón).

### Paginado de capítulos: la página va en la RUTA

El JS de la ficha del sitio no pagina por query string:

```js
$.ajax({ type: "POST", url: 'https://jkanime.net/ajax/episodes/' + id + '/' + pag, ... })
```

Comprobado con curl (misma sesión y `_token` de `/naruto-shippuden/`, id 142):

| Petición | Resultado |
|---|---|
| `POST /ajax/episodes/142/` | `current_page=1`, caps 1-16 |
| `POST /ajax/episodes/142/?page=2` | `current_page=1`, caps 1-16 (**ignora**) |
| `POST /ajax/episodes/142/?p=2` | `current_page=1`, caps 1-16 (**ignora**) |
| `POST` con `page=2` en el body | `current_page=1`, caps 1-16 (**ignora**) |
| `GET /ajax/episodes/142/?p=2` | HTML, no JSON |
| `POST /ajax/episodes/142/2` | `current_page=2`, caps 17-32 |
| `POST /ajax/episodes/142/32` | `current_page=32`, caps 497-500 |

El JSON de paginación publica `next_page_url: "...?p=2"`, que es una **pista
falsa**: el servidor arma esa URL pero solo respeta el segmento de ruta.
`per_page` es 16 y `total` 500, así que las últimas páginas traen menos.

Fix: `Jk.episodesAjaxUrl(animeId, page)` construye la ruta y hay test en
`JkUrlTest` (`episodios pagina en la ruta no en la query`). Síntoma típico si
se vuelve a romper: "Cargar más" repite el mismo bloque y el contador no
avanza.

### Paginado direccional

- "Más nuevos primero" (por defecto) arranca en `lastPage` y **retrocede**
  (`p-1`); "más antiguos primero" arranca en la 1 y **avanza** (`p+1`).
- `DetailViewModel.loadEpisodes` reancla el cursor al cambiar de orden y
  `Success.hasMore` se calcula al cargar, porque depende del sentido. Si una
  página no aporta episodios nuevos, el paginado se cierra en vez de repetirse.
- La carga inicial pide la página 1 solo para conocer `lastPage` y luego salta
  a la página objetivo (con cache TTL del repositorio, sin red extra).

### Foco al "cargar más"

Devolver el foco al botón **no funciona**: al crecer la lista el botón se sale
del viewport, la fila se descarta y `requestFocus()` falla en silencio — el
D-pad acababa en el botón de atrás de la cabecera.

Patrón que sí funciona (`DetailScreen`):

1. Al pasar `loadingMore` a `true`, guardar `countBeforeLoad = episodes.size`.
2. Al volver a `false`, si creció la lista, `pendingFocusIndex = countBeforeLoad`
   (índice del primer capítulo nuevo en el orden mostrado).
3. En un `LaunchedEffect`: `listState.scrollToItem(target)` y luego reintento
   con `withFrameNanos {}` hasta que `rowFocusers[target]?.requestFocus()`
   devuelva `true`. `rowFocusers` es un `mutableMapOf<Int, FocusRequester>`
   con una entrada por índice (la inicial apunta a `firstChapterFocus`).

### Trampas nuevas

- **Nunca combinar `focusable()` con `clickable()` en el mismo nodo.** Son dos
  focus targets anidados: ARRIBA/ABAJO se mueven entre el target externo y el
  interno **sin cambiar de elemento** y el foco queda atrapado. Pasó en
  `FilterOptionRow` del Directorio: era imposible recorrer el panel y llegar a
  "Ir a página". Con `clickable()` basta; para capturar CENTER antes del scroll
  se usa `onPreviewKeyEvent` sobre ese mismo nodo.
- **Icono pausa/play invertido:** con player nativo, `NativePlayer` ya emite
  `onPlayingChanged` de forma síncrona dentro de `pause()`/`play()`. Invertir
  `isPlaying` a mano pisaba ese valor y el botón quedaba al revés. La lambda
  `togglePlay` lee `controller.player?.isPlaying` **antes** del toggle y solo
  invierte cuando no hay player nativo (WebView).
- **Verificar reproducción/pausa en el emulador:** `screencap` del vídeo nativo
  sí funciona (el del WebView sale negro). Dos capturas separadas ~2 s y
  `ImageChops.difference(a, b).getbbox()`: `None` = congelado (está en pausa).
- **Pulsación sostenida por adb:** `adb shell input keyevent --longpress
  KEYCODE_DPAD_CENTER`.
- **La APK de release no es debuggable:** `run-as com.macastil.tv` falla con
  "package not debuggable", así que inyectar progreso en Room (para probar
  verdes o el foco inicial) exige instalar el build **debug**.
- **El panel del Directorio es largo:** desde la grilla, `LEFT` ×4-5 entra al
  panel y el final ("Ir a página") queda a ~60-70 `DOWN`; llegar con
  `input tap` es más rápido que recorrerlo.

### Verificación en emulador

- Reproductor: overlay a 1 s y 4 s tras un seek, oculto a ~9 s; pausa con
  `content-desc="Play"` y vídeo congelado; reanudar con `Pause` y auto-ocultar.
- Home: OK sostenido añadió "Dark Machine" a Favoritos; con los 5 capítulos
  vistos pasó a "Up to date" con badge verde.
- Búsqueda "one" (30 resultados): títulos a una línea y "Concluido" en verde.
- Directorio: diálogo "Go to page" sin Cancelar, salto a la página 22 y foco
  devuelto al botón; en el paginado ya no aparece el botón.
- Temblor: grabación de 8 s navegando tarjetas (`screenrecord`) analizada con
  ffmpeg + PIL; el borde de foco es estable y monótono entre fotogramas.
- Paginado con "Naruto Shippuden" (500 caps): arranca en 497-500 y
  "Load more" → 496…481 (20 caps) → 480…465 (36 caps). Con "Oldest first"
  arranca en 1-16 → 17-32, y en ambos casos el foco cae en el primer capítulo
  nuevo.

## F20 — El temblor de la grilla al navegar en horizontal (1.4.1) — verificado 2026-11-11

Síntoma reportado: al recorrer las tarjetas del Home con `RIGHT`/`LEFT`, toda
la grilla "tiembla" — el contenido sube y baja unos píxeles aunque no se esté
haciendo scroll vertical.

### Medición (antes del fix)

No se puede diagnosticar esto mirando capturas sueltas: el desplazamiento dura
~200 ms y vuelve a su sitio, así que un `screencap` antes y después sale
idéntico. Se graba con `screenrecord`, se extraen los fotogramas a 30 fps y se
mide el desplazamiento entre fotogramas consecutivos con **correlación de fase**
(`np.fft.rfft2` + pico del `irfft2`) por zonas independientes.

Resultado con la animación de escala activa, tras cada pulsación `RIGHT`:

| zona (banda vertical) | dy entre fotogramas | confianza |
|---|---|---|
| cabecera (logo + buscador) | `0` en todos | 1.00 |
| títulos de la fila 1 | `+5, +3, -1, -4, -2, 0` | 0.96-1.00 |
| pósters | `+5, +3, -1, -4, -2, 0` | 0.78-0.94 |
| pie / fila 2 | `+5, +3, -1, -4, -2, 0` | 0.69-0.86 |

Conclusiones de la medición:

- La **cabecera no se mueve** y los `bounds` de `uiautomator` son idénticos
  antes y después ⇒ no es un cambio de layout ni un inset de ventana.
- `dx` siempre `0` ⇒ no hay scroll horizontal.
- El mismo `dy` en todas las bandas de la grilla ⇒ se desplaza **el contenido
  del `LazyVerticalGrid` completo**, no una tarjeta.
- La secuencia no es monótona (`+5 … -4 … 0`) ⇒ es un **spring con rebote**,
  no un `tween`.

### Causa

`PosterCard` aplicaba `Modifier.scale(animateFloatAsState(1.06f))` al **mismo
nodo que tiene el `combinedClickable`**, es decir al nodo que recibe el foco.
Al cambiar el foco, ese nodo cambia de tamaño y Compose pide `bringIntoView`
para el nodo recién enfocado; el `LazyVerticalGrid` responde con un scroll
animado (spring) de unos pocos píxeles que mueve **todo** su contenido y luego
vuelve. Eso es exactamente el temblor: pósters, títulos, fila 2 y pie se
desplazan a la vez, y la cabecera (fuera de la grilla) no.

Prueba A/B que lo confirma: con la escala fijada a `1f` (sin animación), 236
fotogramas analizados dan `dy = 0` en todas las zonas.

### Fix

Separar el **nodo enfocado** (tamaño de layout fijo) de la **capa visual** que
se escala:

```kotlin
Box(                                  // foco + tamaño estable
    Modifier
        .fillMaxWidth()
        .aspectRatio(2f / 3f)
        .focusRequester(focusRequester)
        .combinedClickable(...)
) {
    Box(                              // capa visual: aquí va la escala
        Modifier.fillMaxSize().scale(scale).clip(...).background(...).border(...)
    ) {
        AsyncImage(...)
        // badge
    }
}
```

Así el nodo que recibe el foco nunca cambia de tamaño, `bringIntoView` no tiene
nada que compensar y la grilla no se desplaza. El zoom se conserva tal cual
(6 % sobre el póster, con el borde naranja y el badge escalando junto).

### Verificación

`tools/measure-jitter.sh` (nuevo en el repo) automatiza todo: reinicia la app,
graba 16 s mientras pulsa `RIGHT` ×4 y `LEFT` ×4, extrae fotogramas y mide
grilla y cabecera. Sale con código 1 si detecta el temblor.

```
$ ./tools/measure-jitter.sh verify
[verify] fotogramas analizados: 236
  grilla   : 0 eventos de desplazamiento, max |dy| = 0 px
  cabecera : max |dy| = 0 px (debe ser 0)
  ✓ SIN temblor: el contenido de la grilla no se desplaza
```

El zoom se comprobó con la captura de la tarjeta enfocada: el póster pasa de
270 px de layout a 286 px de dibujo (1.06) manteniendo el borde naranja y el
badge, y las tarjetas vecinas no se mueven.

### Trampa nueva

- **Nunca animes la escala del nodo que recibe el foco dentro de un
  `LazyGrid`/`LazyColumn`.** El `<T>` que escala tiene que ser un hijo, y el
  `focusable`/`clickable` va en un contenedor de tamaño constante. Si no, cada
  cambio de foco provoca un `bringIntoView` con spring que desplaza todo el
  listado unos píxeles y se percibe como temblor. Aplica igual a
  `CardDefaults.scale(focusedScale = …)` de `tv-material3` dentro de listas.
- **Diagnóstico:** un desplazamiento transitorio no se ve con `screencap`
  (antes/después salen iguales). Hay que grabar y medir por correlación de
  fase, y separar las bandas de la pantalla para distinguir "se mueve todo el
  listado" de "se mueve la ventana/layout".
- **Ojo con la conclusión fácil:** se había cambiado el spring por
  `tween(110)` en la creencia de que eso quitaba el temblor; el temblor seguía
  igual. La animación no era el problema, sino el tamaño del nodo enfocado.
- La APK de debug tiene `versionCode` menor (`100 + commits` no aplica: en
  debug cae a `1` por defecto): para instalarla encima de la release sin
  desinstalar, compilar con `-PVERSION_CODE=<actual+1>` y **re-firmar** con el
  keystore de release (`apksigner sign --ks …`); si no, Android rechaza por
  downgrade o por firma distinta.

## App móvil (`:app-mobile`) — verificado 2026-11-13

Segunda app del repositorio, para teléfono/tablet. UI propia con Compose
Material3 táctil; comparte `:core:model`, `:core:network`, `:core:ui` y
`:data:catalog` con la app de TV (mismo scraping, mismo rate limit, mismos
parsers).

### El "se traba y se sale" en el celular

No era un fallo de la app móvil: el pipeline publicaba **un solo release con
las dos APKs**, y desde el teléfono se descargaba la primera
(`app-release.apk`), que es la de **TV** (Leanback, sin táctil). Instalada en
un celular se queda sin entrada táctil y Android la cierra.

Desde 1.4.7 el workflow publica **dos releases**, cada uno con una sola APK:

| Tag | APK | Público |
|---|---|---|
| `vX.Y.Z-tv` | `jkanime-tv-X.Y.Z.apk` | Android TV |
| `vX.Y.Z-mobile` | `jkanime-mobile-X.Y.Z.apk` | teléfono/tablet |

El job verifica con `aapt2 dump badging` que la de TV declara Leanback y la
móvil no, y aborta si se cruzan. Los tags `-tv`/`-mobile` están excluidos del
disparador para no relanzar el pipeline.

### Cambios de la app móvil

- **`JkGraph`**: DI manual de una sola instancia por proceso (repositorio,
  biblioteca, motor de sync, `appScope`). Antes cada composable creaba su
  propio `JkanimeRepository()` —con su `OkHttpClient`, su cookie jar y su
  límite de 700 ms— en cada recomposición, y el `HomeViewModel` se instanciaba
  dos veces (en `MainActivity` y dentro de `HomeScreen`).
- **Persistencia**: `LibraryRepository` guarda favoritos, progreso y
  credenciales en `SharedPreferences` con JSON (`kotlinx.serialization`). Se
  descartó Room otra vez: KSP falla en `:app-mobile` con
  `java.lang.IllegalStateException` sin mensaje. La app de TV sigue con Room.
- **Detalle**: póster + metadatos + sinopsis + filas de episodio (miniatura
  112×64, barra de avance, verde si está visto) en un único `LazyColumn`.
  Antes reutilizaba `PosterCard` con `width = null` y cada capítulo ocupaba
  una pantalla entera.
- **Reproductor**: `WebPlayer` (copia adaptada del de TV, con el mismo
  `HOOK_SCRIPT` de bloqueo de anuncios y el puente `JkBridge`) sobre el embed
  del servidor, más selector de servidores en chips y barra de tiempo. El
  progreso se guarda cada 5 s y al salir; el detalle ofrece "Continuar Ep N".
- **Tema**: esquema **oscuro** de Material3 con los colores de `:core:ui`.
  Con el tema claro por defecto `JkTextPrimary` (#F4F4F6) era invisible.
- **Atrás**: `BackHandler` en `MobileApp` para que BACK cierre reproductor y
  detalle en lugar de sacar de la app.
- **Sync real**: `pull`/`push` contra las RPC de `docs/supabase/schema.sql`
  con los nombres de parámetro correctos (`p_favorites`/`p_progress`) y merge
  last-write-wins con tombstones. Antes `pull` devolvía vacío y `push` enviaba
  `favorites`/`progress` (parámetros inexistentes), por lo que "sincronizaba"
  sin mover datos.

### Cómo probarlo

```bash
# Imagen de teléfono (una vez)
sdkmanager "system-images;android-36;default;x86_64"
avdmanager create avd -n jkphone -k "system-images;android-36;default;x86_64" -d pixel_6

emulator -avd jkphone -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect -no-audio &
adb install -r app-mobile/build/outputs/apk/debug/app-mobile-debug.apk
adb shell am start -n com.macastil.mobile/.MainActivity
```

Verificado en `jkphone` (API 36):

1. Home carga las tarjetas de Programación (póster, badge `Ep N`).
2. Tap en tarjeta → detalle con póster, estado en naranja y 7 capítulos.
3. Tap en un capítulo → reproductor con `0:12 / 23:39`, decodificador H.264
   activo y chips Streamwish/Vidhide/VOE/Filemoon.
4. BACK → vuelve al detalle (la app **no** se cierra) y aparece
   "Continuar Ep 1" con el progreso guardado:
   `progress=[{"slug":"aoki-denshou-welsh-shedar","episode":1,"positionMs":25542,...}]`.
5. Corazón del detalle → favorito; pantalla Favoritos muestra "Favoritos (1)".
6. Menú → Sincronización → "Crear espacio nuevo" devuelve código real; al
   "Sincronizar ahora" informa `1 favoritos · 1 progresos`.

### Trampas nuevas

- **`screencap` sale negro en el área del WebView** también en móvil (capa de
  vídeo), igual que en TV. Para confirmar reproducción, `logcat` con
  `CCodecConfig ... string input.media-type.value = "video/avc"` y
  `MediaFocusControl: requestAudioFocus()`.
- **Los colores de `:core:ui` son para fondo oscuro.** Reutilizarlos sobre el
  tema claro por defecto de Material3 deja títulos y subtítulos casi
  invisibles; la app móvil monta un `darkColorScheme` propio.
- **`Icons.Filled.Sync` no existe** en `material-icons-core`. Para acciones
  secundarias se usa un `DropdownMenu` (Actualizar / Sincronización) en vez de
  inventar iconos.
- **`LibraryRepository.toggleFavorite` alterna** (si existe, lo borra). Para
  el merge de sync hace falta `upsertFavorite`/`upsertProgress`, o el pull
  remoto deshace favoritos locales.

## 1.5.0 — crash de la app móvil en teléfono real e iconos nuevos (verificado 2026-11-13)

### El síntoma

En el Pixel 7 (Android 17) la app instalada desde `v1.4.9-mobile` se quedaba en
**pantalla negra**. En el emulador `jkphone` (API 36) la misma APK arrancaba
bien, y `logcat` del arranque no mostraba ninguna excepción.

El crash sí estaba registrado, pero no en el buffer de `logcat` ni en
`dumpsys dropbox` a la primera: aparece en **`dumpsys activity exit-info`**
(`reason=4 APP CRASH(EXCEPTION)`) y, con el nombre exacto del evento, en
`dumpsys dropbox --print`:

```
2026-11-13 22:47:05 data_app_crash
Process: com.macastil.mobile
java.lang.IllegalArgumentException: Key "bai-jia-jue-zhi-jianghu-gui-shi-lu"
was already used. If you are using LazyColumn/Row please make sure you provide
a unique key for each item.
```

### La causa

El Home del móvil aplanaba las secciones de Programación en una sola lista y
usaba el `slug` como `key` de la `LazyVerticalGrid`. Pero **el sitio publica un
card por episodio**: ese día `bai-jia-jue-zhi-jianghu-gui-shi-lu` tenía los
episodios 11, 13 y 12 en la portada, así que el mismo `slug` aparecía tres
veces. Compose no admite claves repetidas y mata el proceso.

Por eso era intermitente y no salía en el emulador: depende del contenido del
día. Comprobación contra el HTML real:

```bash
curl -s https://jkanime.net > home.html
grep -o 'bai-jia-jue-zhi-jianghu-gui-shi-lu' home.html | wc -l     # 6 (3 fichas, href + slug)
```

En TV no ocurría porque su grilla usa `key = section.name + episodeUrl`.

### El fix

- `HomeViewModel.mergeSections()` (función pura, `:app-mobile`): un card por
  anime, en la posición de su primera aparición y **con el capítulo más alto**.
  Cubierto por 8 tests en `app-mobile/src/test/.../MergeSectionsTest.kt`
  (incluye el caso real 11/13/12 → Ep 13).
- `DetailViewModel`: `episodes.distinctBy { it.id }` — el `id` del capítulo es
  el `key` del `LazyColumn` del detalle y el sitio puede repetirlo entre
  páginas.
- `FavoritesScreen`: `distinctBy { it.slug }` sobre los favoritos.

### Los iconos

La app móvil tenía como icono un vector de 48 dp con un círculo
`#FF3DDC84` y un trazo negro relleno (invisible): en el launcher se veía
literalmente **un punto verde**. La TV usaba `caiman_icon.png` a pelo.

Ahora las dos apps generan su icono con `tools/make-icons.py` a partir del arte
de marca (`app/src/main/res/drawable/caiman_icon.png`, el conejo enmascarado):

- Fondo `#0E0E11` (jk_background) y silueta `#FF6B35` (jk_orange).
- `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml` (adaptive, con
  capa `<monochrome>` para iconos temáticos de Android 13+).
- PNG legacy por densidad (48/72/96/144/192) para API < 26.
- `CONTENT_RATIO = 0.52`: con 0.58 la máscara circular recortaba los hombros.
- El nombre de la app móvil pasa a **"jkanime"** (antes "jkanime mobile", que el
  launcher recortaba a "jkanime m…").

### Cómo verificar un crash que no sale en `logcat`

```bash
adb shell dumpsys activity exit-info com.macastil.mobile | head -40
adb shell dumpsys dropbox --print | grep -A8 data_app_crash
```

`exit-info` da la hora, el motivo y el PID; `dropbox --print` el stack. El
buffer de `logcat` pierde el crash si el proceso murió antes de que se leyera
(y `logcat -c` lo borra: no limpiar el buffer antes de reproducir).

### Trampa nueva

- **Una `key` repetida en Lazy* mata el proceso**, no degrada la UI. Al usar un
  campo de datos como `key` hay que garantizar unicidad en el origen (dedupe) y
  añadir un test: el sitio devuelve varias fichas del mismo anime y el fallo
  solo aparece según el contenido del día.

---

## 1.5.1 — la app móvil al nivel de la TV (verificado 2026-11-15)

Reporte del usuario sobre `:app-mobile` 1.5.0: el menú no tenía las opciones de
la TV (idioma y sincronización), la reproducción fallaba, los controles de
pausa/adelantar no respondían y la primera fila de la grilla salía cortada.

Los cuatro se corrigieron y se verificaron en el emulador `jkphone`
(API 36, idioma español e inglés).

### 1. Primera fila cortada por la `TopAppBar`

`HomeContent` envolvía `Scaffold` y **descartaba** el `padding` que le llega en
el `content` (reservado para la `TopAppBar`), así que la grilla empezaba en
`y=0` y la barra tapaba la primera tarjeta. Fix: `.padding(padding)` en el
`LazyVerticalGrid`.

### 2. Menú lateral con perfil único, idioma y sync

La app móvil tenía un `DropdownMenu` de tres entradas. Ahora tiene el mismo
`ModalDrawerSheet` que la TV (`ui/menu/AppDrawer.kt`), con estas diferencias:

- **Un solo perfil** (la TV es multiusuario). El menú muestra el nombre y una
  nota "Perfil único del dispositivo"; el lápiz lo renombra
  (`RenameProfileDialog`). El nombre vive en `data/local/ProfileStore.kt`
  (`SharedPreferences` `jk-mobile-settings`, clave `profile_name`); **vacío =
  nombre por defecto traducido**, para que no se quede congelado en el idioma
  de la primera instalación.
- **Grupo PERFIL**: `Idioma / <actual>` → `LanguageDialog` (Sistema / Español /
  English) y `Sincronización / <estado>`, que navega a `SyncScreen` y muestra
  el código de espacio vinculado o "Sin vincular".
- **Grupo BIBLIOTECA**: Favoritos, Actualizar (recarga el home) y la versión.
- Idioma: `AppLocale` propio del módulo + `attachBaseContext` +
  `recreate()`, igual que en la TV pero en el módulo móvil (no se puede
  depender del de `:app`).

### 3. La reproducción fallaba

Dos causas, ambas en `WebPlayer`:

1. **User-Agent de TV.** El móvil reusaba el UA de la TV (`... SmartTV ...`).
   Con ese UA los hosts (`Streamwish`, `vibuxer`, `hanerix`, `audinifer`)
   servían una variante del player **sin zonas táctiles y con el `<video>`
   fuera de tamaño**. Fix: `WebSettings.getDefaultUserAgent(context)` quitando
   el sufijo `"; wv"` (Chrome del sistema, sin delatar que es un WebView).
2. **El barrido de anuncios borraba el reproductor.** `hitTestSweep` comparaba
   el elemento "superficial" con el "perforado" y eliminaba el contenedor
   cuando diferían; con el DOM de esos hosts el contenedor del anuncio
   resultaba ser un ancestro del `<video>` y se iba todo. Fix: `findVideo()` /
   `containsVideo()` en `HOOK_SCRIPT` y `continue` cuando el candidato contiene
   un `<video>` en hasta 3 niveles (shadow DOM e iframes same-origin incluidos).

### 4. Controles de parada y adelantamiento

Antes eran un overlay sobre el WebView: el WebView se quedaba con el gesto y
los botones no recibían el toque. Ahora la barra de transporte va **debajo del
vídeo**, en el mismo `Column`, y el reproductor ocupa el resto:

- `Slider` con `onValueChange` (arrastre) + `onValueChangeFinished` (`seekTo`).
- `−10 s` / `+10 s` (`seekBy`), pausa/play grande (52 dp) y `✕` que detiene y
  vuelve al detalle.
- En apaisado el vídeo lleva `weight(1f)` (≈690 px de alto, antes 374 px), la
  `TopAppBar` se oculta, la barra mínima (volver / título / recargar) se
  superpone al vídeo y se quita la etiqueta "Servidor" para ganar alto.
- El icono de pausa se dibuja a mano (`PauseGlyph`): `Icons.Filled.Pause` no
  existe en `material-icons-core`.

### Trampas nuevas

- **`ViewConfiguration`/`configChanges` congela la orientación.** El manifest
  móvil declara `configChanges="orientation|screenSize|screenLayout|..."`, así
  que la activity **no se recrea** al girar; y el Context que devuelve
  `AppLocale.wrap` (`createConfigurationContext`) nace con la `Configuration`
  del arranque. Resultado: en apaisado `LocalConfiguration.orientation` seguía
  diciendo `1` (portrait) y `LocalWindowInfo.containerSize` seguía dando el
  tamaño vertical → el vídeo medía 1207 px en una pantalla de 1080 y la barra
  de transporte quedaba fuera. **La orientación se mide**, no se pregunta:
  `Modifier.onSizeChanged { landscape = it.width > it.height }`.
- **Nunca formatear mensajes de ViewModel con `getString`.** En un
  `AndroidViewModel`, `getString` usa el **Application** (idioma del sistema),
  no el Context de la activity al que se le aplicó `AppLocale`: con la UI en
  español los mensajes del sync salían en inglés ("Space created"). El estado
  guarda `UiText(@StringRes, args)` y la pantalla resuelve con `stringResource`.
  Aplica a `SyncViewModel`, `HomeViewModel` y cualquier mensaje de error.
- **`screencap` no sirve para saber si el vídeo avanza** (capa hardware del
  WebView → negro). Verificar con `logcat`: `JkPlayer: video info ... ct=`,
  `paused=`, `readyState=4` y `CCodecConfig video/avc`.

### Verificación (emulador `jkphone`, API 36)

| Caso | Resultado |
|---|---|
| Home | primera fila completa, sin solape con la `TopAppBar` |
| Menú | "Mi perfil"/"Alejandro", "Idioma / Español", "Sincronización / Sin vincular", BIBLIOTECA, Favoritos, Actualizar, "Versión 1.5.0" |
| Renombrar | el nombre persiste tras reiniciar el proceso |
| Idioma | cambio en caliente a English (`recreate()`) y vuelta a Sistema sin perder datos |
| Sync | "Crear espacio nuevo" → `04895806`; "Sincronizar ahora" → "Última sincronización: 0 favoritos · 0 progresos" |
| Reproducción | Desu (`blob:`) con `video/avc`, `readyState=4` |
| Pausa | `paused=true` y el icono pasa a ▶ |
| ±10 s | 19.1 → 29.1; dos `−10` → 9.1 |
| Slider | tap → 788.3 s; arrastre → 145.2 s |
| Detener | vuelve al detalle en retrato con "Continuar Ep 1" y la barra guardada |

`./gradlew test` → 76 tests, 0 fallos, 1 skipped. `:app` y `:app-mobile`
compilan (`assembleDebug`).

---

## 1.5.2 — los modos de sincronización de la TV en el móvil (verificado 2026-11-15)

El menú móvil llevaba a `SyncScreen`, pero esa pantalla solo tenía "crear" y
"vincular con código". La TV ofrece **cuatro modos con espacio** y **tres sin
espacio**; el motor ya los soportaba (`setRecoveryKey`, `recoverSpace`), faltaba
la UI. Se replicaron tal cual y se verificaron contra el Supabase real.

| Sin espacio | Con espacio |
|---|---|
| Crear espacio de sync | Sincronizar ahora (+ última sincronización) |
| Tengo un código de vinculación (8 dígitos, 15 min) | Generar código para otro dispositivo |
| Tengo una clave de recuperación (6 hex) | Configurar clave de vinculación |
| — | Desvincular espacio |

Comportamientos copiados de la TV:

- **Crear espacio abre directo el panel de la clave** ("Espacio creado: 6864
  6267" + "Elige una clave de 6 caracteres"), con un `Ahora no` para salir.
  Sin clave, perder el token equivale a perder el espacio.
- **La clave se normaliza** a hex en mayúsculas y se cortan los 6 caracteres:
  la RPC `sync_set_recovery` exige `length = 6` y `^[0-9A-F]+$`, así que
  escribir `ab12cd` a mano acabaría en `invalid_recovery_key_format`.
- **Código y espacio se muestran agrupados** (`0489 5806`), como en la TV.
- **Auto-sync al arrancar**: `JkGraph.syncEngine.syncInBackground()` en
  `MainActivity.onCreate` (en la TV lo dispara el gate de perfiles). Antes el
  teléfono solo sincronizaba con el botón.

### Errores de la API: de "No value for code" a un mensaje útil

`SupabaseSyncClient.rpc` no miraba el código HTTP: PostgREST devuelve
`{"code":..,"message":..}` en los errores y el fallo saltaba más tarde, al leer
`getString("code")`, con un `JSONException` sin sentido para el usuario. Ahora
`rpc` lanza `SyncException` con el recurso de texto ya traducido:

| Respuesta | Mensaje |
|---|---|
| `PGRST202` / 404 "Could not find the function" | Falta ejecutar `docs/supabase/schema.sql` |
| `invalid_pairing_code` | Código inválido o vencido |
| `invalid_recovery_key` (y `_format`) | Clave de recuperación inválida |
| otro | `message` del servidor o `HTTP n: cuerpo` |

`SyncException` lleva `@StringRes` + args (no texto): el idioma lo aplica el
Context de la activity con `AppLocale`, nunca el Application del cliente.

### Verificación (emulador `jkphone`, API 36, es + en)

| Caso | Resultado |
|---|---|
| Sin espacio | 3 opciones: crear / código de vinculación / clave de recuperación |
| Crear espacio | espacio `6864 6267` + panel de clave abierto solo |
| "Ahora no" | cierra el panel |
| Guardar clave `ff00aa` | se muestra `FF00AA`, "Clave de vinculación guardada" y el panel se cierra con la nota verde |
| Generar código | `Código: 47424871` + "caduca en 15 minutos" |
| Vincular con ese código | "Dispositivo vinculado" y vuelve el mismo espacio |
| Desvincular | vuelve a las 3 opciones |
| Recuperar con `FF00AA` | "Espacio recuperado", mismo espacio `0489 5806` con sus datos |
| Recuperar con `000000` | **"Clave de recuperación inválida"** (antes: `No value for code`) |
| Sincronizar ahora | "Última sincronización: 0 favoritos · 4 progresos" |

`./gradlew test` → 88 tests, 0 fallos, 1 skipped (6 nuevos de
`SyncKeysTest`: normalización de clave y código, corte a 6/8 y formato del
espacio).

---

## 1.5.3 — vídeo negro en el móvil: `html/body` colapsados a 0 px (verificado 2026-11-15)

Síntoma: en el teléfono **se escuchaba el audio y el tiempo avanzaba, pero la
imagen no aparecía** (pantalla negra). Solo pasaba en el móvil; la TV no lo
reproducía porque ese episodio va por `NativePlayer` (m3u8) y no por el WebView.

### Por qué las verificaciones anteriores no lo detectaron

| Fuente | Qué prueba | Sirve para este fallo |
|---|---|---|
| `logcat` (`video info ... readyState=4`) | que el stream **decodifica** | NO: decodificar ≠ pintarse |
| `adb exec-out screencap` | la pantalla, salvo la capa de vídeo | NO: el vídeo sale negro siempre |
| CDP `Page.captureScreenshot` | el DOM pintado, **sin** la capa de vídeo | NO para el vídeo, sí para el resto del documento |
| **Foto de la ventana del emulador en el host** (`ffmpeg -f x11grab`) | lo que realmente se ve, capa de vídeo incluida | **SÍ** |

La primera prueba anterior ("reproduce OK") se basó solo en `logcat`, que solo
demuestra decodificación.

### Causa raíz

El embed (`jkplayer`, DPlayer + MSE sobre `blob:`) maqueta con
`html, body { width: 100%; height: 100%; overflow: hidden }`. En el WebView del
móvil ese `height: 100%` **y también `100vh`** se resuelven a **0 px**, aunque la
vista sí tiene tamaño. Medido por CDP:

```
innerWidth x innerHeight        = 818 x 235
documentElement.clientWidth/Height = 818 x 235
document.body (computed)        = … height: 0px, overflow: hidden
documentElement (computed)      = … height: 0px
div de prueba con height:100vh  = 0          <-- la clave
video (getBoundingClientRect)   = 818 x 241 @0,0
video.readyState / currentTime  = 4 / avanzando
```

Con el documento a 0 de alto y `overflow:hidden` **no se pinta nada** (ni los
controles de DPlayer: la captura CDP salía `(0,0,0)` en todos los píxeles), pero
el `<video>` sigue decodificando y sonando. De ahí el "se escucha pero no se ve".

Comprobaciones que acotan el problema a la vista y no a la página:

- Con `document.open()`/`write()` de una página en blanco, `100vh` sigue dando 0
  ⇒ no es CSS del embed.
- Un cambio real de tamaño (`wm size 1000x2200`) actualiza `innerWidth/innerHeight`
  (746x205) pero `100vh` sigue 0 ⇒ no son métricas obsoletas.
- Apagar `useWideViewPort`/`loadWithOverviewMode` no cambia nada.
- El `<video>` sí tiene frames: `canvas.drawImage(video)` da píxeles reales y la
  luminancia cambia entre tomas (139 → 148) ⇒ decodificación y frames correctos.

### Fix

En `HOOK_SCRIPT` de `app-mobile` (`WebPlayer.kt`) se añade `fitDocumentHeight()`:
si `html` o `body` miden **menos de 1 px**, se les pone la altura real de la vista
en píxeles (`innerHeight`, `!important`). Se reaplica en `resize`,
`orientationchange` y en el bucle de 1.2 s que ya existía (DPlayer recalcula su
maquetación). En un WebView sano (que sí resuelve el `height:100%`) no toca nada.

Solo se aplica en móvil: el WebView de la TV resuelve bien los porcentajes y la
TV tiene `NativePlayer` para los servidores con `m3u8`.

Extra en la misma versión: en horizontal la caja del vídeo se limita a **16:9**
centrada. Antes ocupaba todo el ancho (≈3.5:1) y como el embed ajusta el vídeo a
la altura, quedaban dos franjas negras enormes a los lados; ahora el vídeo llena
su área.

### Diagnóstico permanente

La línea `video info` incluye `doc=<alto html>/<alto body>`:

```
doc=0/0      -> documento colapsado: no se pinta (bug de viewport)
doc=246/246  -> documento con altura: se pinta
```

Con eso, un reporte de "pantalla negra" se resuelve mirando `logcat -s JkPlayer`
sin necesidad de CDP.

### Verificación (emulador `jkphone`, API 36)

| Momento | `logcat` | Foto del host |
|---|---|---|
| Antes (1.5.2) | `rect=818x241 doc=0/0` | documento entero negro `(0,0,0)` |
| Después (1.5.3) | `rect=418x246 doc=246/246` | vídeo reproduciendo (frame de "KODANSHA", logo de la productora) |

Foto del host:
```bash
for W in $(wmctrl -l | grep -i "Android Emulator" | awk '{print $1}'); do
  eval "$(xdotool getwindowgeometry --shell $W)"
  ffmpeg -v error -f x11grab -video_size ${WIDTH}x${HEIGHT} -i :0.0+${X},${Y} \
    -frames:v 1 -y /tmp/opencode/host.png
done
```
En debug, `WebView.setWebContentsDebuggingEnabled(true)` deja inspeccionar el
player por CDP (`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`).

## 1.5.4 — formularios de sync unificados + reproductor móvil a pantalla completa (verificado 2026-11-15)

Tres frentes en la misma versión: un bug de la TV en instalación nueva, la
unificación de las credenciales en un módulo compartido y el chrome duplicado
del reproductor del móvil.

### Bug: la clave de recuperación de la TV pedía 10 caracteres

En `ProfileGateScreen.kt` → `SyncChoiceScreen` (el sync que se ofrece al crear el
**primer** perfil) la clave de recuperación se trataba como una credencial de
**10 caracteres base32**:

```kotlin
syncCode.padEnd(if (recovering || settingRecovery) 10 else 8, '·')…
maxLength = if (recovering || settingRecovery) 10 else 8
if (syncCode.length == if (…) 10 else 8) { … }
```

y usaba `TvRecoveryPad` (A-Z + dígitos). Pero el título decía "6 CARACTERES" y
la RPC `sync_set_recovery` / `sync_recover` en `docs/supabase/schema.sql` exige
**exactamente 6 hex**:

```sql
if length(trim(key)) <> 6 or upper(trim(key)) !~ '^[0-9A-F]+$' then raise …
```

Resultado: solo se podía confirmar con 10 caracteres y el servidor rechazaba
siempre. El cajón del avatar (`HomeScreen.kt`) sí usaba 6 hex, y por eso la
recuperación funcionaba **después** de crear el perfil.

Fix: el gate usa el mismo componente (`TvHexPad`), 6 caracteres, display en
grupos de 3 y `✓` disponible con la clave completa. `TvRecoveryPad` se elimina de
`:core:ui`: su alfabeto base32 no puede cumplir la RPC, así que era una trampa
esperando a que alguien lo reutilizara.

### `SyncKeys` compartido (`:core:model`)

La normalización existía solo en el móvil (`app-mobile/.../ui/sync/SyncKeys.kt`).
Ahora vive en `core/model/src/main/kotlin/com/macastil/tv/model/SyncKeys.kt`
(módulo JVM puro, con tests) y la usan las dos apps:

| Función | Regla | La usa |
|---|---|---|
| `normalizeRecoveryKey` | hex, mayúsculas, corta a 6 | TV (gate + cajón), móvil |
| `isCompleteRecoveryKey` | `length == 6` | botones de guardar/recuperar |
| `normalizePairingCode` | solo dígitos, corta a 8 | TV (gate + cajón), móvil |
| `isCompletePairingCode` | `length == 8` | confirmación del teclado |
| `formatCode` / `formatRecoveryKey` / `formatPairingCode` | display (`0489 5806`, `FF0· ···`) | las dos apps |

Además de normalizar en la UI, **los dos `SyncEngine` normalizan antes de llamar
al cliente** (`setRecoveryKey`, `recoverSpace`, `joinSpace`): cualquier pantalla
futura queda protegida aunque se salte el teclado. `SyncKeysTest` pasa al módulo
compartido (8 tests) y el del móvil se borra para no tener dos verdades.

### Reproductor móvil: dos barras y sin pantalla completa

Síntomas reportados: "se ve una barra de controles abajo, y luego otro
mini-reproductor al fondo con otros controles, y no se pone en fullscreen".

Causa: el embed es **DPlayer**, que pinta su propia barra
(`.dplayer-controller`: play, tiempo, barra, volumen, fullscreen) y, en móvil, un
botón de play gigante. Como la app añade su `TransportBar` de Compose, se veían
dos juegos de controles y el vídeo parecía metido dentro de otro player.

Fix, en `WebPlayer.HOOK_SCRIPT`:

- `injectPlayerStyle(doc)` añade un `<style id="jk-player-style">` que oculta
  `.dplayer-controller`, `.dplayer-controller-mask`, `.dplayer-mobile-play`,
  `.dplayer-bezel`, `.dplayer-notice`, `.dplayer-menu`, `.dplayer-icons`,
  `.dplayer-bar-wrap`, `.dplayer-danmaku` y `.dplayer-subtitle`, y fuerza
  `.dplayer`, `.dplayer-video-wrap` y `video` a `100% x 100%` con
  `object-fit: contain`.
- `stylePlayer()` lo aplica al documento superior **y a los iframes
  same-origin** (algunos hosts montan el player en un iframe); se reaplica en el
  `MutationObserver` y en el bucle de 1.2 s.
- Como los controles del embed ya no existen, un toque sobre el vídeo llama a
  `play()` si está pausado (si el navegador rechazó el autoplay, o tras pausar
  desde Compose). `mediaPlaybackRequiresUserGesture` sigue en `false`.

En `PlayerScreen.kt`, apaisado pasa a ser pantalla completa de verdad:

- `DisposableEffect` con `WindowInsetsControllerCompat.hide(systemBars)` (inmersivo,
  `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`); se restauran al salir.
- La caja del vídeo es `fillMaxWidth().weight(1f)` (antes 16:9 centrada, que en un
  teléfono 2.2:1 dejaba el vídeo más pequeño de lo posible).
- La `TransportBar` se **superpone** al vídeo con fondo `0xB3000000` en apaisado
  (y sigue debajo del vídeo en retrato, donde el alto no sobra).
- Los chips de servidor se ocultan en apaisado salvo que el servidor haya
  fallado (`stalled`), que es justo cuando hacen falta.

### Verificación

Formularios (emulador `jktv`, gate de instalación nueva con `pm clear` y la APK
debug; los cuatro flujos del gate):

| Flujo | Entrada | Resultado en pantalla |
|---|---|---|
| Crear espacio | — | `Save this code: 0074 0020` |
| Definir clave | teclado hex, `C0FFEE` | "Linking key saved" |
| Recuperar con clave | `FF00AA` | "Device linked to the space" → espacio `04895806` |
| Vincular con código | `58364653` | "Device linked to the space" |

Comprobado también **contra el servidor** (curl a PostgREST):

```
sync_recover {"p_recovery_key":"C0FFEE"} -> {"code":"00740020", …}
sync_recover {"p_recovery_key":"FF00AA"} -> {"code":"04895806", …}
sync_new_pairing (04895806)             -> {"pairing_code":"58364653", …}
```

Reproductor (emulador `jkphone` + foto del host):

| Momento | `logcat` (`video info`) | Foto del host |
|---|---|---|
| 1.5.3 (caja 16:9 + barra debajo) | `rect=866x303 doc=303/303` | vídeo en una franja + barra de Compose **y** barra de DPlayer dentro del WebView |
| 1.5.4 (pantalla completa) | `rect=866x422 doc=422/422` | vídeo a pantalla completa (914x411 del host), **una sola** barra superpuesta, sin barras de sistema |

### Trampa al fotografiar la ventana del host

`wmctrl -l | grep -i "Android Emulator"` puede devolver **la terminal** cuando el
título de la ventana es el propio comando (`… grep -i "Android Emulator"`), y
`ffmpeg -f x11grab` captura **lo que se ve en pantalla** en esas coordenadas: si
el emulador está tapado por otra ventana, la foto sale de la ventana que está
encima. Filtrar por el nombre del AVD y subir la ventana antes de capturar:

```bash
wmctrl -a "Android Emulator - jkphone:5554"
W=$(wmctrl -l | awk '$NF=="jkphone:5554"{print $1}')
eval "$(xdotool getwindowgeometry --shell $W)"
ffmpeg -v error -f x11grab -video_size ${WIDTH}x${HEIGHT} -i :0.0+${X},${Y} -frames:v 1 -y out.png
```

Para leerla hay que girarla 90° (`PIL rotate(90, expand=True)`): la ventana del
emulador es vertical aunque el contenido esté en apaisado.

## Estado actual — 1.5.6 en desarrollo (2026-11-16)

### Auditoría funcional TV y móvil

- **TV**: Home/Programación, búsqueda real, Directorio, detalle con paginado,
  orden y Todo visto, favoritos/progreso por perfil, sync Supabase, idiomas,
  reproductor nativo HLS con fallback WebView, perfiles y actualización.
- **Móvil**: Home, búsqueda del catálogo cargado, detalle con orden/Todo visto,
  favoritos/progreso JSON, clasificación real de Pendientes/Al día, sync
  Supabase, idiomas y reproductor táctil fullscreen.
- **Actualizaciones**: ambas aplicaciones filtran el asset de su dispositivo
  (`jkanime-tv-*.apk` o `jkanime-mobile-*.apk`). El menú **Actualizar** fuerza
  la consulta aunque ya se haya comprobado recientemente.
- **Favoritos móviles**: los contadores se consultan al entrar en Favoritos.
  Se compara cada episodio publicado contra los episodios completados; un
  capítulo faltante impide clasificar el anime como Al día.

### Limpieza aplicada

- Eliminados recursos Android sin referencias detectados por Lint, incluidos
  textos de controles retirados del reproductor.
- Eliminado `adb.sh`, que quedó obsoleto al existir `adb-tv.sh` y
  `adb-mobile.sh`; ambos scripts arrancan el AVD, esperan el boot e instalan la
  aplicación correspondiente.
- El menú móvil distingue ahora **Refrescar catálogo** de **Actualizar**.
- Los valores secundarios de idioma/sync usan una fuente menor y una sola línea.
- Validación técnica actual: `./gradlew test` y `:app:lintDebug` /
  `:app-mobile:lintDebug` pasan; Lint conserva únicamente advertencias
  informativas de dependencias, orientación, recursos de iconos y APIs
  recomendadas.

## F21 — foco del D-pad en el reproductor de TV (2026-11-17)

**Síntoma.** Con el vídeo reproduciendo y los controles ya ocultos, OK abría
el overlay; al pausar y volver a dar play los controles seguían en pantalla
pero el siguiente OK ya **no pausaba**: solo los ocultaba. Había que pulsar
Arriba para "recuperar" el botón. En el mando era indistinguible de "se perdió
el foco", porque el botón central se veía igual con y sin foco.

**Causa raíz** (confirmada con `logcat`, tag `JkFocus`):

```text
ROOT key=DPAD_CENTER type=KeyDown overlay=false focused=false playing=true
ROOT key=DPAD_CENTER type=KeyUp   overlay=true  focused=false playing=true
PLAY_BTN focus=false
VISIBILITY_CHANGED overlay=true
...
TOGGLE_PLAY overlay=true focused=true playing=true   <- play
PLAY_BTN focus=false
VISIBILITY_CHANGED overlay=false                     <- overlay destruido
```

El callback del botón hacía `overlayVisible = !playing`. Al reanudar, el
overlay se **destruía** y con él su nodo enfocable; `overlayHasFocus` seguía
en `true` (nadie lo reseteaba) y el `Box` raíz ignoraba el OK. Al volver a
mostrar los controles nadie pedía el foco, así que el siguiente OK caía en el
`Box` raíz sin foco y solo alternaba visibilidad.

**Solución** (`app/.../feature/player/PlayerScreen.kt`):

1. `overlayFocusRequest` (contador) + `LaunchedEffect` que enfoca play/pausa
   con reintento multi-frame (12 frames). Solo se dispara cuando el usuario lo
   pide (`showControls(true)`), no cuando los controles aparecen solos por un
   seek o por el aviso de servidor caído.
2. `showControls(focusControls: Boolean)` centraliza "mostrar + reiniciar el
   temporizador + (opcional) llevar el foco".
3. **OK con el overlay sin foco muestra y enfoca**; con foco en un control
   devuelve `false` para que el botón reciba su click.
4. Se eliminó `overlayVisible = !playing`: al reanudar los controles se quedan
   y se auto-ocultan a los 6 s, así el foco nunca queda en el aire.
5. El auto-ocultado ya **no** exige `!overlayHasFocus` (si no, con el botón
   enfocado los controles no se iban nunca).
6. Arriba desde los controles y desde la barra oculta el overlay (ver
   `tv-51-up`); el foco vuelve al vídeo con reintento multi-frame.
7. **Indicador de foco visible**: el play/pausa central pasa a naranja con
   anillo blanco de 3 dp y escala 1.06; el `SeekBar` engorda a 7 dp y el punto
   crece a 18 dp con borde blanco al enfocarse.
8. Con `fallbackSuggested` el foco va al botón **cambiar servidor**
   (`changeServerFocus`), que es lo útil cuando la fuente no arrancó.
9. Al elegir servidor o cerrar la lista el botón desaparece: el foco se
   devuelve al play/pausa (antes quedaba huérfano y el D-pad muerto).
10. El foco inicial del reproductor también reintenta multi-frame: el
    `PlayerView` nativo tarda un frame en colocarse.

**Flujo verificado en `jktv`** (APK debug `VERSION_CODE=200…202`):

| Paso | Tecla | Resultado |
|---|---|---|
| 1 | OK (overlay oculto) | aparecen los controles y el play/pausa queda enfocado (naranja + anillo) |
| 2 | OK | pausa; los controles siguen y el foco no se mueve |
| 3 | OK | play; los controles siguen y se auto-ocultan a los 6 s |
| 4 | OK | aparecen de nuevo enfocados |
| 5 | OK | **pausa** (antes fallaba) |
| 6 | DOWN | el foco baja al `SeekBar` (barra de 7 dp + punto blanco) |
| 7 | LEFT / RIGHT | seek de ±10 s **una sola vez** (el `Box` raíz no lo repite) |
| 8 | UP | oculta los controles y devuelve el foco al vídeo |

**Trampas aprendidas**

- Un overlay compuesto condicionalmente (`if (overlayVisible) { Overlay(...) }`)
  se lleva el foco del sistema al destruirse, y volver a componerlo **no**
  devuelve el foco: hay que pedirlo explícitamente.
- Cualquier bandera tipo `overlayHasFocus` que dependa de la existencia del
  nodo hay que resetearla cuando ese nodo desaparece.
- El foco no se puede pedir durante la misma composición: `requestFocus()`
  devuelve `false` en silencio (o lanza) hasta que el nodo está colocado.
  Reintentar con `withFrameNanos { }` es el patrón que funciona en TV.
- Un botón sin estado de foco visual hace que el bug sea invisible en
  capturas: conviene marcar el foco con color + borde + escala.

## F22 — favoritos concluidos siempre en "Pendientes" (TV, 2026-11-18)

**Síntoma:** en la pantalla de Favoritos de la TV, animes ya terminados y
vistos completos aparecían en el grupo **Pending** con el badge "Ep N" en vez
de "Up to date" (verde). Los que estaban en emisión sí se clasificaban bien.

**Causa raíz:** el sitio **no publica el bloque "Último episodio"** (`a#uep`)
en las fichas de animes concluidos: solo traen "Episodios: N". Comprobado con
curl:

| slug | `a#uep` | Episodios | estado |
|---|---|---|---|
| `tensei-shitara-ken-deshita-ii` (en emisión) | sí (8) | 0 | currently |
| `shingeki-no-kyojin` | **no** | 25 | finished |
| `tantei-wa-mou-shindeiru` | **no** | 12 | finished |
| `kimetsu-no-yaiba` | **no** | 26 | finished |

`AnimeDetailParser` devuelve `lastEpisodeNumber = null` en esos casos, y
`FavoriteEntity.pendingEpisodes` solo miraba `last_aired_episode` → `null` →
la partición `(pendingEpisodes ?: 1) > 0` la mandaba a **Pendientes** para
siempre. La app móvil no lo sufría porque calcula con
`max(totalEpisodes, lastAiredEpisode)`.

**Fix (`JkDatabase.kt`):** el contador de emitidos cae al total:
`val released = lastAiredEpisode ?: totalEpisodes ?: return null`. El último
emitido sigue mandando cuando existe, así que una serie en emisión cuyo total
planificado sea mayor no infla los pendientes (`aired=8, total=24` → 0 si se
vio el 8).

Otros dos arreglos del mismo flujo:

- `LibraryRepository.saveProgress` usaba `setLastWatched` (sobrescribe): al
  **repasar un capítulo viejo**, `last_watched_episode` bajaba y el anime
  saltaba a "+9 pendientes" aunque estuviera al día. Ahora usa
  `bumpLastWatched` (`MAX`), igual que el marcado manual.
- `mergeFavorite` adoptaba el remoto tal cual. Como `updateAired` no toca
  `updated_at` (no debe falsear el last-write-wins del progreso), un
  dispositivo que sincronizaba después con una copia vieja **borraba** el
  `last_aired_episode`/`total_episodes` ya refrescado y el anime volvía a
  Pendientes. Ahora se conserva el mayor de ambos para esos dos campos
  derivados (monótonos) y el `statusLabel` si el remoto no lo trae.

Además, `HomeViewModel.openFavorites` refresca el detalle de los favoritos con
más de 30 min sin sincronizar (fail-open, secuencial): antes el dato de
emisión solo se actualizaba con el worker de 6 h, así que una serie en
emisión podía seguir mostrando "+0" con capítulos nuevos ya publicados.

**Verificación (emulador `jktv`, API 36, build debug 1.5.14):**

1. Datos inyectados en Room (`owner=1`, checkpoint del WAL, `run-as` + `cp`):

   | fila | aired | total | visto | antes | después |
   |---|---|---|---|---|---|
   | TEST A | 12 | 12 | 12 | Up to date | Up to date |
   | TEST B | 12 | 12 | 9 | +3 | +3 |
   | TEST C | **null** | 12 | 12 | **Pending (Ep 12)** | **Up to date** |
   | TEST E | 8 | 12 | 8 | Up to date | Up to date |
   | TEST F | null | null | 5 | Pending (Ep 5) | Pending (Ep 5) |
2. End-to-end con el sitio real: favorito `shingeki-no-kyojin` inyectado con
   `aired=null`, `total=null`, `visto=25`, `synced_at` de hace 2 h → al abrir
   Favoritos el refresh lo completó (`total_episodes=25`,
   `status_label='Concluido'`) y la tarjeta pasó a "Up to date" con subtítulo
   verde.
3. `./gradlew test` → 0 fallos (`PendingEpisodesTest` cubre los 6 casos).

**Trampas**

- `Episodios: 0` es lo normal en las fichas de series en emisión: el parser lo
  convierte a `null` (`takeIf { it > 0 }`), así que el fallback al total no
  infla los pendientes de una serie que aún no termina.
- El WAL engaña al verificar: extraer `jk-tv.db` **sin** `-wal` muestra los
  datos viejos. Hay que copiar las tres piezas y abrir el `.db` con el `-wal`
  en el mismo directorio.

## F23 — móvil: guarda de refresco, `updatedAt` y badge sin datos (2026-11-18)

Verificación de la clasificación del móvil con el space real `51514708`
(60 favoritos vivos, 499 progresos) y tres alineaciones con la TV.

**Lo verificado (sin cambios de lógica)**

- 17 Pendientes / 42 Al día / 1 sin datos = 60.
- La lógica del móvil (`max(totalEpisodes, lastAiredEpisode)` + huecos en
  `ProgressEntity.completed`) y la de la TV (`lastAiredEpisode ?:
  totalEpisodes` − `lastWatchedEpisode`) coinciden en **60/60** favoritos.
- Spot-check con `curl` contra el sitio: `cyberpunk-edgerunners-2` (10),
  `chi-chikyuu-no-undou-ni-tsuite` (25), `hotaru-no-yomeiri` (`a#uep /6/`,
  `Episodios: 0`), `one-punch-man-3` (12), `seihantai-na-kimi-to-boku` (12) →
  los cinco coinciden con lo almacenado.
- `bukiyou-na-senpai` es el caso raro real: el sitio publica `Episodios: 0` y
  **no** trae `a#uep` aunque hay 12 capítulos vistos → Pendientes sin badge,
  igual que la TV.

**Cambios (1.5.15)**

1. `FavoritesScreen` solo refresca favoritos con `syncedAt` de más de
   `STALE_AFTER_MS` (30 min), igual que la TV. Antes pedía las 60 fichas en
   **cada** entrada: ~45-60 s de red por el rate limit de 700 ms.
   Verificado: primera entrada refrescó 2 fichas (las que tenían `syncedAt=0`)
   y la segunda entrada **0**.
2. `LibraryRepository.updateFavoriteCounters` ya **no** toca `updatedAt`, solo
   `syncedAt`: los contadores vienen del sitio, no son una edición del usuario.
   Antes cada refresco re-marcaba los favoritos como modificados y el push los
   re-subía por last-write-wins. Verificado con `syncedAt=0` y
   `updatedAt=123456789` inyectados: tras el refresco `syncedAt` avanzó y
   `updatedAt` quedó intacto.
3. Badge de favorito sin datos: ahora muestra `Ep N` (último visto) en naranja
   en vez de nada, como la TV. Nuevo string `favorites_badge_episode`
   (`values`/`values-en`).

**Refactor con test**

La clasificación salió del composable a
`ui/favorites/FavoriteStatus.kt` (`favoriteStatus(favorite, progress)`), con
`FavoriteStatusTest` (8 casos): concluido sin `a#uep` al día, un capítulo
menos, en emisión con total 0, sin datos, total 0 sin aired (`bukiyou`), huecos
(vistos 1 y 3 de 5), progreso de otro slug y progreso sin `completed`.
`./gradlew test` → 114 tests, 0 fallos, 1 skipped.

**Trampa**

- Al verificar `updatedAt` hay que **quitar las credenciales de sync** antes de
  arrancar: el pull del arranque (`JkGraph.syncEngine.syncInBackground`) trae
  la foto remota con `updatedAt` más nuevo y pisa el valor inyectado por LWW,
  así que el refresco de Favoritos parecía haberlo modificado.

### F23.1 — favoritos "sin datos": completar desde el AJAX de episodios (2026-11-18)

Con F23 quedaba **1 favorito sin datos** (`bukiyou-na-senpai`): el sitio publica
`Episodios: 0` y **no** trae `a#uep`, así que `totalEpisodes=0` y
`lastAiredEpisode=null` → "Pendientes" sin badge para siempre.

**Diagnóstico**

- `GET /bukiyou-na-senpai/` → `Episodios:</span> 0`, sin `a#uep`,
  `data-status="finished"`, `#episodes-content` **vacío** (se llena por JS).
- `POST /ajax/episodes/4368/1` (con `X-CSRF-TOKEN` y cookie de la ficha) →
  `{"total":12,"last_page":1,"data":[1..12]}`. El dato **sí** existe: solo que
  en el AJAX, no en la ficha.
- Ojo con el parser: `Episodios: 0` llega como **0**, no como `null`. La guarda
  `if (totalEpisodes != null ...)` no disparaba el fallback y la primera prueba
  parecía "no funciona". Lo correcto es `<= 0`.

**Fix (1.5.16, TV + móvil)**

`data/catalog/EpisodeCounts.kt`:

- `needsEpisodeCounts(total, aired)` → `(total ?: 0) <= 0 && (aired ?: 0) <= 0`.
- `episodeCountsOf(page, lastPageMaxNumber, status)`: `total` = `page.total` (o el
  número más alto listado); `aired` solo para `AiringStatus.CURRENTLY` (en
  concluidos manda el total, igual que antes).
- `JkanimeRepository.episodeCountsFallback(detail)`: si el detalle no trae nada,
  pide `page 1` del AJAX y, **solo** en emisión con más de una página, la última
  página para el número real del último capítulo. Se ejecuta dentro del refresco
  que ya existe (guarda de 30 min) y solo para fichas sin datos.
- TV (`HomeViewModel.refreshFavoritesIfStale`) y móvil (`FavoritesScreen`)
  consumen el fallback; ambos tratan `0` como ausente con `takeIf { it > 0 }` y
  conservan el total previo si todo falla.

**Verificado en los dos emuladores (space real `51514708`)**

- Móvil: `bukiyou-na-senpai` pasó de `total=0` a `total=12` y se movió de
  *Pending* a *Up to date* (badge verde). De paso quedaron completados
  `made-in-abyss` (13), `samurai-champloo` (26), `made-in-abyss: retsujitsu`
  (12), `isekai-meikyuu-de-harem-wo` (12): **0 favoritos sin datos** de 60.
- TV (`jktv`, favorito inyectado en Room con `aired=NULL, total=NULL, visto=12`):
  tras abrir Favoritos, Room quedó con `total_episodes=12` y la tarjeta pasó de
  "Pending (Ep 12)" a "Up to date" verde.
- Contraste con el sitio: `made-in-abyss` 13, `samurai-champloo` 26,
  `kusuriya-...-3rd-season` (`a#uep /7/`, `Episodios: 0`) → `aired=7` correcto.
- `./gradlew test` → **121 tests, 0 fallos, 1 skipped** (`EpisodeCountsTest`, 7
  casos, incluido el fixture real `episodes_no_episode_count.json`).

**Trampa**

- El refresh de Favoritos puede tardar 1-2 s por ficha con fallback (detalle +
  AJAX, con rate limit de 700 ms). Solo afecta a las que no tienen datos, y
  normalmente contrae el grupo *Pending* en la primera pasada.

### F24 — el botón "Actualizar" no instalaba nada (2026-11-18)

Síntoma reportado por el usuario: en el móvil se pulsa *Actualizar*, se ve el
diálogo, se pulsa *Update* —parece que descarga— y no pasa nada. En la TV el
instalador sí abría, pero moría en un aviso sin salida.

**Tres causas independientes**

1. **Móvil: `FileProvider` sin el directorio del APK.** El APK se descarga en
   `cache/updates/`, pero `app-mobile/src/main/res/xml/file_paths.xml` solo
   declaraba `<external-path>`. `FileProvider.getUriForFile()` lanzaba
   `IllegalArgumentException: Failed to find configured root that contains …`,
   la excepción se tragaba el flujo y el diálogo quedaba igual (además el
   mensaje de error solo se pintaba cuando **no** había actualización, así que
   ni se veía). Comprobación en logcat: no había ni rastro de
   `PackageInstallerActivity`.
2. **Móvil: falta `REQUEST_INSTALL_PACKAGES` en el manifest** (la TV sí lo
   tenía). Sin ese permiso el sistema no deja instalar el APK aunque el intent
   llegue.
3. **Ambas: sin gestionar el app-op "instalar apps desconocidas"** (Android 8+).
   Con el permiso declarado pero **no concedido**, el instalador abre un aviso
   muerto: *"For your security, your TV currently isn't allowed to install
   unknown apps from this source… You can change this in Settings."* El botón
   *Settings* de ese aviso deja al usuario en Ajustes sin saber qué activar y
   al volver ya no se reintenta nada.

**Fix (1.5.17, TV + móvil)**

- Nuevo `ApkInstaller` en cada app (TV: `feature/update/`, móvil: `update/`):
  - `canInstall()` → `PackageManager.canRequestPackageInstalls()` (API 26+).
  - `launch()` devuelve `STARTED` / `NEEDS_PERMISSION` / `NO_INSTALLER`,
    comprueba que el APK existe y pesa > 0, captura el
    `IllegalArgumentException` del FileProvider y verifica que el intent
    resuelve a un instalador.
  - `openPermissionSettings()`: `ACTION_MANAGE_UNKNOWN_APP_SOURCES` con el
    paquete, degradando a `ACTION_MANAGE_APPLICATIONS_SETTINGS`,
    `ACTION_APPLICATION_DETAILS_SETTINGS` y `ACTION_SETTINGS` (en TV el camino
    corto abre el panel de "Install unknown apps" con jkanimetv ya listado).
- El diálogo tiene ahora un tercer estado: **"Permitir e instalar"** con el
  texto explicativo. Al pulsarlo se descarga y se abre Ajustes; el APK queda en
  caché.
- **El reintento va en `onResume` de la Activity** (`resumeTick` observado por
  Compose), no en un bucle. Con un bucle desde segundo plano Android aborta el
  lanzamiento (`Background activity launch blocked!` → `Abort background
  activity starts`): el instalador nunca salía y, encima, el estado se perdía
  porque `startActivity` no lanza excepción.
- `UpdateManager` expone `apkFile()` / `installDownloaded()` para reintentar sin
  volver a descargar, y `downloadAndInstall()` devuelve `InstallLaunch`.
- Móvil: `file_paths.xml` declara `<cache-path name="updates" path="updates/"/>`
  y el manifest añade `REQUEST_INSTALL_PACKAGES`. El mensaje de error se pinta
  también cuando hay actualización (antes era invisible).

**Verificación**

- Móvil (`jkphone`), con el app-op en `default` (denegado): *Update* → abre
  `Settings → Install unknown apps → jkanime`; al activar el interruptor y
  volver con BACK, la app reintenta sola y abre el instalador
  (`PackageInstallerActivity`), sin `Background activity launch blocked`.
- Instalación real de punta a punta: APK servido por HTTP local con la **misma
  firma debug** y versión 1.5.99 → el instalador quedó en `InstallSuccess` y
  `dumpsys package` mostró `versionCode=300 / versionName=1.5.99` (el fallo
  previo con `INSTALL_FAILED_UPDATE_INCOMPATIBLE` era solo firmar debug vs el
  APK release del CI, no un bug de la app).
- TV (`jktv`): *Update* → "One permission is missing…" → panel
  *Install unknown apps* con jkanimetv → al volver, el instalador abre solo y
  tras *Update* queda `InstallSuccess` (`1.5.99`).
- `./gradlew test` → **121 tests, 0 fallos, 1 skipped**.

**Trampas**

- **`canRequestPackageInstalls()` no es fiable en Android TV**: con el app-op en
  `default` contesta `true` y el instalador rechaza igualmente el APK con el
  aviso "your TV currently isn't allowed…". La comprobación buena es leer el
  modo real del app-op (`AppOpsManager`, op `android:request_install_packages`,
  sin constante pública en el SDK) y exigir `MODE_ALLOWED`; `default` se trata
  como denegado. En el móvil pasa lo mismo conceptualmente, pero ahí
  `canRequestPackageInstalls()` sí devolvía `false`.
- `canRequestPackageInstalls()` y el listado de Ajustes pueden discrepar: tras
  una **reinstalación** (`adb install` de otra firma) el app-op de la TV volvió
  a `deny` mientras el panel mostraba el toggle; hay que concederlo de nuevo.
- No basta con comprobar el permiso y lanzar: si el lanzamiento ocurre con la
  app en segundo plano, Android lo aborta en silencio.
- Un `startActivity` bloqueado **no lanza excepción**, así que no se puede
  detectar con `runCatching`: por eso el reintento va en `onResume`.
