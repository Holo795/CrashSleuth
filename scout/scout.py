#!/usr/bin/env python3
"""The weekly scout: finds new public Minecraft problem reports that CrashSleuth says nothing about.

It searches GitHub for issues opened in the last days that carry a real Minecraft log, gives each one to
CrashSleuth exactly as it was written, and keeps the ones where the tool stays silent. It proposes; it never
adds a rule. Deciding what the right answer is stays a human job, because the loudest name in a log is so
often the wrong one.

    GITHUB_TOKEN=... python3 scout/scout.py --cli path/to/crashsleuth --days 7 --out report.md

Only the standard library, so it runs as it is in a GitHub Actions runner.
"""
from __future__ import annotations

import argparse
import datetime
import json
import os
import re
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SIGNATURES = ROOT / "core-logs" / "src" / "main" / "resources" / "crashsleuth" / "signatures.json"
REPOSITORY = "Holo795/CrashSleuth"

# Lines that only a real Minecraft log contains. A report must carry some to be worth reading: a question
# written in prose, with no log in it, is rightly left unanswered by the tool.
SEARCHES = [
    '"Server thread/ERROR"',
    '"Render thread/ERROR"',
    '"---- Minecraft Crash Report ----"',
    '"Failed to start the minecraft server"',
    '"Encountered an unexpected exception"',
    '"Error occurred while enabling"',
    '"has failed to load correctly"',
    '"Mixin apply failed"',
    '"Could not pass event"',
    '"Failed to load datapacks"',
    '"Registry remapping failed"',
    '"Exception in server tick loop"',
]
LOG_LINE = re.compile(
    r"^\s*\[?\d{2}:\d{2}:\d{2}\]?.*(?:INFO|WARN|ERROR|FATAL)|^\s*at [\w.$/]+\(|^Caused by: |"
    r"---- Minecraft Crash Report ----|^\s*\[[\w ./-]+/(?:INFO|WARN|ERROR|FATAL)\]",
    re.MULTILINE,
)
MINIMUM_LOG_LINES = 4
IN_RANGE = re.compile(r"^(?:1\.(?:19|2\d)(?:\.\d+)?|2\d\.\d+(?:\.\d+)?)$")


@dataclass
class Candidate:
    url: str
    title: str
    repository: str
    created: str
    state: str
    comments: int
    platform: str | None = None
    minecraft: str | None = None
    excerpt: list[str] = field(default_factory=list)
    near: list[str] = field(default_factory=list)

    @property
    def reproducible(self) -> bool:
        """Enough to rebuild in the lab: a known platform and a version inside the range that is tested."""
        return bool(self.platform and self.platform != "UNKNOWN" and self.minecraft and IN_RANGE.match(self.minecraft))

    @property
    def answered(self) -> bool:
        """A closed issue with a discussion usually holds the fix, which is what a rule needs as a source."""
        return self.state == "closed" and self.comments > 0


# ---------------------------------------------------------------------------------------------- GitHub


def github(path: str, params: dict | None = None) -> dict | list:
    url = f"https://api.github.com{path}" + (f"?{urllib.parse.urlencode(params)}" if params else "")
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "CrashSleuth-scout",
        **({"Authorization": f"Bearer {os.environ['GITHUB_TOKEN']}"} if os.environ.get("GITHUB_TOKEN") else {}),
    })
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            # The search API allows a few calls a minute: waiting is the answer, not failing.
            if error.code in (403, 429) and attempt < 3:
                time.sleep(20 * (attempt + 1))
                continue
            raise
    raise RuntimeError("unreachable")


def search(days: int, per_query: int) -> dict[str, dict]:
    since = (datetime.date.today() - datetime.timedelta(days=days)).isoformat()
    found: dict[str, dict] = {}
    for query in SEARCHES:
        result = github("/search/issues", {"q": f"{query} in:body type:issue created:>={since}", "per_page": per_query})
        for item in result.get("items", []):
            if f"/{REPOSITORY}/" not in item["html_url"]:
                found.setdefault(item["html_url"], item)
        time.sleep(3)
    return found


def already_reported() -> set[str]:
    """The links listed by earlier scout issues: a report is proposed once, not every week."""
    seen: set[str] = set()
    try:
        issues = github(f"/repos/{REPOSITORY}/issues", {"labels": "scout", "state": "all", "per_page": 100})
    except Exception:
        return seen
    for issue in issues:
        seen.update(re.findall(r"https://github\.com/[\w.-]+/[\w.-]+/issues/\d+", issue.get("body") or ""))
    return seen


# ------------------------------------------------------------------------------------------ CrashSleuth


def analyse(cli: str, text: str) -> dict | None:
    with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as handle:
        handle.write(text)
        path = handle.name
    try:
        output = subprocess.run([cli, "analyze", path, "--json"], capture_output=True, text=True, timeout=120).stdout
        return json.loads(output)
    except Exception:
        return None
    finally:
        os.unlink(path)


def known_phrases() -> list[tuple[str, str]]:
    """The plain words each rule looks for. When they are in a report and the rule still did not fire, the
    message probably changed its wording — which is how a rule quietly stops working after an update."""
    phrases = []
    for signature in json.loads(SIGNATURES.read_text())["signatures"]:
        literal = re.sub(r"\\[sSdDwWbB]|\\.|\[[^\]]*\]|\([^)]*\)|[.*+?^${}|]", " ", signature["pattern"])
        # Real words only: three of them, of three letters or more, or the phrase matches everything.
        runs = [run.strip() for run in re.split(r"\s{2,}", literal)
                if len([word for word in run.split() if re.fullmatch(r"[A-Za-z]{3,}\w*", word)]) >= 3]
        if runs:
            phrases.append((signature["id"], max(runs, key=len)))
    return phrases


