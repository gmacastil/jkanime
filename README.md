# JK — clientes de jkanime.net para Android TV y móvil

Dos apps en un mismo repositorio, con el mismo scraping y los mismos parsers:

| App | Módulo | Paquete | UI |
|---|---|---|---|
| **JK TV** | `:app` | `com.macastil.tv` | Leanback + Compose for TV, D-pad, apaisado |
| **JK Mobile** | `:app-mobile` | `com.macastil.mobile` | Compose Material3 táctil, vertical |

Listan la sección **Programación** de jkanime.net como tarjetas, muestran
descripción y capítulos, reproducen los episodios, guardan favoritos y progreso,
y sincronizan entre dispositivos (Supabase). La app de TV añade varios perfiles
locales en la misma TV (estilo Netflix) y reproducción nativa HLS.

> ⚠️ Uso personal. JKanime distribuye contenido con derechos de autor sin licencia:
> este proyecto no se publica en Play Store (violaría la política de Google) y no
> debe monetizarse. Se distribuye como APK por sideload.

## Estado actual — 1.5.9 (2026-11-17)

### Auditoría funcional TV y móvil

- **TV**: Home/Programación, búsqueda real, Directorio, detalle con paginado,
  orden y Todo visto, favoritos/progreso por perfil, sync Supabase, idiomas,
  reproductor nativo HLS con fallback WebView, perfiles y actualización.
- **Móvil**: Home con buscador real, búsqueda del catálogo cargado, detalle con
  orden/Todo visto, favoritos/progreso JSON, clasificación real de Pendientes/Al
  día, sync Supabase, idiomas y reproductor táctil fullscreen.
- **Actualizaciones**: ambas aplicaciones filtran el asset de su dispositivo
  (`jkanime-tv-*.apk` o `jkanime-mobile-*.apk`). El menú **Actualizar** fuerza
  la consulta aunque ya se haya comprobado recientemente.
- **Favoritos móviles**: los contadores se consultan al entrar en Favoritos.
  Se compara cada episodio publicado contra los episodios completados; un
  capítulo faltante impide clasificar el anime como Al día.

### F21 — foco del D-pad en el reproductor de TV (2026-11-17)

**Síntoma.** Con el vídeo reproduciendo y los controles ya ocultos, OK abría
el overlay; al pausar y volver a dar play los controles seguían en pantalla
pero el siguiente OK ya **no pausaba**: solo alternaba visibilidad. Había que
pulsar Arriba para "recuperar" el botón. En el mando era indistinguible de
"se perdió el foco", porque el botón central se veía igual con y sin foco.

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
overlay se **destruía** y con él su nodo enfocable, pero `overlayHasFocus`
seguía en `true` (nadie lo reseteaba) y el `Box` raíz ignoraba el OK. Al volver
a mostrar los controles nadie pedía el foco, así que el siguiente OK caía en
el `Box` raíz sin foco y solo alternaba visibilidad.

**Solución** (`app/.../feature/player/PlayerScreen.kt`):

1. `showControls(focus)` centraliza mostrar + reiniciar temporizador + (opcional)
   llevar el foco al play/pausa solo cuando el usuario lo pide.
2. `overlayFocusRequest` + `LaunchedEffect` con reintento multi-frame (12 frames).
   Solo se dispara cuando el usuario lo pide (`showControls(true)`), no cuando
   los controles aparecen solos (seek o aviso de servidor caído).
3. **OK con el overlay sin foco muestra y enfoca**; con foco en un control
   devuelve `false` para que el botón reciba su click.
4. Se eliminó `overlayVisible = !playing`: al reanudar los controles se quedan
   y se auto-ocultan a los 6 s, así el foco nunca queda en el aire.
5. El auto-ocultado ya **no** exige `!overlayHasFocus` (antes, con el botón
   enfocado, los controles no se iban nunca).
6. Arriba desde los controles y desde la barra oculta el overlay y devuelve el
   foco al vídeo.
7. **Indicador de foco visible**: el play/pausa pasa a naranja con anillo blanco
   de 3 dp y escala 1.06; el `SeekBar` engorda a 7 dp y el punto crece a 18 dp
   con borde blanco al enfocarse.
8. Con el aviso de servidor caído el foco va a **cambiar servidor**;
   al elegir o cerrar la lista el botón desaparece: el foco se devuelve al
   play/pausa (antes quedaba huérfano y el D-pad muerto).
