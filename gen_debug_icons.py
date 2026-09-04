"""Generate debug icons with red DBG badge for PixelPlayer."""
from PIL import Image, ImageDraw, ImageFont
import os

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

def tint_red(base_img):
    """Tint the image red while preserving alpha. Used for debug icon background."""
    r, g, b, a = base_img.split()
    # Boost red channel, suppress green/blue to create a strong red tint
    r = r.point(lambda i: min(255, int(i * 1.2) + 40))
    g = g.point(lambda i: int(i * 0.3))
    b = b.point(lambda i: int(i * 0.3))
    return Image.merge("RGBA", (r, g, b, a))


def add_dbg_badge(base_img, size):
    """Add a red DBG badge in bottom-right corner."""
    badge_radius = size // 5
    badge_x = size - badge_radius * 2 - 2
    badge_y = size - badge_radius * 2 - 2
    draw = ImageDraw.Draw(base_img)
    draw.ellipse(
        [badge_x, badge_y, badge_x + badge_radius * 2, badge_y + badge_radius * 2],
        fill=(220, 50, 50, 255),
        outline=(255, 255, 255, 220),
        width=2,
    )
    try:
        font = ImageFont.truetype("C:\\Windows\\Fonts\\arialbd.ttf", badge_radius)
    except Exception:
        font = ImageFont.load_default()
    text = "DBG"
    bbox = draw.textbbox((0, 0), text, font=font)
    tw = bbox[2] - bbox[0]
    th = bbox[3] - bbox[1]
    tx = badge_x + badge_radius - tw // 2
    ty = badge_y + badge_radius - th // 2
    draw.text((tx, ty), text, fill=(255, 255, 255, 255), font=font)
    return base_img

for density, size in MAIN_ICONS.items():
    # Re-compose launcher icon from red-tinted background + foreground so the
    # whole debug icon is visually distinct from release.
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
    # Center foreground on background (adaptive icon safe zone is centered).
    fg_x = (size - fg_size) // 2
    fg_y = (size - fg_size) // 2
    composed = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    composed.paste(bg, (0, 0), bg)
    composed.paste(fg, (fg_x, fg_y), fg)

    add_dbg_badge(composed, size)
    out_dir = os.path.join(DEBUG_DIR, f"mipmap-{density}")
    os.makedirs(out_dir, exist_ok=True)
    composed.save(os.path.join(out_dir, "ic_launcher.webp"), "WEBP", quality=90)
    composed.save(os.path.join(out_dir, "ic_launcher_round.webp"), "WEBP", quality=90)
    print(f"icon {density}: {size}x{size}")

for density, size in FG_ICONS.items():
    src = Image.open(SRC_FG).convert("RGBA").resize((size, size), Image.LANCZOS)
    add_dbg_badge(src, size)
    out_dir = os.path.join(DEBUG_DIR, f"mipmap-{density}")
    os.makedirs(out_dir, exist_ok=True)
    src.save(os.path.join(out_dir, "ic_launcher_foreground.webp"), "WEBP", quality=90)
    print(f"foreground {density}: {size}x{size}")

def tint_red(base_img):
    """Tint the image red while preserving alpha. Used for debug icon background."""
    r, g, b, a = base_img.split()
    # Boost red channel, suppress green/blue to create a strong red tint
    r = r.point(lambda i: min(255, int(i * 1.2) + 40))
    g = g.point(lambda i: int(i * 0.3))
    b = b.point(lambda i: int(i * 0.3))
    return Image.merge("RGBA", (r, g, b, a))

# Tint background red for debug so the icon is clearly distinct from release.
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

# Adaptive icon XML (same as main but in debug source set)
xml_dir = os.path.join(DEBUG_DIR, "mipmap-anydpi-v26")
os.makedirs(xml_dir, exist_ok=True)
with open(os.path.join(xml_dir, "ic_launcher.xml"), "w") as f:
    f.write("""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/new_monochrome"/>
</adaptive-icon>
""")
with open(os.path.join(xml_dir, "ic_launcher_round.xml"), "w") as f:
    f.write("""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/new_monochrome"/>
</adaptive-icon>
""")

# Debug strings
vals_dir = os.path.join(DEBUG_DIR, "values")
os.makedirs(vals_dir, exist_ok=True)
with open(os.path.join(vals_dir, "strings.xml"), "w", encoding="utf-8") as f:
    f.write("""<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">PixelPlayer (Debug)</string>
</resources>
""")

print("Done!")
