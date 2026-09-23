#!/usr/bin/env python3
"""Turns wiki/*.md into the documentation site under docs/.

One source, two places: the same Markdown is pushed to the GitHub wiki and rendered here, so the two can
never drift. Only the small part of Markdown these pages use is understood, on purpose: the source is ours,
and a dependency for a dozen files is not worth it.

    python3 docs/build.py
"""
from __future__ import annotations

import html
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WIKI = ROOT / "wiki"
OUT = ROOT / "docs"

# The order of the sidebar, and the title each page gets in it.
NAV = [
    ("Getting started", [
        ("Home", "Overview"),
        ("Install", "Installation"),
        ("First-analysis", "Your first analysis"),
        ("Reading-a-report", "Reading a report"),
    ]),
    ("Doing more", [
        ("Sharing-a-report", "Sharing a report"),
        ("Finding-the-culprit", "Finding the culprit"),
        ("Command-line", "Command line"),
        ("Assistants-MCP", "Assistants (MCP)"),
    ]),
    ("About", [
        ("What-it-covers", "What it covers"),
        ("Supported-versions", "Supported versions"),
        ("Privacy", "What leaves your computer"),
        ("How-it-is-tested", "How it is tested"),
        ("Contributing", "Contributing"),
        ("FAQ", "FAQ"),
    ]),
]

PAGES = [name for _, group in NAV for name, _ in group]


def target(name: str) -> str:
    return "index.html" if name == "Home" else f"{name}.html"


def anchor(text: str) -> str:
    """The id GitHub would give this heading, so links written for the wiki work here too."""
    slug = re.sub(r"[^\w\- ]", "", strip_marks(text)).strip().lower().replace(" ", "-")
    return slug


def strip_marks(text: str) -> str:
    text = re.sub(r"`([^`]*)`", r"\1", text)
    text = re.sub(r"\*\*([^*]*)\*\*", r"\1", text)
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    return text


def inline(text: str) -> str:
    """Code, bold, italic, images and links. Code is taken out first so nothing is formatted inside it."""
    codes: list[str] = []

    def keep(match: re.Match) -> str:
        codes.append(f"<code>{html.escape(match.group(1))}</code>")
        return f"\x00{len(codes) - 1}\x00"

    text = re.sub(r"`([^`]+)`", keep, text)
    text = html.escape(text)
    text = re.sub(r"!\[([^\]]*)\]\(([^)]+)\)", r'<img src="\2" alt="\1" loading="lazy">', text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", lambda m: link(m.group(1), m.group(2)), text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\w*])\*([^*]+)\*(?![\w*])", r"<em>\1</em>", text)
    return re.sub(r"\x00(\d+)\x00", lambda m: codes[int(m.group(1))], text)


def link(label: str, href: str) -> str:
    """A wiki link points at a page name; here it has to point at a file."""
    if not href.startswith(("http://", "https://", "#", "images/")):
        page, _, fragment = href.partition("#")
        href = target(page) + (f"#{fragment}" if fragment else "")
    external = ' target="_blank" rel="noopener"' if href.startswith("http") else ""
    return f'<a href="{href}"{external}>{label}</a>'