9. El foco inicial del reproductor también reintenta multi-frame: el
   `PlayerView` nativo tarda un frame en colocarse.

**Flujo verificado en `jktv`** (VERSION_CODE 200-202):

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
- Reintentar con `withFrameNanos { }` es el patrón que funciona en TV.
- Un botón sin estado de foco visual hace que el bug sea invisible en
  capturas: conviene marcar el foco con color + borde + escala.
- **Lección del bug:** un overlay compuesto condicionalmente (`if (overlayVisible) { Overlay(...) }`)
  se lleva el foco del sistema al destruirse; hay que reponerlo explícitamente
  al mostrarlo.

### Limpieza aplicada

- Eliminados recursos Android sin referencias detectados por Lint, incluidos
  textos de controles retirados del reproductor.
- Eliminado `adb.sh`, que quedó obsoleto al existir `adb-tv.sh` y
  `adb-mobile.sh`; ambos scripts arrancan el AVD, esperan el boot e instalan
  la aplicación correspondiente.
- El menú móvil distingue ahora **Refrescar catálogo** de **Actualizar**.
- Los valores secundarios de idioma/sync usan una fuente menor y una sola línea.
- Validación técnica actual: `./gradlew test` y `:app:lintDebug` /
  `:app-mobile:lintDebug` pasan; Lint conserva únicamente advertencias
  informativas de dependencias, orientación, recursos de iconos y APIs
  recomendadas.

## Funciones desarrolladas

### Catálogo y navegación
- **Directorio móvil**: filtros tipo `<select>` presentados como chips, grilla de
  30 fichas por página y paginado inferior. Desde el menú lateral (`icono List`
  junto a Favoritos).
- **Búsqueda en Home**: campo de búsqueda en la TopAppBar con debounce 450 ms,
  limpiar (x) y mostrar "N resultados". Al escribir, la grilla pasa de Programación
  a resultados del buscador real del sitio (hasta 30 fichas, sin paginación).
- **HomeScreen**: `SearchField` reutilizable, `SearchStatus` / `SearchResults`
  / `SearchCard` con `titleMaxLines = 1`.
- **AppDrawer**: nuevo ítem "Directorio" (icono List) junto a Favoritos.

### Actualizaciones

- `showControls(focus)` centraliza mostrar + reiniciar temporizador + pedir foco.
- Se elimina `overlayVisible = !playing`: al reanudar el overlay ya no se
  destruye y el foco no queda huerfano.
- OK con el overlay sin foco lo enfoca; Arriba vuelve al video y Abajo baja
  a la barra de progreso.
- Indicador de foco visible en play/pausa y en la barra de progreso.
- Al cambiar de servidor el foco vuelve al play/pausa o al boton de cambiar
  servidor cuando la fuente no arrancó.
- `CancellationException` relanzada; solo los fallos reales se muestran como
  error.
- `UpdateManager` (TV y móvil) propaga los fallos de red/JSON en vez de
  devolver `null`, para distinguir 'no hay versión nueva' de 'no se pudo
  consultar'; la cache de 6 h solo se marca si la consulta tuvo éxito.
- El menú **Actualizar** de la TV fuerza la consulta (`force = true`).

### Sincronización Supabase

- Space `51514708` con 53 favoritos y 394 progress, compartido entre 2 TVs y
  Pixel 7.
- Sync via RPC `SECURITY DEFINER` con códigos de emparejamiento de 8 dígitos.
- No es necesario crear spaces nuevos; el espacio existente con tus datos ya
  está configurado y tus dispositivos están sincronizados.

## Versiones

| Version | Contenido | Fecha |
|---|---|---|
| **1.5.9** | Directorio y búsqueda móvil, foco robusto TV, limpieza de opciones | 2026-11-17 |
| 1.5.8 | Fix de 'no se pudo comprobar si hay actualizaciones' | 2026-11-17 |
| 1.5.7 | Fix del foco del D-pad del reproductor TV al pausar y reanudar | 2026-11-17 |
| 1.5.6 | Actualización manual TV/móvil, foco robusto del diálogo y limpieza | 2026-11-16 |

## Descargas

| Release | APK | Tamaño |
|---|---|---|
| `v1.5.9-tv` | `jkanime-tv-1.5.9.apk` | 11,7 MB |
| `v1.5.9-mobile` | `jkanime-mobile-1.5.9.apk` | 12,1 MB |

