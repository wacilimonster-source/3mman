import os
from PIL import Image

SRC = r"G:\game\新建文件夹\3mman\generated-images\Clean_modern_Android_app_launc_2026-08-04T17-14-31.png"
RES_ROOT = r"G:\game\新建文件夹\3mman\app\src\main\res"

DENSITY = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

img = Image.open(SRC).convert("RGBA")

for folder, size in DENSITY.items():
    d = os.path.join(RES_ROOT, folder)
    if not os.path.isdir(d):
        print("SKIP missing", d)
        continue
    resized = img.resize((size, size), Image.LANCZOS)
    for name in ("ic_launcher.png", "ic_launcher_round.png"):
        out = os.path.join(d, name)
        if os.path.exists(out):
            resized.save(out)
            print("WROTE", out)
        else:
            print("MISSING", out)
print("DONE")
