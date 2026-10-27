"""Generates Talkies' TV banner and adaptive app icon as Android vector drawables, plus SVG previews.

    python3 tools/make_icons.py            (run from the project folder)

The wordmark is Outfit Medium (the launcher's own font) turned into outlines, so no font is needed at runtime.
"""
import math
import os
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
RES = os.path.join(ROOT, "app", "src", "main", "res")
PREVIEW = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "icon-preview")

BG_TOP, BG_BOTTOM = "#2A1216", "#0B0608"   # warm dark red to near black
GLOW = "#FF5A3C"                            # a warm light behind the logo, like a cinema screen
TICKET_A, TICKET_B = "#FFB547", "#FF4D5E"   # the ticket: amber to red
PLAY = "#FFF7EE"
TEXT = "#F5F5F7"


def wordmark(text, size, x, baseline):
    """SVG path data for `text` in Outfit Medium, `size` units tall (em), starting at x on the baseline."""
    font = TTFont(os.path.join(RES, "font", "outfit.ttf"))
    font = instancer.instantiateVariableFont(font, {"wght": 500})
    glyphs, cmap, hmtx = font.getGlyphSet(), font.getBestCmap(), font["hmtx"]
    scale = size / font["head"].unitsPerEm
    pen = SVGPathPen(glyphs)
    cursor = x
    for ch in text:
        name = cmap[ord(ch)]
        glyphs[name].draw(TransformPen(pen, (scale, 0, 0, -scale, cursor, baseline)))
        cursor += hmtx[name][0] * scale
    return pen.getCommands(), cursor - x


def logo(ox, oy, s):
    """
    A cinema ticket, tilted, with a play button and a perforated stub, in an s×s box at (ox, oy).
    Returns (path, fill, stroke, stroke width) tuples; fill "ticket" is the amber-to-red gradient (see ticket_box).
    """
    a = math.radians(-12)
    cx, cy = ox + s / 2, oy + s / 2

    def pt(x, y):
        # unit coordinates in the box, turned 12° about its centre
        x, y = ox + x * s - cx, oy + y * s - cy
        return f"{cx + x * math.cos(a) - y * math.sin(a):.2f},{cy + x * math.sin(a) + y * math.cos(a):.2f}"

    l, r_, t, b, c, n = 0.06, 0.94, 0.24, 0.76, 0.075, 0.085   # edges, corner radius, notch radius
    rad = f"{n * s:.2f},{n * s:.2f}"
    body = (f"M{pt(l + c, t)} L{pt(r_ - c, t)} Q{pt(r_, t)} {pt(r_, t + c)} L{pt(r_, 0.5 - n)} "
            f"A{rad} 0 0 0 {pt(r_, 0.5 + n)} L{pt(r_, b - c)} Q{pt(r_, b)} {pt(r_ - c, b)} L{pt(l + c, b)} "
            f"Q{pt(l, b)} {pt(l, b - c)} L{pt(l, 0.5 + n)} A{rad} 0 0 0 {pt(l, 0.5 - n)} L{pt(l, t + c)} "
            f"Q{pt(l, t)} {pt(l + c, t)} Z")
    # the perforation: dots (vector drawables can't dash a line)
    dots = []
    d = 0.022 * s
    for k in range(5):
        y = 0.32 + k * 0.09
        p = pt(0.72, y)
        px, py = (float(v) for v in p.split(","))
        dots.append(f"M{px - d:.2f},{py:.2f} A{d:.2f},{d:.2f} 0 1 1 {px + d:.2f},{py:.2f} A{d:.2f},{d:.2f} 0 1 1 {px - d:.2f},{py:.2f} Z")
    # the play button, corners softened
    play = (f"M{pt(0.335, 0.405)} Q{pt(0.335, 0.37)} {pt(0.366, 0.388)} L{pt(0.535, 0.483)} "
            f"Q{pt(0.565, 0.5)} {pt(0.535, 0.517)} L{pt(0.366, 0.612)} Q{pt(0.335, 0.63)} {pt(0.335, 0.595)} Z")
    return [
        (body, "ticket", None, 0),
        (" ".join(dots), "#592A1216", None, 0),
        (play, PLAY, None, 0),
    ]


def ticket_box(ox, oy, s):
    """Where the ticket's gradient runs: from its top-left (amber) to its bottom-right (red)."""
    return (ox + 0.1 * s, oy + 0.2 * s, ox + 0.9 * s, oy + 0.8 * s)


# ---------------------------------------------------------------- Android vector drawables

def vd_gradient_linear(x0, y0, x1, y1, c0, c1):
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="{x0}" '
            f'android:startY="{y0}" android:endX="{x1}" android:endY="{y1}" android:startColor="{argb(c0)}" '
            f'android:endColor="{argb(c1)}"/></aapt:attr>')


def vd_gradient_radial(cx, cy, r, color):
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="radial" android:centerX="{cx}" '
            f'android:centerY="{cy}" android:gradientRadius="{r}"><item android:offset="0" android:color="#73{color[1:]}"/>'
            f'<item android:offset="0.55" android:color="#24{color[1:]}"/><item android:offset="1" android:color="#00{color[1:]}"/>'
            f'</gradient></aapt:attr>')


def argb(c):
    return c if len(c) == 9 else "#FF" + c[1:]