CI: `./gradlew test`, `:app:lintDebug` y `:app-mobile:lintDebug` en verde.

## Funcionamiento de sincronización

- Space `51514708` en Supabase con 53 favoritos y 394 progress
- Todos los dispositivos (2 TVs + Pixel 7) usan ese space
- Sync via RPC `SECURITY DEFINER` con códigos de emparejamiento
- No es necesario crear spaces nuevos; el espacio existente con tus datos ya
  está configurado y tus dispositivos están sincronizados

## Limitaciones conocidas

- `jkplayer` (player propio del sitio) puede responder **500** en algunos
  episodios (fallo del sitio, no de la app); por eso el player arranca con
  Desu y cae a Magi como fallback automático cuando no obtiene una fuente HLS
  reproducible.
- Sin descargas offline: las fuentes son embeds de terceros.
- El anti-bot puede devolver 403 si se hacen muchas peticiones seguidas: hay caché
  y backoff, pero no hay proxy.
- Perfiles con PIN: el modelo (`pin_hash` en `ProfileEntity`) está listo pero sin
  UI — cualquier perfil es accesible sin contraseña en la misma TV.
- Login por email/OTP (portabilidad fuera de la TV) quedó descartado por ahora;
  los perfiles son solo locales a cada TV + su espacio de sync.

## Enlaces útiles

