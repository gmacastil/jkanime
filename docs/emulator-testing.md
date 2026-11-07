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

## F8 — Cabecera con menú lateral, buscador fijo e icono de orden — verificado

### Home

- La cabecera ya no tiene la fila de botones (Animes/Donghuas/OVAs/Favoritos/
  Sync) ni el texto "Programación": queda `☰` + marca + campo de búsqueda en
  forma de píldora, siempre visible y ocupando el ancho restante.
- Los filtros viven en un `SideDrawer` de 260 dp sobre un velo negro al 60 %.
  Se abre con `☰`, se cierra con `BACK` (`BackHandler`) o al elegir una entrada.
  Al abrir, el foco va al primer ítem (`Animes`); al cerrar, la grilla lo
  recupera.
- La búsqueda ya no es un modo: `query` no vacío gana sobre la sección y sobre
  favoritos. Con la consulta vacía se ve la sección elegida.
- La entrada `Favoritos` del menú **muestra** favoritos (antes era un toggle);
  para volver a una sección se elige en el mismo menú.

**Trampa:** al elegir una entrada del menú, el ítem enfocado desaparece del
árbol y el D-pad queda muerto. `HomeScreen` lleva un `focusNonce` que se
incrementa en cada selección y entra en la clave del `LaunchedEffect` que pide
el foco de la primera tarjeta (si no, con las mismas tarjetas el efecto no se
relanza). Si el contenido queda vacío (favoritos sin nada) el foco cae al botón
`☰`, que siempre existe.

**Trampa:** el velo del menú **no** bloquea el foco geométricamente. Con el
menú abierto, `DPAD_RIGHT` desde un ítem puede aterrizar en una tarjeta de la
grilla de detrás; para salir del menú está `BACK` o elegir una entrada.

### Detalle

- El botón de orden pasó a `TvIconButton("⇅")` pegado al texto
  `Capítulos (N)`; el estado actual se lee en una etiqueta pequeña a la derecha
  de la fila (`Nuevos primero` / `Antiguos primero`).
- El `LaunchedEffect` que pide el foco inicial **no** incluye el orden en su
  clave: si lo incluyera, al tocar `⇅` el foco saltaba a la primera fila y no
  se podía volver a invertir sin navegar de nuevo.

Verificado en emulador: `☰` → menú con foco en `Animes` → `Donghuas` cierra y
recarga la grilla con foco en la primera tarjeta; `BACK` cierra el menú sin
cambiar de sección; `Favoritos` vacío deja el foco en `☰`; `Sync` abre la
pantalla de sincronización; la búsqueda filtra al instante (`majutsu` → 2
resultados, etiqueta `Animes · Jueves 05`) y `⇅` invierte el orden sin perder el
foco.
