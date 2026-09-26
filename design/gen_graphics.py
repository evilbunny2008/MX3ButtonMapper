#!/usr/bin/env python3
import subprocess

DEVICE_GROUP = '''
    <g stroke="#5CE1E6" stroke-width="10" stroke-linecap="round" fill="none" opacity="0.9">
      <path d="M 318 118 A 92 92 0 0 1 372 196"/>
      <path d="M 300 92 A 128 128 0 0 1 384 214" opacity="0.55"/>
    </g>
    <g transform="rotate(28 256 268)">
      <rect x="222" y="118" width="90" height="300" rx="34"
            fill="url(#bodyGrad)" stroke="#000000" stroke-opacity="0.25" stroke-width="2"/>
      <rect x="222" y="118" width="90" height="52" rx="26" fill="url(#capGrad)"/>
      <circle cx="267" cy="144" r="7" fill="#5CE1E6"/>
      <rect x="232" y="180" width="14" height="220" rx="7" fill="#FFFFFF" opacity="0.06"/>
      <circle cx="267" cy="232" r="34" fill="#0B0B0E"/>
      <circle cx="267" cy="232" r="34" fill="none" stroke="#3A3B45" stroke-width="3"/>
      <circle cx="267" cy="232" r="9" fill="#5CE1E6"/>
      <path d="M267 208 v12 M267 244 v12 M243 232 h12 M279 232 h12"
            stroke="#54566A" stroke-width="6" stroke-linecap="round"/>
      <rect x="238" y="296" width="26" height="18" rx="7" fill="#2E3040"/>
      <rect x="270" y="296" width="26" height="18" rx="7" fill="#2E3040"/>
      <rect x="240" y="330" width="54" height="14" rx="7" fill="#22232C"/>
      <rect x="240" y="352" width="54" height="14" rx="7" fill="#22232C"/>
      <rect x="308" y="200" width="8" height="46" rx="4" fill="#0B0B0E"/>
    </g>
'''

DEFS = '''
    <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#23265F"/>
      <stop offset="55%" stop-color="#3A3DA0"/>
      <stop offset="100%" stop-color="#5B4FE0"/>
    </linearGradient>
    <linearGradient id="bodyGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#3A3B45"/>
      <stop offset="55%" stop-color="#1D1E24"/>
      <stop offset="100%" stop-color="#101014"/>
    </linearGradient>
    <linearGradient id="capGrad" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#C9CCD6"/>
      <stop offset="100%" stop-color="#9497A6"/>
    </linearGradient>
    <radialGradient id="glow" cx="50%" cy="50%" r="50%">
      <stop offset="0%" stop-color="#5CE1E6" stop-opacity="0.35"/>
      <stop offset="100%" stop-color="#5CE1E6" stop-opacity="0"/>
    </radialGradient>
'''

def feature_graphic():
    w, h = 1024, 500
    icon_size = 320
    icon_x, icon_y = 50, (h - icon_size) / 2
    scale = icon_size / 512
    text_x = icon_x + icon_size + 60
    return f'''<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg">
  <defs>{DEFS}</defs>
  <rect x="0" y="0" width="{w}" height="{h}" fill="url(#bg)"/>
  <circle cx="{icon_x + icon_size/2}" cy="{h/2}" r="230" fill="url(#glow)"/>
  <g transform="translate({icon_x},{icon_y}) scale({scale})">{DEVICE_GROUP}</g>
  <g font-family="Liberation Sans, Arial, sans-serif" fill="#FFFFFF">
    <text x="{text_x}" y="{h/2 - 55}" font-size="56" font-weight="bold">MX3 Button</text>
    <text x="{text_x}" y="{h/2 + 8}" font-size="56" font-weight="bold">Mapper</text>
    <text x="{text_x}" y="{h/2 + 55}" font-size="27" fill="#BFE9EB">Remap MX3 Air Mouse buttons</text>
    <text x="{text_x}" y="{h/2 + 90}" font-size="27" fill="#BFE9EB">for Google TV</text>
  </g>
</svg>'''