- [GitHub Releases](https://github.com/gmacastil/jkanime/releases)
- [docs/emulator-testing.md](docs/emulator-testing.md) - Bitácora de fases y verificación
- [docs/updates.md](docs/updates.md) - Registro de actualizaciones y errores

---

Versión 1.5.9 — Sincronización activa entre 2 TVs y Pixel 7 con space `51514708`.
| F7 | Búsqueda en Home, detalle de dos columnas, player con fallback (Desu/Magi) | ✅ |
| F8 | Buscador fijo en la cabecera + menú lateral colapsable | ✅ |
| F9 | Búsqueda contra el buscador real del sitio + iconos Material | ✅ |
| F10 | Foco en el último capítulo visto + Directorio real del sitio | ✅ |
| F11 | Perfiles locales tipo Netflix (multiusuario en la misma TV) | ✅ |
| F11.1 | Puerta de entrada de perfiles ("¿Quién está viendo?") al arrancar | ✅ |
| F11.2 | Sync automático en background al entrar a un perfil | ✅ |
| F11.3 | Tarjetas Material Design 3 en el selector/creación de perfiles | ✅ |
| F12 | Ajustes de UI: Directorio simplificado, Recientes, badges de favoritos e icono de menú | ✅ |
| F13 | Internacionalización completa español/inglés y selector de idioma por perfil | ✅ |
| F14 | Release firmado, actualización en línea y correcciones de filtros/foco | ✅ |
| 1.5.1 | App móvil: menú con perfil/idioma/sync, reproductor usable y grilla completa | ✅ |
| 1.5.2 | App móvil: todos los modos de sincronización de la TV + auto-sync al arrancar | ✅ |
| 1.5.3 | Fix del vídeo negro en móvil (documento colapsado a 0 px) + vídeo 16:9 en horizontal | ✅ |
| 1.5.4 | Sync unificado TV/móvil (clave de 6 hex en el gate) + reproductor móvil a pantalla completa con una sola barra | ✅ |
| 1.5.5 | Detalle móvil con ordenar/Todo visto, clasificación real de favoritos y controles de player con auto-ocultado | ✅ |
| 1.5.6 | Actualización manual TV/móvil, foco robusto del diálogo y del reproductor TV, limpieza de opciones obsoletas | ✅ |
| 1.5.7 | Fix del foco del D-pad del reproductor TV al pausar y reanudar | ✅ |
| 1.5.8 | Fix de «no se pudo comprobar si hay actualizaciones» en móvil (efecto que se cancelaba a sí mismo) | En desarrollo |

No hay fases abiertas en el roadmap original; ver [Pendientes](#pendientes) para
tareas sueltas (requieren acceso humano a un dashboard o un segundo dispositivo
físico).

## Funciones desarrolladas

### Catálogo y navegación

- Home con la programación reciente del sitio en tarjetas TV.
- Búsqueda contra el buscador real de jkanime, con debounce, estados de carga,
  errores, reintento y hasta 30 resultados.
- Directorio real con paginación y filtros visibles para **Por fecha**, **Por
  nombre**, **Por popularidad** y géneros.
- Panel de filtros angosto, scrollable y compatible con D-pad; las opciones se
  conservan durante la carga para permitir seleccionar varios filtros seguidos.
- Detalle del anime con descripción, estado, tipo, próximos capítulos y lista
  ordenable de episodios.
- Foco inicial del detalle en el último episodio empezado o visto.
- Navegación completa con mando/D-pad, incluyendo foco inicial, recuperación de
  foco al cambiar de sección y soporte para teclado virtual.

### Biblioteca personal

- Favoritos por perfil.
- Progreso persistente por episodio con posición, estado iniciado/completado y
  reanudación.
- Botón de continuar viendo.
- Badges de favoritos con capítulos pendientes (`+N`) y estado **Al día** solo
  cuando todos los capítulos publicados están vistos.
- TV: WorkManager actualiza cada seis horas los datos de emisión.
- Móvil: los contadores se refrescan al entrar en Favoritos, no desde Home ni
  mediante un evento global.

### Perfiles y sincronización

- Perfiles locales múltiples en la misma TV, con selector tipo Netflix.
- **En el móvil hay un solo perfil** (no es multiusuario): el menú lateral
  muestra su nombre, permite renombrarlo y expone las mismas opciones de idioma
  y sincronización que un perfil de la TV.
- **La sincronización del móvil replica los cuatro modos de la TV**: crear
  espacio, vincular con código temporal (8 dígitos, 15 min), recuperar con
  clave (6 hex) y — ya con espacio — sincronizar ahora, generar código para
  otro dispositivo, definir la clave de recuperación y desvincular.
- Tras crear el espacio se pide la clave de recuperación, igual que en la TV:
  sin ella, perder el token del dispositivo equivale a perder el espacio.
- El móvil sincroniza solo al arrancar (además del botón manual), como la TV al
  entrar en un perfil.
- Aislamiento de favoritos, progreso y credenciales de sincronización por perfil.
- Creación, activación, eliminación y cambio de perfil.
- Sincronización multi-dispositivo mediante Supabase.
- Merge last-write-wins con `updated_at` y tombstones para borrados.
- Emparejamiento mediante código temporal de un solo uso, válido durante 15
  minutos; el token completo nunca se introduce en el mando.
- Errores de la API de sync traducidos: código vencido, clave inválida o
  `schema.sql` sin aplicar se explican en pantalla en vez de mostrar un
  `JSONException`.
- Sincronización automática al arrancar y al entrar/cambiar de perfil.

### Idiomas

- UI completa extraída a recursos Android: español por defecto e inglés.
- Selector **Sistema / Español / English** en el menú del avatar y en la puerta
  de perfiles.
- Aplicación inmediata del idioma mediante `AppLocale` y recreación de la
  Activity.
- La preferencia se guarda en `SharedPreferences` (`jk-profiles`, clave
  `lang`).
- En el móvil el idioma vive en su propio `AppLocale` (módulo
  `:app-mobile`, prefs `jk-mobile-settings`) con las mismas tres opciones.

### Reproducción

- Reproducción de fuentes directas HLS mediante Media3/ExoPlayer para evitar la
  pantalla negra observada en algunos televisores con WebView.
- Fallback entre servidores Desu y Magi cuando una fuente falla o no inicia.
- Controles TV: OK sobre el vídeo muestra los controles y deja el foco en
  play/pausa (naranja con anillo blanco), OK pausa/reanuda, Arriba vuelve al
  vídeo y Abajo baja a la barra de progreso, donde ◀ ▶ busca ±10 s. Los
  controles se auto-ocultan a los 6 s y el foco se repone siempre que se
  muestran, así que pausar y reanudar repetidamente nunca lo pierde.
- En el móvil el vídeo ocupa la pantalla en apaisado; play/pausa y ±10 s están
  sobre el vídeo, con doble toque a izquierda/derecha para buscar. La barra
  inferior conserva únicamente slider, tiempo y salir.
- El `WebPlayer` móvil usa el User-Agent del Chrome del sistema (con UA de TV
  los hosts servían un player sin zonas táctiles).
- Guardado periódico del progreso y reanudación en la posición anterior.
- Bloqueo de anuncios y overlays en los embeds mediante interceptación de red y
  hit-test sweep cuando aplica al reproductor web.

## Módulos

```
:app              TV: Activities + navegación + features (home, detail, player,
                  directorio, profiles, sync) + Room (favoritos/progreso/perfiles)
:app-mobile       Móvil: navegación por estado + home/detail/player/favoritos/sync,
                  menú lateral (perfil único, idioma, sync), biblioteca persistida
                  en SharedPreferences + JSON, recursos es/en propios
:core:model       Modelos puros (AnimeCard, AnimeDetail, Episode, VideoServer, ...)
:core:network     OkHttp + cookie jar de sesión + rate limit + token CSRF
:core:ui          Design system TV (PosterCard, EpisodeTile, TvActionButton, estados)
:data:catalog     Parsers Jsoup + data source + repositorio con caché TTL
```

`core:model`, `core:network` y `data:catalog` son módulos JVM puros: los parsers se
testean sin emulador ni Robolectric.

### Iconos

Las dos apps comparten icono de marca (el conejo enmascarado, silueta naranja
sobre `#0E0E11`) y se generan con un solo script:

```bash
python3 tools/make-icons.py
```

Escribe `mipmap-anydpi-v26/ic_launcher.xml` (adaptive, con capa monocroma),
los PNG legacy por densidad y las capas `drawable/ic_launcher_foreground.png` /
`ic_launcher_monochrome.png` en `:app` y `:app-mobile`. La fuente es
`app/src/main/res/drawable/caiman_icon.png`.

## Build

Requisitos: JDK 17+, Android SDK con `platforms;android-36` y `build-tools;36.0.0`.

```bash
# tests de parsers (rápidos, sin emulador)
./gradlew :core:network:test :data:catalog:test

# smoke test contra el sitio real (valida HTTP + CSRF + parsers de punta a punta)
JK_LIVE=1 ./gradlew :data:catalog:test --tests "*LiveSmokeTest*"

# tests unitarios de todo el proyecto
./gradlew test

# APK debug
./gradlew :app:assembleDebug
# salida: app/build/outputs/apk/debug/app-debug.apk

# APK debug de la app móvil
./gradlew :app-mobile:assembleDebug
# salida: app-mobile/build/outputs/apk/debug/app-mobile-debug.apk

# instalar en el TV / caja Android TV
adb connect <IP-DEL-TV>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release firmado y publicación

El workflow `.github/workflows/release.yml` se ejecuta cuando se publica un tag
`vX.Y.Z`. Construye **las dos** APKs con la clave permanente y publica **dos
releases separados**, cada uno con una sola APK:

| Tag publicado | APK | Para |
|---|---|---|
| `vX.Y.Z-tv` | `jkanime-tv-X.Y.Z.apk` | Android TV (Leanback) |
| `vX.Y.Z-mobile` | `jkanime-mobile-X.Y.Z.apk` | teléfono / tablet |

Así, al abrir la página de releases desde un celular solo se ofrece la APK
móvil (y desde el televisor solo la de TV). El job verifica con `aapt2 dump
badging` que la APK de TV declara Leanback y la móvil no, y aborta si se
cruzan. Los tags `-tv` / `-mobile` están excluidos del disparador para no
relanzar el pipeline.

```bash
# 1) subir VERSION_NAME en gradle.properties y commitear
git tag -a v1.4.8 -m "jkanime 1.4.8"
git push origin main v1.4.8
```

Para un build release local:

```bash
./tools/build-apk.sh --version 0.1.4 --release
```

La firma release actual usa el alias `jkanimetv_release`. No se debe cambiar
la clave, porque Android no permite actualizar una instalación firmada con
otra.

### Actualizaciones en línea

TV y móvil consultan la lista de releases de GitHub, filtrando el APK correcto
por dispositivo:

```text
https://api.github.com/repos/gmacastil/jkanime/releases?per_page=30
```

La TV busca `jkanime-tv-*.apk` y el móvil `jkanime-mobile-*.apk`. La TV hace una
comprobación automática con caché de seis horas. Ambas apps exponen
**Actualizar** en su menú; en móvil la comprobación se ejecuta bajo demanda y en
TV la acción fuerza una consulta inmediata. Esto permite recuperar el diálogo si
se perdió el foco o se cerró anteriormente.
Al aceptar, se descarga la APK y se abre el instalador del sistema Android; la
instalación requiere confirmación del usuario.

Un APK de debug ya compilado (listo para sideload) vive en `dist/` — no se
versiona en git (`*.apk` está en `.gitignore`). Para generarlo por demanda,
en vez de `assembleDebug` + copiar a mano:

```bash
./tools/build-apk.sh              # build + copia versionada a dist/
./tools/build-apk.sh --install    # además lo instala en el dispositivo/emulador conectado
./tools/build-apk.sh --clean      # ./gradlew clean antes de compilar
./tools/build-apk.sh --out DIR    # copiar a otro directorio en vez de dist/
```

El script imprime ruta, tamaño y sha256 del APK generado.

Si el sitio cambia y los tests fallan, refresca los fixtures con
`./tools/refresh-fixtures.sh` y compara el HTML nuevo contra los selectores
documentados en [docs/scraping.md](docs/scraping.md).

### Configuración de sync (Supabase)

El sync multi-dispositivo necesita un proyecto Supabase (ver
[docs/supabase/README.md](docs/supabase/README.md) para crear el esquema).
En `local.properties` (ignorado por git):

```properties
sdk.dir=/ruta/a/android-sdk
JK_SUPABASE_URL=https://TU-PROYECTO.supabase.co
JK_SUPABASE_ANON_KEY=sb_publishable_...
```

> **Ojo con la clave:** debe ser la *publishable* (`sb_publishable_...`), nunca
> la *secret* (`sb_secret_...`) — la secreta salta RLS y no debe viajar dentro
> del APK.

Sin esta configuración la app sigue funcionando (catálogo, favoritos y
progreso locales), pero la pantalla de Sync muestra "Falta configurar el
backend".

## Probar en un emulador Android TV

No hace falta TV física para ver la app funcionando. Una sola vez:

```bash
sdkmanager --sdk_root=/data/codigo/android-sdk \
  "emulator" "system-images;android-36;android-tv;x86_64"
```

Y luego, cada vez:

```bash
./tools/run-emulator.sh          # crea el AVD "jktv", arranca, instala y abre la app
./tools/run-emulator.sh --wipe   # resetea los datos del AVD
./tools/run-emulator.sh --headless
```

También se puede compilar, arrancar el AVD e instalar directamente:

```bash
./adb-tv.sh                      # AVD jktv / emulator-5554
./adb-mobile.sh                  # AVD jkphone / emulator-5556
./adb-tv.sh --clean              # desinstala TV antes de instalar debug
```

Requisitos: `/dev/kvm` accesible (`sudo usermod -aG kvm $USER`, y volver a entrar).
Sin KVM el emulador x86_64 va a velocidad inusable.

Navegar sin mando, con `adb`:

```bash
adb shell input keyevent 20   # abajo   (19=arriba, 21=izq, 22=der, 23=OK, 4=atrás)
adb exec-out screencap -p > /tmp/jktv.png
adb logcat -s JkBridge chromium AndroidRuntime
```

> **Ojo con el emulador y el video:** los embeds usan DRM/JS de terceros y algunos
> hosts exigen hardware real. Si en el emulador el `<video>` se queda en negro,
> no significa que falle en el TV: compara con `adb logcat chromium`. Arranca con
> `-gpu host` (lo hace el script) para tener decodificación por GPU.

Y para la app **móvil**, un AVD de teléfono:

```bash
./adb-mobile.sh
adb shell input tap 66 130          # ☰ del menú lateral
```

Las pruebas unitarias y de compilación cubren ambos módulos. La verificación
end-to-end en emulador cubre Home, búsqueda, Directorio, detalle, fallback de
servidor, bloqueo de anuncios, favoritos/progreso, sync Supabase, idiomas y
gate de perfiles TV; móvil cubre además menú, reproducción táctil, pantalla
completa y actualización de contadores de favoritos. Detalle y trampas en
[docs/emulator-testing.md](docs/emulator-testing.md).

`local.properties` debe apuntar al SDK:

```properties
sdk.dir=/ruta/a/android-sdk
```

## Cómo se scrapea el sitio

Todo verificado contra el sitio real; ver [docs/scraping.md](docs/scraping.md).
Resumen:

1. **Programación** → `GET /` (HTML server-rendered, tabs `#animes`, `#donghuas`, `#ovas`).
2. **Detalle** → `GET /{slug}/` (incluye `data-anime` = id interno).
3. **Capítulos** → `POST /ajax/episodes/{animeId}/` con `_token` CSRF + cookie de sesión.
4. **Fuentes** → `GET /{slug}/{ep}/` → `var servers = [...]` con `remote` en base64.
5. **Búsqueda** → `GET /buscar/{q}` (HTML server-rendered, hasta 30 fichas, sin paginación).
6. **Directorio** → `GET /directorio` (filtros + resultados inyectados por JS en `var animes = {...}`, JSON paginado de Laravel).

## Decisiones de arquitectura

- **Media3/ExoPlayer para reproducir fuentes HLS.** La reproducción directa de
  m3u8 evita la pantalla negra observada en algunos televisores al usar WebView.
  El player conserva fallback entre servidores, controles D-pad, guardado de
  progreso y reanudación. El WebView sigue disponible para embeds o diagnóstico
  de hosts cuando la fuente directa no está disponible.
- **Bloqueo de anuncios por hit-test, no por recorrido de DOM.** Los overlays
  publicitarios se pintan en shadow-DOM/iframes invisibles a `querySelectorAll`.
  El fix real es un sweep periódico con `document.elementFromPoint(x, y)`
  (igual que un toque real) comparando superficie vs. "perforado" a través de
  shadow roots/iframes — ver [docs/emulator-testing.md](docs/emulator-testing.md).
- **DI manual (`Graph`)** en lugar de Hilt: pocas dependencias reales; Hilt solo
  añadiría tiempo de build.
- **Listas con `LazyRow` / `LazyVerticalGrid` de `androidx.compose.foundation`.**
  Compose for TV 1.0.0 eliminó `TvLazyRow`/`TvLazyColumn`; las de foundation ya
  son focus-aware. `androidx.tv:tv-material` se usa para tema, `Text`, y (desde
  F11.3) `Card`/`Surface` en el gate de perfiles.
- **Rate limit de 1 req / 700 ms + jitter** y UA de dispositivo TV: el sitio tiene
  anti-bot (`bot-detector.js`).
- **Fail-open**: si la red falla pero hay caché previa, la UI muestra la caché y
  un mensaje, nunca una pantalla vacía.
- **Fixtures reales en los tests**: cada parser se valida contra el HTML guardado,
  así un cambio de DOM se detecta en CI y no en el TV del usuario.
- **Room con `owner` (perfil) como parte de la clave primaria** en `favorites`/
  `progress`: varios perfiles locales comparten la misma TV y DB, cada uno con
  su propia biblioteca y su propio espacio de sync.
- **Sync multi-dispositivo sin Supabase Auth**: identidad = "sync space" (código
  de 8 dígitos + token de 64 caracteres) validado por RPC `SECURITY DEFINER`,
  sin políticas RLS por usuario. Merge last-write-wins por `updated_at` +
  tombstones (`deleted_at`). El emparejamiento entre dispositivos usa un código
  temporal de un solo uso (15 min) para no tener que teclear el token completo.

## Estructura de un flujo

```
ProfileGateScreen ──perfil──▶ HomeScreen ──card──▶ DetailScreen ──episodio──▶ PlayerActivity (WebView)
                                 │                     │
                                 └─ JkanimeRepository ─┴─ JkanimeCatalogDataSource ── JkHttpClient
                                       │                                                 │
                                 LibraryRepository (Room, owner=perfil)            catalog.parser (Jsoup / JSON / base64)
                                       │
                                 SyncEngine ── Supabase (pull/push, pairing)
```

## Limitaciones conocidas

- `jkplayer` (player propio del sitio) puede responder **500** en algunos
  episodios (fallo del sitio, no de la app); por eso el player arranca con
  Desu y cae a Magi como fallback automático cuando no obtiene una fuente HLS
  reproducible.
- Sin descargas offline: las fuentes son embeds de terceros.
- El anti-bot puede devolver 403 si se hacen muchas peticiones seguidas: hay caché
  y backoff, pero no hay proxy.
- Perfiles con PIN: el modelo (`pin_hash` en `ProfileEntity`) está listo pero sin
  UI — cualquier perfil es accesible sin contraseña en la misma TV.
- Login por email/OTP (portabilidad fuera de la TV) quedó descartado por ahora;
  los perfiles son solo locales a cada TV + su espacio de sync.

## Pendientes

1. **Probar el emparejamiento con un segundo dispositivo físico real.** El
   flujo completo (generar código en un dispositivo → unirse desde otro → sync)
   está verificado en emulador y con `curl` directo contra las RPC de Supabase,
   pero falta la validación end-to-end con dos TVs/dispositivos reales.

Ver también [docs/emulator-testing.md](docs/emulator-testing.md) para el
detalle de verificación de cada fase y las trampas conocidas del emulador.
