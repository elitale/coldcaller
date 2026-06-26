#!/usr/bin/env python3
"""Generate the coldCalling brand mark and all packaging icons.

Single source of truth for the app icon: a blue squircle tile with a white
telephone receiver and three outbound "signal" arcs. The same geometry drives
every output so the README logo, the macOS dock icon, the Windows taskbar icon
and the Linux launcher icon are visually identical.

Outputs (all committed so CI runners need no image tooling):
  branding/coldcalling.svg                      -- editable master logo
  src/app/packaging/icons/coldcalling.png       -- 512px, Linux launcher
  src/app/packaging/icons/coldcalling.ico       -- multi-res, Windows
  src/app/packaging/icons/coldcalling.icns      -- macOS bundle
  src/ui/.../resources/icons/coldcalling-*.png  -- runtime JavaFX window icon

Run from anywhere:  python3 src/app/packaging/generate-icons.py
Requires Pillow. Uses macOS `iconutil` for .icns, falling back to Pillow.
"""

from __future__ import annotations

import math
import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

# ── Paths ────────────────────────────────────────────────────────────────────
# generate-icons.py -> packaging -> app -> src -> repo root
ROOT = Path(__file__).resolve().parents[3]
PKG_ICONS = ROOT / "src" / "app" / "packaging" / "icons"
UI_ICONS = ROOT / "src" / "ui" / "src" / "main" / "resources" / "icons"
BRANDING = ROOT / "branding"

# ── Brand constants ──────────────────────────────────────────────────────────
BASE = 1024  # master canvas, px
SS = 4  # supersample factor for crisp anti-aliasing
TILE_RADIUS = 228  # squircle corner radius at BASE
GRAD_TOP = (10, 132, 255)  # #0A84FF  (system blue, light)
GRAD_BOTTOM = (0, 86, 214)  # #0056D6  (system blue, deep)

# Telephone receiver, expressed in the BASE frame (y grows downward).
ARC_CX, ARC_CY = 512, 524  # centre of the receiver arc
ARC_RO, ARC_RI = 322, 188  # outer / inner radius of the curved body
ARC_A1, ARC_A2 = 32, 214  # sweep, degrees clockwise from 3 o'clock
BULB_R = 116  # ear / mouth piece radius
RECEIVER_ROT = -6  # whole-handset tilt, degrees (negative = CCW)

# Three outbound signal arcs emanating from the upper-right.
WAVE_CX, WAVE_CY = 470, 560
WAVE_RADII = (250, 340, 430)
WAVE_A1, WAVE_A2 = 274, 330  # upper-right quadrant
WAVE_WIDTH = 26
WAVE_ALPHA = (235, 175, 120)


def _pt(cx: float, cy: float, radius: float, deg: float) -> tuple[float, float]:
    """Point on a circle, y-down screen coordinates."""
    rad = math.radians(deg)
    return cx + radius * math.cos(rad), cy + radius * math.sin(rad)


def _rounded_mask(size: int, radius: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=radius, fill=255
    )
    return mask


def _gradient_tile(size: int) -> Image.Image:
    """Vertical blue gradient clipped to a squircle."""
    grad = Image.new("RGB", (1, size))
    for y in range(size):
        t = y / (size - 1)
        grad.putpixel(
            (0, y),
            tuple(round(a + (b - a) * t) for a, b in zip(GRAD_TOP, GRAD_BOTTOM)),
        )
    grad = grad.resize((size, size))
    tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    tile.paste(grad, (0, 0), _rounded_mask(size, round(TILE_RADIUS * size / BASE)))
    return tile