def vd_paths(shapes, front_box):
    out = []
    for d, fill, stroke, width in shapes:
        attrs = f'android:pathData="{d}"'
        inner = ""
        if fill == "ticket":
            x0, y0, x1, y1 = front_box
            inner = vd_gradient_linear(x0, y0, x1, y1, TICKET_A, TICKET_B)
        elif fill:
            attrs += f' android:fillColor="{fill}"'
        if stroke:
            attrs += f' android:strokeColor="{stroke}" android:strokeWidth="{width:.2f}" android:strokeLineCap="round"'
        out.append(f'    <path {attrs}>{inner}</path>' if inner else f'    <path {attrs}/>')
    return "\n".join(out)


def vector(w, h, body):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/make_icons.py -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{w}dp" android:height="{h}dp" android:viewportWidth="{w}" android:viewportHeight="{h}">\n'
            f'{body}\n</vector>\n')


# ---------------------------------------------------------------- SVG previews

def svg_paths(shapes, front_box):
    out = []
    for d, fill, stroke, width in shapes:
        f = "url(#front)" if fill == "ticket" else (svg_color(fill) if fill else "none")
        s = f' stroke="{svg_color(stroke)}" stroke-width="{width:.2f}" stroke-linecap="round"' if stroke else ""
        out.append(f'<path d="{d}" fill="{f}"{s}/>')
    x0, y0, x1, y1 = front_box
    defs = (f'<linearGradient id="front" gradientUnits="userSpaceOnUse" x1="{x0}" y1="{y0}" x2="{x1}" y2="{y1}">'
            f'<stop offset="0" stop-color="{TICKET_A}"/><stop offset="1" stop-color="{TICKET_B}"/>'
            f'</linearGradient>')
    return defs, "".join(out)


def svg_color(c):
    if c and len(c) == 9:
        a = int(c[1:3], 16) / 255
        return f"rgba({int(c[3:5], 16)},{int(c[5:7], 16)},{int(c[7:9], 16)},{a:.2f})"
    return c


def svg(w, h, bg_glow, defs, body):
    gx, gy, gr = bg_glow
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w * 4}" height="{h * 4}" viewBox="0 0 {w} {h}"><defs>'
            f'<linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="{w}" y2="{h}">'
            f'<stop offset="0" stop-color="{BG_TOP}"/><stop offset="1" stop-color="{BG_BOTTOM}"/></linearGradient>'
            f'<radialGradient id="glow" gradientUnits="userSpaceOnUse" cx="{gx}" cy="{gy}" r="{gr}">'
            f'<stop offset="0" stop-color="{GLOW}" stop-opacity="0.45"/><stop offset="0.55" stop-color="{GLOW}" stop-opacity="0.14"/>'
            f'<stop offset="1" stop-color="{GLOW}" stop-opacity="0"/></radialGradient>{defs}</defs>'
            f'<rect width="{w}" height="{h}" fill="url(#bg)"/><rect width="{w}" height="{h}" fill="url(#glow)"/>{body}</svg>')


def main():
    os.makedirs(PREVIEW, exist_ok=True)

    # Banner: 320×180, logo then wordmark, centred.
    W, H, LOGO, GAP, SIZE = 320, 180, 74, 8, 34
    text_d, text_w = wordmark("Talkies", SIZE, 0, 0)
    left = (W - (LOGO + GAP + text_w)) / 2
    lx, ly = left, (H - LOGO) / 2
    text_d, _ = wordmark("Talkies", SIZE, lx + LOGO + GAP, H / 2 + SIZE * 0.36)
    shapes = logo(lx, ly, LOGO)
    front = ticket_box(lx, ly, LOGO)
    glow = (lx + LOGO / 2, H / 2, 150)
    body = "\n".join([
        f'    <path android:pathData="M0,0h{W}v{H}h-{W}z">{vd_gradient_linear(0, 0, W, H, BG_TOP, BG_BOTTOM)}</path>',
        f'    <path android:pathData="M0,0h{W}v{H}h-{W}z">{vd_gradient_radial(*glow, GLOW)}</path>',
        vd_paths(shapes, front),
        f'    <path android:pathData="{text_d}" android:fillColor="{TEXT}"/>',
    ])
    open(os.path.join(RES, "drawable", "banner.xml"), "w").write(vector(W, H, body))
    defs, paths = svg_paths(shapes, front)
    open(os.path.join(PREVIEW, "banner.svg"), "w").write(
        svg(W, H, glow, defs, paths + f'<path d="{text_d}" fill="{TEXT}"/>'))

    # Adaptive icon: 108×108 layers; the logo sits inside the 66-unit safe zone.
    S, L = 108, 64
    ix = iy = (S - L) / 2
    shapes = logo(ix, iy, L)
    front = ticket_box(ix, iy, L)
    open(os.path.join(RES, "drawable", "ic_launcher_background.xml"), "w").write(vector(S, S, "\n".join([
        f'    <path android:pathData="M0,0h{S}v{S}h-{S}z">{vd_gradient_linear(0, 0, S, S, BG_TOP, BG_BOTTOM)}</path>',
        f'    <path android:pathData="M0,0h{S}v{S}h-{S}z">{vd_gradient_radial(S / 2, S / 2, 60, GLOW)}</path>',
    ])))
    open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w").write(vector(S, S, vd_paths(shapes, front)))
    defs, paths = svg_paths(shapes, front)
    open(os.path.join(PREVIEW, "icon.svg"), "w").write(svg(S, S, (S / 2, S / 2, 60), defs, paths))

    os.makedirs(os.path.join(RES, "mipmap-anydpi-v26"), exist_ok=True)
    open(os.path.join(RES, "mipmap-anydpi-v26", "ic_launcher.xml"), "w").write(
        '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/make_icons.py -->\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@drawable/ic_launcher_background" />\n'
        '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n')
    print("wrote banner, adaptive icon, previews in", PREVIEW)


if __name__ == "__main__":
    main()
