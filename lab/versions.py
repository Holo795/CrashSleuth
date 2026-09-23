#!/usr/bin/env python3
"""Writes wiki/Supported-versions.md from what the lab really starts.

The table is never written by hand: it is read from lab/matrix.json (servers), lab/client-matrix.json
(game clients) and the proxy versions net_lab.py uses, so it cannot claim a version nobody tested.

    python3 lab/versions.py
"""
from __future__ import annotations

import collections
import json
import re
from pathlib import Path

LAB = Path(__file__).resolve().parent
PAGE = LAB.parent / "wiki" / "Supported-versions.md"

# Columns in the order people look for them, with the name they know.
SERVERS = [
    ("vanilla", "Vanilla"), ("paper", "Paper"), ("purpur", "Purpur"), ("spigot", "Spigot"), ("folia", "Folia"),
    ("fabric", "Fabric"), ("quilt", "Quilt"), ("neoforge", "NeoForge"), ("forge", "Forge"),
    ("mohist", "Mohist"), ("youer", "Youer"), ("arclight-forge", "Arclight (Forge)"),
    ("arclight-neoforge", "Arclight (NeoForge)"), ("arclight-fabric", "Arclight (Fabric)"),
]
CLIENTS = [("vanilla", "Vanilla"), ("fabric", "Fabric"), ("neoforge", "NeoForge"), ("forge", "Forge")]


def order(version: str) -> list[int]:
    return [int(part) for part in re.findall(r"\d+", version)]


def cells(matrix: list[dict], key: str) -> dict[str, dict[str, set[str]]]:
    """platform -> version -> the kinds of check that ran there (baseline, dependency)."""
    table: dict[str, dict[str, set[str]]] = collections.defaultdict(lambda: collections.defaultdict(set))
    for scenario in matrix:
        kind = "dependency" if "dependency" in scenario["name"] else "baseline"
        table[scenario.get(key) or "vanilla"][scenario["minecraft"]].add(kind)
    return table


def mark(kinds: set[str]) -> str:
    if {"baseline", "dependency"} <= kinds:
        return "✅"
    if kinds:
        return "☑️"
    return ""


def grid(table: dict[str, dict[str, set[str]]], columns: list[tuple[str, str]], rows: list[str]) -> str:
    present = [(key, label) for key, label in columns if key in table]
    head = "| Minecraft | " + " | ".join(label for _, label in present) + " |"
    rule = "| --- | " + " | ".join(":---:" for _ in present) + " |"
    body = [f"| **{version}** | " + " | ".join(mark(table[key].get(version, set())) for key, _ in present) + " |"
            for version in rows]
    return "\n".join([head, rule, *body])


def unstartable() -> str:
    """Versions a platform publishes but whose build does not start: said, rather than left blank."""
    import sys
    sys.path.insert(0, str(LAB))
    from matrix import DOES_NOT_START
    if not DOES_NOT_START:
        return ""
    names = dict(SERVERS)
    rows = "\n".join(f"- **{names.get(p, p)} {v}** — {why}." for (p, v), why in sorted(DOES_NOT_START.items()))
    return ("### Published, but not started\n\n"
            "These versions exist, yet the server they ship does not start in the lab, so nothing can be said about "
            "them. They come back in the table as soon as a working build is published.\n\n" + rows + "\n\n")


def proxies() -> list[str]:
    source = (LAB / "net_lab.py").read_text()
    velocity = sorted(set(re.findall(r'paper_server\("([\d.]+)", "velocity"\)', source)), key=order)
    found = [f"Velocity {version}" for version in velocity]
    if "bungeecord" in source:
        found.append("BungeeCord (latest build)")
    if "waterfall" in source:
        found.append("Waterfall (latest build)")
    return found


def main() -> None:
    servers = cells(json.loads((LAB / "matrix.json").read_text()), "platform")
    clients = cells(json.loads((LAB / "client-matrix.json").read_text()), "loader")

    every_release = sorted(servers["vanilla"], key=order)
    lines = sorted({v for key, versions in servers.items() if key != "vanilla" for v in versions}, key=order)
    client_lines = sorted({v for versions in clients.values() for v in versions}, key=order)
    started = sum(len(kinds) for versions in servers.values() for kinds in versions.values())
    hybrids = [label for key, label in SERVERS if key in servers and key in {k for k, _ in SERVERS[9:]}]

    PAGE.write_text(f"""# Supported versions

This page is **generated from the lab**, not written by hand: every tick below is a server or a game that
was really started, and the page cannot show a version that nobody ran.

**The range is Minecraft {every_release[0]} to {every_release[-1]}**, including the 26.x numbering.

## What a tick means

| Mark | What was started |
| --- | --- |
| ✅ | two launches: a **clean** one, where CrashSleuth must say nothing, and one with a **dependency removed on purpose**, which it must name |
| ☑️ | the clean launch only — CrashSleuth stays silent on a healthy server |
| *(empty)* | not started: the platform does not publish that version, or it sits between two tested lines |

The clean launch matters as much as the other one: a tool that finds problems on healthy servers is worse
than no tool. Vanilla has nothing to remove, so for vanilla the clean launch *is* the whole test.

## Servers

Vanilla is started on **every** release. The other platforms are started on one version per line of
Minecraft — each point where the game, the loader or Java changed in a way that shows in the logs.

{grid(servers, SERVERS, lines)}

**Every vanilla release** from {every_release[0]} to {every_release[-1]} is started on its own
({len(every_release)} versions): {", ".join(every_release)}.

{f"**Hybrid servers** — {', '.join(hybrids)} — run mods and plugins at once; both folders are read, and the checks of both kinds apply.{chr(10)}{chr(10)}" if hybrids else ""}{unstartable()}## Game clients

The real game, started in a window that never comes to the front.

{grid(clients, CLIENTS, client_lines)}

## Proxies

{", ".join(proxies())}, each with a real player joining through it.

## Versions in between

A version that sits between two tested lines — 1.20.2, say — was not started on its own. It usually behaves
like its neighbours, because the messages CrashSleuth reads rarely change within a line, but that is an
expectation and not a proof. If something looks wrong on such a version, [the log is worth sending](Contributing).

## Outside the range

Before {every_release[0]}, CrashSleuth still reads the logs and will often be right, but nothing is started
to check it: older loaders word their errors differently and no rule is tested against them.

---

*{started} launches in the matrix, regenerated with `python3 lab/versions.py`.*
""")
    print(f"{PAGE.relative_to(LAB.parent)}: {len(lines)} server lines, {len(client_lines)} client lines, {started} launches")


if __name__ == "__main__":
    main()
