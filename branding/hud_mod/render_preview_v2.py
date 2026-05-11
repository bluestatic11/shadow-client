"""Render PIL-based previews of the Shadow Client menu and the FPS-module
config panel (v2 — newer layout with always-visible search + 3 toolbar buttons,
gear icons on configurable cards, FPS-style modal with Style cycle + label
toggle).

Output:
  C:/Users/ediso/Downloads/shadow_final_menu.png        (1200x720)
  C:/Users/ediso/Downloads/shadow_config_panel_v2.png   (1200x720, with backdrop)
"""

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------------------
# Color palette (matches ShadowHud.java)
# ---------------------------------------------------------------------------
PANEL_BODY      = (26, 31, 42, 240)
PANEL_BODY_OPQ  = (26, 31, 42, 255)
TOP_SHEEN       = (255, 255, 255, 24)
HEADER_WASH     = (112, 8, 24, 51)
ACCENT_LINE     = (175, 18, 26, 255)
ACCENT_GLOW     = (255, 32, 48, 85)
BRIGHT_ACCENT   = (255, 32, 48, 255)

CARD_ON         = (26, 18, 22, 255)
CARD_OFF        = (14, 14, 17, 255)

BRAND_RED       = (175, 18, 26, 255)
TEXT            = (232, 232, 232, 255)
WHITE           = (255, 255, 255, 255)
MUTED           = (152, 152, 158, 255)
DIM             = (120, 120, 130, 255)
DARK_GRAY       = (53, 53, 61, 255)        # pill OFF track
KNOB_OFF        = (232, 232, 232, 255)
ICON_OFF_BG     = (26, 26, 30, 255)
ICON_INNER_HL   = (255, 255, 255, 68)
GREEN           = (34, 221, 85, 255)
GREEN_BRIGHT    = (102, 220, 119, 255)

# Sidebar dot colors per category (from spec)
CAT_DOTS = {
    "All":      (175, 18, 26),
    "Display":  (224, 112, 144),
    "World":    (85, 214, 122),
    "Inv":      (216, 199, 90),
    "Combat":   (239, 68, 68),
    "Server":   (96, 144, 224),
    "Utility":  (176, 112, 216),
}

CAT_ACCENT = {
    "Display":  (224, 112, 144, 255),
    "World":    (85, 214, 122, 255),
    "Inventory":(216, 199, 90, 255),
    "Combat":   (239, 68, 68, 255),
    "Server":   (96, 144, 224, 255),
    "Utility":  (176, 112, 216, 255),
    "":         (130, 130, 138, 255),
}


# ---------------------------------------------------------------------------
# Drawing helpers
# ---------------------------------------------------------------------------
def get_font(size, bold=False):
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
    x1, y1, x2, y2 = xy
    if x2 <= x1 or y2 <= y1:
        return
    if isinstance(color, tuple) and len(color) == 4 and color[3] == 0:
        return
    draw.rounded_rectangle((x1, y1, x2 - 1, y2 - 1), radius=radius, fill=color)


def rect(draw, xy, color):
    x1, y1, x2, y2 = xy
    if x2 <= x1 or y2 <= y1:
        return
    draw.rectangle((x1, y1, x2 - 1, y2 - 1), fill=color)


