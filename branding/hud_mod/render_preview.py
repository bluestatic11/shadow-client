"""Render PIL-based previews of the Shadow Client menu and config panel
based on the rendering code in ShadowHud.java.

Output:
  C:/Users/ediso/Downloads/shadow_menu_preview.png   (1200x720)
  C:/Users/ediso/Downloads/shadow_config_panel.png   (1200x720, with backdrop)
"""

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# Color palette (matches ShadowHud.java)
# ---------------------------------------------------------------------------
PANEL_BODY      = (26, 31, 42, 240)        # 0xF01A1F2A
PANEL_BODY_OPQ  = (26, 31, 42, 255)
TOP_SHEEN       = (255, 255, 255, 24)      # 0x18FFFFFF
HEADER_WASH     = (112, 8, 24, 51)         # 0x33700818
ACCENT_LINE     = (175, 18, 26, 255)       # 0xFFAF121A
ACCENT_GLOW     = (255, 32, 48, 85)        # 0x55FF2030
BRIGHT_ACCENT   = (255, 32, 48, 255)       # 0xFFFF2030

CARD_ON         = (26, 18, 22, 255)        # 0xEE1A1216 (forced opaque)
CARD_OFF        = (14, 14, 17, 255)        # 0xDD0E0E11 (forced opaque)
CARD_HOVER_ON   = (42, 18, 23, 255)        # 0xFF2A1217
CARD_HOVER_OFF  = (22, 22, 27, 255)        # 0xEE16161B

BRAND_RED       = (175, 18, 26, 255)       # 0xFFAF121A
BRAND_RED_HOV   = (200, 22, 30, 255)       # 0xFFC8161E
TEXT            = (232, 232, 232, 255)     # 0xFFE8E8E8
WHITE           = (255, 255, 255, 255)
MUTED           = (152, 152, 158, 255)     # 0xFF98989E
DIM             = (120, 120, 130, 255)
DARK_GRAY       = (53, 53, 61, 255)        # 0xFF35353D pill off track
KNOB_OFF        = (232, 232, 232, 255)
ICON_OFF_BG     = (26, 26, 30, 255)        # 0xFF1A1A1E
ICON_INNER_HL   = (255, 255, 255, 68)      # 0x44FFFFFF
GREEN           = (34, 221, 85, 255)       # 0xFF22DD55

# Sidebar dot colors per category
CAT_DOTS = {
    "All":      (175, 18, 26),
    "Display":  (224, 112, 144),
    "World":    (85, 214, 122),
    "Inv":      (216, 199, 90),
    "Combat":   (239, 68, 68),
    "Server":   (96, 144, 224),
    "Utility":  (176, 112, 216),
}

# Category-specific accents for icon top stripes (when off, use cat color)
CAT_ACCENT = {
    "Display":  (224, 112, 144, 255),
    "World":    (85, 214, 122, 255),
    "Inventory":(216, 199, 90, 255),
    "Combat":   (239, 68, 68, 255),
    "Server":   (96, 144, 224, 255),
    "Utility":  (176, 112, 216, 255),
    "":         (130, 130, 138, 255),
}

# 16-color crosshair palette (approximate to MC color codes)
CROSSHAIR_COLORS = [
    (255, 85, 85),   # red
    (255, 255, 85),  # yellow
    (85, 255, 85),   # lime
    (85, 255, 255),  # cyan
    (85, 85, 255),   # blue
    (255, 85, 255),  # magenta
    (255, 255, 255), # white
    (170, 170, 170), # light gray
    (85, 85, 85),    # dark gray
    (0, 0, 0),       # black
    (255, 170, 0),   # orange
    (170, 0, 0),     # dark red
    (0, 170, 0),     # green
    (0, 0, 170),     # dark blue
    (170, 0, 170),   # purple
    (255, 200, 200), # pink
]


