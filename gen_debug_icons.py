"""Generate debug icons with red-tinted background (no badge) for PixelPlayer."""
from PIL import Image, ImageDraw, ImageFont
import os
import shutil

MAIN_ICONS = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
FG_ICONS = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

SRC_ICON = r"D:\3rd-party-projects\PixelPlayer\app\src\main\res\mipmap-xxxhdpi\ic_launcher.webp"
SRC_FG = r"D:\3rd-party-projects\PixelPlayer\app\src\main\res\mipmap-xxxhdpi\ic_launcher_foreground.webp"

DEBUG_DIR = r"D:\3rd-party-projects\PixelPlayer\app\src\debug\res"
MINIFIED_DEBUG_DIR = r"D:\3rd-party-projects\PixelPlayer\app\src\minifiedDebug\res"


def tint_red(base_img):
    """Tint the image red while preserving alpha. Used for debug icon background."""
    r, g, b, a = base_img.split()
    r = r.point(lambda i: min(255, int(i * 1.2) + 40))
    g = g.point(lambda i: int(i * 0.3))
    b = b.point(lambda i: int(i * 0.3))
    return Image.merge("RGBA", (r, g, b, a))


for density, size in MAIN_ICONS.items():
    bg = Image.open(
        os.path.join(
            r"D:\3rd-party-projects\PixelPlayer\app\src\main\res",
            f"mipmap-{density}",
            "ic_launcher_background.webp",
        )
    ).resize((size, size), Image.LANCZOS).convert("RGBA")
    bg = tint_red(bg)

    fg_size = FG_ICONS[density]
    fg = Image.open(SRC_FG).convert("RGBA").resize((fg_size, fg_size), Image.LANCZOS)
    fg_x = (size - fg_size) // 2
    fg_y = (size - fg_size) // 2
    composed = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    composed.paste(bg, (0, 0), bg)
    composed.paste(fg, (fg_x, fg_y), fg)

    out_dir = os.path.join(DEBUG_DIR, f"mipmap-{density}")
    os.makedirs(out_dir, exist_ok=True)
    composed.save(os.path.join(out_dir, "ic_launcher.webp"), "WEBP", quality=90)
    composed.save(os.path.join(out_dir, "ic_launcher_round.webp"), "WEBP", quality=90)
    print(f"icon {density}: {size}x{size}")

for density, size in FG_ICONS.items():
    src = Image.open(SRC_FG).convert("RGBA").resize((size, size), Image.LANCZOS)
    out_dir = os.path.join(DEBUG_DIR, f"mipmap-{density}")
    os.makedirs(out_dir, exist_ok=True)
    src.save(os.path.join(out_dir, "ic_launcher_foreground.webp"), "WEBP", quality=90)
    print(f"foreground {density}: {size}x{size}")

for density, size in MAIN_ICONS.items():
    bg_src = Image.open(
        os.path.join(
            r"D:\3rd-party-projects\PixelPlayer\app\src\main\res",
            f"mipmap-{density}",
            "ic_launcher_background.webp",
        )
    ).resize((size, size), Image.LANCZOS).convert("RGBA")
    bg_src = tint_red(bg_src)
    out_dir = os.path.join(DEBUG_DIR, f"mipmap-{density}")
    bg_src.save(os.path.join(out_dir, "ic_launcher_background.webp"), "WEBP", quality=90)
    print(f"background {density}: {size}x{size}")

xml_dir = os.path.join(DEBUG_DIR, "mipmap-anydpi-v26")
os.makedirs(xml_dir, exist_ok=True)
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    with open(os.path.join(xml_dir, name), "w") as f:
        f.write("""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/new_monochrome"/>
</adaptive-icon>
""")

vals_dir = os.path.join(DEBUG_DIR, "values")
os.makedirs(vals_dir, exist_ok=True)
with open(os.path.join(vals_dir, "strings.xml"), "w", encoding="utf-8") as f:
    f.write("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">PixelPlayer (Debug)</string>
</resources>
""")

if os.path.exists(MINIFIED_DEBUG_DIR):
    shutil.rmtree(MINIFIED_DEBUG_DIR)
shutil.copytree(DEBUG_DIR, MINIFIED_DEBUG_DIR)
print(f"Copied debug resources to {MINIFIED_DEBUG_DIR}")

print("Done!")
