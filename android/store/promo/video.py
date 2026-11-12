"""Renders the 15-second Grabbly promo video (1920x1080, 30 fps) with music.py's track.

    pip install pillow numpy scipy imageio-ffmpeg
    python android/store/promo/music.py /tmp/music.wav
    python android/store/promo/video.py android/store/screenshots/raw /tmp/music.wav android/store/promo/grabbly-promo.mp4

Upload the MP4 to YouTube and paste its link into Play Console (Main store listing → Video).

One musical bar (1.875 s at 128 BPM) per scene: logo intro, six features, end card.
"""
import math
import subprocess
import sys
from pathlib import Path

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parents[2]  # android/
sys.path.insert(0, str(REPO / "store"))
from make_store_graphics import phone  # noqa: E402  same phone frame as the store screenshots

FONTS = REPO / "app/src/main/res/font"
ICON = REPO / "store/graphics/icon-512.png"
W, H, FPS = 1920, 1080, 30
BEAT = 60 / 128
BAR = 4 * BEAT
DURATION = 8 * BAR  # 15 s
NAVY_TOP, NAVY_BOTTOM = (16, 38, 72), (5, 13, 27)
TEAL, TEAL_LIGHT, WHITE = (16, 179, 163), (178, 236, 228), (255, 255, 255)

SCENES = [  # title, subtitle, screenshot
    ("Paste a link", "From your favorite apps. That's it.", "02-home"),
    ("Pick your quality", "Up to 1080p, or just the sound as MP3", "04-quality"),
    ("Save photos & albums", "Every video and photo in a post", "05-album"),
    ("Watch in the app", "A built-in player and photo viewer", "07-player"),
    ("Many links at once", "Paste a list. Grabbly does the rest.", "08-batch"),
    ("Your private folder", "Locked with your fingerprint or PIN", "12-private-dark"),
]


def font(name, size):
    return ImageFont.truetype(str(FONTS / f"{name}.ttf"), size)


def ease_out(t):
    t = min(1.0, max(0.0, t))
    return 1 - (1 - t) ** 3


def ease_out_back(t):
    t = min(1.0, max(0.0, t))
    c = 1.70158
    return 1 + (c + 1) * (t - 1) ** 3 + c * (t - 1) ** 2


def with_opacity(img, opacity):
    if opacity >= 0.999:
        return img
    out = img.copy()
    out.putalpha(img.getchannel("A").point(lambda a: int(a * max(0.0, opacity))))
    return out


def background(glow_strength):
    canvas = Image.new("RGB", (W, H))
    column = Image.new("RGB", (1, H))
    for y in range(H):
        t = y / (H - 1)
        column.putpixel((0, y), tuple(round(a + (b - a) * t) for a, b in zip(NAVY_TOP, NAVY_BOTTOM)))
    canvas.paste(column.resize((W, H)))
    canvas = canvas.convert("RGBA")
    layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    d.ellipse((980, 60, 1720, 1020), fill=(*TEAL, int(95 * glow_strength)))
    d.ellipse((-300, 600, 500, 1300), fill=(30, 70, 130, int(110 * glow_strength)))
    canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(170)))
    return canvas