def render(markdown: str) -> tuple[str, list[tuple[int, str, str]]]:
    lines = markdown.split("\n")
    out: list[str] = []
    headings: list[tuple[int, str, str]] = []
    index = 0
    while index < len(lines):
        line = lines[index]

        if line.startswith("```"):
            language = line[3:].strip()
            block: list[str] = []
            index += 1
            while index < len(lines) and not lines[index].startswith("```"):
                block.append(lines[index])
                index += 1
            code = html.escape("\n".join(block))
            out.append(f'<pre class="code" data-lang="{html.escape(language)}"><code>{code}</code></pre>')

        elif line.startswith("#"):
            level = len(line) - len(line.lstrip("#"))
            text = line[level:].strip()
            slug = anchor(text)
            out.append(f'<h{level} id="{slug}">{inline(text)}</h{level}>')
            if 2 <= level <= 3:
                headings.append((level, slug, strip_marks(text)))

        elif line.startswith("|") and index + 1 < len(lines) and re.match(r"^\|[\s:\-|]+\|$", lines[index + 1]):
            header = [cell.strip() for cell in line.strip("|").split("|")]
            index += 2
            rows = []
            while index < len(lines) and lines[index].startswith("|"):
                rows.append([cell.strip() for cell in lines[index].strip("|").split("|")])
                index += 1
            index -= 1
            head = "".join(f"<th>{inline(cell)}</th>" for cell in header)
            body = "".join("<tr>" + "".join(f"<td>{inline(cell)}</td>" for cell in row) + "</tr>" for row in rows)
            out.append(f'<div class="scroll"><table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>')

        elif re.match(r"^\s*[-*] ", line) or re.match(r"^\s*\d+\. ", line):
            ordered = bool(re.match(r"^\s*\d+\. ", line))
            items: list[str] = []
            while index < len(lines) and (re.match(r"^\s*[-*] ", lines[index]) or re.match(r"^\s*\d+\. ", lines[index])):
                items.append(re.sub(r"^\s*(?:[-*]|\d+\.) ", "", lines[index]))
                index += 1
                # A wrapped item continues on the next indented line.
                while index < len(lines) and lines[index].startswith("  ") and lines[index].strip() \
                        and not re.match(r"^\s*(?:[-*]|\d+\.) ", lines[index]):
                    items[-1] += " " + lines[index].strip()
                    index += 1
            index -= 1
            tag = "ol" if ordered else "ul"
            out.append(f"<{tag}>" + "".join(f"<li>{inline(item)}</li>" for item in items) + f"</{tag}>")

        elif line.startswith(">"):
            quote: list[str] = []
            while index < len(lines) and lines[index].startswith(">"):
                quote.append(lines[index].lstrip(">").strip())
                index += 1
            index -= 1
            out.append(f"<blockquote>{inline(' '.join(quote))}</blockquote>")

        elif line.strip() == "---":
            out.append("<hr>")

        elif line.strip():
            paragraph = [line]
            while index + 1 < len(lines) and lines[index + 1].strip() and not lines[index + 1].startswith(
                    ("#", "|", ">", "```", "---")) and not re.match(r"^\s*(?:[-*]|\d+\.) ", lines[index + 1]):
                index += 1
                paragraph.append(lines[index])
            out.append(f"<p>{inline(' '.join(part.strip() for part in paragraph))}</p>")

        index += 1
    return "\n".join(out), headings


def sidebar(current: str) -> str:
    parts = ['<nav class="side"><a class="brand" href="index.html">CrashSleuth</a>']
    for group, pages in NAV:
        parts.append(f'<p class="group">{group}</p><ul>')
        for name, label in pages:
            active = ' class="on"' if name == current else ""
            parts.append(f'<li><a href="{target(name)}"{active}>{label}</a></li>')
        parts.append("</ul>")
    parts.append(
        '<p class="group">Elsewhere</p><ul>'
        '<li><a href="https://github.com/Holo795/CrashSleuth/releases/latest" target="_blank" rel="noopener">Download</a></li>'
        '<li><a href="https://github.com/Holo795/CrashSleuth" target="_blank" rel="noopener">Repository</a></li>'
        "</ul></nav>",
    )
    return "".join(parts)


def contents(headings: list[tuple[int, str, str]]) -> str:
    if len(headings) < 3:
        return ""
    items = "".join(
        f'<li class="l{level}"><a href="#{slug}">{html.escape(text)}</a></li>' for level, slug, text in headings
    )
    return f'<aside class="toc"><p class="group">On this page</p><ul>{items}</ul></aside>'


TEMPLATE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<link rel="stylesheet" href="style.css">
</head>
<body>
<a class="skip" href="#content">Skip to the content</a>
<input type="checkbox" id="menu" hidden>
<label class="burger" for="menu" aria-label="Menu">☰</label>
{sidebar}
<main id="content">
<article>
{body}
</article>
{toc}
</main>
</body>
</html>
"""


def build() -> None:
    (OUT / "images").mkdir(parents=True, exist_ok=True)
    for image in (WIKI / "images").glob("*"):
        shutil.copy(image, OUT / "images" / image.name)

    for name in PAGES:
        source = WIKI / f"{name}.md"
        if not source.exists():
            raise SystemExit(f"missing page: {source}")
        markdown = source.read_text()
        body, headings = render(markdown)
        first = next((line[2:].strip() for line in markdown.split("\n") if line.startswith("# ")), name)
        summary = next(
            (strip_marks(line).strip() for line in markdown.split("\n")
             if line.strip() and not line.startswith(("#", "!", "|", ">"))),
            "Find out why Minecraft crashes, and who is to blame.",
        )
        page = TEMPLATE.format(
            title=html.escape(first if name == "Home" else f"{first} — CrashSleuth"),
            description=html.escape(summary[:180]),
            sidebar=sidebar(name),
            body=body,
            toc=contents(headings),
        )
        (OUT / target(name)).write_text(page)
        print(f"docs/{target(name)}")

    (OUT / "style.css").write_text(STYLE)
    print("docs/style.css")


STYLE = """/* Two columns on a screen, one on a phone; the system's own theme, and no web font to wait for. */
:root {
  --bg: #ffffff; --panel: #f7f7f8; --line: #e4e4e7; --text: #1b1b1f; --muted: #62626b;
  --accent: #5b5bd6; --code-bg: #f4f4f5;
  --font: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  --mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #121215; --panel: #171719; --line: #2a2a30; --text: #ececf0; --muted: #9a9aa4;
    --accent: #a5a5f5; --code-bg: #1c1c21;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--bg); color: var(--text); font-family: var(--font);
  font-size: 16px; line-height: 1.65; -webkit-font-smoothing: antialiased;
}
.skip { position: absolute; left: -9999px; }
.skip:focus { left: 8px; top: 8px; background: var(--panel); padding: 8px 12px; border-radius: 8px; z-index: 10; }

