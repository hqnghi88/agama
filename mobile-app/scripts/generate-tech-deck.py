#!/usr/bin/env python3
"""
Generate the GAMA Mobile technical deck.

    python3 scripts/generate-tech-deck.py            # -> ./GAMA_Mobile_Tech_Talk_v0.3.0.pptx
    python3 scripts/generate-tech-deck.py out.pptx

16:9, 22 slides, built with python-pptx (never hand-rolled OpenXML).
"""

import os
import sys
from pathlib import Path

from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.enum.text import MSO_ANCHOR, MSO_AUTO_SIZE, PP_ALIGN
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt
from lxml import etree

# ── palette ──────────────────────────────────────────────────────────
NAVY = RGBColor(0x0B, 0x1F, 0x3A)
NAVY_SOFT = RGBColor(0x14, 0x31, 0x5A)
INK = RGBColor(0x0F, 0x17, 0x2A)
MUTED = RGBColor(0x5A, 0x6B, 0x7F)
ACCENT = RGBColor(0x25, 0x63, 0xEB)
ACCENT_LIGHT = RGBColor(0x3B, 0x82, 0xF6)
TEAL = RGBColor(0x0D, 0x94, 0x88)
TEAL_LIGHT = RGBColor(0x2D, 0xD4, 0xBF)
AMBER = RGBColor(0xB4, 0x53, 0x09)
RED = RGBColor(0xB9, 0x1C, 0x1C)
WHITE = RGBColor(0xFF, 0xFF, 0xFF)
BG = RGBColor(0xF6, 0xF8, 0xFB)
CARD = RGBColor(0xFF, 0xFF, 0xFF)
BORDER = RGBColor(0xD9, 0xE1, 0xEC)
SOFT = RGBColor(0xEE, 0xF3, 0xF9)

TITLE_FONT = "Calibri"
BODY_FONT = "Calibri"
MONO_FONT = "Consolas"

SLIDE_W, SLIDE_H = 13.333, 7.5
MARGIN = 0.62
CONTENT_W = SLIDE_W - 2 * MARGIN

TOTAL_SLIDES = 22


# ── low-level helpers ────────────────────────────────────────────────
def rgb(hexstr):
    return RGBColor.from_string(hexstr)


def no_shadow(shape):
    try:
        shape.shadow.inherit = False
    except Exception:
        pass


def rect(slide, x, y, w, h, fill=None, line=None, radius=None, line_w=0.75):
    shape_type = MSO_SHAPE.ROUNDED_RECTANGLE if radius is not None else MSO_SHAPE.RECTANGLE
    shape = slide.shapes.add_shape(shape_type, Inches(x), Inches(y), Inches(w), Inches(h))
    if radius is not None:
        shape.adjustments[0] = radius
    if fill is None:
        shape.fill.background()
    else:
        shape.fill.solid()
        shape.fill.fore_color.rgb = fill
    if line is None:
        shape.line.fill.background()
    else:
        shape.line.color.rgb = line
        shape.line.width = Pt(line_w)
    no_shadow(shape)
    shape.text_frame.word_wrap = True
    return shape


def shape_text(shape, text, size=11, color=INK, bold=False, font=BODY_FONT,
               align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE, line=1.0):
    tf = shape.text_frame
    tf.word_wrap = True
    tf.auto_size = MSO_AUTO_SIZE.NONE
    tf.margin_left = tf.margin_right = Inches(0.05)
    tf.margin_top = tf.margin_bottom = Inches(0.02)
    tf.vertical_anchor = anchor
    p = tf.paragraphs[0]
    p.alignment = align
    p.line_spacing = line
    run = p.add_run()
    run.text = text
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.name = font
    run.font.color.rgb = color


def P(text=None, *, lead=None, size=None, bold=False, color=None, bullet=False,
      space_before=None, space_after=0, font=None, align=None, italic=False,
      line=None):
    return dict(text=text, lead=lead, size=size, bold=bold, color=color,
                bullet=bullet, space_before=space_before, space_after=space_after,
                font=font, align=align, italic=italic, line=line)


def _bullet(p, color=ACCENT, char="•"):
    pPr = p._p.get_or_add_pPr()
    pPr.set("marL", str(int(Inches(0.24))))
    pPr.set("indent", str(-int(Inches(0.24))))
    buClr = etree.SubElement(pPr, qn("a:buClr"))
    srgb = etree.SubElement(buClr, qn("a:srgbClr"))
    srgb.set("val", str(color))
    buSz = etree.SubElement(pPr, qn("a:buSzPct"))
    buSz.set("val", "100000")
    buFont = etree.SubElement(pPr, qn("a:buFont"))
    buFont.set("typeface", "Arial")
    buChar = etree.SubElement(pPr, qn("a:buChar"))
    buChar.set("char", char)


def add_text(slide, x, y, w, h, items, *, size=13, color=INK, font=BODY_FONT,
             bold=False, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP,
             margins=(0, 0, 0, 0), line=1.12, gap=5):
    box = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = box.text_frame
    tf.word_wrap = True
    tf.auto_size = MSO_AUTO_SIZE.NONE
    tf.margin_left = Inches(margins[0])
    tf.margin_right = Inches(margins[1])
    tf.margin_top = Inches(margins[2])
    tf.margin_bottom = Inches(margins[3])
    tf.vertical_anchor = anchor

    first = True
    for item in items:
        if isinstance(item, str):
            item = P(item)
        p = tf.paragraphs[0] if first else tf.add_paragraph()
        first = False
        sz = item["size"] or size
        col = item["color"] or color
        if item["space_before"] is not None:
            p.space_before = Pt(item["space_before"])
        elif not first:
            p.space_before = Pt(gap)
        p.space_after = Pt(item["space_after"] or 0)
        p.line_spacing = item["line"] or line
        p.alignment = item["align"] or align
        if item["bullet"]:
            _bullet(p, color=ACCENT if col == INK else col)

        runs = []
        if item["lead"]:
            runs.append((item["lead"], True, accent_or(col), item["font"] or font))
        if item["text"]:
            runs.append((item["text"], item["bold"], col, item["font"] or font))
        for text, is_bold, run_color, run_font in runs:
            run = p.add_run()
            run.text = text
            run.font.size = Pt(sz)
            run.font.bold = is_bold or bold
            run.font.italic = item["italic"]
            run.font.name = run_font
            run.font.color.rgb = run_color
    return box


def accent_or(color):
    return ACCENT if color == INK else color


def tracking(run, hundredths):
    run.font._rPr.set("spc", str(hundredths))


def chip(slide, x, y, w, h, label, fill=SOFT, color=INK, size=10, bold=True,
         line=None, radius=0.5, font=BODY_FONT):
    shape = rect(slide, x, y, w, h, fill=fill, line=line, radius=radius)
    shape_text(shape, label, size=size, color=color, bold=bold, font=font)
    return shape


def chip_row(slide, x, y, labels, *, size=10, h=0.34, gap=0.12, pad=0.26,
             fill=SOFT, color=INK, line=None, font=BODY_FONT):
    cx = x
    for label in labels:
        w = max(0.6, len(label) * size * 0.0082 + pad)
        chip(slide, cx, y, w, h, label, fill=fill, color=color, size=size,
             line=line, font=font)
        cx += w + gap
    return cx


def header(slide, section, title, subtitle=None, number=None):
    rect(slide, MARGIN, 0.47, 0.11, 0.11, fill=ACCENT)
    box = add_text(slide, MARGIN + 0.2, 0.40, 8.5, 0.26,
                   [P(section.upper(), size=9.5, bold=True, color=ACCENT)], gap=0)
    for run in box.text_frame.paragraphs[0].runs:
        tracking(run, 120)
    add_text(slide, MARGIN, 0.66, CONTENT_W, 0.62,
             [P(title, size=29, bold=True, color=NAVY, line=0.95)], gap=0)
    if subtitle:
        add_text(slide, MARGIN, 1.24, CONTENT_W, 0.3,
                 [P(subtitle, size=12.5, color=MUTED)], gap=0)
    rule_y = 1.66 if subtitle else 1.56
    rect(slide, MARGIN, rule_y, CONTENT_W, 0.012, fill=BORDER)
    if number is not None:
        add_text(slide, MARGIN, 7.10, 8.0, 0.22,
                 [P("GAMA Mobile  ·  Technical deep dive  ·  v0.3.0  ·  November 2026",
                    size=8.5, color=MUTED)], gap=0)
        add_text(slide, SLIDE_W - MARGIN - 0.6, 7.10, 0.6, 0.22,
                 [P(f"{number:02d}", size=8.5, bold=True, color=MUTED,
                    align=PP_ALIGN.RIGHT)], gap=0)
    return rule_y + 0.16


def card(slide, x, y, w, h, fill=CARD, line=BORDER, radius=0.055):
    return rect(slide, x, y, w, h, fill=fill, line=line, radius=radius)


