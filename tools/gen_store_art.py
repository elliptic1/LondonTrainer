#!/usr/bin/env python3
"""Draws the launcher icon and Play Store graphics. Needs Pillow and numpy.

Usage: gen_store_art.py <captures-dir>
The captures dir holds 1080x2400 PNGs: held, correct, adapt, wrong, finish.
"""
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

os.environ["PIECE_SIZE"] = "1024"
sys.path.insert(0, str(Path(__file__).parent))
import gen_pieces  # noqa: E402

ROOT = Path(__file__).parent.parent
GRAPHICS = ROOT / "app/src/main/play/listings/en-US/graphics"
DRAWABLE = ROOT / "app/src/main/res/drawable-nodpi"

FELT = (38, 37, 34)
GREEN = (91, 137, 62)
DEEP = (58, 94, 44)
LIGHT = (235, 236, 208)
DARK = (115, 149, 82)
BRASS = (222, 170, 72)

SHOTS = [
    ("held", "Build the London System", "Drag your pieces. Learn the plan."),
    ("correct", "Know why each move works", "A short lesson with every correct move."),
    ("adapt", "Black keeps you guessing", "2,551 positions, different every run."),
    ("wrong", "Mistakes come back", "Missed positions return until they stick."),
    ("finish", "Finish with a clean run", "Eight moves. One setup you can trust."),
]


def font(size, weight):
    """Avenir Next, by style name; the collection's face order isn't stable across macOS versions."""
    path = "/System/Library/Fonts/Avenir Next.ttc"
    for index in range(16):
        face = ImageFont.truetype(path, size, index=index)
        if face.getname()[1] == weight:
            return face
    raise SystemExit(f"Avenir Next {weight} not found")


def backdrop(width, height):
    """A soft diagonal from board green into near-black."""
    ramp = Image.new("L", (width, height))
    pixels = ramp.load()
    for y in range(height):
        for x in range(width):
            pixels[x, y] = int(255 * min(1, (x / width + 2 * y / height) / 2.2))
    return Image.composite(Image.new("RGB", (width, height), FELT), Image.new("RGB", (width, height), DEEP), ramp)


def rounded(image, radius):
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, image.width - 1, image.height - 1], radius, fill=255)
    return mask


def launcher(pieces):
    knight = pieces["wn"]
    # Adaptive foreground: 108dp canvas, keep the art inside the 66dp safe zone.
    foreground = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    art = knight.resize((272, 272), Image.LANCZOS)
    foreground.alpha_composite(art, (80, 80))
    foreground.save(DRAWABLE / "ic_launcher_fg.png", optimize=True)

    icon = Image.new("RGBA", (512, 512), GREEN + (255,))
    art = knight.resize((400, 400), Image.LANCZOS)
    shadow = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 90), (56, 66), art.split()[3])
    icon.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(9)))
    icon.alpha_composite(art, (56, 56))
    target = GRAPHICS / "icon"
    target.mkdir(parents=True, exist_ok=True)
    icon.convert("RGB").save(target / "icon.png", optimize=True)


def feature(pieces):
    width, height, cell = 1024, 500, 125
    canvas = backdrop(width, height).convert("RGBA")
    draw = ImageDraw.Draw(canvas)
    left = width - cell * 4
    for rank in range(4):
        for file in range(4):
            colour = LIGHT if (rank + file) % 2 else DARK
            draw.rectangle([left + file * cell, rank * cell, left + (file + 1) * cell, (rank + 1) * cell], fill=colour)
    for key, file, rank in (("bk", 3, 0), ("bq", 1, 0), ("bn", 2, 1), ("bp", 0, 1), ("wb", 2, 2), ("wn", 1, 3), ("wp", 0, 2), ("wk", 3, 3)):
        canvas.alpha_composite(pieces[key].resize((cell, cell), Image.LANCZOS), (left + file * cell, rank * cell))
    draw.rectangle([56, 142, 118, 148], fill=BRASS)
    draw.text((54, 162), "London Trainer", font=font(62, "Bold"), fill=(255, 255, 255))
    draw.text((56, 250), "Learn the London System,", font=font(30, "Medium"), fill=(226, 226, 218))
    draw.text((56, 290), "one position at a time.", font=font(30, "Medium"), fill=(226, 226, 218))
    target = GRAPHICS / "feature-graphic"
    target.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(target / "feature.png", optimize=True)


def screenshot(capture, title, subtitle):
    width, height = 1080, 1920
    canvas = backdrop(width, height).convert("RGBA")
    draw = ImageDraw.Draw(canvas)
    head = font(76, "Bold")
    while draw.textlength(title, font=head) > width - 120:
        head = font(head.size - 2, "Bold")
    draw.text((width / 2, 118), title, font=head, fill=(255, 255, 255), anchor="mm")
    draw.text((width / 2, 204), subtitle, font=font(38, "Medium"), fill=BRASS, anchor="mm")

    phone_width = 840
    phone = Image.open(capture).convert("RGB").resize((phone_width, phone_width * 2400 // 1080), Image.LANCZOS)
    x, y = (width - phone_width) // 2, 280
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    shadow.paste((0, 0, 0, 130), (x, y + 10), rounded(phone, 44))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(22)))
    canvas.paste(phone, (x, y), rounded(phone, 44))
    return canvas.convert("RGB")


if __name__ == "__main__":
    captures = Path(sys.argv[1])
    pieces = gen_pieces.render()
    launcher(pieces)
    feature(pieces)
    target = GRAPHICS / "phone-screenshots"
    target.mkdir(parents=True, exist_ok=True)
    for old in target.glob("*.png"):
        old.unlink()
    for i, (name, title, subtitle) in enumerate(SHOTS, 1):
        screenshot(captures / f"{name}.png", title, subtitle).save(target / f"{i}.png", optimize=True)