def _receiver_layer(size: int) -> Image.Image:
    """White telephone receiver on a transparent layer, supersampled."""
    s = size * SS
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    k = s / BASE
    white = (255, 255, 255, 255)

    def box(cx: float, cy: float, r: float) -> list[float]:
        return [(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k]

    # Curved body: outer wedge minus inner wedge (ImageDraw writes pixels
    # directly, so an alpha-0 fill carves the hole out).
    d.pieslice(box(ARC_CX, ARC_CY, ARC_RO), ARC_A1, ARC_A2, fill=white)
    d.pieslice(box(ARC_CX, ARC_CY, ARC_RI), ARC_A1 - 1, ARC_A2 + 1, fill=(0, 0, 0, 0))

    # Ear / mouth pieces flare the radial ends of the band.
    rm = (ARC_RO + ARC_RI) / 2
    for ang in (ARC_A1, ARC_A2):
        bx, by = _pt(ARC_CX, ARC_CY, rm, ang)
        d.ellipse(box(bx, by, BULB_R), fill=white)

    layer = layer.rotate(
        RECEIVER_ROT, resample=Image.BICUBIC, center=(ARC_CX * k, ARC_CY * k)
    )
    return layer.resize((size, size), Image.LANCZOS)


def _signal_layer(size: int) -> Image.Image:
    """Three concentric outbound arcs, fading outward."""
    s = size * SS
    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    k = s / BASE
    for radius, alpha in zip(WAVE_RADII, WAVE_ALPHA):
        d.arc(
            [
                (WAVE_CX - radius) * k,
                (WAVE_CY - radius) * k,
                (WAVE_CX + radius) * k,
                (WAVE_CY + radius) * k,
            ],
            WAVE_A1,
            WAVE_A2,
            fill=(255, 255, 255, alpha),
            width=round(WAVE_WIDTH * k),
        )
    return layer.resize((size, size), Image.LANCZOS)


def render_png(size: int) -> Image.Image:
    tile = _gradient_tile(size)
    tile.alpha_composite(_signal_layer(size))
    tile.alpha_composite(_receiver_layer(size))
    return tile


def _svg() -> str:
    rm = (ARC_RO + ARC_RI) / 2
    o1 = _pt(ARC_CX, ARC_CY, ARC_RO, ARC_A1)
    o2 = _pt(ARC_CX, ARC_CY, ARC_RO, ARC_A2)
    i1 = _pt(ARC_CX, ARC_CY, ARC_RI, ARC_A1)
    i2 = _pt(ARC_CX, ARC_CY, ARC_RI, ARC_A2)
    b1 = _pt(ARC_CX, ARC_CY, rm, ARC_A1)
    b2 = _pt(ARC_CX, ARC_CY, rm, ARC_A2)
    large = 1 if (ARC_A2 - ARC_A1) % 360 > 180 else 0
    body = (
        f"M{o1[0]:.1f},{o1[1]:.1f} "
        f"A{ARC_RO},{ARC_RO} 0 {large} 1 {o2[0]:.1f},{o2[1]:.1f} "
        f"L{i2[0]:.1f},{i2[1]:.1f} "
        f"A{ARC_RI},{ARC_RI} 0 {large} 0 {i1[0]:.1f},{i1[1]:.1f} Z"
    )
    waves = "\n".join(
        f'    <path d="M{_pt(WAVE_CX, WAVE_CY, r, WAVE_A1)[0]:.1f},'
        f"{_pt(WAVE_CX, WAVE_CY, r, WAVE_A1)[1]:.1f} "
        f"A{r},{r} 0 0 1 {_pt(WAVE_CX, WAVE_CY, r, WAVE_A2)[0]:.1f},"
        f'{_pt(WAVE_CX, WAVE_CY, r, WAVE_A2)[1]:.1f}" '
        f'fill="none" stroke="#fff" stroke-width="{WAVE_WIDTH}" '
        f'stroke-linecap="round" opacity="{a / 255:.2f}"/>'
        for r, a in zip(WAVE_RADII, WAVE_ALPHA)
    )
    top = "#%02X%02X%02X" % GRAD_TOP
    bottom = "#%02X%02X%02X" % GRAD_BOTTOM
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{BASE}" height="{BASE}" viewBox="0 0 {BASE} {BASE}">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="{top}"/>
      <stop offset="1" stop-color="{bottom}"/>
    </linearGradient>
  </defs>
  <rect width="{BASE}" height="{BASE}" rx="{TILE_RADIUS}" ry="{TILE_RADIUS}" fill="url(#bg)"/>
  <g>
{waves}
  </g>
  <g transform="rotate({RECEIVER_ROT} {ARC_CX} {ARC_CY})" fill="#fff">
    <path d="{body}"/>
    <circle cx="{b1[0]:.1f}" cy="{b1[1]:.1f}" r="{BULB_R}"/>
    <circle cx="{b2[0]:.1f}" cy="{b2[1]:.1f}" r="{BULB_R}"/>
  </g>
</svg>
"""


def _write_icns(master: Image.Image, dest: Path) -> None:
    sizes = [16, 32, 64, 128, 256, 512, 1024]
    if shutil.which("iconutil"):
        with tempfile.TemporaryDirectory() as tmp:
            iconset = Path(tmp) / "coldcalling.iconset"
            iconset.mkdir()
            for px in (16, 32, 128, 256, 512):
                master.resize((px, px), Image.LANCZOS).save(
                    iconset / f"icon_{px}x{px}.png"
                )
                master.resize((px * 2, px * 2), Image.LANCZOS).save(
                    iconset / f"icon_{px}x{px}@2x.png"
                )
            subprocess.run(
                ["iconutil", "-c", "icns", str(iconset), "-o", str(dest)], check=True
            )
    else:  # portable fallback
        master.save(dest, format="ICNS", sizes=[(p, p) for p in sizes if p <= 512])


def main() -> None:
    for d in (PKG_ICONS, UI_ICONS, BRANDING):
        d.mkdir(parents=True, exist_ok=True)

    master = render_png(BASE)

    # Linux launcher icon + Windows multi-res ICO.
    master.resize((512, 512), Image.LANCZOS).save(PKG_ICONS / "coldcalling.png")
    master.save(
        PKG_ICONS / "coldcalling.ico",
        sizes=[
            (16, 16),
            (24, 24),
            (32, 32),
            (48, 48),
            (64, 64),
            (128, 128),
            (256, 256),
        ],
    )
    _write_icns(master, PKG_ICONS / "coldcalling.icns")

    # Runtime JavaFX window/taskbar icon (several sizes; JavaFX picks the best).
    for px in (16, 32, 64, 128, 256, 512):
        master.resize((px, px), Image.LANCZOS).save(UI_ICONS / f"coldcalling-{px}.png")

    (BRANDING / "coldcalling.svg").write_text(_svg(), encoding="utf-8")

    print("Wrote:")
    for p in sorted(PKG_ICONS.glob("coldcalling.*")):
        print(" ", p.relative_to(ROOT))
    print("  branding/coldcalling.svg")
    print(f"  {UI_ICONS.relative_to(ROOT)}/coldcalling-*.png")


if __name__ == "__main__":
    main()
