#!/usr/bin/env python3
"""Generate Google Play Store assets for GAMA Mobile."""

import os
from PIL import Image, ImageDraw, ImageFont

OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "play-store-assets")
os.makedirs(OUT_DIR, exist_ok=True)

# Brand colors
BG_DARK = (15, 23, 42)       # #0f172a
BG_MID = (30, 41, 59)        # #1e293b
ACCENT = (59, 130, 246)      # #3b82f6
ACCENT_LIGHT = (96, 165, 250) # #60a5fa
WHITE = (255, 255, 255)
GRAY = (148, 163, 184)       # #94a3b8
TEAL = (20, 184, 166)        # #14b8a6
GREEN = (34, 197, 94)        # #22c55e

def get_font(size):
    """Try to get a good font, fall back to default."""
    paths = [
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/SFNS.ttf",
        "/System/Library/Fonts/SFNSDisplay.ttf",
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    ]
    for p in paths:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()

def draw_rounded_rect(draw, xy, radius, fill):
    x0, y0, x1, y1 = xy
    draw.rounded_rectangle(xy, radius=radius, fill=fill)

# ── 1. App Icon (512x512) ─────────────────────────────────────────────
def gen_icon():
    size = 512
    img = Image.new("RGB", (size, size), BG_DARK)
    draw = ImageDraw.Draw(img)

    # Background circle
    cx, cy = size // 2, size // 2
    r = 200
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACCENT)

    # "G" letter
    font_big = get_font(220)
    bbox = draw.textbbox((0, 0), "G", font=font_big)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    draw.text((cx - tw // 2, cy - th // 2 - 15), "G", fill=WHITE, font=font_big)

    # Small "AMA" below
    font_small = get_font(60)
    bbox2 = draw.textbbox((0, 0), "AMA", font=font_small)
    tw2 = bbox2[2] - bbox2[0]
    draw.text((cx - tw2 // 2, cy + 100), "AMA", fill=WHITE, font=font_small)

    img.save(os.path.join(OUT_DIR, "icon-512.png"))
    print("  icon-512.png")

# ── 2. Feature Graphic (1024x500) ─────────────────────────────────────
def gen_feature_graphic():
    w, h = 1024, 500
    img = Image.new("RGB", (w, h), BG_DARK)
    draw = ImageDraw.Draw(img)

    # Decorative gradient bars
    for i in range(0, w, 4):
        alpha = int(40 + 30 * (i / w))
        c = (ACCENT[0], ACCENT[1], ACCENT[2])
        draw.line([(i, 0), (i, h)], fill=(c[0] // 3, c[1] // 3, c[2] // 3), width=2)

    # Accent bar top
    draw.rectangle([0, 0, w, 6], fill=ACCENT)

    # App icon circle on left
    cx, cy = 200, h // 2
    r = 90
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACCENT)
    font_icon = get_font(100)
    bbox = draw.textbbox((0, 0), "G", font=font_icon)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    draw.text((cx - tw // 2, cy - th // 2 - 8), "G", fill=WHITE, font=font_icon)

    # Title
    font_title = get_font(64)
    draw.text((350, 120), "GAMA", fill=WHITE, font=font_title)
    font_sub = get_font(32)
    draw.text((350, 200), "Mobile", fill=ACCENT_LIGHT, font=font_sub)

    # Tagline
    font_tag = get_font(22)
    draw.text((350, 260), "Agent-Based Modeling on the Go", fill=GRAY, font=font_tag)

    # Bottom accent bar
    draw.rectangle([0, h - 4, w, h], fill=TEAL)

    img.save(os.path.join(OUT_DIR, "feature-graphic-1024x500.png"))
    print("  feature-graphic-1024x500.png")

# ── 3. Phone Screenshots (1080x1920) ──────────────────────────────────
def draw_screenshot_frame(draw, w, h, title_text, subtitle_text, features):
    """Draw a screenshot template with dark background."""
    # Status bar
    draw.rectangle([0, 0, w, 60], fill=(10, 17, 30))
    font_status = get_font(20)
    draw.text((30, 18), "9:41", fill=GRAY, font=font_status)
    draw.text((w - 100, 18), "100%", fill=GRAY, font=font_status)

    # Title area
    font_title = get_font(48)
    draw.text((60, 120), title_text, fill=WHITE, font=font_title)

    # Accent underline
    draw.rectangle([60, 190, 300, 194], fill=ACCENT)

    # Subtitle
    font_sub = get_font(26)
    draw.text((60, 220), subtitle_text, fill=GRAY, font=font_sub)

    # Feature items
    y = 340
    font_feat = get_font(28)
    font_feat_desc = get_font(22)
    for feat_title, feat_desc in features:
        # Feature card background
        draw.rounded_rectangle([50, y, w - 50, y + 140], radius=16, fill=BG_MID)
        # Accent dot
        draw.ellipse([80, y + 20, 100, y + 40], fill=ACCENT)
        draw.text((120, y + 15), feat_title, fill=WHITE, font=font_feat)
        draw.text((80, y + 60), feat_desc, fill=GRAY, font=font_feat_desc)
        y += 170

    # Bottom navigation bar
    draw.rectangle([0, h - 100, w, h], fill=(10, 17, 30))
    font_nav = get_font(18)
    nav_items = ["Dashboard", "VNC", "Logs", "Settings"]
    nav_w = w // len(nav_items)
    for i, item in enumerate(nav_items):
        nx = i * nav_w + nav_w // 2
        bbox = draw.textbbox((0, 0), item, font=font_nav)
        tw = bbox[2] - bbox[0]
        color = ACCENT if i == 0 else GRAY
        draw.text((nx - tw // 2, h - 60), item, fill=color, font=font_nav)
        if i == 0:
            draw.rectangle([nx - 20, h - 100, nx + 20, h - 96], fill=ACCENT)

def gen_screenshots():
    w, h = 1080, 1920
    screenshots = [
        {
            "name": "screenshot-1-dashboard.png",
            "title": "Dashboard",
            "subtitle": "Monitor your simulation in real-time",
            "features": [
                ("Live VNC View", "See your GAMA simulation running in real-time"),
                ("Progress Tracking", "Track simulation progress with live updates"),
                ("Connection Status", "Always know the state of your PRoot container"),
            ],
        },
        {
            "name": "screenshot-2-vnc.png",
            "title": "Full VNC Access",
            "subtitle": "Interact with GAMA directly on your device",
            "features": [
                ("Touch Controls", "Tap and drag to interact with your model"),
                ("Keyboard Support", "Full keyboard input for complex operations"),
                ("Landscape Mode", "Optimized for landscape simulation view"),
            ],
        },
        {
            "name": "screenshot-3-logs.png",
            "title": "Live Logs",
            "subtitle": "Real-time output from your simulation",
            "features": [
                ("Color-coded Output", "Errors, warnings, and info clearly distinguished"),
                ("Scroll & Search", "Navigate through extensive simulation logs"),
                ("Auto-scroll", "Follow output as it streams in real-time"),
            ],
        },
        {
            "name": "screenshot-4-start.png",
            "title": "One-Tap Start",
            "subtitle": "Launch GAMA simulations instantly",
            "features": [
                ("PRoot Container", "Full Debian environment on your Android device"),
                ("No Server Needed", "Everything runs locally on your phone"),
                ("Java 25 Runtime", "Complete GAMA headless environment included"),
            ],
        },
    ]

    for ss in screenshots:
        img = Image.new("RGB", (w, h), BG_DARK)
        draw = ImageDraw.Draw(img)
        draw_screenshot_frame(draw, w, h, ss["title"], ss["subtitle"], ss["features"])
        img.save(os.path.join(OUT_DIR, ss["name"]))
        print(f"  {ss['name']}")

# ── Main ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    print("Generating Play Store assets...")
    gen_icon()
    gen_feature_graphic()
    gen_screenshots()
    print(f"\nAll assets saved to: {OUT_DIR}")
    print("\nRequired assets summary:")
    print("  icon-512.png                    (512x512, 32-bit PNG with alpha)")
    print("  feature-graphic-1024x500.png    (1024x500, PNG)")
    print("  screenshot-1..4.png             (1080x1920, PNG)")