# ---------------------------------------------------------------------------
# Drawing helpers
# ---------------------------------------------------------------------------
def get_font(size, bold=False):
    """Try to load Consolas (Windows monospace), fallback to default."""
    candidates = [
        "C:/Windows/Fonts/consolab.ttf" if bold else "C:/Windows/Fonts/consola.ttf",
        "C:/Windows/Fonts/cour.ttf",
        "C:/Windows/Fonts/courbd.ttf" if bold else "C:/Windows/Fonts/cour.ttf",
        "C:/Windows/Fonts/segoeuib.ttf" if bold else "C:/Windows/Fonts/segoeui.ttf",
    ]
    for path in candidates:
        try:
            return ImageFont.truetype(path, size)
        except Exception:
            continue
    return ImageFont.load_default()


def rrect(draw, xy, color, radius=3):
    """Anti-aliased rounded rectangle."""
    x1, y1, x2, y2 = xy
    if x2 <= x1 or y2 <= y1:
        return
    if isinstance(color, tuple) and len(color) == 4 and color[3] == 0:
        return
    draw.rounded_rectangle((x1, y1, x2 - 1, y2 - 1), radius=radius, fill=color)


def rect(draw, xy, color):
    """Plain filled rectangle (fill() in Java)."""
    x1, y1, x2, y2 = xy
    if x2 <= x1 or y2 <= y1:
        return
    draw.rectangle((x1, y1, x2 - 1, y2 - 1), fill=color)


def alpha_blend(layer_img, base):
    return Image.alpha_composite(base, layer_img)


