#!/usr/bin/env python3
"""
Generates realistic Android UI mockup screenshots for BTCall app.
Produces 5 PNG frames + 1 animated GIF demonstrating the full call flow.
"""

from PIL import Image, ImageDraw, ImageFont, ImageFilter
import math, os

# ── Font helpers ──────────────────────────────────────────────────────────────
SANS_BOLD   = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"
SANS_REG    = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
MONO_REG    = "/usr/share/fonts/truetype/liberation/LiberationMono-Regular.ttf"

def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except:
        return ImageFont.load_default()

# ── Palette ───────────────────────────────────────────────────────────────────
BG_DARK      = (15,  17,  23)
BG_CARD      = (26,  29,  39)
BG_INCOMING  = (26,  35, 126)
BG_ACTIVE    = (27,  38,  49)
BLUE         = (92, 155, 255)
GREEN        = (46, 204, 113)
RED          = (231, 76,  60)
WHITE        = (255, 255, 255)
GRAY         = (138, 141, 159)
DIVIDER      = (42,  45,  62)
CARD_BORDER  = (50,  55,  75)
YELLOW       = (241, 196,  15)

# ── Canvas size (portrait phone) ─────────────────────────────────────────────
W, H = 390, 844   # iPhone 14 / Pixel 7 proportions (points)
SCALE = 2         # 2× for crisp rendering → actual pixel size 780×1688

PW, PH = W * SCALE, H * SCALE

def new_canvas(bg=BG_DARK):
    img = Image.new("RGB", (PW, PH), bg)
    d = ImageDraw.Draw(img)
    return img, d

def s(v):
    """Scale a logical point value to pixel value."""
    if isinstance(v, (list, tuple)):
        return tuple(int(x * SCALE) for x in v)
    return int(v * SCALE)

def rounded_rect(d, xy, radius, fill=None, outline=None, width=1):
    x0, y0, x1, y1 = xy
    r = radius
    if fill:
        d.rounded_rectangle([x0, y0, x1, y1], radius=r, fill=fill)
    if outline:
        d.rounded_rectangle([x0, y0, x1, y1], radius=r, outline=outline, width=width)

def draw_status_bar(d, y_top=0):
    """Draw a minimal status bar: time left, battery/signal right."""
    d.rectangle([0, y_top, PW, s(44)], fill=BG_DARK)
    d.text(s((20, 14)), "9:41", font=font(SANS_BOLD, s(14)), fill=WHITE)
    # Signal bars
    for i, h in enumerate([6, 9, 12, 15]):
        bx = PW - s(20 + (3-i)*7)
        d.rectangle([bx, s(22)-s(h), bx+s(4), s(22)],
                    fill=WHITE if i < 3 else GRAY)
    # Battery
    d.rounded_rectangle([PW-s(60), s(13), PW-s(35), s(24)],
                         radius=s(2), outline=WHITE, width=s(1))
    d.rectangle([PW-s(58), s(15), PW-s(58)+s(14), s(22)], fill=WHITE)
    d.rectangle([PW-s(35), s(16), PW-s(33), s(21)], fill=WHITE)

