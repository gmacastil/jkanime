# Contratos de scraping (verificados contra el sitio real)

Fecha de verificación: **2026-11-04**. Todo lo de abajo se probó con peticiones
reales y quedó guardado como fixture en
`data/catalog/src/test/resources/fixtures/`.

Si el sitio cambia, el test correspondiente falla antes de que el usuario vea una
pantalla vacía.

| Fixture | Origen |
|---|---|
| `home.html` | `GET https://jkanime.net/` |
| `anime_detail.html` | `GET https://jkanime.net/seitokai-ni-mo-ana-wa-aru/` |
| `episode.html` | `GET https://jkanime.net/seitokai-ni-mo-ana-wa-aru/5/` |
| `episodes_page1.json` | `POST https://jkanime.net/ajax/episodes/4967/` |

---

## 1. Programación (novedades)

`GET /` — HTML server-rendered. Sin AJAX: las 4 pestañas vienen completas.

```html
<div class="tab-content">
  <div class="tab-pane fade show active" id="animes">
    <div class="card">
      <a href="https://jkanime.net/kikansha-no-mahou-wa-tokubetsu-desu-2nd-season/5/">
        <img class="card-img-top" src=".../jkvideo_d27a51ea...jpg"
             data-animepic=".../animes/image/kikansha-....jpg">
        <div class="badges">
          <span class="badge badge-primary">Ep 5</span>
          <span class="badge badge-secondary">Hoy</span>
        </div>
        <h5 class="strlimit card-title">Kikansha no Mahou wa Tokubetsu desu 2nd Season</h5>
      </a>
    </div>
  </div>
  <div class="tab-pane" id="donghuas">…</div>
  <div class="tab-pane" id="ovas">…</div>
  <div class="tab-pane" id="hentai">…</div>   <!-- excluido a propósito -->
</div>
```

Selectores usados (`ProgramacionParser`):

| Dato | Selector |
|---|---|
| tarjeta | `div.tab-pane#<anchor> div.card` |
| URL episodio | `a[href]` → `/slug/N/` |
| thumbnail 16:9 | `img.card-img-top[src]` |
| póster 2:3 | `img[data-animepic]` |
| episodio | `span.badge-primary` → `"Ep 5"` |
| fecha relativa | `span.badge-secondary` → `Hoy` / `Ayer` / `Lunes 02` |
| título | `h5.card-title` |

Slug y número de episodio se derivan del path: `/slug/N/`.

## 2. Detalle del anime

`GET /{slug}/`

| Dato | Selector |
|---|---|
| **id interno** | `[data-anime]` (ej. `4967`) |
| título | `.anime__details__content h3` |
| título alterno | hermano `span` del `h3` |
| sinopsis | `p.scroll` |
| póster | `.anime_pic img[src]` (fallback `og:image`) |
| estado | `[data-status]` → `currently` / `finished` |
| tipo, episodios, duración, calidad | `.anime__details__widget li` (`<span>Etiqueta:</span> valor`) |
| géneros / studios / demografía / idiomas | `li` de la etiqueta → sus `a` |
| último episodio | `a#uep[href]` |
| próximo episodio | `#proxep` cuyo texto contiene "Próximo" |

## 3. Lista de capítulos (requiere sesión)

```
GET  /{slug}/                    → guarda cookies + <meta name="csrf-token" content="…">
POST /ajax/episodes/{animeId}/
     X-CSRF-TOKEN: <token>
     X-Requested-With: XMLHttpRequest
     _token=<token>
```

Sin la cookie de sesión del primer GET, Laravel responde **419 Page Expired**.
Sin `X-Requested-With`, responde 405.

Respuesta:

```json
{
  "current_page": 1,
  "last_page": 1,
  "total": 5,
  "data": [
    {
      "id": 78383,
      "number": 1,
      "title": "Seitokai ni mo Ana wa Aru! - 1",
      "image": "jkvideo_5870475ff796f4458bced16b49ca174c.jpg",
      "timestamp": "2026-10-04 02:28:44"
    }
  ]
}
```

- `number` puede ser decimal (`12.5`): el modelo lo guarda como `Double`.
- `image` es solo el nombre de archivo; el prefijo es
  `https://cdn.jkdesa.com/assets/images/animes/video/image/`.
- Paginación: `POST /ajax/episodes/{animeId}/?page=2` con el mismo token.

## 4. Fuentes de video

`GET /{slug}/{ep}/`

```js
var servers = [
  {"remote":"aHR0cHM6Ly9oZ2Nsb3VkLnRvL2UvaGNhYzRsbzQxN2h3","slug":"pJvB5906jVQTl",
   "server":"Streamwish","lang":1,"size":"289 MB","append":0},
  {"remote":"<base64>","server":"Mega", …},
  …
];
```

- `remote` = **base64 de la URL del embed** (no MP4, no m3u8).
- Servidores vistos: Mediafire, Mega (descarga), Streamwish, Vidhide, VOE,
  Filemoon, Mixdrop (streaming).
- Además hay iframes propios: `/jkplayer/um?e=…`, `/jkplayer/umv?e=…`,
  `/jkplayer/c1?u=…&s=…`.

Clasificación en `VideoServerParser`:

| Host | `ServerKind` |
|---|---|
| Mega, Mediafire | `DOWNLOAD` (no se ofrece para ver) |
| Streamwish, VOE, Filemoon, Vidhide, Mixdrop… | `STREAM` |
| iframe `/jkplayer/` | `JKPLAYER` |
| sin fuentes | `WEB` (abre la página del episodio) |

> El `jkplayer` devolvió **500** desde IP de datacenter durante la verificación.
> En el dispositivo puede responder distinto; por eso no es la fuente primaria.

> **Captcha/interstitial en WebView (sin resolver)**: Streamwish y VOE
> responden `200` limpio por `curl` (sin captcha) tanto con UA de TV como de
> escritorio, desde la IP pública de esta máquina. Pero dentro del WebView de
> la app aparece un overlay "Confirm you're not a robot" (QR). No es bloqueo
> por IP de datacenter (el servidor sirve HTML real). Hosts de terceros vistos
> en la página de VOE que no son el embed en sí —probablemente ads/interstitial
> que capturan el WebView—: `rendingdecreasehere.com`,
> `chaliceguzzlerlandlord.com`, `periodpennavigation.com`. Detalle completo del
> diagnóstico en [emulator-testing.md](emulator-testing.md).

## 5. Otros endpoints (para fases siguientes)

| Uso | Endpoint |
|---|---|
| Búsqueda | `POST /ajax_search` (CSRF, `q=`) |
| Directorio | `GET /directorio` |
| Horario semanal | `GET /horario` (`div.page_horario`) |
| Top | `GET /top` |
| Estrenos | `GET /estrenos` |

## 6. Reglas de convivencia con el sitio

1. `CookieJar` en memoria por proceso (Laravel).
2. 1 request cada 700 ms + jitter de hasta 250 ms.
3. UA de Android TV real; `Accept-Language: es-ES`.
4. Caché: programación 10 min · detalle 24 h · episodios 30 min · fuentes 30 min.
5. `Referer` de la página del anime en el POST AJAX.
6. Si aparece `403` o el DOM no matchea, se reintenta con caché (fail-open) y se
   reporta el error en la UI.