def card_with_title(slide, x, y, w, h, title, items, *, title_color=NAVY,
                    body_size=12, fill=CARD, line=BORDER, accent=ACCENT,
                    pad=0.22, title_size=13, gap=7):
    card(slide, x, y, w, h, fill=fill, line=line)
    rect(slide, x + pad, y + 0.20, 0.30, 0.035, fill=accent)
    add_text(slide, x + pad, y + 0.29, w - 2 * pad, 0.28,
             [P(title, size=title_size, bold=True, color=title_color)], gap=0)
    add_text(slide, x + pad, y + 0.66, w - 2 * pad, h - 0.82, items,
             size=body_size, gap=gap)


def arrow(slide, x, y, w=0.34, h=0.22, color=ACCENT_LIGHT):
    shp = rect(slide, x, y, w, h, fill=color, line=None, radius=0.25)
    return shp


def down_arrow(slide, x, y, w=0.2, h=0.24, color=ACCENT_LIGHT):
    shp = slide.shapes.add_shape(MSO_SHAPE.DOWN_ARROW, Inches(x), Inches(y),
                                 Inches(w), Inches(h))
    shp.fill.solid()
    shp.fill.fore_color.rgb = color
    shp.line.fill.background()
    no_shadow(shp)
    return shp


def table(slide, x, y, widths, rows, *, row_h=0.42, head_h=0.38,
          size=11, head_size=10.5, head_fill=NAVY, first_col_bold=False,
          col_colors=None, col_fonts=None, align=None):
    n_rows, n_cols = len(rows), len(widths)
    total_w = sum(widths)
    frame = slide.shapes.add_table(n_rows, n_cols, Inches(x), Inches(y),
                                   Inches(total_w), Inches(head_h + row_h * (n_rows - 1)))
    tbl = frame.table
    tblPr = tbl._tbl.tblPr
    tblPr.set("firstRow", "0")
    tblPr.set("bandRow", "0")
    for el in tblPr.findall(qn("a:tableStyleId")):
        tblPr.remove(el)
    style = etree.SubElement(tblPr, qn("a:tableStyleId"))
    style.text = "{2D5ABB26-0587-4C30-8999-92F81FD0307C}"  # No Style, No Grid

    for i, w in enumerate(widths):
        tbl.columns[i].width = Inches(w)
    for r in range(n_rows):
        tbl.rows[r].height = Inches(head_h if r == 0 else row_h)

    for r, row in enumerate(rows):
        is_head = (r == 0)
        for c, value in enumerate(row):
            cell = tbl.cell(r, c)
            cell.margin_left = Inches(0.14)
            cell.margin_right = Inches(0.10)
            cell.margin_top = Inches(0.04)
            cell.margin_bottom = Inches(0.04)
            cell.vertical_anchor = MSO_ANCHOR.MIDDLE
            cell.fill.solid()
            if is_head:
                cell.fill.fore_color.rgb = head_fill
            else:
                cell.fill.fore_color.rgb = CARD if r % 2 == 1 else SOFT

            tf = cell.text_frame
            tf.word_wrap = True
            p = tf.paragraphs[0]
            p.alignment = (align[c] if align else
                           (PP_ALIGN.LEFT if c == 0 else PP_ALIGN.LEFT))
            run = p.add_run()
            run.text = str(value)
            run.font.size = Pt(head_size if is_head else size)
            run.font.name = (col_fonts[c] if col_fonts else BODY_FONT)
            run.font.bold = is_head or (first_col_bold and c == 0)
            if is_head:
                run.font.color.rgb = WHITE
            elif col_colors and col_colors[c] is not None:
                run.font.color.rgb = col_colors[c]
            else:
                run.font.color.rgb = INK if c == 0 else MUTED
    return tbl


def notes(slide, text):
    slide.notes_slide.notes_text_frame.text = text


def new_slide(prs):
    return prs.slides.add_slide(prs.slide_layouts[6])


# ── slides ───────────────────────────────────────────────────────────
def slide_title(prs):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = NAVY

    # decorative geometry (behind the text)
    halo = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(9.4), Inches(-1.7),
                              Inches(6.4), Inches(6.4))
    halo.fill.solid()
    halo.fill.fore_color.rgb = NAVY_SOFT
    halo.line.fill.background()
    no_shadow(halo)

    halo2 = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(11.2), Inches(4.6),
                               Inches(3.6), Inches(3.6))
    halo2.fill.solid()
    halo2.fill.fore_color.rgb = RGBColor(0x10, 0x27, 0x47)
    halo2.line.fill.background()
    no_shadow(halo2)

    # wordmark
    mark = rect(s, MARGIN, 0.62, 0.42, 0.42, fill=ACCENT, radius=0.22)
    shape_text(mark, "G", size=20, color=WHITE, bold=True, font=TITLE_FONT)
    add_text(s, MARGIN + 0.58, 0.68, 4.0, 0.3,
             [P("GAMA MOBILE", size=11, bold=True, color=WHITE)], gap=0)
    for run in s.shapes[-1].text_frame.paragraphs[0].runs:
        tracking(run, 220)

    add_text(s, MARGIN, 1.95, 10.4, 0.3,
             [P("TECHNICAL DEEP DIVE  ·  NOVEMBER 2026", size=11.5, bold=True,
                color=TEAL_LIGHT)], gap=0)

    add_text(s, MARGIN - 0.02, 2.28, 11.2, 1.60,
             [P("The full GAMA IDE,", size=48, bold=True, color=WHITE, line=0.92),
              P("running on Android.", size=48, bold=True, color=WHITE, line=0.92)], gap=0)

    add_text(s, MARGIN, 4.18, 8.9, 0.9,
             [P("No root, no cloud, no headless shortcut — a real Ubuntu userland, "
                "a real X server and the untouched Eclipse workbench, wrapped in a "
                "22 MB app.", size=15, color=RGBColor(0xC3, 0xD0, 0xE2), line=1.28)],
             gap=0)

    labels = ["Full Eclipse GUI", "Offline-first", "ARM64 · PRoot", "v0.3.0 · build 22"]
    cx = MARGIN
    for label in labels:
        w = len(label) * 0.082 + 0.42
        b = rect(s, cx, 5.30, w, 0.40, fill=NAVY_SOFT, line=RGBColor(0x27, 0x46, 0x75),
                 radius=0.5)
        shape_text(b, label, size=10.5, color=RGBColor(0xDD, 0xE7, 0xF5), bold=True)
        cx += w + 0.14

    rect(s, MARGIN, 6.42, CONTENT_W, 0.012, fill=RGBColor(0x25, 0x3E, 0x62))
    add_text(s, MARGIN, 6.60, 8.0, 0.28,
             [P("github.com/hqnghi88/agama   ·   com.simulation.mobile",
                size=10.5, color=RGBColor(0x9F, 0xB3, 0xCD))], gap=0)
    add_text(s, SLIDE_W - MARGIN - 3.6, 6.60, 3.6, 0.28,
             [P("React Native  ·  Kotlin  ·  PRoot  ·  JOGL 2.6.0",
                size=10.5, color=RGBColor(0x9F, 0xB3, 0xCD), align=PP_ALIGN.RIGHT)], gap=0)

    notes(s, "One-line pitch: GAMA Mobile runs the complete GAMA desktop IDE on an "
             "ordinary Android phone. Everything in this deck was shipped in v0.3.0.")