# ---------------------------------------------------------------------------
# Menu rendering
# ---------------------------------------------------------------------------
def render_menu(scale=2):
    """Render the main configuration menu. scale=2 paints at 2× then downsamples."""
    W = 580
    SIDEBAR_W = 64
    TAB_BAR_H = 13
    CARD_H = 44
    CARD_GAP = 6
    CARD_W = W - 12 - 4 - SIDEBAR_W
    VISIBLE_ROWS = 8

    modulesH = VISIBLE_ROWS * (CARD_H + CARD_GAP)
    bodyH = 18 + 6 + TAB_BAR_H + 4 + modulesH + 22 + 12 + 18

    sw_gui = 650
    sh_gui = 480
    img_w = sw_gui * scale
    img_h = sh_gui * scale

    # Backdrop
    bg = Image.new("RGBA", (img_w, img_h), (8, 10, 14, 255))
    vignette = Image.new("RGBA", (img_w, img_h), (0, 0, 0, 0))
    vd = ImageDraw.Draw(vignette)
    for i in range(8):
        alpha = 18 + i * 4
        inset = i * 30
        vd.rectangle((inset, inset, img_w - inset, img_h - inset),
                     outline=(0, 0, 0, alpha))
    bg = Image.alpha_composite(bg, vignette)

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

    f12 = get_font(S(8))
    f12b = get_font(S(8), bold=True)
    f10 = get_font(S(7))
    f10b = get_font(S(7), bold=True)
    f14b = get_font(S(10), bold=True)

    x = (sw_gui - W) // 2
    y = max(10, (sh_gui - bodyH) // 2)

    # Drop shadow
    for s in range(1, 4):
        alpha = 96 - s * 24
        srect((x - s, y + s, x + W + s, y + bodyH + s), (0, 0, 0, alpha), r=3)

    # Body (opaque)
    srect((x, y, x + W, y + bodyH), (26, 31, 42, 255), r=3)

    # Translucent overlays
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

    # --- Title bar ---
    # Red square "S" icon
    srect((x + 8, y + 5, x + 18, y + 15), BRAND_RED, r=2)
    srect((x + 9, y + 6, x + 17, y + 8), (255, 255, 255, 102), r=1)
    stext((x + 11, y + 6), "S", WHITE, f10b, shadow=True)

    # SHADOW CLIENT wordmark
    stext((x + 22, y + 6), "SHADOW", (255, 80, 80, 255), f12b, shadow=True)
    stext((x + 22 + 42, y + 6), "CLIENT", WHITE, f12b, shadow=True)
    stext((x + 22 + 42 + 44, y + 6), "by Edison", (140, 140, 140, 255), f10)

    # Live status pill (top-right): green pulsing dot + count
    on_count, total = 12, 100
    stat_str = f"{on_count}/{total}"
    stat_w = 6 * len(stat_str)
    stat_x = x + W - stat_w - 14
    # Pulsing glow ring
    srect((stat_x - 11, y + 6, stat_x - 2, y + 15), (34, 221, 85, 60), r=2)
    srect((stat_x - 9, y + 8, stat_x - 4, y + 13), GREEN, r=1)
    stext((stat_x, y + 6), stat_str, GREEN_BRIGHT, f10b)

    # === Top toolbar: search bar + 3 buttons (always visible) =================
    tabsY = y + 24
    searchW = (W - 12) * 6 // 10 - 3
    btnsRowW = (W - 12) - searchW - 3
    actionBtnW = (btnsRowW - 4) // 3
    searchX = x + 6
    searchY = tabsY
    searchH = TAB_BAR_H

    # Search bar
    srect((searchX, searchY, searchX + searchW, searchY + searchH),
          (31, 20, 34, 255), r=2)
    srect((searchX, searchY + 2, searchX + 2, searchY + searchH - 2),
          BRIGHT_ACCENT)
    # Magnifier dot
    srect((searchX + 4, searchY + 4, searchX + 7, searchY + 7),
          (200, 200, 205, 255), r=1)
    stext((searchX + 10, searchY + 3),
          "Search modules...", (140, 140, 145, 255), f10)

    # Three toolbar buttons
    actionsX = searchX + searchW + 3
    btnLabels = ["All", "Default", "Save"]
    btnTints = [
        (16, 16, 24, 220),
        (40, 22, 22, 220),
        (24, 36, 24, 220),
    ]
    for b in range(3):
        tx = actionsX + b * (actionBtnW + 2)
        ty = tabsY
        srect((tx, ty, tx + actionBtnW, ty + TAB_BAR_H), btnTints[b], r=2)
        # Subtle accent stripe at top
        if b == 0:
            srect((tx, ty, tx + actionBtnW, ty + 1), (175, 18, 26, 130))
        elif b == 1:
            srect((tx, ty, tx + actionBtnW, ty + 1), (255, 90, 90, 130))
        else:
            srect((tx, ty, tx + actionBtnW, ty + 1), (102, 220, 119, 130))
        lbl = btnLabels[b]
        labelX = tx + max(2, (actionBtnW - len(lbl) * 6) // 2)
        stext((labelX, ty + 3), lbl, WHITE, f10b)

    # === Modules section header =============================================
    rowY = tabsY + TAB_BAR_H + 4
    srect((x + 4, rowY, x + 8, rowY + 7), BRAND_RED)
    hdr = "HUD MODULES"
    stext((x + 12, rowY), hdr, (255, 90, 90, 255), f10b)
    stext((x + 12 + 70, rowY),
          f"({on_count}/{total} on)", (140, 140, 140, 255), f10)
    srect((x + 12, rowY + 9, x + W - 12, rowY + 10), (175, 18, 26, 102))
    rowY += 10
    modulesTopY = rowY

    # === Sidebar (7 categories) =============================================
    sbX = x + 6
    sbY = modulesTopY
    sbW = SIDEBAR_W - 4
    catBtnH = 28
    side_labels = ["All", "Display", "World", "Inv", "Combat", "Server", "Utility"]
    cat_counts = [(12, 100), (3, 14), (2, 18), (1, 12), (3, 14), (1, 8), (2, 18)]

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
        # Colored dot
        dot = CAT_DOTS[lbl] + ((255,) if is_sel else (200,))
        srect((sbX + 5, btnY + 8, sbX + 11, btnY + 14), dot, r=1)
        # Label
        prefix_color = WHITE if is_sel else (180, 180, 185, 255)
        font_to_use = f10b if is_sel else f10
        stext((sbX + 14, btnY + 5), lbl, prefix_color, font_to_use)
        # Count badge
        on, tot = cat_counts[t]
        count_str = f"{on}/{tot}"
        stext((sbX + 14, btnY + 16),
              count_str, (160, 160, 165, 255), f10)
        # Activity dot (top-right) for non-empty active cats
        if t > 0 and on > 0:
            srect((sbX + sbW - 7, btnY + 4, sbX + sbW - 3, btnY + 8), GREEN, r=1)

    # === Module cards =======================================================
    sample_modules = [
        ("FPS",        True,  "Display", "Frames per second",          False),
        ("HP",         True,  "Display", "Health and absorption",      False),
        ("XYZ",        True,  "World",   "Coordinates with separators",False),
        ("Crosshair",  True,  "Display", "Custom shape and color",     True),
        ("Map",        False, "World",   "Mini-map with size options", True),
        ("Killstreak", False, "Combat",  "Track kill streaks live",    False),
        ("AutoTotem",  True,  "Combat",  "Auto-swap totem to off-hand",False),
        ("Day",        False, "World",   "In-game day counter",        True),
    ]
    cat_to_letter = {"Display":"D", "World":"W", "Inv":"I",
                     "Inventory":"I", "Combat":"C", "Server":"S", "Utility":"U"}
    cat_full_for_color = {"Display":"Display", "World":"World", "Inv":"Inventory",
                          "Inventory":"Inventory", "Combat":"Combat",
                          "Server":"Server", "Utility":"Utility"}

    for idx, (name, on, cat, desc, has_gear) in enumerate(sample_modules[:VISIBLE_ROWS]):
        cardL = x + 6 + SIDEBAR_W
        cardR = cardL + CARD_W
        cardT = modulesTopY + idx * (CARD_H + CARD_GAP)
        cardB = cardT + CARD_H

        # Background
        bg_card = CARD_ON if on else CARD_OFF
        srect((cardL, cardT, cardR, cardB), bg_card, r=3)
        srect((cardL + 2, cardB - 1, cardR, cardB), (0, 0, 0, 51))

        # Icon square — vertically centered
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
        srect((iconL + 2, iconT, iconR - 2, iconT + 2), iconAccent)
        if on:
            srect((iconL + 1, iconT + 2, iconR - 1, iconT + 3), ICON_INNER_HL)

        letter = cat_to_letter.get(cat, "?")
        stext((iconL + 13, iconT + 11), letter, WHITE, f12b, shadow=True)

        # Name + description (vertically centered in card)
        textL = iconR + 8
        stext((textL, cardT + 11), name, WHITE, f12b, shadow=True)
        stext((textL, cardT + 26), desc, MUTED, f10)

        # ---- Pill toggle (right) ----
        pillW, pillH = 34, 16
        pillR = cardR - 12
        pillL = pillR - pillW
        pillT = cardT + (CARD_H - pillH) // 2
        pillB = pillT + pillH
        track_color = BRAND_RED if on else DARK_GRAY
        if on:
            srect((pillL - 1, pillB, pillR + 1, pillB + 1),
                  (255, 32, 48, 51))
        srect((pillL, pillT, pillR, pillB), track_color, r=8)
        knobX = pillR - 14 if on else pillL + 2
        srect((knobX, pillT + 2, knobX + 12, pillT + 14), KNOB_OFF, r=6)

        # ---- Gear icon (configurable modules) ----
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
            srect((cx_g - 5, cy_g - 5, cx_g + 5, cy_g + 5), gcol, r=2)
            srect((cx_g - 1, gearT + 1, cx_g + 1, gearT + 4), gcol)
            srect((cx_g - 1, gearB - 4, cx_g + 1, gearB - 1), gcol)
            srect((gearL + 1, cy_g - 1, gearL + 4, cy_g + 1), gcol)
            srect((gearR - 4, cy_g - 1, gearR - 1, cy_g + 1), gcol)
            srect((cx_g - 2, cy_g - 2, cx_g + 2, cy_g + 2),
                  (26, 18, 24, 255), r=1)

    # === Footer bar (hover-tooltip area) =====================================
    footTop = y + bodyH - 30
    footBot = y + bodyH - 4
    srect((x + 6, footTop, x + W - 6, footBot), (24, 24, 30, 224), r=2)
    srect((x + 8, footTop, x + W - 8, footTop + 1), (255, 32, 48, 102))

    # Hover tooltip area indicator (small chip on the left)
    tip_l = x + 10
    tip_t = footTop + 4
    srect((tip_l, tip_t, tip_l + 8, tip_t + 8), (175, 18, 26, 200), r=1)
    stext((tip_l + 12, footTop + 3),
          "Hover a module for details", (200, 200, 200, 255), f10b)
    stext((x + 10, y + bodyH - 12),
          "F1 help  Tab cycle cat  arrows nav  Enter toggle  Wheel scroll  / search",
          (170, 170, 175, 255), f10)

    final = Image.alpha_composite(canvas, overlay)
    return final


# ---------------------------------------------------------------------------
# Config panel for "FPS" module (v2 layout)
# ---------------------------------------------------------------------------
def render_config_panel_fps(scale=2):
    backdrop = render_menu(scale=scale)
    img_w, img_h = backdrop.size

    # Dim layer
    dim = Image.new("RGBA", (img_w, img_h), (0, 0, 0, 200))
    backdrop = Image.alpha_composite(backdrop, dim)

    overlay = Image.new("RGBA", (img_w, img_h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)

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

    # Modal layout: 380 × 320, centered
    pw, ph = 380, 320
    px = (sw_gui - pw) // 2
    py = (sh_gui - ph) // 2

    # Drop shadow
    for s in range(1, 5):
        alpha = max(0, 110 - s * 22)
        srect((px - s, py + s, px + pw + s, py + ph + s), (0, 0, 0, alpha), r=3)

    # Body
    srect((px, py, px + pw, py + ph), (22, 26, 36, 255), r=3)

    sheen_layer = Image.new("RGBA", overlay.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(sheen_layer)

    def s_l(layer_d, xy, color, r=0):
        x1, y1, x2, y2 = xy
        x1, y1, x2, y2 = S(x1), S(y1), S(x2), S(y2)
        if r > 0:
            rrect(layer_d, (x1, y1, x2, y2), color, radius=S(r))
        else:
            rect(layer_d, (x1, y1, x2, y2), color)

    s_l(sd, (px + 2, py + 2, px + pw - 2, py + ph // 2),
        (255, 255, 255, 14), r=2)
    s_l(sd, (px + 2, py + 1, px + pw - 2, py + 22),
        (112, 8, 24, 90), r=2)
    s_l(sd, (px + 14, py + 22, px + pw - 14, py + 23), BRAND_RED)
    s_l(sd, (px + 14, py + 23, px + pw - 14, py + 24),
        (255, 32, 48, 85))
    overlay = Image.alpha_composite(overlay, sheen_layer)
    od = ImageDraw.Draw(overlay)

    # --- Title row ---
    stext((px + 14, py + 7), "FPS", WHITE, f12b, shadow=True)
    title_w = 3 * 6  # "FPS" = 3 chars × 6 px
    stext((px + 14 + title_w + 4, py + 7), "* Display",
          (140, 140, 140, 255), f10)

    # Reset button (green) and × close button (red), both top-right
    closeR = px + pw - 8
    closeL = closeR - 16
    closeT = py + 4
    closeB = closeT + 16
    srect((closeL, closeT, closeR, closeB), (90, 30, 30, 220), r=2)
    stext((closeL + 5, closeT + 3), "x", WHITE, f10b)

    # Reset button
    resetW = 38
    resetR = closeL - 4
    resetL = resetR - resetW
    resetT = py + 4
    resetB = resetT + 16
    srect((resetL, resetT, resetR, resetB), (24, 60, 30, 220), r=2)
    stext((resetL + 5, resetT + 3), "Reset", GREEN_BRIGHT, f10b)

    # Description
    rowY = py + 32
    stext((px + 14, rowY), "Frames per second.",
          (160, 160, 165, 255), f10)
    rowY += 16

    # ---- General divider ----
    label = "General"
    label_w_px = len(label) * 6 + 8
    pdx = px + 10
    line_l = pdx + 14
    line_r = line_l + (pw - 20 - label_w_px) // 2 - 4
    line_l2 = line_r + label_w_px
    line_r2 = pdx + (pw - 20) - 4
    srect((line_l, rowY + 4, line_r, rowY + 5), (175, 18, 26, 102))
    stext((line_r + 6, rowY), label, (180, 180, 180, 255), f10)
    srect((line_l2, rowY + 4, line_r2, rowY + 5), (175, 18, 26, 102))
    rowY += 18

    # ---- Style cycle row (showing "Default") ----
    inner_x = px + 14
    inner_w = pw - 28
    labelW = 110

    # Row background
    srect((inner_x, rowY - 2, inner_x + inner_w, rowY + 18),
          (28, 22, 30, 130), r=2)

    stext((inner_x + 6, rowY + 3), "Style", WHITE, f10b)

    # Reset arrow on the far right
    rstW = 18
    rstR = inner_x + inner_w - 6
    rstL = rstR - rstW
    rstT = rowY
    rstB = rowY + 14
    srect((rstL, rstT, rstR, rstB), (26, 18, 24, 255), r=2)
    # Curved-arrow glyph (round-arrow)
    stext((rstL + 4, rstT + 2), "{}".format(chr(0x21BA)),
          (180, 180, 180, 255), f10b)

    # Cycle button "Default"
    valR = rstL - 4
    valL = inner_x + labelW
    valT = rowY
    valB = rowY + 14
    srect((valL, valT, valR, valB), (38, 26, 46, 255), r=2)
    # Tiny left/right cycle arrows on edges
    stext((valL + 4, valT + 2), "<", (180, 180, 180, 255), f10b)
    stext((valR - 8, valT + 2), ">", (180, 180, 180, 255), f10b)
    # "Default" label centered
    label_txt = "Default"
    lw = len(label_txt) * 6
    cx_lbl = (valL + valR) // 2 - lw // 2
    stext((cx_lbl, valT + 3), label_txt, WHITE, f10b)

    rowY += 22

    # ---- Show "FPS" label toggle row (ON, red pill) ----
    srect((inner_x, rowY - 2, inner_x + inner_w, rowY + 18),
          (28, 22, 30, 130), r=2)
    stext((inner_x + 6, rowY + 3), "Show \"FPS\" label",
          WHITE, f10b)

    # Reset arrow on the far right
    rstR2 = inner_x + inner_w - 6
    rstL2 = rstR2 - rstW
    srect((rstL2, rowY, rstR2, rowY + 14), (26, 18, 24, 255), r=2)
    stext((rstL2 + 4, rowY + 2), "{}".format(chr(0x21BA)),
          (180, 180, 180, 255), f10b)

    # Pill ON (red), positioned just left of the reset arrow
    pillW, pillH = 28, 14
    pillR = rstL2 - 4
    pillL = pillR - pillW
    pillT = rowY
    pillB = pillT + pillH
    srect((pillL - 1, pillB, pillR + 1, pillB + 1),
          (255, 32, 48, 60))
    srect((pillL, pillT, pillR, pillB), BRAND_RED, r=7)
    knobX = pillR - 12
    srect((knobX, pillT + 2, knobX + 10, pillT + 12), KNOB_OFF, r=5)

    rowY += 22

    # ---- Active divider (footer) ----
    activeY = py + ph - 32
    a_label = "Active"
    a_label_w = len(a_label) * 6 + 8
    a_line_l = pdx + 14
    a_line_r = a_line_l + (pw - 20 - a_label_w) // 2 - 4
    a_line_l2 = a_line_r + a_label_w
    a_line_r2 = pdx + (pw - 20) - 4
    srect((a_line_l, activeY + 4, a_line_r, activeY + 5),
          (175, 18, 26, 102))
    stext((a_line_r + 6, activeY), a_label, (180, 180, 180, 255), f10)
    srect((a_line_l2, activeY + 4, a_line_r2, activeY + 5),
          (175, 18, 26, 102))

    # ENABLED label (red) + on pill
    aPillW, aPillH = 38, 18
    aPillR = px + pw - 22
    aPillL = aPillR - aPillW
    aPillT = activeY + 14
    aPillB = aPillT + aPillH
    srect((aPillL - 1, aPillB, aPillR + 1, aPillB + 1),
          (255, 32, 48, 60))
    srect((aPillL, aPillT, aPillR, aPillB), BRAND_RED, r=8)
    aKnobX = aPillR - 16
    srect((aKnobX, aPillT + 2, aKnobX + 14, aPillT + 16), KNOB_OFF, r=6)
    stext((px + 14, aPillT + 5), "ENABLED",
          (255, 80, 95, 255), f10b)

    final = Image.alpha_composite(backdrop, overlay)
    return final


# ---------------------------------------------------------------------------
def main():
    out_dir = "C:/Users/ediso/Downloads"

    print("Rendering shadow_final_menu.png ...")
    menu = render_menu(scale=2)
    menu = menu.resize((1200, 720), Image.LANCZOS)
    menu.save(f"{out_dir}/shadow_final_menu.png", "PNG")
    print(f"  saved: {out_dir}/shadow_final_menu.png")

    print("Rendering shadow_config_panel_v2.png ...")
    panel = render_config_panel_fps(scale=2)
    panel = panel.resize((1200, 720), Image.LANCZOS)
    panel.save(f"{out_dir}/shadow_config_panel_v2.png", "PNG")
    print(f"  saved: {out_dir}/shadow_config_panel_v2.png")
    print("Done.")


if __name__ == "__main__":
    main()
