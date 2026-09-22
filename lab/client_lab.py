#!/usr/bin/env python3
"""CrashSleuth real-world lab, game client side.

Runs on a computer with a screen (the game opens small windows): builds game folders with real mods
from Modrinth, breaks them on purpose, launches the real client through the CrashSleuth command line
(which installs Minecraft in its own cache), and checks the diagnosis and the culprit search.

Usage:
    python3 lab/client_lab.py run all --cli cli/build/install/crashsleuth/bin/crashsleuth --java /path/to/java
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lab  # noqa: E402  (shares the Modrinth and cache helpers)

LAB_DIR = Path(__file__).resolve().parent
RUNS = Path(os.environ.get("CRASHSLEUTH_LAB_RUNS", "/tmp/crashsleuth-lab-runs")) / "client"
CORPUS = LAB_DIR / "corpus"


def fabric_fixture(java_home: Path) -> Path:
    """Compiles lab/fixtures/fabric against Fabric Loader only (it needs no Minecraft class)."""
    sources = sorted(p for p in (LAB_DIR / "fixtures" / "fabric").rglob("*") if p.is_file())
    digest = hashlib.sha1(b"".join(p.read_bytes() for p in sources)).hexdigest()[:10]
    output = lab.CACHE / "fixtures" / f"crashsleuth-fixture-fabric-{digest}.jar"
    if output.exists():
        return output
    loader = json.load(urllib.request.urlopen(urllib.request.Request("https://meta.fabricmc.net/v2/versions/loader", headers={"User-Agent": lab.USER_AGENT})))[0]["version"]
    api = lab.download(f"https://maven.fabricmc.net/net/fabricmc/fabric-loader/{loader}/fabric-loader-{loader}.jar")
    work = lab.CACHE / "fixtures" / f"fabric-build-{digest}"
    shutil.rmtree(work, ignore_errors=True)
    (work / "out").mkdir(parents=True)
    java_files = [str(p) for p in (LAB_DIR / "fixtures" / "fabric" / "src").rglob("*.java")]
    subprocess.run([str(java_home / "bin" / "javac"), "--release", "21", "-nowarn", "-proc:none", "-d", str(work / "out"), "-cp", str(api), *java_files], check=True)
    shutil.copy(LAB_DIR / "fixtures" / "fabric" / "fabric.mod.json", work / "out" / "fabric.mod.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(java_home / "bin" / "jar"), "cf", str(output), "-C", str(work / "out"), "."], check=True)
    return output


def prepare(scenario: dict, directory: Path, java_home: Path) -> None:
    (directory / "mods").mkdir(parents=True)
    skip = set(scenario.get("skip", []))
    for slug, path in lab.resolve_mods(scenario.get("mods", []), "fabric", scenario["minecraft"], skip).items():
        if slug not in skip:
            shutil.copy(path, directory / "mods" / path.name)
    if scenario.get("fixture"):
        shutil.copy(fabric_fixture(java_home), directory / "mods" / "crashsleuth-fixture-1.0.0.jar")
        (directory / "fixture-mode.txt").write_text(scenario["fixture"] + "\n")
    # Small, quiet, no tutorial: nothing waits for the player.
    (directory / "options.txt").write_text("onboardAccessibility:false\nsoundCategory_master:0.0\ntutorialStep:none\nskipMultiplayerWarning:true\n")


def run(names: list[str], cli: str, java_home: Path) -> int:
    scenarios = json.loads((LAB_DIR / "client-scenarios.json").read_text())
    selected = scenarios if names == ["all"] else [s for s in scenarios if s["name"] in names]
    java = str(java_home / "bin" / "java")
    failures = 0
    for scenario in selected:
        directory = RUNS / scenario["name"]
        shutil.rmtree(directory, ignore_errors=True)
        print(f"== {scenario['name']}: {scenario['description']}", flush=True)
        common = ["--minecraft", scenario["minecraft"], "--java", java]
        try:
            prepare(scenario, directory, java_home)
            started = time.time()
            launch = subprocess.run([cli, "run-client", str(directory), "--loader", "fabric", *common, "--settle", "8"], capture_output=True, text=True, timeout=900)
            outcome = launch.stdout.split()[0] if launch.stdout else "ERROR"
            report = json.loads(subprocess.run([cli, "analyze", str(directory), "--json"], capture_output=True, text=True, timeout=300).stdout)
            findings = report["findings"]
            summary = ", ".join(f"{f['situation']}[{'/'.join(c['id'] for c in f['culprits'])}]" for f in findings[:3]) or "no finding"
            expect = scenario.get("expect")
            if expect is None and scenario.get("crashes"):
                ok = outcome != "READY"
            elif expect is None:
                ok = outcome == "READY" and not findings
            else:
                primary = findings[0] if findings else None
                ok = primary is not None and primary["situation"] == expect["situation"] and (
                    expect.get("culprit") is None or any(expect["culprit"].lower() in (c.get("id") or "").lower() for c in primary["culprits"]))
            detail = f"launch={outcome} ({time.time() - started:.0f} s), {summary}"
            if scenario.get("bisect"):
                print(f"   analysis {'PASS' if ok else 'FAIL'}: {detail}", flush=True)
                started = time.time()
                search = subprocess.run([cli, "bisect", str(directory), "--client", "--loader", "fabric", *common, "--json",
                                         "--work", str(RUNS / "work"), *scenario["bisect"].get("args", [])], capture_output=True, text=True, timeout=4 * 3600)
                (directory / "bisect.log").write_text(search.stderr)
                answer = json.loads(search.stdout)
                found = [c.lower() for c in answer["culprits"]]
                expected = [c.lower() for c in scenario["bisect"]["culprits"]]
                ok = ok and answer["complete"] and len(found) == len(expected) and all(any(e in f for f in found) for e in expected)
                detail = f"bisect: {answer['labels']} in {len(answer['runs'])} launches, {(time.time() - started) / 60:.1f} min"
                (directory / "bisect.json").write_text(json.dumps(answer, indent=1) + "\n")
        except Exception as error:  # a broken scenario must not stop the others
            ok, detail = False, f"lab error: {error}"
        failures += 0 if ok else 1
        print(f"   {'PASS' if ok else 'FAIL'} {detail}", flush=True)
        keep(scenario, directory)
    print(f"\n{len(selected) - failures}/{len(selected)} client scenarios passed")
    return 1 if failures else 0


def private(text: str) -> str:
    """Removes paths that name the computer's user: the tool's cache, the home folder, temporary folders."""
    cache = os.environ.get("CRASHSLEUTH_CACHE")
    if cache:
        text = text.replace(cache, "/cache")
    text = re.sub(r"/private/tmp/[^\s:;'\"]*?/scratchpad", "/tmp", text)
    return re.sub(r"/(?:Users|home)/[^/\s:;'\"]+", "/home/user", text)


def keep(scenario: dict, directory: Path) -> None:
    """Keeps the logs and the answer in the corpus, like the server lab."""
    target = CORPUS / f"client-{scenario['name']}"
    shutil.rmtree(target, ignore_errors=True)
    logs = sorted((directory / "crash-reports").glob("*.txt"))[-1:] + [p for p in [directory / "logs" / "latest.log"] if p.exists()]
    if not logs:
        return
    target.mkdir(parents=True)
    for log in logs:
        (target / log.name).write_text(private(lab.anonymise(log.read_text(errors="replace")).replace(str(directory), "/game")))
    for extra in ("bisect.json",):
        if (directory / extra).exists():
            shutil.copy(directory / extra, target / extra)
    expect = scenario.get("expect") or {}
    (target / "expected.json").write_text(json.dumps(
        {"situation": expect.get("situation"), "culprit": expect.get("culprit"), "logs": [p.name for p in logs],
         **({"crashes": True} if scenario.get("crashes") else {})}, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    runner = sub.add_parser("run")
    runner.add_argument("scenarios", nargs="+")
    runner.add_argument("--cli", required=True)
    runner.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    args = parser.parse_args()
    return run(args.scenarios, args.cli, Path(args.java_home))


if __name__ == "__main__":
    sys.exit(main())
