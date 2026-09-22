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
import zipfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lab  # noqa: E402  (shares the Modrinth and cache helpers)

LAB_DIR = Path(__file__).resolve().parent
RUNS = Path(os.environ.get("CRASHSLEUTH_LAB_RUNS", "/tmp/crashsleuth-lab-runs")) / "client"
CORPUS = LAB_DIR / "corpus"


def fabric_fixture(java_home: Path) -> Path:
    """The test mod of the lab, built here with the JDK of this computer (lab.py builds the same jar)."""
    return lab.build_fabric_fixture(java_home)


def neoforge_fixture(java_home: Path) -> Path:
    """Compiles lab/fixtures/neoforge against the FML loader jar the client install downloaded."""
    sources = sorted(p for p in (LAB_DIR / "fixtures" / "neoforge").rglob("*") if p.is_file())
    digest = hashlib.sha1(b"".join(p.read_bytes() for p in sources)).hexdigest()[:10]
    output = lab.CACHE / "fixtures" / f"crashsleuth-fixture-neoforge-{digest}.jar"
    if output.exists():
        return output
    cache = Path(os.environ.get("CRASHSLEUTH_CACHE", Path.home() / ".cache" / "crashsleuth")) / "game"
    loader = sorted((cache / "libraries" / "net" / "neoforged" / "fancymodloader" / "loader").rglob("loader-*.jar"))[-1]
    work = lab.CACHE / "fixtures" / f"neoforge-build-{digest}"
    shutil.rmtree(work, ignore_errors=True)
    (work / "out" / "META-INF").mkdir(parents=True)
    java_files = [str(p) for p in (LAB_DIR / "fixtures" / "neoforge" / "src").rglob("*.java")]
    subprocess.run([str(java_home / "bin" / "javac"), "--release", "21", "-nowarn", "-proc:none", "-d", str(work / "out"), "-cp", str(loader), *java_files], check=True)
    shutil.copy(LAB_DIR / "fixtures" / "neoforge" / "META-INF" / "neoforge.mods.toml", work / "out" / "META-INF" / "neoforge.mods.toml")
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(java_home / "bin" / "jar"), "cf", str(output), "-C", str(work / "out"), "."], check=True)
    return output


def test_world(minecraft: str) -> Path:
    """A world made once by the real server of that version, entered with Quick Play singleplayer."""
    world = lab.CACHE / "worlds" / minecraft / "world"
    if (world / "level.dat").exists():
        return world
    scenario = lab.Scenario(name=f"client-world-{minecraft}", description="world", platform="vanilla", minecraft=minecraft, java=21)
    directory = lab.RUNS / scenario.name
    shutil.rmtree(directory, ignore_errors=True)
    outcome, _ = lab.run_server(scenario, directory, lab.prepare(scenario, directory))
    if outcome != "ready":
        raise RuntimeError(f"the server making the test world did not start ({outcome})")
    world.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(directory / "world", world)
    shutil.rmtree(directory, ignore_errors=True)
    return world


def prepare(scenario: dict, directory: Path, java_home: Path) -> None:
    (directory / "mods").mkdir(parents=True)
    if scenario.get("auto_missing"):
        mod, dependency = lab.auto_missing(scenario.get("loader", "fabric"), scenario["minecraft"], server=False)
        scenario["mods"], scenario["skip"] = [mod], [dependency]
    skip = set(scenario.get("skip", []))
    for slug, path in lab.resolve_mods(scenario.get("mods", []), scenario.get("loader", "fabric"), scenario["minecraft"], skip).items():
        if slug not in skip:
            shutil.copy(path, directory / "mods" / path.name)
    if scenario.get("fixture"):
        build = neoforge_fixture if scenario.get("loader") == "neoforge" else fabric_fixture
        shutil.copy(build(java_home), directory / "mods" / "crashsleuth-fixture-1.0.0.jar")
        (directory / "fixture-mode.txt").write_text(scenario["fixture"] + "\n")
    # Mods of another loader dropped in on purpose: {"slug", "loader"}.
    for extra in scenario.get("extra", []):
        path = lab.modrinth_file(lab.modrinth_version(extra["slug"], extra["loader"], scenario["minecraft"]))
        shutil.copy(path, directory / "mods" / path.name)
    # Files of the game folder: a text, a zip of texts, or bytes that are no zip at all.
    for item in scenario.get("files", []):
        target = directory / item["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        if "zip" in item:
            with zipfile.ZipFile(target, "w") as archive:
                for name, text in item["zip"].items():
                    archive.writestr(name, text)
        elif "garbage" in item:
            target.write_bytes(bytes((i * 131 + 7) % 256 for i in range(item["garbage"])))
        else:
            target.write_text(item["text"])
    if scenario.get("world"):
        shutil.copytree(test_world(scenario["minecraft"]), directory / "saves" / "lab")
    # Small, quiet, no tutorial: nothing waits for the player.
    (directory / "options.txt").write_text("onboardAccessibility:false\nsoundCategory_master:0.0\ntutorialStep:none\nskipMultiplayerWarning:true\n" + scenario.get("options", ""))


LINUX_IMAGE = "crashsleuth-linux-client"


def run_in_container(directory: Path, cli: str, scenario: dict, joining: list[str]) -> str:
    """Runs the game on a virtual screen inside a container: the world is really drawn, nothing is shown."""
    dockerfile = (LAB_DIR / "fixtures" / "linux-client.Dockerfile").read_text()
    subprocess.run(["docker", "build", "--platform", "linux/amd64", "-q", "-t", LINUX_IMAGE, "-"], input=dockerfile, text=True, capture_output=True, check=True)
    cache = lab.CACHE / "linux-client"
    cache.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        # Docker only shares folders named in full, never a path relative to where the lab was started.
        ["docker", "run", "--rm", "--platform", "linux/amd64", "-e", "HOME=/tmp", "-e", "CRASHSLEUTH_CACHE=/cache", "-e", "LIBGL_ALWAYS_SOFTWARE=1",
         "-v", f"{Path(cli).resolve().parent.parent}:/opt/crashsleuth:ro", "-v", f"{cache}:/cache", "-v", f"{directory.resolve()}:/game", LINUX_IMAGE,
         "/opt/crashsleuth/bin/crashsleuth", "run-client", "/game", "--minecraft", scenario["minecraft"], "--loader", scenario.get("loader", "fabric"),
         "--java", "/opt/java/openjdk/bin/java", "--settle", str(scenario.get("settle", 45)), "--timeout", "10", *joining],
        capture_output=True, text=True, timeout=3600)
    if not result.stdout:  # the container itself could not start: say why instead of blaming the game
        print(f"   (container failed: {result.stderr.strip()[:200]})", flush=True)
    return result.stdout.split()[0] if result.stdout else "ERROR"


