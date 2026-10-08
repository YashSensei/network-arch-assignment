"""Render the two-page SPEC.md hand-in. Optional dependency: reportlab."""

import html
from pathlib import Path
import re

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

ROOT = Path(__file__).resolve().parents[1]
INK = colors.HexColor("#142b3b")
ACCENT = colors.HexColor("#137c74")
BODY = ParagraphStyle("body", fontName="Helvetica", fontSize=9, leading=11.5,
                      spaceAfter=5, textColor=INK)
CELL = ParagraphStyle("cell", parent=BODY, fontSize=8.5, leading=10, spaceAfter=0)
STYLES = {
    1: ParagraphStyle("title", parent=BODY, fontName="Helvetica-Bold", fontSize=16,
                      leading=19, spaceAfter=5, textColor=ACCENT),
    2: ParagraphStyle("section", parent=BODY, fontName="Helvetica-Bold", fontSize=10.5,
                      leading=13, spaceBefore=6, spaceAfter=4, keepWithNext=True),
    3: ParagraphStyle("subsection", parent=BODY, fontName="Helvetica-Bold", fontSize=9,
                      leading=11, spaceBefore=3, spaceAfter=3, keepWithNext=True),
}


def inline(text):
    text = html.escape(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    # Only replace paired backticks; the header-name alphabet has a literal backtick.
    return re.sub(r"`([^`]+)`", r'<font name="Courier" size="8">\1</font>', text)


def footer(canvas, doc):
    canvas.setTitle("BinHTTP/1 - Two-page protocol specification")
    canvas.setAuthor("YashSensei | Adapted from parthdagia05/binhttp")
    canvas.setStrokeColor(ACCENT)
    canvas.line(34, 28, A4[0] - 34, 28)
    canvas.setFont("Helvetica", 8)
    canvas.setFillColor(INK)
    canvas.drawString(34, 16, "NETWORK ARCHITECTURE  /  TRACK 1 - SERVER")
    canvas.drawRightString(A4[0] - 34, 16, str(doc.page))


def main():
    lines = (ROOT / "SPEC.md").read_text(encoding="utf-8").splitlines()
    flow = []
    i = 0
    while i < len(lines):
        line = lines[i].strip()
        i += 1
        if not line:
            continue
        if line.startswith("<div"):
            flow.append(PageBreak())
        elif line.startswith("#"):
            level = len(line) - len(line.lstrip("#"))
            flow.append(Paragraph(inline(line[level:].strip()), STYLES[level]))
        elif line.startswith("|"):
            rows = [line]
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(lines[i])
                i += 1
            data = [[Paragraph(inline(cell.strip()), CELL) for cell in row.strip("|").split("|")]
                    for row in rows if not re.match(r"^\|[\s:|\-]+\|$", row)]
            width = A4[0] - 68
            if len(data[0]) == 4:
                widths = [40, 40, 85, width - 165]
            elif data[0][0].getPlainText() == "ID":
                widths = [30, 110, width - 140]
            elif len(data[0]) == 3:
                widths = [48, 85, width - 133]
            else:
                widths = [48, width - 48]
            table = Table(data, colWidths=widths, hAlign="LEFT")
            table.setStyle(TableStyle([
                ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#dceeea")),
                ("VALIGN", (0, 0), (-1, -1), "TOP"),
                ("LEFTPADDING", (0, 0), (-1, -1), 5),
                ("RIGHTPADDING", (0, 0), (-1, -1), 5),
                ("TOPPADDING", (0, 0), (-1, -1), 2),
                ("BOTTOMPADDING", (0, 0), (-1, -1), 2),
                ("LINEBELOW", (0, 0), (-1, -1), 0.3, colors.HexColor("#d5dedf")),
            ]))
            flow.extend([table, Spacer(1, 5)])
        else:
            flow.append(Paragraph(inline(line), BODY))
    document = SimpleDocTemplate(str(ROOT / "SPEC.pdf"), pagesize=A4, leftMargin=34,
                                 rightMargin=34, topMargin=30, bottomMargin=38)
    document.build(flow, onFirstPage=footer, onLaterPages=footer)
    if document.page != 2:
        raise SystemExit(f"Expected exactly 2 pages, got {document.page}; adjust layout before submission.")
    print("Wrote SPEC.pdf (2 pages).")


if __name__ == "__main__":
    main()
