#!/usr/bin/env python3
"""Generate store assets from raw screenshots.

Run inside Docker with Python + Pillow + Noto fonts:

    docker run --rm -v "$(pwd):/work" -w /work python:3.12-slim bash -c \
        "apt-get update -qq && apt-get install -y -qq fonts-noto-core fonts-dejavu-core && pip install -q pillow && python fastlane/scripts/store_assets.py"
"""

import os
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
SRC_DIR = ROOT / "fastlane/metadata/android/en-US/images/phoneScreenshots"
OUT_DIR = SRC_DIR / "stylised"
FEATURE_OUT = OUT_DIR / "featureGraphic.png"

WIDTH, HIGHT = 1080, 1920
MARGIN = 96
TEXT_W = WIDTH - 2 * MARGIN

COLOR_BG_TOP = (8, 11, 34)
COLOR_BG_BOTTOM = (18, 23, 70)
COLOR_ACCENT = (130, 165, 255)
COLOR_MUTED = (195, 205, 235)
COLOR_FRAME = (10, 13, 32)
COLOR_TITLE_BAR = (17, 20, 33)


def font_paths(weight: str):
    """Likely paths for TrueType fonts inside common containers."""
    names = {
        "regular": ["NotoSans-Regular.ttf", "DejaVuSans.ttf", "LiberationSans-Regular.ttf", "FreeSans.ttf"],
        "bold": ["NotoSans-Bold.ttf", "DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf", "FreeSansBold.ttf"],
        "semibold": ["NotoSans-SemiBold.ttf", "DejaVuSans-Bold.ttf", "LiberationSans-Bold.ttf", "FreeSansBold.ttf"],
    }
    dirs = [
        "/usr/share/fonts/truetype/noto",
        "/usr/share/fonts/truetype/dejavu",
        "/usr/share/fonts/truetype/liberation",
        "/usr/share/fonts/truetype/freefont",
        "/usr/share/fonts/truetype",
        "/usr/local/share/fonts",
        "/System/Library/Fonts",
        "/Library/Fonts",
        os.path.expanduser("~/Library/Fonts"),
    ]
    for name in names.get(weight, names["regular"]):
        for d in dirs:
            p = Path(d) / name
            if p.exists():
                return str(p)
    return None


def load_font(weight: str, size: int):
    path = font_paths(weight)
    if path:
        return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def hex_rgb(value: int):
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)


def draw_linear_gradient(w: int, h: int, top: tuple, bottom: tuple):
    img = Image.new("RGB", (1, h), top)
    pixels = img.load()
    for y in range(h):
        t = y / (h - 1)
        pixels[0, y] = (
            int(top[0] + (bottom[0] - top[0]) * t),
            int(top[1] + (bottom[1] - top[1]) * t),
            int(top[2] + (bottom[2] - top[2]) * t),
        )
    return img.resize((w, h), Image.Resampling.BILINEAR)