def run(names: list[str], cli: str, java_home: Path) -> int:
    os.environ["CRASHSLEUTH_CLI_FOR_KEEP"] = cli
    scenarios = json.loads((LAB_DIR / "client-scenarios.json").read_text())
    # The version matrix of the game client (lab/matrix.py).
    if (LAB_DIR / "client-matrix.json").exists():
        scenarios += json.loads((LAB_DIR / "client-matrix.json").read_text())
    import fnmatch
    if names != ["all"]:
        scenarios = [s for s in scenarios if any(fnmatch.fnmatch(s["name"], n) for n in names)]
        names = ["all"]
    selected = scenarios if names == ["all"] else [s for s in scenarios if s["name"] in names]
    java = str(java_home / "bin" / "java")
    failures = 0
    for scenario in selected:
        directory = RUNS / scenario["name"]
        shutil.rmtree(directory, ignore_errors=True)
        print(f"== {scenario['name']}: {scenario['description']}", flush=True)
        # 26.x needs Java 25: CRASHSLEUTH_JAVA25_HOME points at one.
        java = str(Path(os.environ["CRASHSLEUTH_JAVA25_HOME"]) / "bin" / "java") if scenario.get("java") == 25 else str(java_home / "bin" / "java")
        common = ["--minecraft", scenario["minecraft"], "--java", java]
        try:
            prepare(scenario, directory, java_home)
            started = time.time()
            joining = ["--join", "world:lab"] if scenario.get("world") else []
            if scenario.get("render"):
                # Drawing the world needs a real render loop: a hidden window stops drawing on macOS.
                outcome = run_in_container(directory, cli, scenario, joining)
            else:
                launch = subprocess.run([cli, "run-client", str(directory), "--loader", scenario.get("loader", "fabric"), *common, "--settle", "8", *joining], capture_output=True, text=True, timeout=900)
                outcome = launch.stdout.split()[0] if launch.stdout else "ERROR"
                # No screen at all (locked or asleep): the game cannot open a window, so it runs on a virtual one.
                log = directory / "logs" / "latest.log"
                if "primary monitor" in (launch.stdout + launch.stderr + (log.read_text(errors="replace") if log.exists() else "")):
                    print("   (no usable screen on this computer: running it on a virtual screen)", flush=True)
                    shutil.rmtree(directory / "logs", ignore_errors=True)
                    shutil.rmtree(directory / "crash-reports", ignore_errors=True)
                    outcome = run_in_container(directory, cli, scenario, joining)
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
                search = subprocess.run([cli, "bisect", str(directory), "--client", "--loader", scenario.get("loader", "fabric"), *common, "--json",
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
    # The installed mods, as the server lab keeps them: the replay sees what the analysis saw.
    inventory = subprocess.run([os.environ.get("CRASHSLEUTH_CLI_FOR_KEEP", "crashsleuth"), "inventory", str(directory)], capture_output=True, text=True)
    if inventory.returncode == 0 and inventory.stdout.strip().startswith("{"):
        (target / "inventory.json").write_text(private(lab.anonymise(inventory.stdout)).replace(str(directory), "/game"))
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
