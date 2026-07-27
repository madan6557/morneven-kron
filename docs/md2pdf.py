"""Convert TEAM_ACCOUNT_PLAN.md to PDF using fpdf2 with proper table rendering."""
import re
from pathlib import Path
from fpdf import FPDF

MD_PATH = Path(__file__).parent / "TEAM_ACCOUNT_PLAN.md"
OUT_PATH = Path(__file__).parent / "TEAM_ACCOUNT_PLAN.pdf"


class PlanPDF(FPDF):
    def header(self):
        if self.page_no() > 1:
            self.set_font("Helvetica", "I", 8)
            self.set_text_color(120, 120, 120)
            self.cell(0, 5, "KRON Team Account Plan", align="R")
            self.ln(8)

    def footer(self):
        self.set_y(-15)
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(120, 120, 120)
        self.cell(0, 10, f"Halaman {self.page_no()}/{{nb}}", align="C")


def parse_md(text: str) -> list[dict]:
    """Parse markdown into blocks."""
    lines = text.split("\n")
    blocks = []
    i = 0
    while i < len(lines):
        line = lines[i]

        # horizontal rule
        if re.match(r"^---+\s*$", line):
            blocks.append({"type": "hr"})
            i += 1
            continue

        # heading
        m = re.match(r"^(#{1,4})\s+(.*)", line)
        if m:
            blocks.append({"type": "heading", "level": len(m.group(1)), "text": m.group(2)})
            i += 1
            continue

        # code block
        if line.strip().startswith("```"):
            lang = line.strip().lstrip("`").strip()
            code_lines = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code_lines.append(lines[i])
                i += 1
            i += 1
            blocks.append({"type": "code", "lang": lang, "text": "\n".join(code_lines)})
            continue

        # table
        if "|" in line and i + 1 < len(lines) and re.match(r"^\s*\|[-:\s|]+\|\s*$", lines[i + 1]):
            headers = [c.strip() for c in line.strip().strip("|").split("|")]
            i += 2
            rows = []
            while i < len(lines) and "|" in lines[i] and lines[i].strip():
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                rows.append(cells)
                i += 1
            blocks.append({"type": "table", "headers": headers, "rows": rows})
            continue

        # bullet
        m = re.match(r"^(\s*)[-*]\s+(.*)", line)
        if m:
            indent = len(m.group(1))
            blocks.append({"type": "bullet", "text": m.group(2), "indent": indent})
            i += 1
            continue

        # numbered list
        m = re.match(r"^(\s*)\d+\.\s+(.*)", line)
        if m:
            blocks.append({"type": "numbered", "text": m.group(2)})
            i += 1
            continue

        # paragraph
        if line.strip():
            para_lines = []
            while i < len(lines) and lines[i].strip() and not re.match(r"^#{1,4}\s", lines[i]) and not lines[i].strip().startswith("```") and not re.match(r"^---+\s*$", lines[i]):
                if "|" in lines[i] and i + 1 < len(lines) and re.match(r"^\s*\|[-:\s|]+\|\s*$", lines[i + 1]):
                    break
                if re.match(r"^(\s*)[-*]\s+", lines[i]):
                    break
                if re.match(r"^(\s*)\d+\.\s+", lines[i]):
                    break
                para_lines.append(lines[i])
                i += 1
            blocks.append({"type": "paragraph", "text": " ".join(para_lines)})
            continue

        i += 1
    return blocks


def strip_md_inline(text: str) -> str:
    """Remove markdown inline formatting."""
    text = re.sub(r"```([^`]+)```", r"\1", text)
    text = re.sub(r"`([^`]+)`", r"\1", text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"\1", text)
    text = re.sub(r"\*([^*]+)\*", r"\1", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)
    return text


def calc_col_widths(headers: list[str], rows: list[list[str]], total_w: float) -> list[float]:
    """Calculate column widths based on content, proportional to max content length."""
    col_count = len(headers)
    max_lens = []
    for ci in range(col_count):
        mx = len(headers[ci]) if ci < len(headers) else 0
        for row in rows:
            if ci < len(row):
                mx = max(mx, len(row[ci]))
        max_lens.append(mx)
    total_len = sum(max_lens) or 1
    # minimum 15mm per column, distribute remaining proportionally
    min_w = 15
    remaining = total_w - min_w * col_count
    if remaining < 0:
        min_w = total_w / col_count
        remaining = 0
    widths = []
    for ml in max_lens:
        prop = ml / total_len if total_len > 0 else 1 / col_count
        w = min_w + remaining * prop
        widths.append(w)
    return widths


