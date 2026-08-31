#!/usr/bin/env python3
"""Add a BLAKE2b band to the Sparrow icon, and regenerate every packaged form.

Run from the repository root against the upstream .icns, which is the largest source artwork:

    python3 tools/brand-icon.py src/main/deploy/package/macos/sparrow.icns

Re-run this after any upstream change to the icon; the outputs are committed, so nothing in the
build regenerates them. The tray glyphs (sparrow-black-small, sparrow-white-small) are left alone
deliberately: they are monochrome silhouettes sized for a system tray, where a text band would be
both illegible and wrong for a theme-following icon.

The band colour is the darkest opaque tone already in the bird, so it reads as part of the
artwork rather than pasted on. Sized as a fraction of the canvas so every resolution matches.
"""
from PIL import Image, ImageDraw, ImageFont
import sys

BAND_COLOUR = (38, 47, 56, 255)
TEXT_COLOUR = (255, 255, 255, 255)
BAND_FRACTION = 0.21
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
LABEL = 'BLAKE2b'

def banded(src: Image.Image) -> Image.Image:
    """Scale the artwork into the space above the band rather than letting the band cover it.

    The bird's tail and feet reach almost to the bottom edge, so a band laid over the original
    crops them. Fitting the artwork to the remaining height keeps the whole bird and costs a
    little size, which is invisible next to losing the feet.
    """
    art = src.convert('RGBA')
    w, h = art.size
    band_h = int(h * BAND_FRACTION)
    top = h - band_h

    bbox = art.getbbox()
    cropped = art.crop(bbox)
    avail_w, avail_h = int(w * 0.96), int(top * 0.96)
    scale = min(avail_w / cropped.width, avail_h / cropped.height)
    art_w, art_h = max(1, int(cropped.width * scale)), max(1, int(cropped.height * scale))

    im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    im.paste(cropped.resize((art_w, art_h), Image.LANCZOS),
             ((w - art_w) // 2, (top - art_h) // 2), cropped.resize((art_w, art_h), Image.LANCZOS))

    layer = Image.new('RGBA', im.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).rectangle([0, top, w, h], fill=BAND_COLOUR)
    im = Image.alpha_composite(im, layer)

    # Fit the label to ~86% of the width and ~62% of the band, whichever binds first.
    draw = ImageDraw.Draw(im)
    size = band_h
    while size > 4:
        font = ImageFont.truetype(FONT, size)
        l, t, r, b = draw.textbbox((0, 0), LABEL, font=font)
        if (r - l) <= w * 0.86 and (b - t) <= band_h * 0.62:
            break
        size -= 1
    font = ImageFont.truetype(FONT, size)
    l, t, r, b = draw.textbbox((0, 0), LABEL, font=font)
    draw.text(((w - (r - l)) / 2 - l, top + (band_h - (b - t)) / 2 - t), LABEL, font=font, fill=TEXT_COLOUR)
    return im

master = banded(Image.open(sys.argv[1]).convert('RGBA').resize((1024, 1024), Image.LANCZOS))
master.save('/tmp/icon_master.png')

# Linux: a single 256 PNG.
master.resize((256, 256), Image.LANCZOS).save('src/main/deploy/package/linux/Sparrow.png')

# Windows: multi-resolution ICO.
master.save('src/main/deploy/package/windows/sparrow.ico', format='ICO',
            sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])

# macOS: multi-resolution ICNS.
master.save('src/main/deploy/package/macos/sparrow.icns', format='ICNS')

print('  wrote Sparrow.png, sparrow.ico, sparrow.icns')
