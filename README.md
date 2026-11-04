# JK TV — cliente Android TV para jkanime.net

App de Android TV (Leanback + Compose for TV) que lista la sección **Programación**
de jkanime.net como tarjetas, muestra descripción y capítulos, y reproduce los
episodios con un reproductor WebView.

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
| F4 | Favoritos + progreso (Room) + "Continuar viendo" | ⬜ |
| F5 | Worker de 6 h + badge de capítulos pendientes | ⬜ |
| F6 | Búsqueda + directorio | ⬜ |
| F7 | Player Media3 (resolver HLS por host) | ⬜ |

## Módulos

```
:app              Activities + navegación + features (home, detail, player)
:core:model       Modelos puros (AnimeCard, AnimeDetail, Episode, VideoServer)
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

# APK debug
./gradlew :app:assembleDebug
# salida: app/build/outputs/apk/debug/app-debug.apk

# instalar en el TV / caja Android TV
adb connect <IP-DEL-TV>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Si el sitio cambia y los tests fallan, refresca los fixtures con
`./tools/refresh-fixtures.sh` y compara el HTML nuevo contra los selectores
documentados en [docs/scraping.md](docs/scraping.md).

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

## Decisiones de arquitectura

- **WebView para reproducir, no ExoPlayer.** Los servidores entregan reproductores
  JS con ofuscado propio; no hay MP4 ni m3u8 público. Un extractor por host se
  rompe cada semana. El WebView cubre el 100% desde el día uno y el puente JS
  (`JkBridge.onProgress/onEnded`) ya deja lista la integración con favoritos.
- **DI manual (`Graph`)** en lugar de Hilt: 3 dependencias reales; Hilt solo
  añadiría tiempo de build. Se reevalúa al entrar Room + WorkManager.
- **Listas con `LazyRow` / `LazyVerticalGrid` de `androidx.compose.foundation`.**
  Compose for TV 1.0.0 eliminó `TvLazyRow`/`TvLazyColumn`; las de foundation ya
  son focus-aware. `androidx.tv:tv-material` se usa solo para tema y `Text`.
- **Rate limit de 1 req / 700 ms + jitter** y UA de dispositivo TV: el sitio tiene
  anti-bot (`bot-detector.js`).
- **Fail-open**: si la red falla pero hay caché previa, la UI muestra la caché y
  un mensaje, nunca una pantalla vacía.
- **Fixtures reales en los tests**: cada parser se valida contra el HTML guardado,
  así un cambio de DOM se detecta en CI y no en el TV del usuario.

## Estructura de un flujo

```
HomeScreen ──card──▶ DetailScreen ──episodio──▶ PlayerActivity (WebView)
   │                     │
   └─ JkanimeRepository ─┴─ JkanimeCatalogDataSource ── JkHttpClient
                              │
                        catalog.parser (Jsoup / JSON / base64)
```

## Limitaciones conocidas

- `jkplayer` (player propio del sitio) respondió **500** desde IP de datacenter.
  En el dispositivo puede funcionar; por eso la fuente primaria es el embed
  decodificado de `servers[]` y `jkplayer` queda como alternativa.
- Sin descargas offline: las fuentes son embeds de terceros.
- El anti-bot puede devolver 403 si se hacen muchas peticiones seguidas: hay caché
  y backoff, pero no hay proxy.
- Pendiente F4/F5: favoritos, progreso y detección de capítulos pendientes
  necesitan Room + WorkManager (el modelo `Episode`/`AnimeDetail` ya expone todo
  lo necesario: `animeId`, `number`, `lastEpisodeNumber`).
