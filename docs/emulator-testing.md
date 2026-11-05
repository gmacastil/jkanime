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
