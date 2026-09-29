#!/usr/bin/env python3
"""
Generates app/src/main/res/values/themes.xml from Krita's colour schemes.

Usage: tools/generate_krita_themes.py /path/to/krita/share/color-schemes

Colours follow the Qt palette roles Krita's own UI uses them for:

  droidrefBoard, droidrefWindow  [Colors:Window] BackgroundNormal: the main window
                                 background, as around Krita's canvas and in its dockers
  droidrefWindowForeground       [Colors:Window] ForegroundNormal
  droidrefWindowForegroundInactive [Colors:Window] ForegroundInactive

[Colors:View] is deliberately unused: Qt only uses it inside item views and inputs
(e.g. Krita's layers list), which DroidRef doesn't have.
  droidrefButton                 [Colors:Button] BackgroundNormal
  droidrefButtonForeground       [Colors:Button] ForegroundNormal
  droidrefButtonForegroundDisabled  Button ForegroundNormal with [ColorEffects:Disabled]
                                 applied as KDE's KColorScheme does
  droidrefPrimary                [Colors:Selection] BackgroundNormal
  droidrefOnPrimary              [Colors:Selection] ForegroundNormal
"""
import os
import sys

# (scheme file, Android style name) in menu order; Krita dark is Krita's default.
SCHEMES = [
    ("KritaDark.colors", "KritaDark"),
    ("KritaDarker.colors", "KritaDarker"),
    ("KritaBright.colors", "KritaBright"),
    ("KritaNeutral.colors", "KritaNeutral"),
    ("KritaBlender.colors", "KritaBlender"),
    ("KritaDarkOrange.colors", "KritaDarkOrange"),
]

# --- KDE colour maths (kcolorutils.cpp / khcy.cpp), for grey colours ---------------
GAMMA = 2.2


def luma(g):
    return max(0.0, min(1.0, g)) ** GAMMA  # grey: the HCY luma weights sum to 1


def from_luma(y):
    return max(0.0, min(1.0, y)) ** (1 / GAMMA)


def mix(a, b, t):
    return a if t <= 0 else b if t >= 1 else a + (b - a) * t


def contrast(y1, y2):
    return (y1 + .05) / (y2 + .05) if y1 > y2 else (y2 + .05) / (y1 + .05)


def tint(base, color, amount):
    if amount <= 0:
        return base
    if amount >= 1:
        return color
    by = luma(base)
    target = 1 + (contrast(by, luma(color)) + 1) * amount ** 3
    lo, hi, result = 0.0, 1.0, base
    for _ in range(12):
        a = (lo + hi) / 2
        result = from_luma(mix(by, luma(mix(base, color, a ** .3)), a))
        if contrast(by, luma(result)) > target:
            hi = a
        else:
            lo = a
    return result


def disabled(fg, bg, fx):
    """KColorScheme StateEffects::brush(foreground, background) for grey colours."""
    contrast_effect, contrast_amount = int(fx["ContrastEffect"]), float(fx["ContrastAmount"])
    if contrast_effect == 1:  # ContrastFade
        fg = mix(fg, bg, contrast_amount)
    elif contrast_effect == 2:  # ContrastTint
        fg = tint(fg, bg, contrast_amount)
    intensity_effect, intensity_amount = int(fx["IntensityEffect"]), float(fx["IntensityAmount"])
    if intensity_effect == 2:  # IntensityDarken
        fg = from_luma(luma(fg) * (1 - intensity_amount))
    elif intensity_effect == 3:  # IntensityLighten
        fg = from_luma(1 - (1 - luma(fg)) * (1 - intensity_amount))
    elif intensity_effect == 1:  # IntensityShade
        fg = from_luma(luma(fg) + intensity_amount)
    color_effect, color_amount = int(fx["ColorEffect"]), float(fx["ColorAmount"])
    color = grey(fx["Color"])
    if color_effect == 2:  # ColorFade
        fg = mix(fg, color, color_amount)
    elif color_effect == 3:  # ColorTint
        fg = tint(fg, color, color_amount)
    # ColorDesaturate (1) leaves greys unchanged.
    return fg