# ------------------------------------------------------------------------------------------------ report


def neutral(text: str) -> str:
    """A report is untrusted text going into a public issue: no one is pinged, nothing breaks the block."""
    return text.replace("@", "@​").replace("```", "'''")


def excerpt(body: str, limit: int = 12) -> list[str]:
    lines = [line.rstrip() for line in body.splitlines() if LOG_LINE.search(line)]
    errors = [line for line in lines if re.search(r"ERROR|FATAL|Exception|Caused by|Error:", line)]
    chosen = (errors or lines)[:limit]
    return [neutral(line)[:220] for line in chosen]


def write(candidates: list[Candidate], scanned: int, days: int) -> str:
    today = datetime.date.today().isoformat()
    ready = [c for c in candidates if c.reproducible and c.answered]
    near = [c for c in candidates if c.near]
    lines = [
        f"The scout read **{scanned}** public reports opened in the last {days} days that carry a real Minecraft log.",
        f"CrashSleuth said **nothing** about **{len(candidates)}** of them.",
        "",
        "Nothing here has been added to the tool. Each one is a lead to read: the right answer is in the "
        "thread, and it is often not the name the log shouts loudest.",
        "",
        "| | Meaning |",
        "| --- | --- |",
        "| 🧪 | platform and Minecraft version inside the tested range: it can be rebuilt in the lab |",
        "| ✅ | closed with a discussion: the fix is probably written in the thread |",
        "| 🔁 | the words of an existing rule are in it, yet it did not fire: probably a new wording |",
        "",
    ]
    if ready:
        lines += ["## Worth doing first", "",
                  "Rebuildable, and the thread probably holds the answer.", ""]
        lines += [f"- [ ] {row(c)}" for c in ready] + [""]
    if near:
        lines += ["## Existing rules that may have stopped matching", ""]
        lines += [f"- [ ] {row(c)} — looks like `{', '.join(c.near)}`" for c in near] + [""]
    lines += ["## Everything", ""]
    for c in candidates:
        lines += [f"<details><summary>{marks(c)} {neutral(c.title)[:90]} — {c.repository}</summary>", "",
                  f"{c.url}  ", f"{c.platform or 'platform unknown'} · Minecraft {c.minecraft or 'unknown'} · "
                  f"opened {c.created[:10]} · {c.state}, {c.comments} comment(s)", ""]
        if c.excerpt:
            lines += ["```", *c.excerpt, "```"]
        lines += ["", "</details>", ""]
    lines += ["---", f"*Scout run of {today}. Links already proposed by earlier runs are skipped.*"]
    return "\n".join(lines)


def marks(candidate: Candidate) -> str:
    return "".join(icon for icon, on in (("🧪", candidate.reproducible), ("✅", candidate.answered), ("🔁", bool(candidate.near))) if on) or "·"


def row(candidate: Candidate) -> str:
    return f"{marks(candidate)} [{neutral(candidate.title)[:80]}]({candidate.url}) — {candidate.platform or '?'} {candidate.minecraft or ''}".rstrip()


# -------------------------------------------------------------------------------------------------- main


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--cli", required=True, help="the crashsleuth executable")
    parser.add_argument("--days", type=int, default=7)
    parser.add_argument("--per-query", type=int, default=30)
    parser.add_argument("--out", type=Path, default=Path("scout-report.md"))
    parser.add_argument("--json", type=Path, help="also write the candidates as JSON")
    options = parser.parse_args()

    reports = search(options.days, options.per_query)
    seen = already_reported()
    phrases = known_phrases()
    candidates: list[Candidate] = []
    scanned = 0
    for url, item in reports.items():
        body = item.get("body") or ""
        if url in seen or len(LOG_LINE.findall(body)) < MINIMUM_LOG_LINES:
            continue
        scanned += 1
        report = analyse(options.cli, body.replace("```", ""))
        if report is None or report.get("findings"):
            continue
        environment = report.get("environment") or {}
        lowered = body.lower()
        candidates.append(Candidate(
            url=url, title=item["title"], repository=url.split("/")[3] + "/" + url.split("/")[4],
            created=item["created_at"], state=item["state"], comments=item["comments"],
            platform=environment.get("platform"), minecraft=environment.get("minecraftVersion"),
            excerpt=excerpt(body),
            near=[rule for rule, phrase in phrases if phrase.lower() in lowered],
        ))

    # The ones most likely to become a rule first.
    candidates.sort(key=lambda c: (not (c.reproducible and c.answered), not c.near, not c.reproducible, c.created))
    options.out.write_text(write(candidates, scanned, options.days))
    if options.json:
        options.json.write_text(json.dumps([c.__dict__ for c in candidates], indent=2))
    print(f"scanned {scanned}, silent on {len(candidates)}, report in {options.out}")
    # The workflow opens an issue only when there is something to read.
    if "GITHUB_OUTPUT" in os.environ:
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"candidates={len(candidates)}\nscanned={scanned}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
