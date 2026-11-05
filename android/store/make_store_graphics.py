"""Makes the Google Play graphics from the raw app screenshots.

    pip install pillow
    python android/store/make_store_graphics.py

Reads   store/screenshots/raw/*.png   (1080 x 1920, taken by ScreenshotTest on an emulator)
        store/graphics/icon-512.png
Writes  store/screenshots/phone/*.png (1080 x 1920, framed, with a caption)
        store/screenshots/raw/*.png   (flattened to RGB, so they can be uploaded as they are too)
        store/graphics/feature-graphic.png (1024 x 500, no transparency)
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

STORE = Path(__file__).resolve().parent
FONTS = STORE.parent / "app" / "src" / "main" / "res" / "font"
RAW = STORE / "screenshots" / "raw"
PHONE = STORE / "screenshots" / "phone"
GRAPHICS = STORE / "graphics"

NAVY = (11, 26, 51)
NAVY_DEEP = (6, 15, 31)
TEAL = (16, 179, 163)
TEAL_LIGHT = (201, 239, 234)
WHITE = (255, 255, 255)

CAPTIONS = {
    "01-welcome": "Save videos from your favorite apps",
    "02-home": "Paste a link. That's it.",
    "03-link-found": "Links are recognised instantly",
    "04-quality": "Pick the quality you want",
    "05-downloads": "All your downloads in one place",
    "06-home-dark": "Easy on the eyes in dark mode",
    "07-quality-dark": "Or save just the sound as MP3",
    "08-downloads-dark": "Watch, share or delete anytime",
}


def font(name: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONTS / f"{name}.ttf"), size)


def gradient(size: tuple[int, int], top: tuple, bottom: tuple) -> Image.Image:
    w, h = size
    column = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        column.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))
    return column.resize((w, h))


def glow(canvas: Image.Image, box: tuple, color: tuple, alpha: int, blur: int) -> None:
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(layer).ellipse(box, fill=(*color, alpha))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))


def rounded(image: Image.Image, radius: int) -> Image.Image:
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, image.width - 1, image.height - 1], radius=radius, fill=255)
    out = image.convert("RGBA")
    out.putalpha(mask)
    return out


def phone(screen: Image.Image, width: int) -> Image.Image:
    """The screenshot in a simple dark phone frame with rounded corners."""
    bezel = round(width * 0.022)
    inner_w = width - 2 * bezel
    inner = screen.convert("RGB").resize((inner_w, round(inner_w * screen.height / screen.width)), Image.LANCZOS)
    height = inner.height + 2 * bezel
    body = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    ImageDraw.Draw(body).rounded_rectangle([0, 0, width - 1, height - 1], radius=round(width * 0.085), fill=(4, 10, 20, 255))
    ImageDraw.Draw(body).rounded_rectangle([1, 1, width - 2, height - 2], radius=round(width * 0.085),
                                           outline=(60, 80, 110, 255), width=2)
    body.alpha_composite(rounded(inner, round(width * 0.065)), (bezel, bezel))
    return body


def shadow(canvas: Image.Image, item: Image.Image, xy: tuple[int, int], blur: int = 30, alpha: int = 140) -> None:
    mask = item.getchannel("A").point(lambda a: alpha if a else 0)
    layer = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    layer.paste(Image.new("RGBA", item.size, (0, 0, 0, 255)), (xy[0], xy[1] + blur // 2), mask)
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(blur)))
    canvas.alpha_composite(item, xy)


def wrap(draw: ImageDraw.ImageDraw, text: str, typeface: ImageFont.FreeTypeFont, max_width: int) -> list[str]:
    lines, line = [], ""
    for word in text.split():
        candidate = f"{line} {word}".strip()
        if draw.textlength(candidate, font=typeface) <= max_width or not line:
            line = candidate
        else:
            lines.append(line)
            line = word
    return lines + [line]


def framed_screenshot(raw: Path, caption: str) -> Image.Image:
    w, h = 1080, 1920
    canvas = gradient((w, h), (14, 34, 64), NAVY_DEEP).convert("RGBA")
    glow(canvas, (90, 700, 990, 1700), TEAL, 70, 160)

    draw = ImageDraw.Draw(canvas)
    title = font("outfit_bold", 88)
    lines = wrap(draw, caption, title, 940)
    y = 120 if len(lines) > 1 else 170
    draw.rounded_rectangle([w // 2 - 40, y - 44, w // 2 + 40, y - 34], radius=5, fill=TEAL)
    for line in lines:
        draw.text((w // 2, y), line, font=title, fill=WHITE, anchor="mt")
        y += 106

    device = phone(Image.open(raw), 820)
    shadow(canvas, device, ((w - device.width) // 2, 380))
    return canvas.convert("RGB")


def feature_graphic(home: Path, downloads: Path) -> Image.Image:
    w, h = 1024, 500
    canvas = gradient((w, h), (14, 34, 64), NAVY_DEEP).convert("RGBA")
    glow(canvas, (520, -60, 1100, 560), TEAL, 80, 120)
    glow(canvas, (-200, 260, 400, 700), (24, 50, 90), 160, 120)

    # Two phones on the right, cut off by the bottom edge
    back = phone(Image.open(downloads), 250).rotate(8, resample=Image.BICUBIC, expand=True)
    front = phone(Image.open(home), 270).rotate(-6, resample=Image.BICUBIC, expand=True)
    shadow(canvas, back, (775, 70), blur=24, alpha=150)
    shadow(canvas, front, (585, 95), blur=24, alpha=170)

    # Logo, name and tagline on the left
    icon = rounded(Image.open(GRAPHICS / "icon-512.png").convert("RGB").resize((104, 104), Image.LANCZOS), 26)
    shadow(canvas, icon, (64, 112), blur=16, alpha=120)
    draw = ImageDraw.Draw(canvas)
    draw.text((188, 164), "Grably", font=font("outfit_bold", 84), fill=WHITE, anchor="lm")
    tagline = font("outfit_semibold", 36)
    draw.text((66, 268), "Save videos to your phone", font=tagline, fill=TEAL_LIGHT, anchor="ls")
    draw.text((66, 314), "in a couple of taps.", font=tagline, fill=TEAL_LIGHT, anchor="ls")

    # Translucent chips: drawn on their own layer, since drawing on RGBA replaces pixels instead of blending
    chip_font = font("inter_medium", 22)
    chips = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    chip_draw = ImageDraw.Draw(chips)
    x, labels = 66, []
    for label in ("Free", "No sign-up", "MP4 & MP3"):
        tw = draw.textlength(label, font=chip_font)
        chip_draw.rounded_rectangle([x, 352, x + tw + 36, 396], radius=22, fill=(16, 179, 163, 46),
                                    outline=(16, 179, 163, 220), width=2)
        labels.append((x + 18, label))
        x += tw + 50
    canvas.alpha_composite(chips)
    for lx, label in labels:
        draw.text((lx, 374), label, font=chip_font, fill=WHITE, anchor="lm")
    return canvas.convert("RGB")


def main() -> None:
    PHONE.mkdir(parents=True, exist_ok=True)
    # Google Play wants screenshots without transparency; the emulator's captures have an alpha channel
    for raw in sorted(RAW.glob("*.png")):
        with Image.open(raw) as im:
            flat = im.convert("RGB") if im.mode != "RGB" else None
        if flat:
            flat.save(raw, optimize=True)
    made = 0
    for name, caption in CAPTIONS.items():
        raw = RAW / f"{name}.png"
        if not raw.is_file():
            print(f"missing {raw.relative_to(STORE)}, skipped")
            continue
        framed_screenshot(raw, caption).save(PHONE / f"{name}.png", optimize=True)
        made += 1
    print(f"{made} framed screenshots in {PHONE.relative_to(STORE)}")
    if (RAW / "02-home.png").is_file() and (RAW / "05-downloads.png").is_file():
        feature_graphic(RAW / "02-home.png", RAW / "05-downloads.png").save(GRAPHICS / "feature-graphic.png", optimize=True)
        print("feature graphic in graphics/feature-graphic.png")


if __name__ == "__main__":
    main()