def make_shadow(size: tuple, radius: int, blur: int, alpha: int):
    pad = blur * 2
    img = Image.new("RGBA", (size[0] + pad * 2, size[1] + pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([pad, pad, pad + size[0], pad + size[1]], radius=radius, fill=(0, 0, 0, alpha))
    return img.filter(ImageFilter.GaussianBlur(radius=blur))


def make_glow(size: tuple, color: tuple, blur: int):
    pad = blur * 2
    img = Image.new("RGBA", (size[0] + pad * 2, size[1] + pad * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse([pad, pad, pad + size[0], pad + size[1]], fill=color)
    return img.filter(ImageFilter.GaussianBlur(radius=blur))


def rounded_mask(size: tuple, radius: int):
    mask = Image.new("L", size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def draw_text_spaced(draw: ImageDraw.Draw, text: str, font: ImageFont.FreeTypeFont, x: int, y: int, color: tuple, spacing: int = 4):
    for ch in text:
        draw.text((x, y), ch, font=font, fill=color)
        x += draw.textbbox((0, 0), ch, font=font)[2] + spacing


def fit_text(text: str, font: ImageFont.FreeTypeFont, max_w: int):
    bbox = font.getbbox(text)
    return bbox[2] - bbox[0] <= max_w


def text_size(text: str, font: ImageFont.FreeTypeFont):
    bbox = font.getbbox(text)
    return bbox[2] - bbox[0], bbox[3] - bbox[1]


def draw_branding(base: Image.Image, draw: ImageDraw.Draw):
    icon_path = ROOT / "fastlane/metadata/android/en-US/images/icon.png"
    if icon_path.exists():
        icon = Image.open(icon_path).convert("RGBA").resize((74, 74), Image.Resampling.LANCZOS)
        icon_mask = rounded_mask((74, 74), 18)
        # subtle shadow behind icon
        shadow = make_shadow((74, 74), 18, 18, 100)
        base.paste(shadow, (MARGIN - 18, 84 - 18), shadow)
        base.paste(icon, (MARGIN, 84), icon_mask)

    font = load_font("bold", 34)
    draw.text((MARGIN + 94, 104), "Android IP Camera", font=font, fill=(255, 255, 255))


def draw_text_block(draw: ImageDraw.Draw, eyebrow: str, headline: list[str], sub: list[str]):
    # eyebrow
    font_eyebrow = load_font("semibold", 24)
    draw_text_spaced(draw, eyebrow.upper(), font_eyebrow, MARGIN, 200, COLOR_ACCENT, spacing=4)

    # headline
    font_head = load_font("bold", 76)
    y = 250
    for line in headline:
        w, _ = text_size(line, font_head)
        if w > TEXT_W:
            # shrink to fit
            size = 76
            while size > 42:
                font_head = load_font("bold", size)
                w, _ = text_size(line, font_head)
                if w <= TEXT_W:
                    break
                size -= 2
        draw.text((MARGIN, y), line, font=font_head, fill=(255, 255, 255))
        y += 86

    # subtitle
    font_sub = load_font("regular", 34)
    y += 22
    for line in sub:
        draw.text((MARGIN, y), line, font=font_sub, fill=COLOR_MUTED)
        y += 48


def paste_centered(base: Image.Image, img: Image.Image, x: int, y: int):
    base.paste(img, (x, y), img)


def draw_phone_frame(base: Image.Image, screenshot: Image.Image):
    # target screen width
    screen_w = 570
    src_w, src_h = screenshot.size
    screen_h = int(screen_w * src_h / src_w)
    bezel = 14
    frame_w = screen_w + bezel * 2
    frame_h = screen_h + bezel * 2
    x = (WIDTH - frame_w) // 2
    y = 560

    # shadow
    shadow = make_shadow((frame_w, frame_h), 50, 55, 130)
    base.paste(shadow, (x - 55, y - 55 + 30), shadow)

    # outer frame
    draw = ImageDraw.Draw(base)
    draw.rounded_rectangle([x, y, x + frame_w, y + frame_h], radius=50, fill=COLOR_FRAME, outline=(255, 255, 255, 40), width=2)

    # screen (nudged up 1 px so there is no bright gap below the frame)
    screen = screenshot.resize((screen_w, screen_h), Image.Resampling.LANCZOS)
    screen_x = x + bezel
    screen_y = y + bezel - 1
    mask = rounded_mask((screen_w, screen_h), 38)
    base.paste(screen, (screen_x, screen_y), mask)


def draw_desktop_card(base: Image.Image, screenshot: Image.Image):
    # Show the full desktop screenshot inside a framed card with a visible border on all sides.
    src_w, src_h = screenshot.size
    bezel = 8
    card_w = 940
    screen_w = card_w - bezel * 2
    screen_h = int(screen_w * src_h / src_w)
    card_h = screen_h + bezel * 2
    x = (WIDTH - card_w) // 2
    y = 560 + (1300 - card_h) // 2

    # shadow
    shadow = make_shadow((card_w, card_h), 28, 60, 140)
    base.paste(shadow, (x - 60, y - 60 + 30), shadow)

    draw = ImageDraw.Draw(base)
    # outer frame
    draw.rounded_rectangle([x, y, x + card_w, y + card_h], radius=28, fill=COLOR_FRAME, outline=(255, 255, 255, 55), width=2)

    # screenshot inset by the bezel so the frame shows on all sides
    screen = screenshot.resize((screen_w, screen_h), Image.Resampling.LANCZOS)
    screen_x = x + bezel
    screen_y = y + bezel
    screen_mask = rounded_mask((screen_w, screen_h), 22)
    base.paste(screen, (screen_x, screen_y), screen_mask)


def build_screenshot(source: str, eyebrow: str, headline: list[str], sub: list[str], device: str):
    base = draw_linear_gradient(WIDTH, HIGHT, COLOR_BG_TOP, COLOR_BG_BOTTOM).convert("RGBA")

    # decorative glows
    glow = make_glow((700, 700), (COLOR_ACCENT[0], COLOR_ACCENT[1], COLOR_ACCENT[2], 70), 180)
    base.paste(glow, (WIDTH // 2 - 350, 850), glow)

    small_glow = make_glow((500, 500), (120, 80, 255, 45), 140)
    base.paste(small_glow, (-120, -50), small_glow)

    # clean gradient + glows only for a professional look

    draw = ImageDraw.Draw(base)
    draw_branding(base, draw)
    draw_text_block(draw, eyebrow, headline, sub)

    screenshot = Image.open(SRC_DIR / source).convert("RGB")
    if device == "phone":
        draw_phone_frame(base, screenshot)
    else:
        draw_desktop_card(base, screenshot)

    return base.convert("RGB")


def build_feature_graphic():
    W, H = 1024, 500
    base = draw_linear_gradient(W, H, (6, 9, 30), (16, 21, 62)).convert("RGBA")

    # glows
    glow = make_glow((600, 600), (COLOR_ACCENT[0], COLOR_ACCENT[1], COLOR_ACCENT[2], 60), 160)
    base.paste(glow, (W - 450, -80), glow)

    small = make_glow((350, 350), (110, 70, 255, 35), 110)
    base.paste(small, (-80, 280), small)

    d = ImageDraw.Draw(base)
    # icon
    icon_path = ROOT / "fastlane/metadata/android/en-US/images/icon.png"
    if icon_path.exists():
        icon = Image.open(icon_path).convert("RGBA").resize((110, 110), Image.Resampling.LANCZOS)
        mask = rounded_mask((110, 110), 24)
        base.paste(icon, (60, 60), mask)

    font_title = load_font("bold", 44)
    d.text((190, 86), "Android IP Camera", font=font_title, fill=(255, 255, 255))

    font_head = load_font("bold", 42)
    d.text((60, 210), "Turn your Android device", font=font_head, fill=(255, 255, 255))
    d.text((60, 264), "into a remote security camera", font=font_head, fill=(255, 255, 255))

    font_sub = load_font("regular", 26)
    d.text((60, 360), "Live feed, remote control, recording and", font=font_sub, fill=COLOR_MUTED)
    d.text((60, 396), "motion alerts from your phone.", font=font_sub, fill=COLOR_MUTED)

    # phone mockup
    phone_src = Image.open(SRC_DIR / "1.png").convert("RGB")
    phone_w = 200
    phone_h = int(phone_w * phone_src.height / phone_src.width)
    phone = phone_src.resize((phone_w, phone_h), Image.Resampling.LANCZOS)
    px, py = 780, 28
    frame_w = phone_w + 20
    frame_h = phone_h + 20
    fx, fy = px - 10, py - 10
    shadow = make_shadow((frame_w, frame_h), 24, 36, 120)
    base.paste(shadow, (fx - 36, fy - 36 + 16), shadow)
    d.rounded_rectangle([fx, fy, fx + frame_w, fy + frame_h], radius=22, fill=COLOR_FRAME, outline=(255, 255, 255, 40), width=2)
    mask = rounded_mask((phone_w, phone_h), 16)
    base.paste(phone, (px, py), mask)

    return base.convert("RGB")


def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    slides = [
        ("1.png", "LIVE PREVIEW", ["Your phone is now", "a remote security camera"],
         ["Watch the live feed in any web browser,", "on your phone, tablet or laptop."], "phone"),
        ("2.png", "SECURE ACCESS", ["Lock down", "the stream"],
         ["Password protection, secure TLS, RTSP and", "optional remote access from anywhere."], "phone"),
        ("3.png", "REMOTE CONTROL", ["Control it from", "any browser"],
         ["Zoom, rotate, record clips and take snapshots", "without installing an app."], "desktop"),
        ("4.png", "STREAM SETTINGS", ["Tune it for", "your setup"],
         ["Resolution, frame rate, exposure and", "motion recording, all adjustable."], "desktop"),
    ]

    for source, eyebrow, headline, sub, device in slides:
        img = build_screenshot(source, eyebrow, headline, sub, device)
        out_path = OUT_DIR / source
        img.save(out_path, "PNG", optimize=True)
        print(f"wrote {out_path}")

    feature = build_feature_graphic()
    feature.save(FEATURE_OUT, "PNG", optimize=True)
    print(f"wrote {FEATURE_OUT}")


if __name__ == "__main__":
    main()
