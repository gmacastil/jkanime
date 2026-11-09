# JK TV — cliente Android TV para jkanime.net

App de Android TV (Leanback + Compose for TV) que lista la sección **Programación**
de jkanime.net como tarjetas, muestra descripción y capítulos, reproduce los
episodios con un reproductor WebView, guarda favoritos/progreso, sincroniza
entre dispositivos (Supabase) y soporta varios perfiles locales en la misma TV
(estilo Netflix).

> ⚠️ Uso personal. JKanime distribuye contenido con derechos de autor sin licencia:
> este proyecto no se publica en Play Store (violaría la política de Google) y no
> debe monetizarse. Se distribuye como APK por sideload.

## Estado

| Fase | Alcance | Estado |
|---|---|---|
| F0 | Esqueleto multi-módulo, tema TV, navegación D-pad, manifest Leanback | ✅ |
| F1 | Parser de Programación + grilla de tarjetas + caché 10 min | ✅ |
| F2 | Parser de detalle + `ajax/episodes` (CSRF) + grilla de capítulos | ✅ |
| F3 | Player WebView + puente JS + controles D-pad + selector de fuente | ✅ |
| — | Bloqueo de anuncios/overlays en el reproductor (hit-test sweep) | ✅ |
| F4 | Favoritos + progreso persistente (Room) + "Continuar viendo" | ✅ |
| F5 | Worker de 6h (WorkManager) + badge de capítulos pendientes | ✅ |
| F6 | Sync multi-dispositivo (Supabase) + emparejamiento por código temporal | ✅ |
| F7 | Búsqueda en Home, detalle de dos columnas, player con fallback (Desu/Magi) | ✅ |
| F8 | Buscador fijo en la cabecera + menú lateral colapsable | ✅ |
| F9 | Búsqueda contra el buscador real del sitio + iconos Material | ✅ |
| F10 | Foco en el último capítulo visto + Directorio real del sitio | ✅ |
| F11 | Perfiles locales tipo Netflix (multiusuario en la misma TV) | ✅ |
| F11.1 | Puerta de entrada de perfiles ("¿Quién está viendo?") al arrancar | ✅ |
| F11.2 | Sync automático en background al entrar a un perfil | ✅ |
| F11.3 | Tarjetas Material Design 3 en el selector/creación de perfiles | ✅ |

No hay fases abiertas en el roadmap original; ver [Pendientes](#pendientes) para
tareas sueltas (requieren acceso humano a un dashboard o un segundo dispositivo
físico).

## Módulos

```
:app              Activities + navegación + features (home, detail, player,
                  directorio, profiles, sync) + Room (favoritos/progreso/perfiles)
:core:model       Modelos puros (AnimeCard, AnimeDetail, Episode, VideoServer, ...)
:core:network     OkHttp + cookie jar de sesión + rate limit + token CSRF
:core:ui          Design system TV (PosterCard, EpisodeTile, TvActionButton, estados)
:data:catalog     Parsers Jsoup + data source + repositorio con caché TTL
```

`core:model`, `core:network` y `data:catalog` son módulos JVM puros: los parsers se
testean sin emulador ni Robolectric.

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

# instalar en el TV / caja Android TV
adb connect <IP-DEL-TV>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Un APK de debug ya compilado (listo para sideload) vive en `dist/` — no se
versiona en git (`*.apk` está en `.gitignore`); se regenera con
`./gradlew :app:assembleDebug` y copiándolo a `dist/`.

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

Todas las fases (F0–F11.3) están verificadas end-to-end en emulador: Home,
tabs, búsqueda real del sitio, Directorio, Detail, selector/fallback de
servidor del Player, bloqueo de anuncios, favoritos/progreso con Room, sync
multi-dispositivo con Supabase (incluido emparejamiento por código), y el
gate de perfiles con tarjetas Material Design 3. Detalle completo, bugs
encontrados/corregidos y trampas de cada fase en
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

- **WebView para reproducir, no ExoPlayer.** Los servidores entregan reproductores
  JS con ofuscado propio; no hay MP4 ni m3u8 público. Un extractor por host se
  rompe cada semana. El WebView cubre el 100% desde el día uno y el puente JS
  (`JkBridge.onProgressEvent/onEndedEvent`) integra favoritos y progreso.
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
  Desu y cae a Magi como fallback automático tras 20s sin `<video>`.
- Sin descargas offline: las fuentes son embeds de terceros.
- El anti-bot puede devolver 403 si se hacen muchas peticiones seguidas: hay caché
  y backoff, pero no hay proxy.
- Perfiles con PIN: el modelo (`pin_hash` en `ProfileEntity`) está listo pero sin
  UI — cualquier perfil es accesible sin contraseña en la misma TV.
- Login por email/OTP (portabilidad fuera de la TV) quedó descartado por ahora;
  los perfiles son solo locales a cada TV + su espacio de sync.

## Pendientes

1. **Rotar la clave `sb_secret_…` vieja en el dashboard de Supabase.** Viajó
   compilada en APKs anteriores al usarse por error como `JK_SUPABASE_ANON_KEY`.
   Dashboard → Project Settings → API keys → Legacy API keys → Rotate. La app ya
   usa la clave *publishable* correcta en `local.properties`; esto es solo
   higiene de seguridad en el proyecto Supabase (acción manual, no de código).
2. **Probar el emparejamiento con un segundo dispositivo físico real.** El
   flujo completo (generar código en un dispositivo → unirse desde otro → sync)
   está verificado en emulador y con `curl` directo contra las RPC de Supabase,
   pero falta la validación end-to-end con dos TVs/dispositivos reales.

Ver también [docs/emulator-testing.md](docs/emulator-testing.md) para el
detalle de verificación de cada fase y las trampas conocidas del emulador.