def slide_agenda(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "Agenda", "What this talk covers", number=number)

    items = [
        ("01", "Context & goals", "Why a desktop modelling IDE has to run on a phone"),
        ("02", "Product & experience", "What the user sees, from install to a running model"),
        ("03", "Architecture", "Five layers from React Native down to Eclipse RCP"),
        ("04", "The hard parts", "PRoot, X11, software OpenGL and the JOGL mismatch"),
        ("05", "Build & release", "One command, reproducible rootfs, signed APK + AAB"),
        ("06", "Verification", "What was actually tested, and what it costs in practice"),
        ("07", "Roadmap", "Store rollout, GPU research, audio and updates"),
        ("08", "Takeaways", "Lessons that transfer to any Linux-on-Android project"),
    ]
    col_x = [MARGIN, MARGIN + 6.15]
    for i, (num, title, desc) in enumerate(items):
        x = col_x[i % 2]
        y = 1.94 + (i // 2) * 1.22
        add_text(s, x, y, 0.6, 0.4, [P(num, size=15, bold=True, color=ACCENT)], gap=0)
        add_text(s, x + 0.62, y - 0.02, 5.35, 0.3,
                 [P(title, size=14, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.62, y + 0.31, 5.35, 0.34,
                 [P(desc, size=11.5, color=MUTED)], gap=0)
        if i // 2 < 3:
            rect(s, x + 0.62, y + 0.86, 5.2, 0.008, fill=BORDER)

    notes(s, "Eight sections, roughly 25-30 minutes, with the JOGL bug as the "
             "technical centerpiece.")


def slide_context(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "01 · Context", "GAMA was built for a workstation",
           "A full RCP product, a JVM, a workspace and an OpenGL display — none of which Android offers out of the box.",
           number=number)

    card_with_title(s, MARGIN, 1.94, 5.95, 2.55, "GAMA in its natural habitat", [
        P("Eclipse RCP desktop product on Java 25", bullet=True),
        P("GAML editors, experiment control, multi-scale simulation", bullet=True),
        P("3D displays through JOGL / OpenGL", bullet=True),
        P("Stateful workspace: projects, models, preferences", bullet=True),
    ])
    card_with_title(s, MARGIN + 6.14, 1.94, 5.95, 2.55, "The gap on mobile", [
        P("Fieldwork and teaching need the IDE, not a results viewer", bullet=True),
        P("Carrying a laptop defeats the point of a phone in the field", bullet=True),
        P("No Android port, no web build of the workbench", bullet=True),
        P("Headless mode removes exactly what users need: the GUI", bullet=True),
    ], accent=AMBER)

    rect(s, MARGIN, 4.78, CONTENT_W, 1.05, fill=RGBColor(0xE8, 0xF0, 0xFE),
         line=RGBColor(0xBF, 0xD3, 0xFA), radius=0.06)
    add_text(s, MARGIN + 0.28, 4.94, 0.06, 0.72, [P("", size=1)], gap=0)
    rect(s, MARGIN + 0.28, 4.94, 0.045, 0.72, fill=ACCENT)
    add_text(s, MARGIN + 0.48, 4.94, CONTENT_W - 0.7, 0.75,
             [P("So we brought the desktop with us: ", size=14.5, bold=True, color=NAVY),
              P("a Linux userland, an X server and the untouched GAMA product, inside a normal sandboxed Android app.",
                size=14.5, color=INK)], gap=0, line=1.25)

    add_text(s, MARGIN, 6.12, CONTENT_W, 0.5,
             [P("Design constraint: no root, no custom ROM, no host kernel patches — everything had to work on stock Android 8.0+.",
                size=11, italic=True, color=MUTED)], gap=0)

    notes(s, "GAMA is an Eclipse RCP app. Android gives us a JVM but no X11, no glibc "
             "userland and no desktop GL, so the only honest answer was to ship a Linux userland.")


def slide_product(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "02 · Product", "What the app actually is",
           "Three numbers explain the architecture better than any diagram.",
           number=number)

    stats = [
        ("22 MB", "install size", "APK and AAB, no rootfs inside", ACCENT),
        ("790 MB", "Linux userland", "downloaded once on first launch", TEAL),
        ("100 %", "of the workbench", "Navigator, editors, console, 3D", AMBER),
    ]
    w = (CONTENT_W - 2 * 0.24) / 3
    for i, (big, label, sub, color) in enumerate(stats):
        x = MARGIN + i * (w + 0.24)
        card(s, x, 1.94, w, 1.62)
        rect(s, x, 1.94, w, 0.055, fill=color, radius=0.5)
        add_text(s, x + 0.26, 2.14, w - 0.5, 0.55,
                 [P(big, size=34, bold=True, color=NAVY, line=0.95)], gap=0)
        add_text(s, x + 0.26, 2.76, w - 0.5, 0.26,
                 [P(label.upper(), size=9.5, bold=True, color=color)], gap=0)
        add_text(s, x + 0.26, 3.03, w - 0.5, 0.3,
                 [P(sub, size=11, color=MUTED)], gap=0)

    items = [
        P(lead="Runtime — ", text="Ubuntu 24.04 ARM64 under PRoot: ptrace-based syscall "
          "translation in userspace, with no root and no custom kernel."),
        P(lead="Guest — ", text="the real ./Gama product, launched exactly as on a desktop, "
          "with OSGi workspace locking relaxed for the sandbox."),
        P(lead="Shell — ", text="React Native 0.74 plus a Kotlin foreground service for "
          "bootstrap, health checks, downloads, VNC proxying and settings."),
    ]
    add_text(s, MARGIN + 0.05, 3.94, CONTENT_W - 0.1, 1.9, items, size=13, gap=13)

    add_text(s, MARGIN, 5.62, CONTENT_W, 0.9,
             [P("Deliberate non-goal: reimplementing GAMA for mobile. ", size=12.5, bold=True, color=NAVY),
              P("A native rewrite would fork the product. Shipping the real binary keeps every model, "
                "plugin and experiment valid by construction.", size=12.5, color=MUTED)],
             gap=0, line=1.3)

    notes(s, "The app is a runtime, not a port. Size split matters: 22 MB store artifact, "
             "790 MB fetched on first run, so Play size limits are never the constraint.")


def slide_journey(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "02 · Experience", "From install to a running model",
           "First run is the only slow one; after that the workspace is local and persistent.",
           number=number)

    steps = [
        ("01", "Install", "22 MB APK from GitHub or Play. No Play Services, no account."),
        ("02", "Bootstrap", "Download the 790 MB rootfs with progress, extract to app storage."),
        ("03", "Boot", "PRoot, Xvfb, x11vnc, Openbox. 2–4 min cold on an emulator."),
        ("04", "Work", "Full desktop gestures, on-screen keyboard, GAMA workbench."),
        ("05", "Iterate", "Projects live in app data; warm restarts skip the download."),
    ]
    w = (CONTENT_W - 4 * 0.20) / 5
    for i, (num, title, desc) in enumerate(steps):
        x = MARGIN + i * (w + 0.20)
        card(s, x, 1.96, w, 3.20)
        add_text(s, x + 0.20, 2.12, w - 0.34, 0.32,
                 [P(num, size=12, bold=True, color=ACCENT)], gap=0)
        add_text(s, x + 0.20, 2.44, w - 0.34, 0.3,
                 [P(title, size=15, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.20, 2.82, w - 0.34, 2.1,
                 [P(desc, size=11.5, color=MUTED, line=1.22)], gap=0)
        if i < 4:
            arrow(s, x + w + 0.015, 3.42, 0.17, 0.2)

    rect(s, MARGIN, 5.48, CONTENT_W, 0.86, fill=SOFT, line=BORDER, radius=0.05)
    add_text(s, MARGIN + 0.26, 5.62, 3.2, 0.55,
             [P("Measured on a Pixel-class AVD", size=11, bold=True, color=NAVY),
              P("llvmpipe, 960 × 540 guest", size=10.5, color=MUTED)], gap=1)
    add_text(s, MARGIN + 3.65, 5.63, CONTENT_W - 3.9, 0.55,
             [P("Cold boot 2–4 min  ·  warm restart 60–90 s  ·  3D usable at 960 × 540",
                size=12.5, color=INK)], gap=0)
    add_text(s, MARGIN, 6.48, CONTENT_W, 0.3,
             [P("The UI never blocks on a fixed timeout: it polls the guest and shows real pipeline stages.",
                size=10.5, italic=True, color=MUTED)], gap=0)

    notes(s, "Set expectations up front: the first boot is minutes, not seconds. "
             "Everything after that is a warm start with a persistent workspace.")


def slide_architecture(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "03 · Architecture", "Five layers, one process tree",
           "Each layer has a single job and a narrow contract with the layer below.",
           number=number)

    rows = [
        ("APP", "React Native 0.74", [
            ("Dashboard", "status · progress · models"),
            ("VNC surface", "native view + noVNC"),
            ("Settings", "workspace · logs"),
        ]),
        ("ANDROID", "Kotlin foreground service", [
            ("SimulationService", "orchestration"),
            ("PRootManager", "rootfs · exec"),
            ("HTTP proxy :8080", "→ bridge :8081"),
            ("VNC proxy :6080", "→ x11vnc :5901"),
        ]),
        ("CONTAINER", "PRoot · userspace", [
            ("libproot.so", "ptrace syscall translation"),
            ("Ubuntu 24.04", "ARM64 userland"),
            ("bind mounts", "/proc /dev /data"),
        ]),
        ("GUEST", "X11 session + GAMA", [
            ("Xvfb :0", "960×540 · GLX"),
            ("x11vnc :5901", "RFB stream"),
            ("Openbox", "window manager"),
            ("bridge :8081", "REST + frames"),
            ("GAMA ./Gama", "Eclipse RCP"),
        ]),
    ]

    y = 1.88
    for label, sub, boxes in rows:
        band_h = 0.94
        rect(s, MARGIN, y, CONTENT_W, band_h, fill=SOFT if label != "GUEST" else RGBColor(0xE7, 0xF5, 0xF2),
             line=BORDER, radius=0.04)
        lab = rect(s, MARGIN, y, 1.72, band_h, fill=NAVY, line=None, radius=0.04)
        lab.text_frame.margin_left = Inches(0.16)
        lab.text_frame.margin_right = Inches(0.06)
        p = lab.text_frame.paragraphs[0]
        p.alignment = PP_ALIGN.LEFT
        r = p.add_run()
        r.text = label
        r.font.size = Pt(11)
        r.font.bold = True
        r.font.name = BODY_FONT
        r.font.color.rgb = WHITE
        tracking(r, 100)
        p2 = lab.text_frame.add_paragraph()
        p2.alignment = PP_ALIGN.LEFT
        r2 = p2.add_run()
        r2.text = sub
        r2.font.size = Pt(8.5)
        r2.font.name = BODY_FONT
        r2.font.color.rgb = RGBColor(0xB6, 0xC6, 0xDD)

        inner_x = MARGIN + 1.86
        inner_w = CONTENT_W - 1.96
        n = len(boxes)
        gap = 0.10
        bw = (inner_w - gap * (n - 1)) / n
        for i, (b_title, b_sub) in enumerate(boxes):
            bx = inner_x + i * (bw + gap)
            b = rect(s, bx, y + 0.12, bw, band_h - 0.24, fill=CARD, line=BORDER, radius=0.05)
            add_text(s, bx + 0.10, y + 0.18, bw - 0.18, 0.26,
                     [P(b_title, size=9.5 if n > 3 else 10.5, bold=True, color=NAVY)], gap=0)
            add_text(s, bx + 0.10, y + 0.45, bw - 0.18, 0.36,
                     [P(b_sub, size=8 if n > 3 else 9, color=MUTED)], gap=0)
        y += band_h + 0.12

        if label in ("APP", "ANDROID", "CONTAINER"):
            down_arrow(s, SLIDE_W - MARGIN + 0.10, y - 0.12, 0.16, 0.12)

    add_text(s, MARGIN, 6.28, CONTENT_W, 0.55,
             [P("Pixel transport: ", size=11, bold=True, color=NAVY),
              P("the app never embeds GAMA rendering code — it renders a VNC stream, so any GAMA view "
                "(2D, 3D, charts, editors) works without a single Android-specific widget.",
                size=11, color=MUTED)], gap=0, line=1.25)

    notes(s, "Key design rule: Android code knows nothing about GAMA. It knows how to start a "
             "container, check health and display a VNC stream.")


def slide_boot(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "03 · Lifecycle", "Boot sequence, step by step",
           "Order matters: every layer assumes the one before it is already healthy.",
           number=number)

    steps = [
        ("1", "Foreground service", "SimulationService starts with a notification; process is kept alive."),
        ("2", "Rootfs ready", "Download 790 MB with progress, or reuse an extracted tree."),
        ("3", "Natives", "8 JOGL 2.6.0 .so files copied from APK assets."),
        ("4", "PRoot exec", "Bind /proc, /dev, app data; run /opt/gama/startup.sh."),
        ("5", "Xvfb", "960×540×24, GLX/RENDER/COMPOSITE, keymap pre-seeded."),
        ("6", "x11vnc", "-noshm -noxdamage on :5901, wrapped in a relaunch loop."),
        ("7", "Openbox", "Window manager, desktop session, keyboard handling."),
        ("8", "GAMA", "./Gama with OSGi locking off; keep-alive restarts it if it dies."),
    ]
    for i, (num, title, desc) in enumerate(steps):
        col = i % 2
        row = i // 2
        x = MARGIN + col * 6.15
        y = 1.92 + row * 1.24
        circ = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(x), Inches(y + 0.02),
                                  Inches(0.38), Inches(0.38))
        circ.fill.solid()
        circ.fill.fore_color.rgb = ACCENT
        circ.line.fill.background()
        no_shadow(circ)
        shape_text(circ, num, size=11, color=WHITE, bold=True)
        add_text(s, x + 0.54, y, 5.3, 0.28,
                 [P(title, size=13.5, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.54, y + 0.31, 5.3, 0.55,
                 [P(desc, size=11.3, color=MUTED, line=1.18)], gap=0)

    rect(s, MARGIN, 6.42, CONTENT_W, 0.42, fill=SOFT, line=BORDER, radius=0.05)
    add_text(s, MARGIN + 0.2, 6.50, CONTENT_W - 0.4, 0.28,
             [P("Guard rails: stale Xvfb/x11vnc processes are killed, X locks removed and ports re-checked on every boot.",
                size=10.5, color=INK)], gap=0)

    notes(s, "The keep-alive loop is what makes the app feel like an appliance: if GAMA "
             "crashes, the desktop and bridge stay up and the product relaunches.")


def slide_constraints(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "04 · PRoot", "Android is not Linux — here is what breaks",
           "Every row below was a real failure during bring-up, not a theoretical concern.",
           number=number)

    rows = [
        ["Constraint", "Why it breaks", "What we do instead"],
        ["No root, no namespaces", "Sandboxed app, no CAP_SYS_ADMIN",
         "PRoot translates syscalls with ptrace + seccomp"],
        ["Hard links fail", "link() cannot be emulated across the sandbox",
         "override_link.so LD_PRELOAD shim for Xvfb and GAMA"],
        ["No /dev/dri, no GPU driver", "Mesa needs a DRM node to load",
         "Create /dev/dri, force llvmpipe, override GL 3.3"],
        ["POSIX shared memory missing", "Xvfb/x11vnc assume /dev/shm semantics",
         "-noshm for x11vnc, /dev/shm prepared with permissive modes"],
        ["Stale servers after a crash", "Relaunch loops pile up Xvfb/x11vnc",
         "pkill guards, X lock cleanup, single-instance checks"],
    ]
    table(s, MARGIN, 1.90, [2.55, 4.55, 4.99], rows, row_h=0.72, head_h=0.40,
          size=10.8, head_size=10.2, first_col_bold=True)

    add_text(s, MARGIN, 6.20, CONTENT_W, 0.6,
             [P("Rule of thumb: ", size=11.5, bold=True, color=NAVY),
              P("if a desktop daemon assumes a kernel feature, assume it is missing on Android and "
                "provide a userspace shim or a documented fallback.", size=11.5, color=MUTED)],
             gap=0, line=1.25)

    notes(s, "PRoot is not a VM: it is the same kernel, with syscalls rewritten. That is why "
             "there is no GPU passthrough and why hard links need a shim.")


def slide_x11(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "04 · Display", "Two paths through the same X server",
           "Rendering stays in the guest; only pixels cross into Android.",
           number=number)

    add_text(s, MARGIN, 1.86, 6.6, 0.24,
             [P("RENDERING PATH", size=9.5, bold=True, color=ACCENT)], gap=0)
    boxes_a = [("Xvfb :0", "960×540×24\nGLX · RENDER · COMPOSITE"),
               ("Openbox", "window manager\nsession"),
               ("GAMA / SWT", "./Gama\nEclipse RCP")]
    w = 2.55
    x = MARGIN
    for i, (t, sub) in enumerate(boxes_a):
        b = rect(s, x, 2.14, w, 0.98, fill=CARD, line=BORDER, radius=0.05)
        add_text(s, x + 0.16, 2.26, w - 0.28, 0.28, [P(t, size=12, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.16, 2.56, w - 0.28, 0.48, [P(sub, size=9.5, color=MUTED)], gap=0)
        if i < 2:
            arrow(s, x + w + 0.08, 2.52, 0.26, 0.2)
        x += w + 0.42

    add_text(s, MARGIN, 3.34, 6.6, 0.24,
             [P("TRANSPORT PATH", size=9.5, bold=True, color=TEAL)], gap=0)
    boxes_b = [("Xvfb :0", "framebuffer"),
               ("x11vnc :5901", "RFB · -noshm"),
               ("VncProxy :6080", "noVNC + WS"),
               ("Android view", "native / WebView")]
    bw = 2.74
    x = MARGIN
    for i, (t, sub) in enumerate(boxes_b):
        b = rect(s, x, 3.62, bw, 0.92, fill=CARD, line=BORDER, radius=0.05)
        add_text(s, x + 0.14, 3.72, bw - 0.24, 0.26,
                 [P(t, size=10.5, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.14, 3.99, bw - 0.24, 0.4,
                 [P(sub, size=9, color=MUTED)], gap=0)
        if i < 3:
            arrow(s, x + bw + 0.04, 3.97, 0.2, 0.18, color=TEAL_LIGHT)
        x += bw + 0.28

    notes_items = [
        ("Patched Xvfb", "In-process xkbcomp bypassed with a pre-placed /var/lib/xkb/server-0.xkm, re-copied every boot."),
        ("Hard-link shim", "override_link.so is preloaded for Xvfb and GAMA; PRoot cannot emulate link() reliably."),
        ("Why -noshm", "POSIX shared memory is unavailable under PRoot; x11vnc falls back to a socket transport."),
        ("Restart safety", "/tmp/.X0-lock and /tmp/.X11-unix/X0 are cleared, so a killed session never blocks the next boot."),
    ]
    w2 = (CONTENT_W - 0.24) / 2
    for i, (t, d) in enumerate(notes_items):
        x = MARGIN + (i % 2) * (w2 + 0.24)
        y = 4.78 + (i // 2) * 0.98
        add_text(s, x, y, 0.16, 0.2, [P("—", size=11, bold=True, color=ACCENT)], gap=0)
        add_text(s, x + 0.2, y - 0.02, w2 - 0.2, 0.26, [P(t, size=11, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.2, y + 0.25, w2 - 0.2, 0.6,
                 [P(d, size=10.5, color=MUTED, line=1.18)], gap=0)

    notes(s, "The app is a VNC client. That is the single most important decision in the "
             "project: it decouples GAMA's UI stack from Android entirely.")


def slide_graphics(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "04 · Graphics", "Software OpenGL is the only honest option",
           "Turnip/Zink need MSM DRM and Android's hwservicemanager — neither exists inside PRoot.",
           number=number)

    chain = [("GAMA · JOGL", "glXChooseFBConfig\nGL 2 / GLSL"),
             ("GLX 1.4", "Xvfb + GLX\nextension"),
             ("Mesa llvmpipe", "CPU rasterizer\nGL 3.3"),
             ("Xvfb buffer", "960×540×24\nframebuffer")]
    w = 2.72
    x = MARGIN
    for i, (t, sub) in enumerate(chain):
        col = CARD if i < 2 else RGBColor(0xE8, 0xF5, 0xF1)
        b = rect(s, x, 2.02, w, 1.05, fill=col, line=BORDER, radius=0.05)
        add_text(s, x + 0.16, 2.14, w - 0.28, 0.3, [P(t, size=12, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.16, 2.47, w - 0.28, 0.5, [P(sub, size=9.5, color=MUTED)], gap=0)
        if i < 3:
            arrow(s, x + w + 0.055, 2.44, 0.2, 0.2)
        x += w + 0.30

    add_text(s, MARGIN, 3.44, CONTENT_W, 0.3,
             [P("Environment contract enforced in startup.sh", size=11.5, bold=True, color=NAVY)], gap=0)

    env = ["GALLIUM_DRIVER=llvmpipe", "LIBGL_ALWAYS_SOFTWARE=1",
           "MESA_GL_VERSION_OVERRIDE=3.3", "MESA_GLSL_VERSION_OVERRIDE=330",
           "MESA_LOADER_DRIVER_OVERRIDE=llvmpipe"]
    card(s, MARGIN, 3.78, 6.45, 1.85)
    yy = 3.94
    for e in env:
        add_text(s, MARGIN + 0.22, yy, 6.1, 0.24,
                 [P(e, size=9.8, color=INK, font=MONO_FONT)], gap=0)
        yy += 0.30

    card_with_title(s, MARGIN + 6.66, 3.78, 5.43, 1.85, "JOGL flags that matter", [
        P("-Djogamp.gluegen.UseTempJarCache=false", font=MONO_FONT, size=9.2),
        P("-Djogamp.opengl.GLContext.nativeGL2=1", font=MONO_FONT, size=9.2),
        P("-Djava.awt.headless=false", font=MONO_FONT, size=9.2),
        P("never set -Dnativewindow.ws.name=x11", font=MONO_FONT, size=9.2, color=RED),
    ], body_size=9.2, gap=6)

    add_text(s, MARGIN, 5.86, CONTENT_W, 0.9,
             [P("Cost: ", size=12, bold=True, color=AMBER),
              P("every pixel is rasterized on the CPU. Resolution is the dominant lever, not CPU cores — "
                "which is why the guest defaults to 960 × 540 and scales up only when asked.",
                size=12, color=INK)], gap=0, line=1.28)

    notes(s, "There is no GPU path under PRoot today. The 3.3 override exists because llvmpipe "
             "reports a lower version than JOGL expects; it is a compatibility shim, not a performance trick.")


def slide_jogl(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "04 · Root cause", "The bug that blocked 3D for weeks",
           "JOGL's Java jars and its native libraries had drifted apart by five years.",
           number=number)

    rows = [
        ["Symptom", "Root cause", "Fix"],
        ["\"Native graphics initialization failed\" at startup",
         "GAMA's plugin shipped 2021-era aarch64 natives alongside JOGL 2.6.0 Java classes",
         "Replace the native set with real 2.6.0 aarch64 builds from Maven Central"],
        ["UnsatisfiedLinkError: dladdr_1fname",
         "JNI entry point absent from the old libgluegen_rt.so",
         "libgluegen_rt.so 2.6.0 (13.7 KB)"],
        ["SIGSEGV inside glXChooseFBConfig",
         "getSizeOfPointerImpl missing → pointer size read as garbage",
         "libjogl_desktop.so 2.6.0 (862 KB) + correct libjogl_mobile.so"],
    ]
    table(s, MARGIN, 1.90, [3.55, 4.75, 3.79], rows, row_h=0.80, head_h=0.40,
          size=10.3, head_size=10.2, first_col_bold=True)

    rect(s, MARGIN, 5.02, 6.0, 1.32, fill=RGBColor(0xFD, 0xF3, 0xE7),
         line=RGBColor(0xF3, 0xD3, 0xA8), radius=0.05)
    add_text(s, MARGIN + 0.24, 5.14, 5.5, 0.28,
             [P("The dead end we abandoned", size=11.5, bold=True, color=AMBER)], gap=0)
    add_text(s, MARGIN + 0.24, 5.44, 5.5, 0.8,
             [P("Patching gluegen bytecode with ASM produced VerifyError: the injected try/catch "
                "broke stack-map frames. Shipping the right binaries is simpler and correct.",
                size=10.5, color=INK, line=1.22)], gap=0)

    rect(s, MARGIN + 6.28, 5.02, 5.81, 1.32, fill=RGBColor(0xE9, 0xF6, 0xF3),
         line=RGBColor(0xBF, 0xE3, 0xDB), radius=0.05)
    add_text(s, MARGIN + 6.52, 5.14, 5.3, 0.28,
             [P("Why it was hard to see", size=11.5, bold=True, color=TEAL)], gap=0)
    add_text(s, MARGIN + 6.52, 5.44, 5.3, 0.8,
             [P("The Java side loaded fine and the workbench opened. Only the 3D view died, so the "
                "failure looked like a display bug, not an ABI mismatch.",
                size=10.5, color=INK, line=1.22)], gap=0)

    add_text(s, MARGIN, 6.55, CONTENT_W, 0.28,
             [P("Verification: nm -D on the shipped libraries shows the required JNI symbols, and the "
                "Stairs demo renders without the native-init error.", size=10, italic=True, color=MUTED)], gap=0)

    notes(s, "This is the centerpiece. The lesson is not 'patch harder' — it is that JNI "
             "requires exact version matching between jar and .so.")


def slide_natives(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "04 · Native delivery", "Getting the right .so files to the guest",
           "Bundled in the APK, installed on every rootfs setup, verified before GAMA starts.",
           number=number)

    steps = [
        ("1", "APK assets", "jogl_natives_aarch64 · 266 KB zip\n8 aarch64 libraries"),
        ("2", "setupRootfs()", "installJoglNatives() runs on every\nfirst launch and re-init"),
        ("3", "Guest path", "/opt/gama/natives/linux-aarch64\nchmod 755 on every file"),
        ("4", "JOGL loads", "GAMA_HOME/natives/<os>-<arch>\nresolved automatically"),
    ]
    w = (CONTENT_W - 3 * 0.42) / 4
    x = MARGIN
    for i, (num, t, d) in enumerate(steps):
        card(s, x, 1.94, w, 1.22)
        add_text(s, x + 0.16, 2.04, 0.3, 0.26, [P(num, size=11, bold=True, color=ACCENT)], gap=0)
        add_text(s, x + 0.16, 2.30, w - 0.3, 0.26, [P(t, size=12, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.16, 2.60, w - 0.3, 0.5, [P(d, size=9.2, color=MUTED)], gap=0)
        if i < 3:
            arrow(s, x + w + 0.07, 2.46, 0.28, 0.18)
        x += w + 0.42

    add_text(s, MARGIN, 3.42, 6.0, 0.28,
             [P("Shipped libraries (JOGL 2.6.0)", size=11.5, bold=True, color=NAVY)], gap=0)
    libs = ["libgluegen_rt.so", "libjogl_desktop.so", "libjogl_mobile.so",
            "libnativewindow_awt.so", "libnativewindow_drm.so",
            "libnativewindow_x11.so", "libnewt_drm.so", "libnewt_head.so"]
    card(s, MARGIN, 3.74, 6.05, 1.86)
    for i, lib in enumerate(libs):
        add_text(s, MARGIN + 0.2 + (i % 2) * 2.85, 3.88 + (i // 2) * 0.34, 2.7, 0.26,
                 [P(lib, size=9.5, color=INK, font=MONO_FONT)], gap=0)

    card_with_title(s, MARGIN + 6.28, 3.74, 5.81, 1.86, "Delivery rules", [
        P("assets/ is the source of truth; res/raw proved unreliable in packaging", bullet=True),
        P("startup.sh keeps a nested-jar extraction as a last-resort fallback", bullet=True),
        P("installation is idempotent and runs before every PRoot start", bullet=True),
    ], body_size=10.5, gap=7)

    add_text(s, MARGIN, 5.90, CONTENT_W, 0.6,
             [P("Why not rely on the rootfs alone? ", size=11.5, bold=True, color=NAVY),
              P("Rootfs archives are large, cached and occasionally stale. Tying natives to the "
                "APK version guarantees that a UI update can also fix a graphics regression.",
                size=11.5, color=MUTED)], gap=0, line=1.25)

    notes(s, "This is also the fix for a subtle class of bugs: an old extracted rootfs would "
             "otherwise pin old libraries forever.")


def slide_rootfs(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "04 · Runtime payload", "790 MB that must not live in the store bundle",
           "Build the userland once, publish it as a release asset, fetch it on first launch.",
           number=number)

    card(s, MARGIN, 1.94, 5.95, 3.55)
    add_text(s, MARGIN + 0.24, 2.08, 5.4, 0.28,
             [P("BUILD TIME  ·  Docker + QEMU", size=9.5, bold=True, color=ACCENT)], gap=0)
    build_items = [
        P("arm64v8/ubuntu:24.04 base image", bullet=True),
        P("JDK 25 (Temurin) + Mesa llvmpipe + Python 3 + websockets", bullet=True),
        P("GAMA product, bridge server, Xvfb patches, launcher scripts", bullet=True),
        P("Repacked as a ≈ 790 MB tar.gz", bullet=True),
    ]
    add_text(s, MARGIN + 0.24, 2.40, 5.4, 2.2, build_items, size=11.5, gap=8)
    add_text(s, MARGIN + 0.24, 4.62, 5.4, 0.7,
             [P("docker run --privileged multiarch/qemu-user-static\n./scripts/build-rootfs.sh",
                size=9.2, color=INK, font=MONO_FONT, line=1.35)], gap=0)

    card(s, MARGIN + 6.14, 1.94, 5.95, 3.55)
    add_text(s, MARGIN + 6.38, 2.08, 5.4, 0.28,
             [P("RUNTIME  ·  on device", size=9.5, bold=True, color=TEAL)], gap=0)
    run_items = [
        P("PRootManager.downloadOrExtractRootfs() with progress callbacks", bullet=True),
        P("Extract to app storage, fix permissions, remove AppleDouble files", bullet=True),
        P("Install JOGL natives, then start PRoot", bullet=True),
        P("Reuse the extracted tree on later launches", bullet=True),
    ]
    add_text(s, MARGIN + 6.38, 2.40, 5.4, 2.2, run_items, size=11.5, gap=8)
    add_text(s, MARGIN + 6.38, 4.62, 5.4, 0.7,
             [P("github.com/hqnghi88/agama/releases/download/rootfs/rootfs_tar_gz",
                size=8.8, color=INK, font=MONO_FONT, line=1.35)], gap=0)

    bars = [
        ("APK / AAB", "22 MB", 0.06, ACCENT),
        ("rootfs download", "≈ 790 MB", 0.62, TEAL),
        ("extracted footprint", "≈ 1.0 GB", 0.78, AMBER),
    ]
    y = 5.78
    for label, value, frac, color in bars:
        add_text(s, MARGIN, y, 2.05, 0.24, [P(label, size=10, color=MUTED)], gap=0)
        rect(s, MARGIN + 2.05, y + 0.05, 5.2, 0.14, fill=SOFT, radius=0.5)
        rect(s, MARGIN + 2.05, y + 0.05, 5.2 * frac, 0.14, fill=color, radius=0.5)
        add_text(s, MARGIN + 7.36, y - 0.01, 1.2, 0.24,
                 [P(value, size=10, bold=True, color=NAVY)], gap=0)
        y += 0.38

    add_text(s, MARGIN + 8.55, 5.78, 3.55, 1.1,
             [P("Trade-off accepted", size=10, bold=True, color=NAVY),
              P("First run needs network and free space. In exchange the store artifact stays small "
                "and the rootfs can be updated independently.", size=9.8, color=MUTED, line=1.2)], gap=2)

    notes(s, "Play's base module limit is why this split exists. Never re-add res/raw/rootfs_tar_gz "
             "to a publish build, or the AAB blows past the limit.")


def slide_ports(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "03 · Control plane", "Four ports, one loopback-only contract",
           "Every service binds to 127.0.0.1; nothing is reachable from the network.",
           number=number)

    rows = [
        ["Port", "Owner", "Purpose"],
        ["8080", "Kotlin HTTP proxy (FallbackHealthServer)",
         "Stable origin for the app; forwards to the bridge on 8081"],
        ["8081", "bridge-server.py in the guest",
         "/health, /status, /models, /run plus frame and console endpoints"],
        ["5901", "x11vnc in the guest",
         "Raw RFB desktop stream, -noshm -noxdamage, localhost only"],
        ["6080", "VncProxyServer on Android",
         "noVNC assets + WebSocket bridge for the in-app WebView"],
        ["6868", "GAMA headless (-socket)",
         "Optional WebSocket mode for scripted, UI-less simulations"],
    ]
    table(s, MARGIN, 1.90, [1.05, 4.05, 6.99], rows, row_h=0.60, head_h=0.40,
          size=10.8, head_size=10.2, first_col_bold=True,
          col_colors=[ACCENT, NAVY, MUTED], col_fonts=[MONO_FONT, BODY_FONT, BODY_FONT])

    add_text(s, MARGIN, 5.32, 6.0, 0.26,
             [P("Health-first polling", size=11.5, bold=True, color=NAVY)], gap=0)
    add_text(s, MARGIN, 5.62, 6.0, 0.9,
             [P("The UI asks /health with backoff and paints real pipeline stages. "
                "No fixed sleep, no assumption that a process means a ready desktop.",
                size=11, color=MUTED, line=1.25)], gap=0)

    add_text(s, MARGIN + 6.4, 5.32, 5.7, 0.26,
             [P("Why proxy 8080 → 8081 at all?", size=11.5, bold=True, color=NAVY)], gap=0)
    add_text(s, MARGIN + 6.4, 5.62, 5.7, 0.9,
             [P("The bridge can restart, and PRoot forwards ports differently across devices. "
                "One stable Android-side port keeps the JS client trivial.",
                size=11, color=MUTED, line=1.25)], gap=0)

    rect(s, MARGIN, 6.52, CONTENT_W, 0.012, fill=BORDER)

    notes(s, "Loopback-only also means a malicious app on the same device cannot reach the "
             "bridge through the network stack.")


def slide_frontend(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "02 · Frontend", "Thin client, honest status",
           "The React Native layer is intentionally small: start, watch, display, configure.",
           number=number)

    chip_row(s, MARGIN, 1.92,
             ["React Native 0.74.5", "React 18.2", "Hermes", "TypeScript",
              "Zustand", "WebView", "react-native-fs"],
             size=9.5, h=0.32, gap=0.10, fill=SOFT)

    rows = [
        ["Screen", "Responsibility", "Key detail"],
        ["Dashboard", "Service state, download progress, model list, console",
         "Polling with backoff; no fake completion"],
        ["VNC", "Desktop surface and input",
         "Native view first, noVNC in WebView as fallback"],
        ["Settings", "Workspace, resolution, logs",
         "Restart paths that do not require reinstalling"],
    ]
    table(s, MARGIN, 2.44, [1.85, 5.35, 4.89], rows, row_h=0.66, head_h=0.38,
          size=10.8, head_size=10.2, first_col_bold=True)

    items = [
        P("JS is bundled at build time (debuggableVariants = []), so there is no Metro server at runtime.",
          lead="No dev server: ", ),
        P("the same screens run in debug and release; screenshots and support match the shipped app.",
          lead="One code path: "),
        P("VNC keeps GAMA rendering out of the JS bridge; the app only moves bytes and gestures.",
          lead="No rendering bridge: "),
    ]
    add_text(s, MARGIN, 5.12, CONTENT_W, 1.2, items, size=11.8, gap=9)

    notes(s, "Keeping the JS layer thin is what makes the architecture maintainable: "
             "GAMA UI changes never require a React Native change.")


def slide_build(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "05 · Build", "One command, reproducible artifacts",
           "From a fresh checkout to a signed APK and AAB — including the rootfs decision.",
           number=number)

    steps = ["Check", "Deps", "Rootfs", "Gradle", "16 KB patch", "Publish"]
    w = 2.12
    x = MARGIN
    for i, label in enumerate(steps):
        shp = s.shapes.add_shape(MSO_SHAPE.CHEVRON, Inches(x), Inches(1.96),
                                 Inches(w), Inches(0.62))
        shades = [RGBColor(0x1E, 0x3A, 0x8A), RGBColor(0x1D, 0x4E, 0xD8),
                  ACCENT, RGBColor(0x3B, 0x82, 0xF6), RGBColor(0x60, 0xA5, 0xFA),
                  RGBColor(0x93, 0xC5, 0xFD)]
        shp.fill.solid()
        shp.fill.fore_color.rgb = shades[i]
        shp.line.fill.background()
        no_shadow(shp)
        shape_text(shp, label, size=10.5, color=WHITE if i < 5 else NAVY, bold=True)
        x += w - 0.14

    desc = [
        ("Toolchain gate", "JDK 25, Node ≥ 18, Docker, SDK 36"),
        ("npm + patch", "Lockfile install, RN Gradle plugin patch"),
        ("Prebuilt or Docker", "Rootfs from git, or rebuild via QEMU"),
        ("assemble + bundle", "Release APK and AAB in one run"),
        ("Play compliance", "p_align rewritten to 16 KB on all .so"),
        ("Upload", "GitHub release assets + rootfs tag"),
    ]
    for i, (t, d) in enumerate(desc):
        x = MARGIN + i * (2.12 - 0.14)
        add_text(s, x + 0.06, 2.68, 1.95, 0.26, [P(t, size=9.5, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.06, 2.94, 1.95, 0.6, [P(d, size=9, color=MUTED)], gap=0)

    card(s, MARGIN, 3.72, 7.35, 1.30, fill=RGBColor(0x0F, 0x17, 0x2A), line=None)
    add_text(s, MARGIN + 0.22, 3.84, 7.0, 1.05,
             [P("./scripts/build.sh all        # check → deps → rootfs → APK",
                size=9.8, color=RGBColor(0xE2, 0xE8, 0xF0), font=MONO_FONT),
              P("./scripts/build.sh install  # build + adb install",
                size=9.8, color=RGBColor(0xE2, 0xE8, 0xF0), font=MONO_FONT, space_before=6),
              P("JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home",
                size=9.8, color=TEAL_LIGHT, font=MONO_FONT, space_before=6)], gap=0)

    rows = [
        ["Component", "Version"],
        ["JDK (build)", "25 · Temurin"],
        ["Gradle / AGP", "9.1.0 / 8.13.2"],
        ["Kotlin / NDK", "2.1.21 / 27.1"],
        ["SDK / build-tools", "36 / 36.0.0"],
    ]
    table(s, MARGIN + 7.58, 3.72, [2.05, 2.46], rows, row_h=0.36, head_h=0.34,
          size=9.8, head_size=9.5, first_col_bold=True)

    add_text(s, MARGIN, 5.28, CONTENT_W, 0.9,
             [P("Signing is not a manual step: ", size=11.5, bold=True, color=NAVY),
              P("the release build reads the keystore from Documents and signs both artifacts with "
                "alias gama-release. The AAB is what goes to Play; the APK is for direct installs and testing.",
                size=11.5, color=MUTED)], gap=0, line=1.25)

    notes(s, "Reproducibility is the goal: a fresh machine runs one script. The only manual "
             "decision is whether to rebuild the rootfs.")


def slide_compliance(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "05 · Compliance", "Shipping on a 2026 Play policy",
           "Android 16 requirements collide with 2021-era binaries; both had to be handled.",
           number=number)

    items = [
        ("SDK 36", "targetSdk 36, minSdk 26",
         "Android 8.0+ covers the field devices we care about. Foreground service types and "
         "edge-to-edge behaviour are handled explicitly."),
        ("16 KB pages", "ELF alignment patch",
         "AGP 8.13 does not fix prebuilt RN .so files. patch_elf_16kb.py rewrites PT_LOAD "
         "p_align after every merge, which Play's checker verifies."),
        ("No root", "userspace only",
         "PRoot is a ptrace tracer inside the app sandbox: no su, no Magisk, no unlocked "
         "bootloader requirement, no dynamic code loading."),
        ("Store size", "22 MB artifact",
         "The rootfs is deliberately not in the AAB. Store builds must delete res/raw/rootfs_tar_gz "
         "or the bundle exceeds Play's limit."),
    ]
    w = (CONTENT_W - 0.24) / 2
    for i, (tag, sub, body) in enumerate(items):
        x = MARGIN + (i % 2) * (w + 0.24)
        y = 1.92 + (i // 2) * 2.30
        card(s, x, y, w, 2.10)
        add_text(s, x + 0.24, y + 0.18, w - 0.5, 0.34,
                 [P(tag, size=18, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.24, y + 0.56, w - 0.5, 0.26,
                 [P(sub, size=10, bold=True, color=ACCENT)], gap=0)
        add_text(s, x + 0.24, y + 0.92, w - 0.5, 1.0,
                 [P(body, size=11, color=MUTED, line=1.24)], gap=0)

    add_text(s, MARGIN, 6.60, CONTENT_W, 0.3,
             [P("Permissions requested: network (rootfs download), notifications and foreground service. No storage, contacts or location.",
                size=10, italic=True, color=MUTED)], gap=0)

    notes(s, "The 16 KB patch is the least obvious one. Without it the app installs on older "
             "devices and fails Play review for new submissions.")


def slide_verification(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "06 · Verification", "What was actually tested",
           "Release v0.3.0, installed as a non-debuggable build on a Pixel-class AVD.",
           number=number)

    rows = [
        ["Check", "Evidence", "Result"],
        ["Cold install", "APK installs; rootfs downloads and extracts", "Pass"],
        ["Container boot", "PRoot reaches the guest startup script", "Pass"],
        ["X / VNC", "Desktop renders; pointer and keyboard input work", "Pass"],
        ["GAMA workbench", "Full IDE loads; workspace is selected and remembered", "Pass"],
        ["Model", "GAML project opens; experiment console is live", "Pass"],
        ["3D display", "Stairs demo renders; no native-init error", "Pass"],
        ["Persistence", "Workspace and preferences survive restart", "Pass"],
        ["Release artifact", "Signed APK and AAB install and launch", "Pass"],
    ]
    tbl = table(s, MARGIN, 1.90, [2.15, 8.35, 1.59], rows, row_h=0.44, head_h=0.38,
                size=10.6, head_size=10.2, first_col_bold=True,
                col_colors=[NAVY, MUTED, TEAL])
    # emphasise the result column
    for r in range(1, len(rows)):
        cell = tbl.cell(r, 2)
        for p in cell.text_frame.paragraphs:
            for run in p.runs:
                run.font.bold = True

    add_text(s, MARGIN, 5.98, 3.0, 0.26,
             [P("Cold boot", size=9.5, bold=True, color=MUTED)], gap=0)
    add_text(s, MARGIN, 6.22, 3.0, 0.34,
             [P("2–4 min", size=18, bold=True, color=NAVY)], gap=0)
    add_text(s, MARGIN + 3.3, 5.98, 3.0, 0.26,
             [P("Warm restart", size=9.5, bold=True, color=MUTED)], gap=0)
    add_text(s, MARGIN + 3.3, 6.22, 3.0, 0.34,
             [P("60–90 s", size=18, bold=True, color=NAVY)], gap=0)
    add_text(s, MARGIN + 6.6, 5.98, 5.4, 0.26,
             [P("Guest display", size=9.5, bold=True, color=MUTED)], gap=0)
    add_text(s, MARGIN + 6.6, 6.22, 5.4, 0.34,
             [P("960 × 540, llvmpipe", size=18, bold=True, color=NAVY)], gap=0)

    notes(s, "Be honest about the gaps: large-model performance and multi-hour sessions were not "
             "benchmarked in this release; they are on the roadmap.")


def slide_performance(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "06 · Performance", "Set expectations, then tune the right knob",
           "This is a desktop IDE on a phone CPU. It is usable, not instant.",
           number=number)

    card_with_title(s, MARGIN, 1.92, 5.95, 3.12, "What dominates", [
        P("Cold start: rootfs extraction and JVM/Eclipse class loading", bullet=True),
        P("3D: CPU rasterization in llvmpipe, scaling with pixel count", bullet=True),
        P("Input latency: one RFB round trip; fine on localhost", bullet=True),
        P("Memory: the guest JVM is capped, GAMA defaults to 512 MB", bullet=True),
    ], body_size=11.5)

    card_with_title(s, MARGIN + 6.14, 1.92, 5.95, 3.12, "The knobs that actually help", [
        P("Lower guest resolution before touching anything else", bullet=True),
        P("G1GC with a 200 ms pause target, string deduplication on", bullet=True),
        P("-noshm and -noxdamage on x11vnc; disable desktop animations", bullet=True),
        P("Keep the rootfs warm; never re-extract on every launch", bullet=True),
    ], body_size=11.5, accent=TEAL)

    add_text(s, MARGIN, 5.28, 5.9, 0.26,
             [P("What does not work (yet)", size=11.5, bold=True, color=AMBER)], gap=0)
    add_text(s, MARGIN, 5.58, 5.9, 0.9,
             [P("GPU passthrough via Zink/Turnip needs MSM DRM plus Android's hwservicemanager, "
                "which PRoot cannot provide. Vulkan in the guest is research, not a shipping feature.",
                size=10.8, color=MUTED, line=1.24)], gap=0)

    add_text(s, MARGIN + 6.14, 5.28, 5.95, 0.26,
             [P("What surprised us", size=11.5, bold=True, color=TEAL)], gap=0)
    add_text(s, MARGIN + 6.14, 5.58, 5.95, 0.9,
             [P("Boot time is dominated by Eclipse, not by PRoot. Optimising the container barely "
                "moved the needle; trimming plugins and reusing the workspace did.",
                size=10.8, color=MUTED, line=1.24)], gap=0)

    notes(s, "Honest framing: the app is for real work, not for frame-rate demos. "
             "Resolution is the single biggest lever.")


def slide_roadmap(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "07 · Roadmap", "What ships next",
           "Short, concrete, and ordered by user impact rather than novelty.",
           number=number)

    cols = [
        ("NOW", "v0.3.x", ACCENT, [
            "Play rollout with the signed AAB",
            "Verify release signatures on every asset",
            "Rootfs delta / resume downloads",
            "Crash and boot-stage reporting",
        ]),
        ("NEXT", "v0.4", TEAL, [
            "Clipboard and file bridge (host ↔ guest)",
            "Audio via OpenSL ES in the guest",
            "Configurable resolution profiles",
            "Faster warm start and session restore",
        ]),
        ("LATER", "research", AMBER, [
            "Vulkan / Zink feasibility study",
            "Multi-window and external display",
            "Model marketplace and sharing",
            "Headless mode as a first-class option",
        ]),
    ]
    w = (CONTENT_W - 0.48) / 3
    for i, (tag, ver, color, items) in enumerate(cols):
        x = MARGIN + i * (w + 0.24)
        card(s, x, 1.94, w, 3.95)
        rect(s, x, 1.94, w, 0.06, fill=color, radius=0.5)
        add_text(s, x + 0.24, 2.14, w - 0.5, 0.26,
                 [P(tag, size=9.5, bold=True, color=color)], gap=0)
        for run in s.shapes[-1].text_frame.paragraphs[0].runs:
            tracking(run, 140)
        add_text(s, x + 0.24, 2.42, w - 0.5, 0.32,
                 [P(ver, size=17, bold=True, color=NAVY)], gap=0)
        yy = 2.92
        for item in items:
            add_text(s, x + 0.24, yy, w - 0.48, 0.62,
                     [P(item, size=10.8, color=INK, line=1.18)], gap=0)
            rect(s, x + 0.24, yy + 0.09, 0.07, 0.07, fill=color)
            yy += 0.70

    add_text(s, MARGIN, 6.24, CONTENT_W, 0.45,
             [P("Non-goal for v0.4: rewriting GAMA for mobile. The desktop product remains the "
                "source of truth; the app is the delivery vehicle.", size=10.8, italic=True,
                color=MUTED)], gap=0)

    notes(s, "The roadmap avoids promising GPU acceleration until the DRM/HAL problem is solved; "
             "audio and clipboard are far more valuable in the near term.")


def slide_lessons(prs, number):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = BG
    header(s, "08 · Takeaways", "Four lessons that transfer",
           "Applicable to any project that runs Linux or a desktop stack on Android.",
           number=number)

    items = [
        ("Match JNI exactly", "A five-year gap between jar and .so is a crash, not a warning. "
         "Ship natives with the app and install them on every boot."),
        ("Keep payloads out of the store", "A 790 MB userland belongs in a release asset, not in "
         "an AAB. Version it independently and make the download resumable."),
        ("Treat the guest as a service", "Health endpoint, explicit ports, restart guards, "
         "idempotent setup. Never assume that a PID means a ready desktop."),
        ("Emulate the smallest thing that works", "No GPU, no root, no namespaces. Find the "
         "minimum environment that runs the product, then document its limits."),
    ]
    w = (CONTENT_W - 0.24) / 2
    for i, (t, d) in enumerate(items):
        x = MARGIN + (i % 2) * (w + 0.24)
        y = 1.94 + (i // 2) * 2.12
        card(s, x, y, w, 1.92)
        add_text(s, x + 0.26, y + 0.22, 0.4, 0.3,
                 [P(f"0{i + 1}", size=11, bold=True, color=ACCENT)], gap=0)
        add_text(s, x + 0.26, y + 0.54, w - 0.5, 0.3,
                 [P(t, size=14, bold=True, color=NAVY)], gap=0)
        add_text(s, x + 0.26, y + 0.94, w - 0.5, 0.8,
                 [P(d, size=11, color=MUTED, line=1.24)], gap=0)

    add_text(s, MARGIN, 6.32, CONTENT_W, 0.3,
             [P("If you take one thing away: the hard part was not Android — it was making a desktop "
                "stack honest about a kernel it does not own.", size=10.8, italic=True,
                color=MUTED)], gap=0)

    notes(s, "These are the reusable engineering lessons, independent of GAMA.")


def slide_close(prs):
    s = new_slide(prs)
    s.background.fill.solid()
    s.background.fill.fore_color.rgb = NAVY

    halo = s.shapes.add_shape(MSO_SHAPE.OVAL, Inches(-1.9), Inches(4.2),
                              Inches(5.2), Inches(5.2))
    halo.fill.solid()
    halo.fill.fore_color.rgb = NAVY_SOFT
    halo.line.fill.background()
    no_shadow(halo)

    add_text(s, MARGIN, 0.86, 11.0, 0.3,
             [P("THANK YOU", size=11, bold=True, color=TEAL_LIGHT)], gap=0)
    add_text(s, MARGIN, 1.20, 11.5, 1.35,
             [P("Try it, break it,", size=40, bold=True, color=WHITE, line=0.95),
              P("and tell us what fails.", size=40, bold=True, color=WHITE, line=0.95)], gap=0)
    add_text(s, MARGIN, 2.68, 9.2, 0.7,
             [P("v0.3.0 ships the full GUI, the JOGL 2.6.0 fix and a signed AAB. "
                "Reproduction steps and known limits are in the release notes.",
                size=13.5, color=RGBColor(0xC3, 0xD0, 0xE2), line=1.3)], gap=0)

    links = [
        ("Releases", "github.com/hqnghi88/agama/releases"),
        ("Runtime", "releases / tag / rootfs"),
        ("Source", "github.com/hqnghi88/agama"),
        ("Issues", "github.com/hqnghi88/agama/issues"),
    ]
    for i, (t, d) in enumerate(links):
        x = MARGIN + (i % 2) * 6.15
        y = 3.95 + (i // 2) * 1.10
        rect(s, x, y, 5.85, 0.92, fill=NAVY_SOFT, line=RGBColor(0x27, 0x46, 0x75), radius=0.06)
        add_text(s, x + 0.24, y + 0.14, 5.3, 0.26, [P(t, size=9.5, bold=True, color=TEAL_LIGHT)], gap=0)
        add_text(s, x + 0.24, y + 0.43, 5.3, 0.3,
                 [P(d, size=11.5, color=WHITE, font=MONO_FONT)], gap=0)

    rect(s, MARGIN, 6.42, CONTENT_W, 0.012, fill=RGBColor(0x25, 0x3E, 0x62))
    add_text(s, MARGIN, 6.58, 8.0, 0.28,
             [P("GAMA Mobile  ·  com.simulation.mobile  ·  v0.3.0 (22)", size=10,
                color=RGBColor(0x9F, 0xB3, 0xCD))], gap=0)
    add_text(s, SLIDE_W - MARGIN - 4.0, 6.58, 4.0, 0.28,
             [P("Questions welcome — bring logs.", size=10,
                color=RGBColor(0x9F, 0xB3, 0xCD), align=PP_ALIGN.RIGHT)], gap=0)

    notes(s, "Close with a concrete ask: install the APK, try a real model and open issues with "
             "logs from /opt/gama/logs inside the guest.")


# ── main ─────────────────────────────────────────────────────────────
def build(out_path):
    prs = Presentation()
    prs.slide_width = Inches(SLIDE_W)
    prs.slide_height = Inches(SLIDE_H)

    slide_title(prs)
    slide_agenda(prs, 2)
    slide_context(prs, 3)
    slide_product(prs, 4)
    slide_journey(prs, 5)
    slide_architecture(prs, 6)
    slide_boot(prs, 7)
    slide_constraints(prs, 8)
    slide_x11(prs, 9)
    slide_graphics(prs, 10)
    slide_jogl(prs, 11)
    slide_natives(prs, 12)
    slide_rootfs(prs, 13)
    slide_ports(prs, 14)
    slide_frontend(prs, 15)
    slide_build(prs, 16)
    slide_compliance(prs, 17)
    slide_verification(prs, 18)
    slide_performance(prs, 19)
    slide_roadmap(prs, 20)
    slide_lessons(prs, 21)
    slide_close(prs)

    cp = prs.core_properties
    cp.title = "GAMA Mobile — Technical Deep Dive"
    cp.subject = "Running the full GAMA IDE on Android with PRoot, X11 and JOGL 2.6.0"
    cp.author = "GAMA Mobile"
    cp.keywords = "GAMA, Android, PRoot, X11, JOGL, Eclipse RCP, React Native"
    cp.comments = "v0.3.0 (versionCode 22) — generated with python-pptx"

    prs.save(out_path)
    return prs


def main():
    default = Path(__file__).resolve().parents[1] / "GAMA_Mobile_Tech_Talk_v0.3.0.pptx"
    out = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else default
    prs = build(str(out))
    size = os.path.getsize(out)
    print(f"saved: {out}")
    print(f"slides: {len(prs.slides)}  size: {size} bytes ({size / 1024:.1f} KB)")


if __name__ == "__main__":
    main()
