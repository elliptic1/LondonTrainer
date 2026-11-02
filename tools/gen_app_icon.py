#!/usr/bin/env python3
"""Render the original London Trainer mark into Android vectors and Play PNG assets.

Run: python3 -I tools/gen_app_icon.py
Requires CairoSVG and Pillow (pip install cairosvg). The design source is native vector geometry, not a bitmap.
"""
from pathlib import Path
import cairosvg
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
DESIGN = ROOT / 'design/icon'
RES = ROOT / 'app/src/main/res'
PLAY = ROOT / 'app/src/main/play/listings/en-US/graphics/icon'
BODY = 'M59,24 C60,23 61,24 62,26 L65,30 C73,36 78,44 78,53 C78,61 74,67 70,72 L39,72 C40,67 44,63 48,59 C51,56 53,53 53,50 L47,54 C42,58 37,60 33,58 C30,57 29,54 31,51 C38,43 46,37 54,34 Z'
MANE = 'M65,36 C71,42 74,51 71,59'
BASE = 'M36,76 L72,76 C73.1,76 74,76.9 74,78 L74,79 C74,80.1 73.1,81 72,81 L36,81 C34.9,81 34,80.1 34,79 L34,78 C34,76.9 34.9,76 36,76 Z'
BAND = 'M40,71 L69,71 C70.7,71 72,72.1 72,73.5 C72,74.9 70.7,76 69,76 L40,76 C38.3,76 37,74.9 37,73.5 C37,72.1 38.3,71 40,71 Z'
EYE = 'M51,43 A1.8,1.8 0,1 1,47.4,43 A1.8,1.8 0,1 1,51,43 Z'
EAR = 'M57,34 L60,29 L62,35 Z'
MOUTH = 'M33.5,54.5 C36,55.5 39,55.1 41.2,53.8'


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)


def main():
    vector_start = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
'''
    bg = vector_start + '''    <path android:pathData="M0,0 H108 V108 H0 Z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="8" android:startY="0"
                android:endX="100" android:endY="108"
                android:startColor="#20634F" android:endColor="#0A2B27" />
        </aapt:attr>
    </path>
    <path android:fillColor="#FFFFFF" android:fillAlpha="0.035"
        android:pathData="M54,0 H108 V54 H54 Z M0,54 H54 V108 H0 Z" />
</vector>
'''
    fg = vector_start + f'''    <group android:translateY="1.6">
        <path android:fillColor="#000000" android:fillAlpha="0.22" android:pathData="{BODY} {BAND} {BASE}" />
    </group>
    <path android:pathData="{BODY}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="43" android:startY="28"
                android:endX="69" android:endY="76"
                android:startColor="#FFF9E9" android:endColor="#DED9BF" />
        </aapt:attr>
    </path>
    <path android:fillColor="#164637" android:pathData="{EAR}" />
    <path android:fillColor="#D4A657" android:pathData="{EYE}" />
    <path android:strokeColor="#164637" android:strokeWidth="2.3"
        android:strokeLineCap="round" android:fillColor="#00000000" android:pathData="{MANE}" />
    <path android:strokeColor="#164637" android:strokeWidth="1.6"
        android:strokeLineCap="round" android:fillColor="#00000000" android:pathData="{MOUTH}" />
    <path android:fillColor="#D4A657" android:pathData="{BAND}" />
    <path android:fillColor="#FFF5DB" android:pathData="{BASE}" />
</vector>
'''
    mono = vector_start + f'''    <path android:fillColor="#FFFFFF" android:fillType="evenOdd"
        android:pathData="{BODY} {EYE} {EAR}" />
    <path android:fillColor="#FFFFFF" android:pathData="{BAND} {BASE}" />
</vector>
'''
    write(RES/'drawable/ic_launcher_emerald.xml', bg)
    write(RES/'drawable/ic_launcher_knight.xml', fg)
    write(RES/'drawable/ic_launcher_monochrome.xml', mono)
    for level in (26, 33):
        monochrome = '\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />' if level == 33 else ''
        write(RES/f'mipmap-anydpi-v{level}/ic_launcher.xml', f'''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_emerald" />
    <foreground android:drawable="@drawable/ic_launcher_knight" />{monochrome}
</adaptive-icon>
''')
    # The same geometry and palette as the Android drawable. Google applies its own store mask.
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="14 14 80 80">
<defs>
 <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="8" y1="0" x2="100" y2="108"><stop offset="0%" stop-color="#20634F"/><stop offset="100%" stop-color="#0A2B27"/></linearGradient>
 <linearGradient id="ivory" gradientUnits="userSpaceOnUse" x1="43" y1="28" x2="69" y2="76"><stop offset="0%" stop-color="#FFF9E9"/><stop offset="100%" stop-color="#DED9BF"/></linearGradient>
</defs>
<path fill="url(#bg)" d="M0 0H108V108H0Z"/>
<path fill="#fff" opacity=".035" d="M54 0H108V54H54Z M0 54H54V108H0Z"/>
<g transform="translate(0 1.6)" fill="#000" opacity=".22"><path d="{BODY} {BAND} {BASE}"/></g>
<path fill="url(#ivory)" d="{BODY}"/>
<path fill="#164637" d="{EAR}"/>
<path fill="#D4A657" d="{EYE}"/>
<path fill="none" stroke="#164637" stroke-width="2.3" stroke-linecap="round" d="{MANE}"/>
<path fill="none" stroke="#164637" stroke-width="1.6" stroke-linecap="round" d="{MOUTH}"/>
<path fill="#D4A657" d="{BAND}"/>
<path fill="#FFF5DB" d="{BASE}"/>
</svg>
'''
    write(DESIGN/'london-trainer.svg', svg)
    PLAY.mkdir(parents=True, exist_ok=True)
    for destination, size in [(PLAY/'icon.png', 512), (DESIGN/'london-trainer.png', 1024)]:
        cairosvg.svg2png(bytestring=svg.encode(), write_to=str(destination), output_width=size, output_height=size)
        # Play expects an 8-bit RGBA PNG; preserve an opaque full-bleed background.
        with Image.open(destination) as rendered:
            rgba = rendered.convert('RGBA')
        rgba.save(destination, optimize=True)
    print('Generated adaptive vectors, themed icon, 512px Play icon, and 1024px source preview.')


if __name__ == '__main__':
    main()