main { display: grid; grid-template-columns: minmax(0, 1fr) 220px; gap: 48px; max-width: 1100px;
       margin-left: 280px; padding: 56px 40px 96px; }
article { min-width: 0; }

.side {
  position: fixed; inset: 0 auto 0 0; width: 260px; padding: 32px 20px; overflow-y: auto;
  border-right: 1px solid var(--line); background: var(--panel);
}
.brand { display: block; font-weight: 650; font-size: 1.05rem; color: var(--text); text-decoration: none; margin-bottom: 28px; }
.side ul { list-style: none; margin: 0 0 22px; padding: 0; }
.side li { margin: 1px 0; }
.side a { display: block; padding: 5px 10px; border-radius: 7px; color: var(--muted); text-decoration: none; font-size: 0.93rem; }
.side a:hover { background: var(--bg); color: var(--text); }
.side a.on { background: var(--bg); color: var(--accent); font-weight: 550; }
.group { margin: 0 0 6px; padding-left: 10px; font-size: 0.72rem; letter-spacing: 0.09em;
         text-transform: uppercase; color: var(--muted); opacity: 0.75; }

.toc { position: sticky; top: 56px; align-self: start; font-size: 0.87rem; }
.toc ul { list-style: none; margin: 0; padding: 0; border-left: 1px solid var(--line); }
.toc a { display: block; padding: 3px 0 3px 12px; color: var(--muted); text-decoration: none; }
.toc a:hover { color: var(--text); }
.toc .l3 a { padding-left: 24px; font-size: 0.95em; }

h1 { font-size: 2.1rem; line-height: 1.2; margin: 0 0 20px; letter-spacing: -0.02em; }
h2 { font-size: 1.4rem; margin: 44px 0 14px; letter-spacing: -0.01em; }
h3 { font-size: 1.08rem; margin: 30px 0 10px; }
h2, h3 { scroll-margin-top: 24px; }
p { margin: 0 0 16px; }
a { color: var(--accent); text-decoration-thickness: 1px; text-underline-offset: 2px; }
ul, ol { margin: 0 0 16px; padding-left: 22px; }
li { margin: 5px 0; }
hr { border: 0; border-top: 1px solid var(--line); margin: 40px 0; }
strong { font-weight: 640; }

blockquote {
  margin: 20px 0; padding: 12px 18px; border-left: 3px solid var(--accent);
  background: var(--panel); border-radius: 0 8px 8px 0; color: var(--muted);
}
code { font-family: var(--mono); font-size: 0.88em; background: var(--code-bg);
       padding: 0.14em 0.4em; border-radius: 5px; }
pre.code { margin: 0 0 18px; padding: 15px 18px; background: var(--code-bg);
           border: 1px solid var(--line); border-radius: 10px; overflow-x: auto; }
pre.code code { background: none; padding: 0; font-size: 0.86rem; line-height: 1.55; }

.scroll { overflow-x: auto; margin: 0 0 20px; }
table { border-collapse: collapse; width: 100%; font-size: 0.93rem; }
th, td { text-align: left; padding: 9px 14px; border-bottom: 1px solid var(--line); vertical-align: top; }
th { font-weight: 600; font-size: 0.78rem; letter-spacing: 0.05em; text-transform: uppercase; color: var(--muted); }
tbody tr:last-child td { border-bottom: 0; }

img { max-width: 100%; height: auto; border-radius: 10px; border: 1px solid var(--line); margin: 6px 0 22px; display: block; }

.burger { display: none; }
@media (max-width: 1080px) {
  main { grid-template-columns: minmax(0, 1fr); margin-left: 260px; padding: 44px 32px 80px; gap: 0; }
  .toc { display: none; }
}
@media (max-width: 820px) {
  main { margin-left: 0; padding: 72px 20px 64px; }
  .side { transform: translateX(-100%); transition: transform 0.18s ease-out; z-index: 5; width: 250px; }
  #menu:checked ~ .side { transform: none; }
  .burger { display: block; position: fixed; top: 12px; left: 12px; z-index: 6; cursor: pointer;
            background: var(--panel); border: 1px solid var(--line); border-radius: 9px;
            padding: 7px 12px; font-size: 1.05rem; line-height: 1; }
  h1 { font-size: 1.7rem; }
}
"""


if __name__ == "__main__":
    build()
