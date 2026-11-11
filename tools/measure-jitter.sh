#!/usr/bin/env bash
# Mide el "temblor" de la grilla del Home: cuántos píxeles se desplaza el
# contenido al mover el foco en horizontal entre tarjetas.
#
# Uso:
#   ./tools/measure-jitter.sh [etiqueta]
#
# Requisitos: emulador/dispositivo con la app instalada, adb, ffmpeg y
# numpy+Pillow en python3. La app debe arrancar en el Home (con un perfil
# activo o en el gate: si está en el gate, la medición no aplica).
#
# Cómo funciona: graba la pantalla con `screenrecord` mientras pulsa
# RIGHT/LEFT cuatro veces, extrae los fotogramas a 30 fps y mide, con
# correlación de fase (FFT), el desplazamiento entre fotogramas consecutivos
# en dos zonas independientes:
#
#   - grilla (pósters + títulos): debe quedarse en dy=0
#   - cabecera (logo + buscador): control; nunca debe moverse
#
# Un `dy` distinto de 0 en la grilla es el temblor: el contenido del
# `LazyVerticalGrid` se desplaza y vuelve. Sale con código 1 si lo detecta.
set -euo pipefail

ADB="${ADB:-/data/codigo/android-sdk/platform-tools/adb}"
EMU="${EMU:-emulator-5554}"
PKG="${PKG:-com.macastil.tv}"
TAG="${1:-run}"
OUT="/tmp/opencode/jitter-$TAG"

need() { command -v "$1" >/dev/null || { echo "falta $1" >&2; exit 2; }; }
need ffmpeg
need python3

rm -rf "$OUT" && mkdir -p "$OUT"

echo "· reiniciando $PKG en $EMU"
$ADB -s "$EMU" shell am force-stop "$PKG"
$ADB -s "$EMU" shell monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
sleep 9   # arranque + posible sync en background

# Foco en la grilla, columna 1.
$ADB -s "$EMU" shell input keyevent KEYCODE_DPAD_DOWN
sleep 1.5
$ADB -s "$EMU" shell input keyevent KEYCODE_DPAD_LEFT
sleep 1.5

echo "· grabando 16 s y navegando RIGHT ×4 / LEFT ×4"
$ADB -s "$EMU" shell rm -f "/sdcard/jitter-$TAG.mp4"
setsid $ADB -s "$EMU" shell screenrecord --time-limit 16 --bit-rate 16000000 \
  "/sdcard/jitter-$TAG.mp4" >/dev/null 2>&1 &
RECPID=$!
sleep 2
for _ in 1 2 3 4; do $ADB -s "$EMU" shell input keyevent KEYCODE_DPAD_RIGHT; sleep .8; done
for _ in 1 2 3 4; do $ADB -s "$EMU" shell input keyevent KEYCODE_DPAD_LEFT;  sleep .8; done
wait $RECPID 2>/dev/null || true
sleep 1

SZ=$($ADB -s "$EMU" shell stat -c %s "/sdcard/jitter-$TAG.mp4" 2>/dev/null | tr -d '\r')
if [ "${SZ:-0}" -lt 100000 ]; then
  echo "✗ grabación vacía o incompleta (${SZ:-0} bytes)" >&2
  exit 2
fi
$ADB -s "$EMU" pull "/sdcard/jitter-$TAG.mp4" "$OUT/nav.mp4" >/dev/null 2>&1
ffmpeg -loglevel error -i "$OUT/nav.mp4" -vf fps=30 -frames:v 460 "$OUT/%03d.png"

python3 - "$TAG" "$OUT" <<'PY'
import glob, sys
import numpy as np
from PIL import Image

tag, out = sys.argv[1], sys.argv[2]

def gray(p):
    return np.asarray(Image.open(p).convert("L"), dtype=np.float32)

def shift(a, b):
    """Desplazamiento (dx, dy) del contenido de b respecto a a, y confianza."""
    a = a - a.mean(); b = b - b.mean()
    F = np.fft.rfft2(a) * np.conj(np.fft.rfft2(b))
    F /= (np.abs(F) + 1e-6)
    r = np.fft.irfft2(F, s=a.shape)
    p = np.unravel_index(np.argmax(r), r.shape)
    dy = p[0] if p[0] <= a.shape[0] // 2 else p[0] - a.shape[0]
    dx = p[1] if p[1] <= a.shape[1] // 2 else p[1] - a.shape[1]
    return int(dx), int(dy), float(r.max())

files = sorted(glob.glob(f"{out}/*.png"))
if not files:
    sys.exit("sin fotogramas extraídos")

GRID = (150, 900, 40, 1900)    # pósters + títulos + fila 2
HDR  = (40, 130, 30, 900)      # logo + buscador (control)

def measure(zone):
    prev, events = None, []
    for f in files:
        g = gray(f)[zone[0]:zone[1], zone[2]:zone[3]]
        if prev is not None:
            dx, dy, c = shift(prev, g)
            if dx or dy:
                events.append((f.rsplit("/", 1)[-1], dx, dy, round(c, 2)))
        prev = g
    return events

grid = measure(GRID)
hdr = measure(HDR)
maxdy = max((abs(e[2]) for e in grid), default=0)
hdr_dy = max((abs(e[2]) for e in hdr), default=0)

print(f"\n[{tag}] fotogramas analizados: {len(files)}")
print(f"  grilla   : {len(grid)} eventos de desplazamiento, max |dy| = {maxdy} px")
print(f"  cabecera : max |dy| = {hdr_dy} px (debe ser 0)")
if grid:
    muestra = "  ".join(f"{n}:{dx:+d}/{dy:+d}" for n, dx, dy, _ in grid[:20])
    print(f"  muestra  : {muestra}")

if maxdy == 0:
    print("  ✓ SIN temblor: el contenido de la grilla no se desplaza")
    sys.exit(0)
print("  ✗ TEMBLOR: la grilla se desplaza en vertical al navegar en horizontal")
sys.exit(1)
PY