def render_pdf(blocks: list[dict], out: Path):
    pdf = PlanPDF("P", "mm", "A4")
    pdf.alias_nb_pages()
    pdf.set_auto_page_break(auto=True, margin=20)
    pdf.add_page()

    # Add Unicode font
    font_paths = [
        "C:/Windows/Fonts/segoeui.ttf",
        "C:/Windows/Fonts/tahoma.ttf",
        "C:/Windows/Fonts/arial.ttf",
    ]
    font_added = False
    for fp in font_paths:
        if Path(fp).exists():
            pdf.add_font("UI", "", fp)
            bd = fp.replace(".ttf", "bd.ttf")
            it = fp.replace(".ttf", "i.ttf")
            pdf.add_font("UI", "B", bd if Path(bd).exists() else fp)
            pdf.add_font("UI", "I", it if Path(it).exists() else fp)
            font_added = True
            break

    rf = "UI" if font_added else "Helvetica"
    bf = "UI" if font_added else "Helvetica"
    mf = "Courier"

    for block in blocks:
        bt = block["type"]

        if bt == "hr":
            pdf.ln(2)
            y = pdf.get_y()
            pdf.set_draw_color(180, 180, 180)
            pdf.line(20, y, 190, y)
            pdf.ln(4)
            continue

        if bt == "heading":
            level = block["level"]
            text = strip_md_inline(block["text"])
            sizes = {1: 18, 2: 14, 3: 12, 4: 11}
            sz = sizes.get(level, 11)
            if level <= 2:
                pdf.ln(4)
            pdf.set_font(rf, "B", sz)
            pdf.set_text_color(30, 30, 30)
            pdf.multi_cell(0, sz * 0.5, text)
            pdf.ln(2)
            continue

        if bt == "code":
            pdf.set_font(mf, "", 8)
            pdf.set_text_color(50, 50, 50)
            pdf.set_fill_color(245, 245, 245)
            for cline in block["text"].split("\n"):
                pdf.set_x(25)
                pdf.multi_cell(160, 4, cline, fill=True)
            pdf.ln(2)
            continue

        if bt == "table":
            headers = block["headers"]
            rows = block["rows"]
            col_count = len(headers)
            total_w = 170
            col_widths = calc_col_widths(headers, rows, total_w)
            x_start = 20

            # Check if table fits on current page, otherwise add page
            if pdf.get_y() + 20 > 277:
                pdf.add_page()

            # Header row
            pdf.set_font(rf, "B", 7.5)
            pdf.set_text_color(255, 255, 255)
            pdf.set_fill_color(50, 50, 50)
            pdf.set_x(x_start)
            for ci, h in enumerate(headers):
                w = col_widths[ci] if ci < len(col_widths) else 20
                txt = strip_md_inline(h)
                pdf.cell(w, 7, txt, border=0, fill=True, align="C")
            pdf.ln()

            # Data rows
            pdf.set_font(rf, "", 7)
            pdf.set_text_color(30, 30, 30)
            fill = False
            for row in rows:
                if fill:
                    pdf.set_fill_color(245, 245, 245)
                else:
                    pdf.set_fill_color(255, 255, 255)

                # Calculate needed height for this row
                max_lines = 1
                pdf.set_font(rf, "", 7)
                for ci in range(col_count):
                    txt = strip_md_inline(row[ci]) if ci < len(row) else ""
                    w = col_widths[ci] if ci < len(col_widths) else 20
                    # Estimate lines needed
                    str_w = pdf.get_string_width(txt)
                    lines_needed = max(1, int(str_w / (w - 2)) + 1)
                    max_lines = max(max_lines, lines_needed)

                row_h = max(6, max_lines * 4)

                # Check page break
                if pdf.get_y() + row_h > 277:
                    pdf.add_page()
                    # Reprint header
                    pdf.set_font(rf, "B", 7.5)
                    pdf.set_text_color(255, 255, 255)
                    pdf.set_fill_color(50, 50, 50)
                    pdf.set_x(x_start)
                    for ci, h in enumerate(headers):
                        w = col_widths[ci] if ci < len(col_widths) else 20
                        pdf.cell(w, 7, strip_md_inline(h), border=0, fill=True, align="C")
                    pdf.ln()
                    pdf.set_font(rf, "", 7)
                    pdf.set_text_color(30, 30, 30)
                    if fill:
                        pdf.set_fill_color(245, 245, 245)
                    else:
                        pdf.set_fill_color(255, 255, 255)

                y_before = pdf.get_y()
                pdf.set_x(x_start)
                max_y = y_before
                for ci in range(col_count):
                    txt = strip_md_inline(row[ci]) if ci < len(row) else ""
                    w = col_widths[ci] if ci < len(col_widths) else 20
                    pdf.set_xy(x_start + sum(col_widths[:ci]), y_before)
                    pdf.multi_cell(w, 4, txt, border=0, fill=True)
                    max_y = max(max_y, pdf.get_y())

                pdf.set_y(max_y)
                fill = not fill

            pdf.ln(3)
            continue

        if bt == "bullet":
            text = strip_md_inline(block["text"])
            indent = 10 + (1 if block["indent"] > 2 else 0) * 5
            pdf.set_font(rf, "", 9)
            pdf.set_text_color(30, 30, 30)
            pdf.set_x(indent)
            pdf.cell(4, 5, chr(8226))
            pdf.set_x(indent + 4)
            pdf.multi_cell(170 - indent, 5, text)
            pdf.ln(0.5)
            continue

        if bt == "numbered":
            text = strip_md_inline(block["text"])
            pdf.set_font(rf, "", 9)
            pdf.set_text_color(30, 30, 30)
            pdf.set_x(10)
            pdf.multi_cell(170, 5, text)
            pdf.ln(0.5)
            continue

        if bt == "paragraph":
            text = strip_md_inline(block["text"])
            pdf.set_font(rf, "", 9)
            pdf.set_text_color(30, 30, 30)
            pdf.multi_cell(0, 5, text)
            pdf.ln(2)
            continue

    pdf.output(str(out))


def main():
    text = MD_PATH.read_text(encoding="utf-8")
    blocks = parse_md(text)
    render_pdf(blocks, OUT_PATH)
    print(f"PDF created: {OUT_PATH}")


if __name__ == "__main__":
    main()
