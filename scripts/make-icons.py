# Ritar Sign-ikonerna i samma stil som Trace (kräver Pillow och numpy).
#   python3 scripts/make-icons.py
# Mörk petroleumgrön bakgrund, guldring med sken, ett stort S i Cinzel med ett signaturdrag under,
# en mint pennspets och en ljusguld punkt på ringen.
import math, os
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = os.path.join(os.path.dirname(__file__), '..')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
CINZEL = os.path.join(RES, 'font', 'cinzel_bold.ttf')
GOLD, GOLD_DIM, GOLD_PALE, MINT, BG, OUTLINE = (232, 176, 74), (138, 109, 46), (243, 217, 160), (95, 196, 180), (11, 20, 22), (15, 26, 28)
S = 4  # översampling
DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}

def background(size):
    y, x = np.mgrid[0:size, 0:size]
    d = np.sqrt((x - size / 2) ** 2 + (y - size * 0.45) ** 2) / (size * 0.65)
    c0, c1, c2 = np.array([29, 74, 80]), np.array([17, 37, 40]), np.array(BG)
    t = np.clip(d, 0, 1)[..., None]
    col = np.where(t < 0.62, c0 + (c1 - c0) * (t / 0.62), c1 + (c2 - c1) * ((t - 0.62) / 0.38))
    img = Image.fromarray(col.astype('uint8'), 'RGB').convert('RGBA')
    halo = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    hd = ImageDraw.Draw(halo)
    r = size * 0.43
    hd.ellipse([size / 2 - r, size / 2 - r, size / 2 + r, size / 2 + r], outline=GOLD + (70,), width=int(size * 0.05))
    return Image.alpha_composite(img, halo.filter(ImageFilter.GaussianBlur(size * 0.03)))

def bezier(p0, p1, p2, p3, n=40):
    pts = []
    for i in range(n + 1):
        t = i / n
        a = (1 - t) ** 3; b = 3 * (1 - t) ** 2 * t; c = 3 * (1 - t) * t ** 2; d = t ** 3
        pts.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return pts

def stroke(d, pts, color, w):
    d.line(pts, fill=color, width=int(w), joint='curve')
    r = w / 2
    for x, y in (pts[0], pts[-1]):
        d.ellipse([x - r, y - r, x + r, y + r], fill=color)

def mark(size, k, mono=False):
    n = size * S
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = n / 2
    u = S * k * size / 1024           # en enhet i 1024-ikonen
    R = 300 * u
    g = (255, 255, 255, 255) if mono else GOLD + (255,)
    gp = (255, 255, 255, 220) if mono else GOLD_PALE + (255,)

    # Ring och ett stort S i Cinzel, som T:et i Trace
    d.ellipse([c - R, c - R, c + R, c + R], outline=g, width=int(34 * u))
    font = ImageFont.truetype(CINZEL, int(450 * u))
    d.text((c, c - 45 * u), 'S', font=font, fill=g, anchor='mm')

    # Signaturdraget under S:et
    def P(x, y): return (c + x * u, c + y * u)
    pts = bezier(P(-165, 168), P(-115, 132), P(-60, 212), P(-5, 165)) + \
          bezier(P(-5, 165), P(50, 118), P(95, 198), P(165, 148))[1:]
    stroke(d, pts, gp, 22 * u)

    # Accenter på ringen: mint pennspets uppe till vänster, ljusguld punkt nere till höger
    o = R * math.cos(math.pi / 4)
    ar = 44 * u
    tx, ty = c - o, c - o
    nib = lambda s: [(tx, ty + ar * 1.1 * s), (tx + ar * 0.75 * s, ty - ar * 0.1 * s), (tx, ty - ar * 1.0 * s), (tx - ar * 0.75 * s, ty - ar * 0.1 * s)]
    if not mono:
        d.polygon(nib(1.55), fill=OUTLINE + (255,))
    d.polygon(nib(1.0), fill=(255, 255, 255, 255) if mono else MINT + (255,))
    px, py = c + o, c + o
    if not mono:
        d.ellipse([px - ar * 1.25, py - ar * 1.25, px + ar * 1.25, py + ar * 1.25], fill=OUTLINE + (255,))
    d.ellipse([px - ar * 0.8, py - ar * 0.8, px + ar * 0.8, py + ar * 0.8], fill=(255, 255, 255, 255) if mono else GOLD_PALE + (255,))
    return img.resize((size, size), Image.LANCZOS)

def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)
    print('skrev', os.path.relpath(path, ROOT))

# Adaptiv ikon: 108 dp per sida
for name, f in DENSITIES.items():
    px = int(108 * f)
    folder = os.path.join(RES, f'mipmap-{name}')
    save(background(px).convert('RGB'), os.path.join(folder, 'ic_launcher_background.png'))
    save(mark(px, 0.62), os.path.join(folder, 'ic_launcher_foreground.png'))
    save(mark(px, 0.62, mono=True), os.path.join(folder, 'ic_launcher_monochrome.png'))

# Loggan i appen (rund, utan bakgrund) och stor ikon för README och webbversionen
logo = Image.new('RGBA', (512, 512), (0, 0, 0, 0))
mask = Image.new('L', (512, 512), 0); ImageDraw.Draw(mask).ellipse([0, 0, 511, 511], fill=255)
logo.paste(background(512), (0, 0), mask)
save(Image.alpha_composite(logo, mark(512, 1.08)), os.path.join(RES, 'drawable-nodpi', 'sign_logo.png'))
icon = Image.alpha_composite(background(1024), mark(1024, 1)).convert('RGB')
save(icon, os.path.join(ROOT, 'docs', 'icon.png'))
WEB = os.path.join(ROOT, 'web', 'icons')
save(icon.resize((512, 512), Image.LANCZOS), os.path.join(WEB, 'icon-512.png'))
save(icon.resize((192, 192), Image.LANCZOS), os.path.join(WEB, 'icon-192.png'))
mk = Image.alpha_composite(background(512), mark(512, 0.78)).convert('RGB')
save(mk, os.path.join(WEB, 'icon-maskable-512.png'))
