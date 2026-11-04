#!/usr/bin/env bash
# Refresca los fixtures de test desde el sitio real.
#
# Uso:  ./tools/refresh-fixtures.sh [slug] [animeId] [episodio]
# Por defecto: seitokai-ni-mo-ana-wa-aru / 4967 / 5
#
# Después de correrlo, ejecuta `./gradlew :data:catalog:test` para ver qué
# selectores cambiaron.
set -euo pipefail

SLUG="${1:-seitokai-ni-mo-ana-wa-aru}"
ANIME_ID="${2:-4967}"
EPISODE="${3:-5}"
BASE="https://jkanime.net"
UA="Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
OUT="$(cd "$(dirname "$0")/.." && pwd)/data/catalog/src/test/resources/fixtures"

mkdir -p "$OUT"
COOKIES="$(mktemp)"
trap 'rm -f "$COOKIES"' EXIT

echo "→ GET /"
curl -sS -A "$UA" "$BASE/" -o "$OUT/home.html"

echo "→ GET /$SLUG/"
curl -sS -A "$UA" -c "$COOKIES" "$BASE/$SLUG/" -o "$OUT/anime_detail.html"

TOKEN=$(grep -oE 'csrf-token" content="[^"]*' "$OUT/anime_detail.html" | sed 's/.*content="//')
if [ -z "$TOKEN" ]; then
  echo "✗ no se encontró el token CSRF: el sitio pudo haber cambiado o bloqueado la IP" >&2
  exit 1
fi

echo "→ POST /ajax/episodes/$ANIME_ID/"
curl -sS -A "$UA" -b "$COOKIES" -c "$COOKIES" \
  -X POST "$BASE/ajax/episodes/$ANIME_ID/" \
  -H "X-Requested-With: XMLHttpRequest" \
  -H "X-CSRF-TOKEN: $TOKEN" \
  -d "_token=$TOKEN" \
  -o "$OUT/episodes_page1.json"

if ! head -c 1 "$OUT/episodes_page1.json" | grep -q '{'; then
  echo "✗ el endpoint de episodios no devolvió JSON (¿419/405?)" >&2
  head -c 200 "$OUT/episodes_page1.json" >&2
  exit 1
fi

echo "→ GET /$SLUG/$EPISODE/"
curl -sS -A "$UA" -e "$BASE/$SLUG/" "$BASE/$SLUG/$EPISODE/" -o "$OUT/episode.html"

echo "✓ fixtures actualizados en $OUT"
echo "  ahora: ./gradlew :data:catalog:test"
