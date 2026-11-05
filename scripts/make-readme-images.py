"""Builds docs/images/* for the README from the CI UI-test screenshots (ci-screenshots branch).

    git fetch origin ci-screenshots
    python3 scripts/make-readme-images.py <dir with the screenshots> docs/images
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

SRC, OUT = Path(sys.argv[1]), Path(sys.argv[2])
OUT.mkdir(parents=True, exist_ok=True)
PHONE = {
    "scanner": "06_refined", "address-list": "37_pointer_entry_follows", "pointer-scan": "36_pointer_scan",
    "write-confirmation": "09_write_confirmation", "memory-viewer": "12_memory_viewer",
    "cheat-library": "13_cheat_library", "diagnostics": "38_diagnostics", "guide": "39_guide",
}
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


def save(img: Image.Image, path: Path):
    # UI screenshots compress well to a 256-colour palette.
    img.convert("RGB").quantize(colors=256, method=Image.Quantize.MEDIANCUT).save(path, optimize=True)


def rounded(img: Image.Image, r: int) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, *img.size), r, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def phone(name: str, width: int) -> Image.Image:
    im = Image.open(SRC / f"{name}.png").convert("RGB")
    return im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)


for out, src in PHONE.items():
    save(rounded(phone(src, 540), 28).convert("RGB"), OUT / f"{out}.png")

tab = Image.open(SRC / "40_tablet_scanner.png").convert("RGB")
save(tab.resize((1640, round(tab.height * 1640 / tab.width)), Image.LANCZOS), OUT / "tablet.png")


def poster(w: int, h: int, path: Path, title_size: int, phones: list[str], phone_w: int):
    bg = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(bg)
    for y in range(h):  # vertical gradient, PlayStation-blue to near black
        t = y / h
        d.line([(0, y), (w, y)], fill=(int(10 + 10 * (1 - t)), int(22 + 40 * (1 - t)), int(48 + 90 * (1 - t))))
    canvas = bg.convert("RGBA")
    x = w - 40 - phone_w * len(phones) + 60 * (len(phones) - 1)
    for i, n in enumerate(phones):
        p = rounded(phone(n, phone_w), 26)
        y = int(h * 0.12) + (0 if i == 1 else 40)
        shadow = Image.new("RGBA", (p.width + 60, p.height + 60), (0, 0, 0, 0))
        ImageDraw.Draw(shadow).rounded_rectangle((30, 30, 30 + p.width, 30 + p.height), 26, fill=(0, 0, 0, 150))
        canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(18)), (x - 30, y - 18))
        canvas.alpha_composite(p, (x, y))
        x += phone_w - 60
    d = ImageDraw.Draw(canvas)
    tx, ty = int(w * 0.05), int(h * 0.26)
    d.text((tx, ty), "PS4 SmartCheat", font=ImageFont.truetype(BOLD, title_size), fill="white")
    sub = ImageFont.truetype(FONT, int(title_size * 0.36))
    lines = ["Memory scanner & cheat workbench", "for your own PS4 — on Android"]
    for k, line in enumerate(lines):
        d.text((tx, ty + int(title_size * 1.35) + k * int(title_size * 0.5)), line, font=sub, fill=(200, 215, 240))
    tag = ImageFont.truetype(BOLD, int(title_size * 0.27))
    chips = ["Turbo Scan", "Pointer scan", "Safe edits", "GoldHEN"]
    cx, cy = tx, ty + int(title_size * 2.75)
    for c in chips:
        tw = d.textlength(c, font=tag)
        d.rounded_rectangle((cx, cy, cx + tw + 28, cy + int(title_size * 0.5)), 14, fill=(0, 112, 204))
        d.text((cx + 14, cy + int(title_size * 0.09)), c, font=tag, fill="white")
        cx += tw + 40
    save(canvas.convert("RGB"), path)


poster(1600, 560, OUT / "banner.png", 84, ["13_cheat_library", "06_refined", "37_pointer_entry_follows"], 230)
poster(1280, 640, OUT / "social-preview.png", 64, ["13_cheat_library", "06_refined", "37_pointer_entry_follows"], 210)
print("written:", sorted(p.name for p in OUT.iterdir()))