# ---------------------------------------------------------------------------
# Menu rendering
# ---------------------------------------------------------------------------
def render_menu(scale=2):
    """Render the main menu. scale=2 means we paint at 2× the GUI px size for
    crispness (similar to MC's GUI scale).

    Returns the final PIL Image.
    """
    # === Layout constants from ShadowHud.java =================================
    # GUI-px coordinates that mirror Java side. We render at scale× and downsample.
    W = 580
    SIDEBAR_W = 64
    TAB_BAR_H = 13
    CARD_H = 44
    CARD_GAP = 6
    GRID_COLS = 1
    CARD_W = W - 12 - 4 - SIDEBAR_W
    VISIBLE_ROWS = 8                  # we want 8 cards in the preview

    modulesH = VISIBLE_ROWS * (CARD_H + CARD_GAP)
    bodyH = (
        18 + 6 + TAB_BAR_H + 4 + modulesH + 22 + 12 + 18
    )
    # Slightly bigger virtual GUI to fit 8 cards comfortably.
    sw_gui = 650
    sh_gui = 480

    # We'll render everything at scale×.
    img_w = sw_gui * scale
    img_h = sh_gui * scale

    # Fake a Minecraft-y backdrop: dark with a subtle radial gradient.
    bg = Image.new("RGBA", (img_w, img_h), (8, 10, 14, 255))
    # Add a vignette/gradient feel
    vignette = Image.new("RGBA", (img_w, img_h), (0, 0, 0, 0))
    vd = ImageDraw.Draw(vignette)
    for i in range(8):
        alpha = 18 + i * 4
        inset = i * 30
        vd.rectangle((inset, inset, img_w - inset, img_h - inset),
                     outline=(0, 0, 0, alpha))
    bg = Image.alpha_composite(bg, vignette)

    # Fake "blocks" in the backdrop so the modal panel reads as floating.
    # We don't draw any blocks in the panel area to keep the preview clean.

    # === Create scaled drawing canvas =========================================
    canvas = bg.copy()
    overlay = Image.new("RGBA", (img_w, img_h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)

    def S(v):
        return v * scale

    def srect(xy, color, r=0):
        x1, y1, x2, y2 = xy
        x1, y1, x2, y2 = S(x1), S(y1), S(x2), S(y2)
        if r > 0:
            rrect(od, (x1, y1, x2, y2), color, radius=S(r))
        else:
            rect(od, (x1, y1, x2, y2), color)

    def stext(xy, txt, color, font, shadow=False):
        x, y = S(xy[0]), S(xy[1])
        if shadow:
            od.text((x + scale, y + scale), txt, fill=(0, 0, 0, 220), font=font)
        od.text((x, y), txt, fill=color, font=font)

    f12 = get_font(S(8))                  # body font (~12px after scale)
    f12b = get_font(S(8), bold=True)      # bold body
    f10 = get_font(S(7))                  # small
    f10b = get_font(S(7), bold=True)
    f14b = get_font(S(10), bold=True)     # title
    f8 = get_font(S(6))

    # Center the panel
    x = (sw_gui - W) // 2
    y = max(10, (sh_gui - bodyH) // 2)

    # --- panel chrome -------------------------------------------------------
    # Drop shadow (3 layers fading out)
    for s in range(1, 4):
        alpha = 96 - s * 24
        srect((x - s, y + s, x + W + s, y + bodyH + s), (0, 0, 0, alpha), r=3)

    # Body — fully opaque so the panel reads as solid; the Java code uses
    # 0xF0 (94% alpha) but for a static preview opacity helps clarity.
    srect((x, y, x + W, y + bodyH), (26, 31, 42, 255), r=3)

    # Translucent overlays (sheen, header wash, accent) need to be alpha-
    # composited rather than directly drawn — PIL's draw.* OVERWRITES alpha,
    # so a 24-alpha sheen on top of an opaque body produces a 24-alpha pixel.
    sheen_layer = Image.new("RGBA", overlay.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(sheen_layer)

    def s_l(xy, color, r=0):
        x1, y1, x2, y2 = xy
        x1, y1, x2, y2 = S(x1), S(y1), S(x2), S(y2)
        if r > 0:
            rrect(sd, (x1, y1, x2, y2), color, radius=S(r))
        else:
            rect(sd, (x1, y1, x2, y2), color)

    s_l((x + 2, y + 2, x + W - 2, y + bodyH // 2), TOP_SHEEN, r=2)
    s_l((x + 2, y + 1, x + W - 2, y + 19), HEADER_WASH, r=2)
    s_l((x + 12, y + 19, x + W - 12, y + 20), ACCENT_LINE)
    s_l((x + 12, y + 20, x + W - 12, y + 21), ACCENT_GLOW)
    overlay = Image.alpha_composite(overlay, sheen_layer)
    od = ImageDraw.Draw(overlay)

    # --- Title ---------------------------------------------------------------
    # Square red icon
    srect((x + 8, y + 5, x + 18, y + 15), BRAND_RED, r=2)
    srect((x + 9, y + 6, x + 17, y + 8), (255, 255, 255, 102), r=1)   # gloss
    stext((x + 11, y + 6), "S", WHITE, f10b, shadow=True)

    # SHADOW CLIENT wordmark
    stext((x + 22, y + 6), "SHADOW", (255, 80, 80, 255), f12b, shadow=True)
    stext((x + 22 + 42, y + 6), "CLIENT", WHITE, f12b, shadow=True)
    stext((x + 22 + 42 + 44, y + 6), "by Edison", (140, 140, 140, 255), f10)

    # Live status pill (top-right)
    on_count, total = 12, 84
    stat_str = f"{on_count}/{total}"
    stat_w = 6 * len(stat_str)
    stat_x = x + W - stat_w - 14
    # Green status dot
    srect((stat_x - 9, y + 8, stat_x - 4, y + 13), GREEN, r=1)
    stext((stat_x, y + 6), stat_str, (102, 220, 119, 255), f10b)

    # === Top toolbar (search + 3 buttons) ====================================
    tabsY = y + 24
    searchW = (W - 12) * 6 // 10 - 3
    btnsRowW = (W - 12) - searchW - 3
    actionBtnW = (btnsRowW - 4) // 3
    searchX = x + 6
    searchY = tabsY
    searchH = TAB_BAR_H

    # Search bar — active state
    srect((searchX, searchY, searchX + searchW, searchY + searchH),
          (31, 20, 34, 255), r=2)
    srect((searchX, searchY + 2, searchX + 2, searchY + searchH - 2),
          BRIGHT_ACCENT)
    stext((searchX + 6, searchY + 3),
          "Click to search modules", (140, 140, 145, 255), f10)

    actionsX = searchX + searchW + 3
    btnLabels = ["All", "Default", "Save"]
    for b in range(3):
        tx = actionsX + b * (actionBtnW + 2)
        ty = tabsY
        bg_color = (16, 16, 24, 200)
        srect((tx, ty, tx + actionBtnW, ty + TAB_BAR_H), bg_color, r=2)
        lbl = btnLabels[b]
        labelX = tx + max(2, (actionBtnW - len(lbl) * 6) // 2)
        stext((labelX, ty + 3), lbl, WHITE, f10b)

    # === Modules section header =============================================
    rowY = tabsY + TAB_BAR_H + 4
    srect((x + 4, rowY, x + 8, rowY + 7), BRAND_RED)
    hdr = "HUD MODULES"
    stext((x + 12, rowY), hdr, (255, 90, 90, 255), f10b)
    stext((x + 12 + 70, rowY), f"({on_count}/{total} on, {total} visible all)",
          (140, 140, 140, 255), f10)
    srect((x + 12, rowY + 9, x + W - 12, rowY + 10), (175, 18, 26, 102))
    rowY += 10
    modulesTopY = rowY

    # === Sidebar ============================================================
    sbX = x + 6
    sbY = modulesTopY
    sbW = SIDEBAR_W - 4
    catBtnH = 28
    side_labels = ["All", "Display", "World", "Inv", "Combat", "Server", "Utility"]
    cat_counts = [(12, 84), (3, 18), (1, 14), (2, 12), (3, 14), (1, 8), (2, 18)]

    for t, lbl in enumerate(side_labels):
        btnY = sbY + t * (catBtnH + 2)
        btnY2 = btnY + catBtnH
        is_sel = (t == 0)
        if is_sel:
            bg_color = (58, 22, 32, 229)
        else:
            bg_color = (16, 16, 24, 96)
        srect((sbX, btnY, sbX + sbW, btnY2), bg_color, r=2)
        if is_sel:
            srect((sbX, btnY + 2, sbX + 2, btnY2 - 2), BRIGHT_ACCENT)
            srect((sbX + 2, btnY + 4, sbX + 3, btnY2 - 4), (255, 32, 48, 102))
        # Dot
        dot = CAT_DOTS[lbl] + ((255,) if is_sel else (128,))
        srect((sbX + 6, btnY + 9, sbX + 11, btnY + 14), dot, r=1)
        # Label
        prefix_color = WHITE if is_sel else (140, 140, 140, 255)
        font_to_use = f10b if is_sel else f10
        stext((sbX + 14, btnY + (catBtnH - 8) // 2 - 3), lbl, prefix_color, font_to_use)
        # Count
        on, tot = cat_counts[t]
        stext((sbX + 14, btnY + (catBtnH - 8) // 2 + 5),
              f"{on}/{tot}", (170, 170, 170, 255), f10)
        # Activity dot top-right
        if t > 0 and on > 0:
            srect((sbX + sbW - 7, btnY + 4, sbX + sbW - 3, btnY + 8), GREEN, r=1)

    # === Module cards =======================================================
    sample_modules = [
        ("FPS",          True,  "Display", "Frames per second counter"),
        ("HP",           True,  "Display", "Health and absorption display"),
        ("XYZ",          True,  "World",   "Coordinates with separators"),
        ("Crosshair",    True,  "Display", "Custom shape and color", True),
        ("Map",          False, "World",   "Mini-map with size options", True),
        ("Killstreak",   False, "Combat",  "Track kill streaks live"),
        ("AutoTotem",    True,  "Combat",  "Auto-swap totem to off-hand"),
        ("EnchantPreview",False,"Inv",     "Preview enchants before applying"),
    ]
    cat_to_letter = {"Display":"D", "World":"W", "Inv":"I",
                     "Inventory":"I", "Combat":"C", "Server":"S", "Utility":"U"}
    cat_full_for_color = {"Display":"Display", "World":"World", "Inv":"Inventory",
                          "Inventory":"Inventory", "Combat":"Combat",
                          "Server":"Server", "Utility":"Utility"}

    for idx, mod in enumerate(sample_modules[:VISIBLE_ROWS]):
        if len(mod) == 5:
            name, on, cat, desc, has_gear = mod
        else:
            name, on, cat, desc = mod
            has_gear = False

        cardL = x + 6 + SIDEBAR_W
        cardR = cardL + CARD_W
        cardT = modulesTopY + idx * (CARD_H + CARD_GAP)
        cardB = cardT + CARD_H

        # Background
        bg_card = CARD_ON if on else CARD_OFF
        srect((cardL, cardT, cardR, cardB), bg_card, r=3)
        # Hairline divider
        srect((cardL + 2, cardB - 1, cardR, cardB), (0, 0, 0, 51))

        # Icon square
        iconL = cardL + 6
        iconT = cardT + (CARD_H - 30) // 2
        iconR = iconL + 30
        iconB = iconT + 30
        if on:
            iconBg = BRAND_RED
            iconAccent = BRIGHT_ACCENT
        else:
            iconBg = ICON_OFF_BG
            iconAccent = CAT_ACCENT.get(cat_full_for_color.get(cat, ""),
                                        (130, 130, 138, 255))
        srect((iconL, iconT, iconR, iconB), iconBg, r=3)
        # Top accent stripe
        srect((iconL + 2, iconT, iconR - 2, iconT + 2), iconAccent)
        if on:
            srect((iconL + 1, iconT + 2, iconR - 1, iconT + 3), ICON_INNER_HL)

        # Big letter
        letter = cat_to_letter.get(cat, "?")
        stext((iconL + 13, iconT + 11), letter, WHITE, f12b, shadow=True)

        # Name + description column
        textL = iconR + 8
        stext((textL, cardT + 11), name, WHITE, f12b, shadow=True)

        # Description
        sub = desc
        descColor = MUTED
        od.text((S(textL), S(cardT + 26)), sub, fill=descColor, font=f10)

        # Variant tag for Crosshair / Map
        if on and name == "Crosshair":
            tag = "Cross"
            tagX = textL + len(sub) * 6 + 6
            stext((tagX, cardT + 26), "* " + tag, (255, 90, 100, 255), f10)

        # ---- pill on right ----
        pillW, pillH = 34, 16
        pillR = cardR - 12
        pillL = pillR - pillW
        pillT = cardT + (CARD_H - pillH) // 2
        pillB = pillT + pillH
        track_color = BRAND_RED if on else DARK_GRAY
        if on:
            srect((pillL - 1, pillB, pillR + 1, pillB + 1), (255, 32, 48, 51))
        srect((pillL, pillT, pillR, pillB), track_color, r=8)
        knobX = pillR - 14 if on else pillL + 2
        srect((knobX, pillT + 2, knobX + 12, pillT + 14), KNOB_OFF, r=6)

        # ---- gear button (configurable modules) ----
        if has_gear:
            gearW, gearH = 16, 16
            gearR = pillL - 6
            gearL = gearR - gearW
            gearT = cardT + (CARD_H - gearH) // 2
            gearB = gearT + gearH
            srect((gearL, gearT, gearR, gearB), (26, 18, 24, 255), r=2)
            cx_g = gearL + gearW // 2
            cy_g = gearT + gearH // 2
            gcol = (170, 180, 192, 255)
            # Body ring
            srect((cx_g - 5, cy_g - 5, cx_g + 5, cy_g + 5), gcol, r=2)
            # 4 cardinal teeth
            srect((cx_g - 1, gearT + 1, cx_g + 1, gearT + 4), gcol)   # N
            srect((cx_g - 1, gearB - 4, cx_g + 1, gearB - 1), gcol)   # S
            srect((gearL + 1, cy_g - 1, gearL + 4, cy_g + 1), gcol)   # W
            srect((gearR - 4, cy_g - 1, gearR - 1, cy_g + 1), gcol)   # E
            # Hub hole
            srect((cx_g - 2, cy_g - 2, cx_g + 2, cy_g + 2), (26, 18, 24, 255), r=1)

    # === Footer card =========================================================
    footTop = y + bodyH - 30
    footBot = y + bodyH - 4
    srect((x + 6, footTop, x + W - 6, footBot), (24, 24, 30, 224), r=2)
    srect((x + 8, footTop, x + W - 8, footTop + 1), (255, 32, 48, 102))

    stext((x + 10, y + bodyH - 24),
          "F1 help  Tab cycle cat  arrows nav  Enter toggle  Wheel scroll  / search",
          (200, 200, 200, 255), f10)
    stext((x + 10, y + bodyH - 12),
          "Click toggle  OPTIONS cycles  Ctrl+S/L/D snap/load/defaults  R Shift/Esc close",
          (200, 200, 200, 255), f10)

    # Composite + downsize for anti-aliasing feel
    final = Image.alpha_composite(canvas, overlay)
    return final


# ---------------------------------------------------------------------------
# Config panel rendering — drawn on top of the menu, with backdrop dim
# ---------------------------------------------------------------------------
def render_config_panel(scale=2):
    # First render the menu as backdrop
    backdrop = render_menu(scale=scale)
    img_w, img_h = backdrop.size

    # Apply the dim layer (0xA8 alpha black) — slightly stronger so the
    # backdrop fades clearly without obscuring it.
    dim = Image.new("RGBA", (img_w, img_h), (0, 0, 0, 200))
    backdrop = Image.alpha_composite(backdrop, dim)

    # Now draw the modal on top
    overlay = Image.new("RGBA", (img_w, img_h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)

    # IMPORTANT: must match the GUI viewport used inside render_menu so the
    # modal lands at the right pixels on top of the menu's image.
    sw_gui, sh_gui = 650, 480

    def S(v):
        return v * scale

    def srect(xy, color, r=0):
        x1, y1, x2, y2 = xy
        x1, y1, x2, y2 = S(x1), S(y1), S(x2), S(y2)
        if r > 0:
            rrect(od, (x1, y1, x2, y2), color, radius=S(r))
        else:
            rect(od, (x1, y1, x2, y2), color)

    def stext(xy, txt, color, font, shadow=False):
        x, y = S(xy[0]), S(xy[1])
        if shadow:
            od.text((x + scale, y + scale), txt, fill=(0, 0, 0, 220), font=font)
        od.text((x, y), txt, fill=color, font=font)

    f10 = get_font(S(7))
    f10b = get_font(S(7), bold=True)
    f12 = get_font(S(8))
    f12b = get_font(S(8), bold=True)
    f14b = get_font(S(10), bold=True)

    # === Modal layout (matches renderConfigPanel) ============================
    pw, ph = 380, 320
    px = (sw_gui - pw) // 2
    py = (sh_gui - ph) // 2

    # Drop shadow
    for s in range(1, 5):
        alpha = max(0, 110 - s * 22)
        srect((px - s, py + s, px + pw + s, py + ph + s), (0, 0, 0, alpha), r=3)

    # Body — slightly more saturated red-tinted dark, fully opaque so menu
    # underneath doesn't bleed through and clobber readability.
    # NOTE: PIL's draw.rectangle(fill=color) does NOT alpha-composite — it
    # overwrites pixels. So a 14-alpha sheen drawn ON TOP of the opaque body
    # would replace the body with mostly-transparent white, defeating the
    # purpose. We layer the translucent overlays on a SEPARATE image and
    # alpha-composite them, instead.
    srect((px, py, px + pw, py + ph), (22, 26, 36, 255), r=3)

    # Sheen + header wash + accent need alpha-composite (layered translucent)
    sheen_layer = Image.new("RGBA", overlay.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(sheen_layer)

    def s_rrect_l(layer_d, xy, color, r=0):
        x1, y1, x2, y2 = xy
        x1, y1, x2, y2 = S(x1), S(y1), S(x2), S(y2)
        if r > 0:
            rrect(layer_d, (x1, y1, x2, y2), color, radius=S(r))
        else:
            rect(layer_d, (x1, y1, x2, y2), color)

    s_rrect_l(sd, (px + 2, py + 2, px + pw - 2, py + ph // 2),
              (255, 255, 255, 14), r=2)
    s_rrect_l(sd, (px + 2, py + 1, px + pw - 2, py + 22),
              (112, 8, 24, 90), r=2)
    s_rrect_l(sd, (px + 14, py + 22, px + pw - 14, py + 23), BRAND_RED)
    s_rrect_l(sd, (px + 14, py + 23, px + pw - 14, py + 24),
              (255, 32, 48, 85))
    overlay = Image.alpha_composite(overlay, sheen_layer)
    od = ImageDraw.Draw(overlay)

    # --- Title ---
    stext((px + 14, py + 7), "Crosshair", WHITE, f12b, shadow=True)
    title_w = 9 * 6  # "Crosshair" = 9 chars × 6 px
    stext((px + 14 + title_w, py + 7), "* Display",
          (140, 140, 140, 255), f10)

    # Close X button
    xBtnL = px + pw - 22
    xBtnT = py + 4
    xBtnR = xBtnL + 16
    xBtnB = xBtnT + 16
    srect((xBtnL, xBtnT, xBtnR, xBtnB), (40, 40, 50, 200), r=2)
    stext((xBtnL + 5, xBtnT + 3), "×", WHITE, f14b)

    # Description
    rowY = py + 32
    stext((px + 14, rowY), "Custom shape and color for the in-world crosshair.",
          (160, 160, 165, 255), f10)
    rowY += 11
    rowY += 4

    # ---- General divider ----
    label = "General"
    label_w_px = len(label) * 6 + 8
    pdx = px + 10
    line_l = pdx + 14
    line_r = line_l + (pw - 20 - label_w_px) // 2 - 4
    line_l2 = line_r + label_w_px
    line_r2 = pdx + (pw - 20) - 4
    srect((line_l, rowY + 4, line_r, rowY + 5), (175, 18, 26, 102))
    stext((line_r + 6, rowY), label, (170, 170, 170, 255), f10)
    srect((line_l2, rowY + 4, line_r2, rowY + 5), (175, 18, 26, 102))
    rowY += 16

    # ---- Shape cycle row ----
    inner_x = px + 14
    inner_w = pw - 28
    labelW = 80
    # Label
    stext((inner_x + 4, rowY + 3), "Shape", (170, 170, 170, 255), f10)
    # Reset arrow
    rstW = 16
    rstR = inner_x + inner_w - 4
    rstL = rstR - rstW
    rstT = rowY
    rstB = rowY + 14
    srect((rstL, rstT, rstR, rstB), (26, 18, 24, 255), r=2)
    stext((rstL + 4, rstT + 2), "<-", (170, 170, 170, 255), f10)
    # Value button
    valL = inner_x + labelW
    valR = rstL - 4
    valT = rowY
    valB = rowY + 14
    srect((valL, valT, valR, valB), (34, 24, 42, 255), r=2)
    stext((valL + 6, valT + 3), "Cross", WHITE, f10b)
    rowY += 18

    # ---- Color swatch row ----
    # Label
    stext((inner_x + 4, rowY + 2), "Color", WHITE, f10b)
    # Reset arrow
    rstHov_R = inner_x + inner_w - 4
    rstHov_L = rstHov_R - 16
    srect((rstHov_L, rowY, rstHov_R, rowY + 14), (26, 18, 24, 255), r=2)
    stext((rstHov_L + 4, rowY + 2), "<-", (170, 170, 170, 255), f10)
    rowY += 14

    # Swatch grid: 16 + 1 (custom) = 17 swatches, 9 per row, 18×14 each
    sw_box = 18
    shh = 14
    gap = 2
    total_swatches = 17
    swatches_per_row = max(8, (inner_w - 4) // (sw_box + gap))

    for i in range(total_swatches):
        sxx = inner_x + 4 + (i % swatches_per_row) * (sw_box + gap)
        syy = rowY + (i // swatches_per_row) * (shh + gap)
        is_custom = (i == 16)
        if is_custom:
            color = (90, 90, 110, 255)
        else:
            r, g, b = CROSSHAIR_COLORS[i]
            color = (r, g, b, 255)
        selected = (i == 0)
        srect((sxx, syy, sxx + sw_box, syy + shh), color, r=2)
        # Selection ring
        if selected:
            srect((sxx - 1, syy - 1, sxx + sw_box + 1, syy), WHITE)
            srect((sxx - 1, syy + shh, sxx + sw_box + 1, syy + shh + 1), WHITE)
            srect((sxx - 1, syy, sxx, syy + shh), WHITE)
            srect((sxx + sw_box, syy, sxx + sw_box + 1, syy + shh), WHITE)
        if is_custom:
            stext((sxx + sw_box // 2 - 2, syy + 2), "+", WHITE, f10b)

    rows = (total_swatches + swatches_per_row - 1) // swatches_per_row
    rowY += rows * (shh + gap) + 6

    # ---- Active divider + pill ----
    activeY = py + ph - 32
    a_label = "Active"
    a_label_w = len(a_label) * 6 + 8
    a_line_l = pdx + 14
    a_line_r = a_line_l + (pw - 20 - a_label_w) // 2 - 4
    a_line_l2 = a_line_r + a_label_w
    a_line_r2 = pdx + (pw - 20) - 4
    srect((a_line_l, activeY + 4, a_line_r, activeY + 5), (175, 18, 26, 102))
    stext((a_line_r + 6, activeY), a_label, (170, 170, 170, 255), f10)
    srect((a_line_l2, activeY + 4, a_line_r2, activeY + 5), (175, 18, 26, 102))

    # ENABLED label + pill
    aPillW, aPillH = 38, 18
    aPillR = px + pw - 22
    aPillL = aPillR - aPillW
    aPillT = activeY + 14
    aPillB = aPillT + aPillH
    # Pill on
    srect((aPillL - 1, aPillB, aPillR + 1, aPillB + 1), (255, 32, 48, 51))
    srect((aPillL, aPillT, aPillR, aPillB), BRAND_RED, r=8)
    aKnobX = aPillR - 16
    srect((aKnobX, aPillT + 2, aKnobX + 14, aPillT + 16), KNOB_OFF, r=6)
    stext((px + 14, aPillT + 5), "ENABLED", (102, 220, 119, 255), f10b)

    final = Image.alpha_composite(backdrop, overlay)
    return final


# ---------------------------------------------------------------------------
def main():
    out_dir = "C:/Users/ediso/Downloads"
    print("Rendering shadow_menu_preview.png...")
    menu = render_menu(scale=2)
    # Resize to exactly 1200x720
    menu = menu.resize((1200, 720), Image.LANCZOS)
    menu.save(f"{out_dir}/shadow_menu_preview.png", "PNG")
    print(f"  saved: {out_dir}/shadow_menu_preview.png")

    print("Rendering shadow_config_panel.png...")
    panel = render_config_panel(scale=2)
    panel = panel.resize((1200, 720), Image.LANCZOS)
    panel.save(f"{out_dir}/shadow_config_panel.png", "PNG")
    print(f"  saved: {out_dir}/shadow_config_panel.png")
    print("Done.")


if __name__ == "__main__":
    main()