def text_layer(text, typeface, color):
    box = ImageDraw.Draw(Image.new("RGBA", (1, 1))).textbbox((0, 0), text, font=typeface)
    img = Image.new("RGBA", (box[2] - box[0] + 20, box[3] - box[1] + 30), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((10 - box[0], 10 - box[1]), text, font=typeface, fill=color)
    return img


def shadowed(item, blur=28, alpha=150):
    pad = blur * 2
    out = Image.new("RGBA", (item.width + 2 * pad, item.height + 2 * pad), (0, 0, 0, 0))
    mask = item.getchannel("A").point(lambda a: alpha if a else 0)
    shadow = Image.new("RGBA", item.size, (0, 0, 0, 255))
    out.paste(shadow, (pad, pad + blur // 2), mask)
    out = out.filter(ImageFilter.GaussianBlur(blur))
    out.alpha_composite(item, (pad, pad))
    return out, pad


def rounded_icon(size):
    icon = Image.open(ICON).convert("RGBA").resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * 0.24), fill=255)
    icon.putalpha(mask)
    return icon


def pill(text, typeface):
    width = int(ImageDraw.Draw(Image.new("RGBA", (1, 1))).textlength(text, font=typeface)) + 52
    img = Image.new("RGBA", (width, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, width - 1, 63], radius=32, fill=(16, 179, 163, 60), outline=(*TEAL, 230), width=3)
    d.text((width / 2, 33), text, font=typeface, fill=WHITE, anchor="mm")  # centred on the font's middle line
    return img


def main(raw_dir, music, out):
    raw_dir = Path(raw_dir)
    bg_dim, bg_bright = background(0.75), background(1.2)

    title_font, sub_font = font("outfit_bold", 92), font("inter_medium", 42)
    scenes = []
    for number, (title, subtitle, shot) in enumerate(SCENES, 1):
        device = phone(Image.open(raw_dir / f"{shot}.png"), 480)
        device, pad = shadowed(device)
        scenes.append({
            "step": text_layer(f"0{number}", font("outfit_bold", 40), TEAL),
            "title": text_layer(title, title_font, WHITE),
            "sub": text_layer(subtitle, sub_font, TEAL_LIGHT),
            "phone": device, "pad": pad,
        })

    icon_big = shadowed(rounded_icon(250), blur=30, alpha=170)[0]
    word = text_layer("Grabbly", font("outfit_bold", 150), WHITE)
    tagline = text_layer("Save videos in a couple of taps", font("inter_medium", 46), TEAL_LIGHT)
    icon_small = shadowed(rounded_icon(170), blur=24, alpha=170)[0]
    end_word = text_layer("Grabbly", font("outfit_bold", 120), WHITE)
    end_line = text_layer("The free video saver for Android", font("inter_medium", 44), TEAL_LIGHT)
    chips = [pill(t, font("inter_semibold", 30)) for t in ("Free", "No sign-up", "MP4 & MP3", "Photos too")]

    cmd = [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error",
           "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
           "-i", music, "-c:v", "libx264", "-preset", "medium", "-crf", "18", "-pix_fmt", "yuv420p",
           "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart", out]
    encoder = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    frames = int(round(DURATION * FPS))
    thumbnail = None
    for i in range(frames):
        t = i / FPS
        # The glow pulses with the kick drum (from bar 2 on), softly during the intro
        since_beat = (t % BEAT) / BEAT
        pulse = math.exp(-since_beat * 4) * (1.0 if BAR <= t < 7 * BAR else 0.35)
        frame = Image.blend(bg_dim, bg_bright, min(1.0, 0.25 + 0.6 * pulse))

        scene = int(t // BAR)
        local = t - scene * BAR
        fade_out = 1 - ease_out((local - (BAR - 0.22)) / 0.22) if scene < 7 else 1.0

        if scene == 0:  # logo intro
            s = 0.55 + 0.45 * ease_out_back(local / 0.6)
            icon = icon_big.resize((int(icon_big.width * s), int(icon_big.height * s)), Image.LANCZOS)
            a = ease_out(local / 0.3) * fade_out
            frame.alpha_composite(with_opacity(icon, a), ((W - icon.width) // 2, 300 - icon.height // 2))
            a = ease_out((local - 0.35) / 0.4) * fade_out
            y = int(560 + 30 * (1 - ease_out((local - 0.35) / 0.4)))
            frame.alpha_composite(with_opacity(word, a), ((W - word.width) // 2, y))
            a = ease_out((local - 0.7) / 0.4) * fade_out
            frame.alpha_composite(with_opacity(tagline, a), ((W - tagline.width) // 2, 760))
        elif scene <= 6:  # features
            sc = scenes[scene - 1]
            enter = ease_out(local / 0.4)
            zoom = 1.0 + 0.035 * (local / BAR)
            device = sc["phone"]
            if abs(zoom - 1) > 0.002:
                device = device.resize((int(device.width * zoom), int(device.height * zoom)), Image.BILINEAR)
            x = 1360 - device.width // 2 + int(140 * (1 - enter))
            y = (H - device.height) // 2 + 10
            frame.alpha_composite(with_opacity(device, enter * fade_out), (x, y))
            for k, (key, top) in enumerate((("step", 405), ("title", 470), ("sub", 600))):
                a = ease_out((local - 0.08 - 0.08 * k) / 0.35)
                dx = int(-60 * (1 - a))
                frame.alpha_composite(with_opacity(sc[key], a * fade_out), (150 + dx, top))
            bar_a = ease_out((local - 0.1) / 0.4) * fade_out
            line = Image.new("RGBA", (int(110 * bar_a) + 1, 10), (*TEAL, int(255 * bar_a)))
            frame.alpha_composite(line, (160, 385))
        else:  # end card
            a = ease_out(local / 0.35)
            s = 0.8 + 0.2 * ease_out_back(local / 0.5)
            icon = icon_small.resize((int(icon_small.width * s), int(icon_small.height * s)), Image.LANCZOS)
            block_w = icon.width - 40 + end_word.width
            x0 = (W - block_w) // 2
            frame.alpha_composite(with_opacity(icon, a), (x0 - 20, 415 - icon.height // 2))
            frame.alpha_composite(with_opacity(end_word, a), (x0 + icon.width - 40, 415 - end_word.height // 2))
            a2 = ease_out((local - 0.25) / 0.4)
            frame.alpha_composite(with_opacity(end_line, a2), ((W - end_line.width) // 2, 555))
            total = sum(c.width for c in chips) + 24 * (len(chips) - 1)
            cx = (W - total) // 2
            for k, chip in enumerate(chips):
                a3 = ease_out((local - 0.45 - 0.08 * k) / 0.35)
                frame.alpha_composite(with_opacity(chip, a3), (cx, 685 + int(20 * (1 - a3))))
                cx += chip.width + 24
            if local > 1.2:
                thumbnail = thumbnail or frame.convert("RGB")
        encoder.stdin.write(frame.convert("RGB").tobytes())
        if i % 90 == 0:
            print(f"frame {i}/{frames}", flush=True)
    encoder.stdin.close()
    if encoder.wait() != 0:
        raise SystemExit("ffmpeg failed")
    if thumbnail:
        thumbnail.resize((1280, 720), Image.LANCZOS).save(Path(out).with_name("grabbly-promo-thumbnail.jpg"), quality=92)
    print("wrote", out)


if __name__ == "__main__":
    main(*sys.argv[1:4])