def tv_banner():
    w, h = 1280, 720
    icon_size = 460
    icon_x, icon_y = 90, (h - icon_size) / 2
    scale = icon_size / 512
    text_x = icon_x + icon_size + 70
    return f'''<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg">
  <defs>{DEFS}</defs>
  <rect x="0" y="0" width="{w}" height="{h}" fill="url(#bg)"/>
  <circle cx="{icon_x + icon_size/2}" cy="{h/2}" r="310" fill="url(#glow)"/>
  <g transform="translate({icon_x},{icon_y}) scale({scale})">{DEVICE_GROUP}</g>
  <g font-family="Liberation Sans, Arial, sans-serif" fill="#FFFFFF">
    <text x="{text_x}" y="{h/2 - 30}" font-size="78" font-weight="bold">MX3 Button</text>
    <text x="{text_x}" y="{h/2 + 58}" font-size="78" font-weight="bold">Mapper</text>
    <text x="{text_x}" y="{h/2 + 120}" font-size="34" fill="#BFE9EB">Remap buttons for Google TV</text>
  </g>
</svg>'''

def promo_graphic():
    w, h = 180, 120
    icon_size = 96
    icon_x, icon_y = (h - icon_size) / 2, (h - icon_size) / 2
    scale = icon_size / 512
    return f'''<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg">
  <defs>{DEFS}</defs>
  <rect x="0" y="0" width="{w}" height="{h}" fill="url(#bg)"/>
  <g transform="translate({icon_x},{icon_y}) scale({scale})">{DEVICE_GROUP}</g>
</svg>'''

def feature_highlight_screenshot():
    w, h = 1920, 1080
    icon_size = 620
    icon_x, icon_y = 130, (h - icon_size) / 2
    scale = icon_size / 512
    text_x = icon_x + icon_size + 130
    bullets = [
        "Remaps MX3 Air Mouse buttons system-wide",
        "No root needed -- uses Shizuku's shell-level access",
        "Auto-enables its own Accessibility Service via Shizuku",
        "Per-app remaps: different behaviour in your TV app",
        "TCL and Blaupunkt TV brand support built in",
    ]
    bullet_items = ""
    start_y = h/2 - 110
    for i, b in enumerate(bullets):
        y = start_y + i * 76
        bullet_items += f'''
    <circle cx="{text_x + 16}" cy="{y - 14}" r="16" fill="#5CE1E6"/>
    <path d="M {text_x+8} {y-14} l 6 7 l 12 -14" stroke="#12142B" stroke-width="4" fill="none" stroke-linecap="round" stroke-linejoin="round"/>
    <text x="{text_x + 50}" y="{y}" font-size="34" fill="#FFFFFF">{b}</text>'''
    return f'''<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg">
  <defs>{DEFS}</defs>
  <rect x="0" y="0" width="{w}" height="{h}" fill="url(#bg)"/>
  <circle cx="{icon_x + icon_size/2}" cy="{h/2}" r="440" fill="url(#glow)"/>
  <g transform="translate({icon_x},{icon_y}) scale({scale})">{DEVICE_GROUP}</g>
  <g font-family="Liberation Sans, Arial, sans-serif" fill="#FFFFFF">
    <text x="{text_x}" y="{start_y - 130}" font-size="64" font-weight="bold">MX3 Button Mapper</text>
    {bullet_items}
  </g>
</svg>'''

targets = {
    "feature_graphic": (feature_graphic(), 1024, 500),
    "tv_banner": (tv_banner(), 1280, 720),
    "promo_graphic": (promo_graphic(), 180, 120),
    "feature_highlight_screenshot": (feature_highlight_screenshot(), 1920, 1080),
}

for name, (svg, w, h) in targets.items():
    svg_path = f"/tmp/claude-1000/-home-duane-AndroidStudioProjects-MX3ButtonMapper/b707849e-eb6f-4380-aa6d-d9de52aa9492/scratchpad/{name}.svg"
    png_path = f"/tmp/claude-1000/-home-duane-AndroidStudioProjects-MX3ButtonMapper/b707849e-eb6f-4380-aa6d-d9de52aa9492/scratchpad/{name}.png"
    with open(svg_path, "w") as f:
        f.write(svg)
    subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), svg_path, "-o", png_path], check=True)
    print(f"Rendered {png_path}")
