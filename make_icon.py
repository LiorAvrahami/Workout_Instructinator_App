from PIL import Image
import os

P = {
    ".": (15, 23, 32, 255),      # background (app dark blue)
    "K": (26, 18, 8, 255),       # outline
    "S": (232, 167, 107, 255),   # skin
    "s": (199, 126, 69, 255),    # skin shadow
    "h": (247, 198, 148, 255),   # skin highlight
    "R": (214, 50, 60, 255),     # bandana red
    "r": (156, 31, 39, 255),     # bandana dark red
    "G": (143, 152, 163, 255),   # plate gray
    "g": (89, 97, 107, 255),     # plate dark gray
    "B": (201, 209, 217, 255),   # bar light
}

ROWS = [
    "",
    "",
    "...KK............KK",
    "..KGGK..........KGGK",
    "..KGgKKKKKKKKKKKKGgK",
    "..KGgKBBBKSSKBBBKGgK",
    "..KGgKKKKSShSKKKKGgK",
    "..KGGK..KSShSK..KGGK",
    "...KK...KSShSK...KK",
    ".........KSShSK",
    "..RR.....KSShSK",
    "...RR....KSShSK",
    "....KKK..KSShSK",
    "...KSRrK.KSShSK",
    "..KSSRrSKKSShSK",
    ".KSSSRrSSSSShSK",
    "KSShSRrSSSSSSSK",
    "KSSsSRrSSSSSSK",
    "KSssSRRSSSSSK",
    "KssssSSSSSKK",
    ".KKKKKKKKK",
    "",
    "",
    "",
]
ROWS = [r.ljust(24, ".")[:24] for r in ROWS]

img = Image.new("RGBA", (24, 24))
for y, row in enumerate(ROWS):
    for x, ch in enumerate(row):
        img.putpixel((x, y), P[ch])

sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
base = "/home/claude/workoutinator/app/src/main/res"
for name, px in sizes.items():
    d = f"{base}/mipmap-{name}"
    os.makedirs(d, exist_ok=True)
    img.resize((px, px), Image.NEAREST).save(f"{d}/ic_launcher.png")

img.resize((512, 512), Image.NEAREST).save("/home/claude/icon_preview.png")
print("done")
