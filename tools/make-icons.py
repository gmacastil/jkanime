#!/usr/bin/env python3
"""Genera los iconos de las dos apps (TV y móvil) a partir del arte de marca.

Fuente: `app/src/main/res/drawable/caiman_icon.png` — el conejo enmascarado de
jkanime (silueta negra sobre blanco).

Salida por módulo (`app` y `app-mobile`):

  drawable/ic_launcher_foreground.png     capa del icono adaptativo (108dp)
  drawable/ic_launcher_monochrome.png     capa monocroma (iconos temáticos 13+)
  mipmap-<dpi>/ic_launcher.png            icono legacy (API < 26)
  mipmap-<dpi>/ic_launcher_round.png      icono legacy circular
  mipmap-anydpi-v26/ic_launcher.xml       adaptive-icon (API 26+)
  mipmap-anydpi-v26/ic_launcher_round.xml

Paleta: fondo `#0E0E11` (jk_background) y silueta naranja `#FF6B35`
(jk_orange), igual que la UI de las dos apps.

Uso:  python3 tools/make-icons.py
"""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "app/src/main/res/drawable/caiman_icon.png"

BACKGROUND = (14, 14, 17, 255)     # #0E0E11
ACCENT = (255, 107, 53, 255)       # #FF6B35

MODULES = ("app", "app-mobile")

LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

# Porción del lienzo que ocupa la silueta. 0.52 la deja dentro de la zona
# segura del icono adaptativo: con máscara circular no se recorta ni una oreja
# ni los hombros (verificado con previews 0.47/0.52/0.58).
CONTENT_RATIO = 0.52


def load_silhouette() -> Image.Image:
    """Silueta del conejo en blanco sobre transparente, recortada al contenido."""
    src = Image.open(SOURCE).convert("RGB")
    alpha = src.convert("L").point(lambda value: 255 - value)
    silhouette = Image.new("RGBA", src.size, (255, 255, 255, 0))
    silhouette.putalpha(alpha)
    bbox = silhouette.getbbox()
    if bbox is None:
        raise SystemExit("la imagen fuente no tiene silueta reconocible")
    return silhouette.crop(bbox)


def tinted(silhouette: Image.Image, color: tuple[int, int, int, int], size: int, ratio: float) -> Image.Image:
    """Silueta escalada al `ratio` del lienzo cuadrado `size` y centrada."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    target = int(size * ratio)
    width, height = silhouette.size
    scale = min(target / width, target / height)
    new_size = (max(1, round(width * scale)), max(1, round(height * scale)))
    layer = Image.new("RGBA", new_size, (0, 0, 0, 0))
    layer.paste(color, (0, 0, new_size[0], new_size[1]))
    layer.putalpha(silhouette.resize(new_size, Image.LANCZOS).getchannel("A"))
    canvas.paste(layer, ((size - new_size[0]) // 2, (size - new_size[1]) // 2), layer)
    return canvas


def legacy_tile(silhouette: Image.Image, size: int, round_icon: bool) -> Image.Image:
    """Icono legacy: tile oscuro con la silueta naranja (sin adaptive icon)."""
    tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(tile)
    if round_icon:
        draw.ellipse((0, 0, size - 1, size - 1), fill=BACKGROUND)
    else:
        draw.rounded_rectangle(
            (0, 0, size - 1, size - 1),
            radius=max(1, round(size * 0.22)),
            fill=BACKGROUND,
        )
    tile.paste(
        tinted(silhouette, ACCENT, size, CONTENT_RATIO),
        (0, 0),
        tinted(silhouette, ACCENT, size, CONTENT_RATIO),
    )
    return tile


ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


def main() -> None:
    silhouette = load_silhouette()
    print(f"silueta {silhouette.size[0]}x{silhouette.size[1]}")

    for module in MODULES:
        res = ROOT / module / "src/main/res"

        # Iconos viejos (vectores verdes del módulo móvil) fuera de mipmap-*.
        for stale in res.glob("mipmap-*/ic_launcher*.xml"):
            stale.unlink()
            print(f"  - {stale.relative_to(ROOT)}")

        for density, size in ADAPTIVE_SIZES.items():
            out = res / "drawable" / "ic_launcher_foreground.png"
            out.parent.mkdir(parents=True, exist_ok=True)
            # La capa adaptativa se sirve desde drawable/ con el tamaño de
            # xxxhdpi; Android la escala por densidad.
            if density != "xxxhdpi":
                continue
            tinted(silhouette, ACCENT, size, CONTENT_RATIO).save(out)
            tinted(silhouette, (0, 0, 0, 255), size, CONTENT_RATIO).save(
                res / "drawable" / "ic_launcher_monochrome.png"
            )
            print(f"  + {out.relative_to(ROOT)} ({size}px)")

        for density, size in LEGACY_SIZES.items():
            folder = res / f"mipmap-{density}"
            folder.mkdir(parents=True, exist_ok=True)
            legacy_tile(silhouette, size, round_icon=False).save(folder / "ic_launcher.png")
            legacy_tile(silhouette, size, round_icon=True).save(folder / "ic_launcher_round.png")
            print(f"  + {folder.relative_to(ROOT)}/ic_launcher(.png|_round.png) {size}px")

        anydpi = res / "mipmap-anydpi-v26"
        anydpi.mkdir(parents=True, exist_ok=True)
        (anydpi / "ic_launcher.xml").write_text(ADAPTIVE_XML)
        (anydpi / "ic_launcher_round.xml").write_text(ADAPTIVE_XML)
        print(f"  + {anydpi.relative_to(ROOT)}/ic_launcher(.xml|_round.xml)")


if __name__ == "__main__":
    main()