def draw_nav_bar(img, d):
    """Bottom nav bar with home indicator pill."""
    d.rectangle([0, PH-s(34), PW, PH], fill=BG_DARK)
    d.rounded_rectangle([PW//2-s(65), PH-s(20), PW//2+s(65), PH-s(10)],
                         radius=s(4), fill=(80, 80, 80))

def circle(d, cx, cy, r, fill=None, outline=None, width=1):
    d.ellipse([cx-r, cy-r, cx+r, cy+r], fill=fill, outline=outline, width=width)

def bluetooth_icon(d, cx, cy, size=s(12), color=BLUE):
    """Draw a simple Bluetooth symbol."""
    s2 = size // 2
    # Vertical spine
    d.line([cx, cy-size, cx, cy+size], fill=color, width=max(2, size//6))
    # Diamond top
    d.line([cx, cy-size, cx+s2, cy-s2], fill=color, width=max(2, size//6))
    d.line([cx+s2, cy-s2, cx-s2, cy+s2], fill=color, width=max(2, size//6))
    # Diamond bottom
    d.line([cx, cy+size, cx+s2, cy+s2], fill=color, width=max(2, size//6))
    d.line([cx+s2, cy+s2, cx-s2, cy-s2], fill=color, width=max(2, size//6))

def mic_icon(d, cx, cy, size=s(12), color=WHITE, muted=False):
    r = size // 2
    # Capsule body
    d.rounded_rectangle([cx-r, cy-size, cx+r, cy], radius=r, fill=color)
    # Arc stand
    d.arc([cx-size, cy-size, cx+size, cy+size//2], 0, 180, fill=color, width=max(2, size//5))
    # Vertical line
    d.line([cx, cy+size//2, cx, cy+size], fill=color, width=max(2, size//5))
    # Base
    d.line([cx-r, cy+size, cx+r, cy+size], fill=color, width=max(2, size//5))
    if muted:
        # Diagonal slash
        d.line([cx-size, cy-size, cx+size, cy+size], fill=RED, width=max(3, size//4))

def person_icon(d, cx, cy, size=s(22), color=WHITE):
    """Simple person silhouette."""
    r = size // 3
    circle(d, cx, cy - size//2, r, fill=color)
    # body arc
    d.ellipse([cx - size//2, cy, cx + size//2, cy + size], fill=color)

def call_icon(d, cx, cy, size=s(14), color=WHITE):
    """Phone handset icon."""
    # Simplified phone arc
    d.arc([cx-size, cy-size, cx+size, cy+size], 200, 340, fill=color, width=max(3, size//4))
    d.line([cx-size//2, cy+size//2, cx-size, cy+size], fill=color, width=max(3, size//4))

def signal_bar_text(rssi):
    if rssi >= -60: return "████", GREEN
    if rssi >= -70: return "███░", GREEN
    if rssi >= -80: return "██░░", YELLOW
    return "█░░░", RED

# ═══════════════════════════════════════════════════════════════════════════════
# SCREEN 1: Nearby Devices (idle, 3 peers visible)
# ═══════════════════════════════════════════════════════════════════════════════
def screen_nearby_idle():
    img, d = new_canvas(BG_DARK)
    draw_status_bar(d)
    draw_nav_bar(img, d)

    # ── Title ──
    d.text(s((24, 58)), "Nearby Devices", font=font(SANS_BOLD, s(26)), fill=WHITE)
    d.text(s((24, 88)), "Devices running BTCall within range",
           font=font(SANS_REG, s(13)), fill=GRAY)

    # Scanning indicator
    circle(d, PW-s(30), s(70), s(5), fill=GREEN)
    d.text(s((W-50, 64)), "•  Scanning", font=font(SANS_REG, s(11)), fill=GREEN)

    # Divider
    d.line([s(24), s(108), PW-s(24), s(108)], fill=DIVIDER, width=s(1))

    # ── Peer cards ──
    peers = [
        ("Alex's Pixel 8",   -55, "Available"),
        ("Sam's Galaxy S24", -68, "Available"),
        ("Jordan's OnePlus", -79, "Available"),
    ]

    for i, (name, rssi, status) in enumerate(peers):
        cy = s(128 + i * 104)
        cx = s(24)
        cw = PW - s(48)
        ch = s(88)

        # Card shadow
        shadow = Image.new("RGBA", (PW, PH), (0,0,0,0))
        ds = ImageDraw.Draw(shadow)
        rounded_rect(ds, [cx+s(2), cy+s(4), cx+cw+s(2), cy+ch+s(4)],
                     radius=s(12), fill=(0,0,0,60))
        img.paste(shadow, mask=shadow.split()[3])
        d = ImageDraw.Draw(img)

        # Card body
        rounded_rect(d, [cx, cy, cx+cw, cy+ch], radius=s(12),
                     fill=BG_CARD, outline=CARD_BORDER, width=s(1))

        # Avatar circle
        circle(d, cx+s(36), cy+s(44), s(22), fill=(30,50,90))
        bluetooth_icon(d, cx+s(36), cy+s(44), size=s(11), color=BLUE)

        # Name + RSSI
        d.text((cx+s(68), cy+s(18)), name,
               font=font(SANS_BOLD, s(15)), fill=WHITE)
        bars, bar_color = signal_bar_text(rssi)
        d.text((cx+s(68), cy+s(38)), bars + f"  {rssi} dBm",
               font=font(MONO_REG, s(11)), fill=bar_color)
        d.text((cx+s(68), cy+s(56)), status,
               font=font(SANS_REG, s(11)), fill=GREEN)

        # Call button
        bx, bw, bh = cx+cw-s(82), s(70), s(32)
        rounded_rect(d, [bx, cy+s(28), bx+bw, cy+s(28)+bh],
                     radius=s(8), fill=GREEN)
        d.text((bx+s(14), cy+s(33)), "Call",
               font=font(SANS_BOLD, s(13)), fill=WHITE)

    # ── "Your device name" chip at bottom ──
    chip_y = s(450)
    rounded_rect(d, [s(24), chip_y, PW-s(24), chip_y+s(40)],
                 radius=s(20), fill=(30,35,50), outline=DIVIDER, width=s(1))
    bluetooth_icon(d, s(48), chip_y+s(20), size=s(9), color=BLUE)
    d.text((s(64), chip_y+s(12)), "Your device: My Pixel 8  •  Discoverable",
           font=font(SANS_REG, s(11)), fill=GRAY)

    return img

# ═══════════════════════════════════════════════════════════════════════════════
# SCREEN 2: Nearby Devices — "Calling…" state
# ═══════════════════════════════════════════════════════════════════════════════
def screen_calling():
    img, d = new_canvas(BG_DARK)
    draw_status_bar(d)
    draw_nav_bar(img, d)

    d.text(s((24, 58)), "Nearby Devices", font=font(SANS_BOLD, s(26)), fill=WHITE)
    d.text(s((24, 88)), "Devices running BTCall within range",
           font=font(SANS_REG, s(13)), fill=GRAY)
    d.line([s(24), s(108), PW-s(24), s(108)], fill=DIVIDER, width=s(1))

    # Calling banner
    banner_y = s(116)
    rounded_rect(d, [s(24), banner_y, PW-s(24), banner_y+s(44)],
                 radius=s(10), fill=(20,40,80), outline=BLUE, width=s(1))
    circle(d, s(46), banner_y+s(22), s(7), fill=BLUE)
    d.text((s(62), banner_y+s(13)), "Calling Alex's Pixel 8…",
           font=font(SANS_BOLD, s(14)), fill=BLUE)
    d.text((s(62), banner_y+s(28)), "Waiting for them to answer",
           font=font(SANS_REG, s(11)), fill=(130,160,220))

    # Peer cards (same as before, dimmed since we're in call)
    peers = [
        ("Alex's Pixel 8",   -55, "Calling…", BLUE),
        ("Sam's Galaxy S24", -68, "Available", GRAY),
        ("Jordan's OnePlus", -79, "Available", GRAY),
    ]

    for i, (name, rssi, status, st_color) in enumerate(peers):
        cy = s(176 + i * 98)
        cx, cw, ch = s(24), PW-s(48), s(82)

        rounded_rect(d, [cx, cy, cx+cw, cy+ch], radius=s(12),
                     fill=BG_CARD if i==0 else (20,22,30),
                     outline=BLUE if i==0 else CARD_BORDER, width=s(2) if i==0 else s(1))

        circle(d, cx+s(36), cy+s(41), s(22),
               fill=(30,50,90) if i==0 else (25,27,35))
        bluetooth_icon(d, cx+s(36), cy+s(41), size=s(11),
                       color=BLUE if i==0 else (60,65,85))

        name_color = WHITE if i==0 else GRAY
        d.text((cx+s(68), cy+s(15)), name,
               font=font(SANS_BOLD, s(15)), fill=name_color)
        bars, bar_color = signal_bar_text(rssi)
        d.text((cx+s(68), cy+s(35)), bars,
               font=font(MONO_REG, s(11)), fill=bar_color if i==0 else (60,65,85))
        d.text((cx+s(68), cy+s(53)), status,
               font=font(SANS_BOLD if i==0 else SANS_REG, s(11)), fill=st_color)

        btn_fill = (50,80,130) if i==0 else (35,38,50)
        btn_text_col = (140,180,255) if i==0 else (60,65,85)
        bx, bw, bh = cx+cw-s(82), s(70), s(30)
        rounded_rect(d, [bx, cy+s(26), bx+bw, cy+s(26)+bh],
                     radius=s(8), fill=btn_fill)
        d.text((bx+s(14), cy+s(31)), "Calling" if i==0 else "Call",
               font=font(SANS_BOLD, s(12)), fill=btn_text_col)

    return img

# ═══════════════════════════════════════════════════════════════════════════════
# SCREEN 3: Incoming Call (on Alex's device)
# ═══════════════════════════════════════════════════════════════════════════════
def screen_incoming():
    img, d = new_canvas(BG_INCOMING)
    draw_status_bar(d)

    # Gradient overlay (simulate dark-to-blue gradient)
    overlay = Image.new("RGBA", (PW, PH), (0,0,0,0))
    od = ImageDraw.Draw(overlay)
    for row in range(PH):
        alpha = int(120 * (1 - row/PH))
        od.line([0, row, PW, row], fill=(0,0,0,alpha))
    img.paste(overlay, mask=overlay.split()[3])
    d = ImageDraw.Draw(img)

    draw_nav_bar(img, d)
    draw_status_bar(d)
    d = ImageDraw.Draw(img)

    # Ripple rings
    cx, cy = PW//2, s(260)
    for r_factor, alpha in [(1.6, 25), (1.3, 45), (1.0, 70)]:
        r = int(s(68) * r_factor)
        circle(d, cx, cy, r, fill=None, outline=(100,140,255,alpha), width=s(2))
        # Re-draw (PIL outline doesn't support alpha directly — approximate)
        for off in range(3):
            circle(d, cx, cy, r-off, fill=None,
                   outline=(70+off*10, 120+off*10, 220), width=s(1))

    # Avatar background circle
    circle(d, cx, cy, s(68), fill=(40,60,140))
    person_icon(d, cx, cy, size=s(36), color=(160,190,255))

    # Bluetooth badge on avatar
    circle(d, cx+s(45), cy+s(45), s(14), fill=(26,35,126))
    circle(d, cx+s(45), cy+s(45), s(13), fill=BLUE)
    bluetooth_icon(d, cx+s(45), cy+s(45), size=s(7), color=WHITE)

    # Labels
    d.text((s(0), s(310)), "Incoming Call",
           font=font(SANS_REG, s(13)), fill=(180,200,255), anchor="mm")
    # Centered text (approximate)
    tw = s(130)
    d.text((PW//2 - tw//2 + s(5), s(303)), "Incoming Call",
           font=font(SANS_REG, s(13)), fill=(180,200,255))

    d.text((PW//2 - s(90), s(330)), "My Pixel 8",
           font=font(SANS_BOLD, s(30)), fill=WHITE)

    d.text((PW//2 - s(85), s(374)), "Bluetooth Voice Call",
           font=font(SANS_REG, s(14)), fill=(180,200,255))

    # BT indicator pill
    pill_w, pill_h = s(160), s(30)
    pill_x = PW//2 - pill_w//2
    rounded_rect(d, [pill_x, s(406), pill_x+pill_w, s(406)+pill_h],
                 radius=s(15), fill=(30,50,110))
    bluetooth_icon(d, pill_x+s(20), s(421), size=s(9), color=BLUE)
    d.text((pill_x+s(34), s(412)), "Bluetooth • Nearby",
           font=font(SANS_REG, s(11)), fill=(140,170,255))

    # Reject button
    rej_cx = PW//2 - s(80)
    rej_cy = s(700)
    circle(d, rej_cx, rej_cy, s(36), fill=RED)
    # X / end call icon
    for angle, length in [((-12,-12),(12,12)), ((-12,12),(12,-12))]:
        d.line([rej_cx+angle[0]*SCALE//12, rej_cy+angle[1]*SCALE//12,
                rej_cx+length[0]*SCALE//12, rej_cy+length[1]*SCALE//12],
               fill=WHITE, width=s(3))
    d.line([rej_cx-s(10), rej_cy, rej_cx+s(10), rej_cy], fill=WHITE, width=s(3))
    d.line([rej_cx-s(10), rej_cy-s(3), rej_cx-s(10), rej_cy+s(6)],
           fill=WHITE, width=s(3))
    d.line([rej_cx+s(10), rej_cy-s(3), rej_cx+s(10), rej_cy+s(6)],
           fill=WHITE, width=s(3))
    d.text((rej_cx-s(22), rej_cy+s(44)), "Decline",
           font=font(SANS_REG, s(13)), fill=(220,220,220))

    # Accept button
    acc_cx = PW//2 + s(80)
    acc_cy = s(700)
    circle(d, acc_cx, acc_cy, s(36), fill=GREEN)
    # Phone icon (simplified)
    call_icon(d, acc_cx, acc_cy, size=s(18), color=WHITE)
    d.text((acc_cx-s(22), acc_cy+s(44)), "Accept",
           font=font(SANS_REG, s(13)), fill=(220,220,220))

    return img

# ═══════════════════════════════════════════════════════════════════════════════
# SCREEN 4: Active Call
# ═══════════════════════════════════════════════════════════════════════════════
def screen_active_call(duration="02:47", muted=False):
    img, d = new_canvas(BG_ACTIVE)
    draw_status_bar(d)
    draw_nav_bar(img, d)

    # Subtle gradient top
    grad = Image.new("RGBA", (PW, s(200)), (0,0,0,0))
    gd = ImageDraw.Draw(grad)
    for row in range(s(200)):
        alpha = int(60 * (1 - row/s(200)))
        gd.line([0, row, PW, row], fill=(30,80,120,alpha))
    img.paste(grad, (0, 0), mask=grad.split()[3])
    d = ImageDraw.Draw(img)

    # Header
    d.text((s(24), s(58)), "Active Call",
           font=font(SANS_REG, s(13)), fill=(140,180,200))

    # Avatar
    cx, cy = PW//2, s(200)
    circle(d, cx, cy, s(58), fill=(35,55,80))
    person_icon(d, cx, cy, size=s(30), color=(140,180,230))

    # Active indicator ring
    circle(d, cx, cy, s(62), fill=None, outline=GREEN, width=s(3))
    # Pulse dot
    circle(d, cx+s(45), cy-s(45), s(8), fill=GREEN)

    # Name
    d.text((PW//2-s(78), s(278)), "Alex's Pixel 8",
           font=font(SANS_BOLD, s(26)), fill=WHITE)

    # Duration
    d.text((PW//2-s(32), s(320)), duration,
           font=font(MONO_REG, s(22)), fill=(160,200,160))

    # BT quality bar
    bar_y = s(362)
    d.text((s(24), bar_y), "Bluetooth Classic  •  RFCOMM",
           font=font(SANS_REG, s(11)), fill=GRAY)
    # Quality dots
    for i in range(5):
        dot_x = PW - s(30 + i*14)
        color = GREEN if i < 4 else GRAY
        circle(d, dot_x, bar_y+s(6), s(4), fill=color)

    # Divider
    d.line([s(24), s(390), PW-s(24), s(390)], fill=DIVIDER, width=s(1))

    # Audio stats
    stats_y = s(400)
    stats = [
        ("Latency",   "~85ms",  s(60)),
        ("Codec",     "PCM",    s(160)),
        ("Jitter",    "12ms",   s(260)),
    ]
    for label, value, x in stats:
        d.text((x, stats_y), label, font=font(SANS_REG, s(11)), fill=GRAY)
        d.text((x, stats_y+s(18)), value, font=font(SANS_BOLD, s(14)), fill=BLUE)

    # Control buttons
    ctrl_y = s(620)
    controls = [
        ("Mute" if not muted else "Unmute", muted, s(80),  BG_CARD, WHITE),
        ("End",                             None,  s(195), RED,     WHITE),
    ]

    for label, is_muted, bx, bg_col, txt_col in controls:
        by = ctrl_y
        bw = s(80) if label != "End" else s(80)
        bh = s(80)
        circle(d, bx, by, s(38),
               fill=RED if label=="End" else (50,60,80))
        if label == "End":
            # End call icon
            d.line([bx-s(18), by-s(2), bx+s(18), by-s(2)], fill=WHITE, width=s(4))
            d.line([bx-s(18), by-s(8), bx-s(18), by+s(6)], fill=WHITE, width=s(4))
            d.line([bx+s(18), by-s(8), bx+s(18), by+s(6)], fill=WHITE, width=s(4))
        else:
            mic_icon(d, bx, by, size=s(16), color=WHITE, muted=is_muted)
        d.text((bx-s(16 if len(label)<5 else 22), by+s(48)),
               label, font=font(SANS_REG, s(13)), fill=(180,180,180))

    # Waveform animation (decorative)
    wave_y = s(520)
    for i in range(30):
        x = s(20 + i * 12)
        h_val = s(int(4 + 12 * abs(math.sin(i * 0.4 + 1.2))))
        col = GREEN if i % 3 != 0 else BLUE
        d.rounded_rectangle([x-s(3), wave_y-h_val, x+s(3), wave_y+h_val],
                             radius=s(3), fill=col)

    return img

# ═══════════════════════════════════════════════════════════════════════════════
# SCREEN 5: Call Ended
# ═══════════════════════════════════════════════════════════════════════════════
def screen_call_ended():
    img, d = new_canvas(BG_DARK)
    draw_status_bar(d)
    draw_nav_bar(img, d)

    d.text(s((24, 58)), "Nearby Devices", font=font(SANS_BOLD, s(26)), fill=WHITE)
    d.text(s((24, 88)), "Devices running BTCall within range",
           font=font(SANS_REG, s(13)), fill=GRAY)
    d.line([s(24), s(108), PW-s(24), s(108)], fill=DIVIDER, width=s(1))

    # Ended banner
    banner_y = s(116)
    rounded_rect(d, [s(24), banner_y, PW-s(24), banner_y+s(44)],
                 radius=s(10), fill=(50,30,30), outline=RED, width=s(1))
    circle(d, s(46), banner_y+s(22), s(7), fill=RED)
    d.text((s(62), banner_y+s(13)), "Call ended  •  02:47",
           font=font(SANS_BOLD, s(14)), fill=RED)
    d.text((s(62), banner_y+s(28)), "Alex's Pixel 8  •  Local hangup",
           font=font(SANS_REG, s(11)), fill=(200,130,130))

    # Peer cards (back to normal)
    peers = [
        ("Alex's Pixel 8",   -55, "Available"),
        ("Sam's Galaxy S24", -68, "Available"),
        ("Jordan's OnePlus", -79, "Available"),
    ]
    for i, (name, rssi, status) in enumerate(peers):
        cy = s(176 + i * 98)
        cx, cw, ch = s(24), PW-s(48), s(82)
        rounded_rect(d, [cx, cy, cx+cw, cy+ch], radius=s(12),
                     fill=BG_CARD, outline=CARD_BORDER, width=s(1))
        circle(d, cx+s(36), cy+s(41), s(22), fill=(30,50,90))
        bluetooth_icon(d, cx+s(36), cy+s(41), size=s(11), color=BLUE)
        d.text((cx+s(68), cy+s(15)), name, font=font(SANS_BOLD, s(15)), fill=WHITE)
        bars, bar_color = signal_bar_text(rssi)
        d.text((cx+s(68), cy+s(35)), bars+f"  {rssi} dBm",
               font=font(MONO_REG, s(11)), fill=bar_color)
        d.text((cx+s(68), cy+s(53)), status, font=font(SANS_REG, s(11)), fill=GREEN)
        bx, bw, bh = cx+cw-s(82), s(70), s(30)
        rounded_rect(d, [bx, cy+s(26), bx+bw, cy+s(26)+bh],
                     radius=s(8), fill=GREEN)
        d.text((bx+s(14), cy+s(31)), "Call", font=font(SANS_BOLD, s(12)), fill=WHITE)

    # Call history chip
    hist_y = s(476)
    rounded_rect(d, [s(24), hist_y, PW-s(24), hist_y+s(52)],
                 radius=s(10), fill=BG_CARD, outline=CARD_BORDER, width=s(1))
    d.text((s(40), hist_y+s(10)), "Call History",
           font=font(SANS_BOLD, s(12)), fill=GRAY)
    d.text((s(40), hist_y+s(26)), "Alex's Pixel 8  •  02:47  •  Local hangup",
           font=font(SANS_REG, s(11)), fill=GRAY)

    return img

# ═══════════════════════════════════════════════════════════════════════════════
# DEVICE FRAME wrapper (phone bezel)
# ═══════════════════════════════════════════════════════════════════════════════
def wrap_in_frame(screen_img):
    """Add a minimal phone bezel around the screen."""
    BEZ = int(20 * SCALE)       # bezel thickness
    NOTCH_W = int(90 * SCALE)
    NOTCH_H = int(28 * SCALE)
    CORNER = int(45 * SCALE)

    frame_w = PW + BEZ*2
    frame_h = PH + BEZ*2
    frame = Image.new("RGB", (frame_w, frame_h), (22, 24, 32))
    fd = ImageDraw.Draw(frame)

    # Outer bezel
    fd.rounded_rectangle([0, 0, frame_w-1, frame_h-1], radius=CORNER,
                          fill=(30, 33, 45))

    # Inner bezel (screen area)
    fd.rounded_rectangle([BEZ-4, BEZ-4, BEZ+PW+4, BEZ+PH+4],
                          radius=CORNER-BEZ+8, fill=(0, 0, 0))

    # Paste screen
    frame.paste(screen_img, (BEZ, BEZ))

    # Notch
    notch_x = (frame_w - NOTCH_W) // 2
    fd.rounded_rectangle([notch_x, 0, notch_x+NOTCH_W, NOTCH_H],
                          radius=NOTCH_H//2, fill=(30, 33, 45))

    # Side buttons
    btn_h = int(50*SCALE)
    btn_w = int(4*SCALE)
    fd.rounded_rectangle([-btn_w//2, int(140*SCALE),
                           btn_w//2, int(140*SCALE)+btn_h],
                          radius=btn_w, fill=(45,48,60))
    fd.rounded_rectangle([frame_w-btn_w//2, int(120*SCALE),
                           frame_w+btn_w//2, int(120*SCALE)+btn_h+20],
                          radius=btn_w, fill=(45,48,60))

    return frame

# ═══════════════════════════════════════════════════════════════════════════════
# COMPOSITE: Two phones side-by-side
# ═══════════════════════════════════════════════════════════════════════════════
def make_composite(left_screen, right_screen, label):
    framed_l = wrap_in_frame(left_screen)
    framed_r = wrap_in_frame(right_screen)

    FW, FH = framed_l.size
    PAD = int(40 * SCALE)
    LBL_H = int(60 * SCALE)
    TOTAL_W = FW * 2 + PAD * 3
    TOTAL_H = FH + PAD * 2 + LBL_H

    out = Image.new("RGB", (TOTAL_W, TOTAL_H), (10, 12, 18))
    od = ImageDraw.Draw(out)

    # Background subtle grid
    for y in range(0, TOTAL_H, int(40*SCALE)):
        od.line([0, y, TOTAL_W, y], fill=(18,20,28), width=1)
    for x in range(0, TOTAL_W, int(40*SCALE)):
        od.line([x, 0, x, TOTAL_H], fill=(18,20,28), width=1)

    # Phones
    out.paste(framed_l, (PAD, PAD + LBL_H))
    out.paste(framed_r, (PAD*2 + FW, PAD + LBL_H))

    # Label
    od.text((PAD, PAD//2), label, font=font(SANS_BOLD, int(20*SCALE)), fill=WHITE)

    # Device labels
    od.text((PAD + FW//3, PAD + LBL_H - int(20*SCALE)), "Device A (Caller)",
            font=font(SANS_REG, int(13*SCALE)), fill=GRAY)
    od.text((PAD*2 + FW + FW//3, PAD + LBL_H - int(20*SCALE)), "Device B (Callee)",
            font=font(SANS_REG, int(13*SCALE)), fill=GRAY)

    # Arrow between phones
    ax1 = PAD + FW + PAD//2
    ax2 = PAD*2 + FW - PAD//2
    ay  = PAD + LBL_H + FH//2
    od.line([ax1, ay, ax2, ay], fill=BLUE, width=int(4*SCALE))
    # Arrowhead
    od.polygon([(ax2, ay), (ax2-int(15*SCALE), ay-int(8*SCALE)),
                (ax2-int(15*SCALE), ay+int(8*SCALE))], fill=BLUE)

    return out

# ═══════════════════════════════════════════════════════════════════════════════
# BUILD ALL FRAMES
# ═══════════════════════════════════════════════════════════════════════════════
os.makedirs("/home/user/Bluetooth-call-app/screenshots", exist_ok=True)
OUT = "/home/user/Bluetooth-call-app/screenshots"

print("Generating screenshots…")

s1_a = screen_nearby_idle()
s1_b = screen_nearby_idle()
frame1 = make_composite(s1_a, s1_b,
    "Step 1 — Both devices discover each other via BLE")
frame1.save(f"{OUT}/01_discovery.png")
print("  ✓ 01_discovery.png")

s2_a = screen_calling()
s2_b = screen_incoming()
frame2 = make_composite(s2_a, s2_b,
    "Step 2 — Device A calls, Device B receives incoming call")
frame2.save(f"{OUT}/02_call_request.png")
print("  ✓ 02_call_request.png")

s3_a = screen_active_call("00:03")
s3_b = screen_active_call("00:03")
frame3 = make_composite(s3_a, s3_b,
    "Step 3 — Call accepted, RFCOMM audio stream established")
frame3.save(f"{OUT}/03_active_call.png")
print("  ✓ 03_active_call.png")

s4_a = screen_active_call("02:47", muted=True)
s4_b = screen_active_call("02:47")
frame4 = make_composite(s4_a, s4_b,
    "Step 4 — Mute toggled on Device A (mic off)")
frame4.save(f"{OUT}/04_muted.png")
print("  ✓ 04_muted.png")

s5_a = screen_call_ended()
s5_b = screen_nearby_idle()
frame5 = make_composite(s5_a, s5_b,
    "Step 5 — Call ended, history saved, back to idle")
frame5.save(f"{OUT}/05_call_ended.png")
print("  ✓ 05_call_ended.png")

# ── ANIMATED GIF ──────────────────────────────────────────────────────────────
print("\nGenerating animated GIF…")

# Resize frames for the GIF (smaller to keep file size reasonable)
GIF_W = 900
frames_raw = [frame1, frame2, frame3, frame4, frame5]
gif_frames = []

for raw in frames_raw:
    ratio = GIF_W / raw.width
    new_h = int(raw.height * ratio)
    resized = raw.resize((GIF_W, new_h), Image.LANCZOS)
    gif_frames.append(resized)

# Duplicate frames to create pause effect (hold each screen for 2.5s at 10fps)
gif_sequence = []
durations = []
for i, frame in enumerate(gif_frames):
    hold = 35 if i < len(gif_frames)-1 else 40  # 100ms per frame × count
    for _ in range(hold // 5):
        gif_sequence.append(frame.convert("P", palette=Image.ADAPTIVE, colors=256))
        durations.append(100)  # 100ms per frame

gif_sequence[0].save(
    f"{OUT}/btcall_demo.gif",
    save_all=True,
    append_images=gif_sequence[1:],
    duration=durations,
    loop=0,
    optimize=False
)
print("  ✓ btcall_demo.gif")

print(f"\nAll files saved to {OUT}/")
import os
for f in sorted(os.listdir(OUT)):
    sz = os.path.getsize(f"{OUT}/{f}") // 1024
    print(f"  {f}  ({sz} KB)")