# --- Scheme parsing ----------------------------------------------------------------

def parse(path):
    sections, current = {}, None
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if line.startswith("["):
            current = sections.setdefault(line[1:-1], {})
        elif "=" in line and current is not None:
            key, value = line.split("=", 1)
            current[key] = value
    return sections


def rgb(value):
    return [int(c) for c in value.split(",")[:3]]


def grey(value):
    r, g, b = rgb(value)
    assert r == g == b, f"expected a grey, got {value}"
    return r / 255


def hex_color(value):
    return "#%02X%02X%02X" % tuple(rgb(value))


def hex_grey(g):
    v = round(g * 255)
    return "#%02X%02X%02X" % (v, v, v)


def luminance(value):
    r, g, b = (c / 255 for c in rgb(value))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def theme(scheme):
    window, button = scheme["Colors:Window"], scheme["Colors:Button"]
    selection = scheme["Colors:Selection"]
    button_disabled = disabled(
        grey(button["ForegroundNormal"]), grey(button["BackgroundNormal"]), scheme["ColorEffects:Disabled"]
    )
    return {
        "droidrefBoard": hex_color(window["BackgroundNormal"]),
        "droidrefWindow": hex_color(window["BackgroundNormal"]),
        "droidrefWindowForeground": hex_color(window["ForegroundNormal"]),
        "droidrefWindowForegroundInactive": hex_color(window["ForegroundInactive"]),
        "droidrefButton": hex_color(button["BackgroundNormal"]),
        "droidrefButtonForeground": hex_color(button["ForegroundNormal"]),
        "droidrefButtonForegroundDisabled": hex_grey(button_disabled),
        "droidrefPrimary": hex_color(selection["BackgroundNormal"]),
        "droidrefOnPrimary": hex_color(selection["ForegroundNormal"]),
    }, luminance(window["BackgroundNormal"]) > 0.5


BASE = """    <style name="Theme.DroidRef.{kind}" parent="Theme.AppCompat.{parent}NoActionBar">
        <item name="droidrefLightTheme">{light}</item>
        <item name="colorPrimary">?attr/droidrefWindow</item>
        <item name="colorPrimaryDark">?attr/droidrefWindow</item>
        <item name="colorAccent">?attr/droidrefPrimary</item>
        <item name="colorControlActivated">?attr/droidrefPrimary</item>
        <item name="colorControlNormal">?attr/droidrefWindowForegroundInactive</item>
        <item name="android:colorBackground">?attr/droidrefWindow</item>
        <item name="android:colorBackgroundFloating">?attr/droidrefWindow</item>
        <item name="android:windowBackground">?attr/droidrefBoard</item>
        <item name="android:textColorPrimary">?attr/droidrefWindowForeground</item>
        <item name="android:textColorSecondary">?attr/droidrefWindowForegroundInactive</item>
    </style>
"""


def main():
    directory = sys.argv[1]
    out = [
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- Generated by tools/generate_krita_themes.py from Krita's colour schemes; don't edit. -->",
        "<resources>",
        BASE.format(kind="Dark", parent="", light="false"),
        BASE.format(kind="Light", parent="Light.", light="true"),
    ]
    for filename, name in SCHEMES:
        scheme = parse(os.path.join(directory, filename))
        colors, light = theme(scheme)
        out.append(f'    <!-- {scheme["General"]["Name"] if "General" in scheme else filename} ({filename}) -->')
        out.append(f'    <style name="Theme.DroidRef.{name}" parent="Theme.DroidRef.{"Light" if light else "Dark"}">')
        for attr, value in colors.items():
            out.append(f'        <item name="{attr}">{value}</item>')
        out.append("    </style>")
        out.append("")
    out.append("</resources>")
    target = os.path.join(os.path.dirname(__file__), "..", "app/src/main/res/values/themes.xml")
    with open(target, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
