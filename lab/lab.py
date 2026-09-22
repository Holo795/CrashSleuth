#!/usr/bin/env python3
"""CrashSleuth real-world lab.

Builds real Minecraft servers (vanilla, Paper, Purpur, Fabric, NeoForge, Forge) from official
sources, installs real mods and plugins from Modrinth, breaks them on purpose, runs them in Docker
with the right Java version, then checks that CrashSleuth finds the expected cause.

Only the resulting logs are kept (lab/corpus). Server jars, mods and plugins are downloaded into a
local cache and never committed.

Requirements: Linux host with Docker and Python 3.9+. Usage:
    python3 lab/lab.py list
    python3 lab/lab.py run all --cli /path/to/crashsleuth/bin/crashsleuth
    python3 lab/lab.py run paper-java17 neoforge-missing-dependency
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
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

LAB_DIR = Path(__file__).resolve().parent
CACHE = Path(os.environ.get("CRASHSLEUTH_LAB_CACHE", Path.home() / ".cache" / "crashsleuth-lab"))
RUNS = Path(os.environ.get("CRASHSLEUTH_LAB_RUNS", "/tmp/crashsleuth-lab-runs"))
CORPUS = LAB_DIR / "corpus"
USER_AGENT = "Holo795/CrashSleuth-lab (github.com/Holo795/CrashSleuth)"
DONE = re.compile(r"Done \([\d.,]+s\)! For help, type")


# ---------------------------------------------------------------------------------------- network

def http_json(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def download(url: str, name: str | None = None, sha1: str | None = None) -> Path:
    """Downloads into the cache once; returns the cached path."""
    digest = hashlib.sha1(url.encode()).hexdigest()[:12]
    target = CACHE / "files" / digest / (name or Path(urllib.parse.urlparse(url).path).name)
    if target.exists():
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=300) as response, open(f"{target}.part", "wb") as out:
        shutil.copyfileobj(response, out)
    if sha1 and hashlib.sha1(Path(f"{target}.part").read_bytes()).hexdigest() != sha1:
        raise RuntimeError(f"checksum mismatch for {url}")
    os.replace(f"{target}.part", target)
    return target


# ---------------------------------------------------------------------------------------- servers

def vanilla_server(version: str) -> Path:
    manifest = http_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
    entry = next(v for v in manifest["versions"] if v["id"] == version)
    server = http_json(entry["url"])["downloads"]["server"]
    return download(server["url"], f"minecraft-server-{version}.jar", server["sha1"])


def paper_server(version: str, project: str = "paper") -> Path:
    build = http_json(f"https://fill.papermc.io/v3/projects/{project}/versions/{version}/builds/latest")
    file = build["downloads"]["server:default"]
    return download(file["url"], f"{project}-{version}-{build['id']}.jar")


def purpur_server(version: str) -> Path:
    build = http_json(f"https://api.purpurmc.org/v2/purpur/{version}")["builds"]["latest"]
    return download(f"https://api.purpurmc.org/v2/purpur/{version}/{build}/download", f"purpur-{version}-{build}.jar")


def fabric_server(version: str) -> Path:
    loader = http_json(f"https://meta.fabricmc.net/v2/versions/loader/{version}")[0]["loader"]["version"]
    installer = http_json("https://meta.fabricmc.net/v2/versions/installer")[0]["version"]
    url = f"https://meta.fabricmc.net/v2/versions/loader/{version}/{loader}/{installer}/server/jar"
    return download(url, f"fabric-server-{version}-{loader}.jar")


def maven_latest(metadata_url: str, prefix: str) -> str:
    request = urllib.request.Request(metadata_url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        versions = re.findall(r"<version>([^<]+)</version>", response.read().decode())
    matching = [v for v in versions if v.startswith(prefix) and "beta" not in v and "alpha" not in v]
    return matching[-1]


def neoforge_installer(minecraft: str) -> tuple[Path, str]:
    prefix = ".".join(minecraft.split(".")[1:]) + "."  # 1.21.1 -> 21.1.
    version = maven_latest("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml", prefix)
    url = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{version}/neoforge-{version}-installer.jar"
    return download(url), version


def forge_installer(minecraft: str) -> tuple[Path, str]:
    version = maven_latest("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml", minecraft + "-")
    url = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{version}/forge-{version}-installer.jar"
    return download(url), version


# ---------------------------------------------------------------------------------------- Modrinth

def modrinth_version(slug: str, loader: str, minecraft: str, index: int = 0) -> dict:
    """Latest release (or the index-th one) of a project for a loader and Minecraft version."""
    query = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps([minecraft])})
    versions = http_json(f"https://api.modrinth.com/v2/project/{slug}/version?{query}")
    if not versions:
        raise LookupError(f"{slug} has no {loader} build for {minecraft}")
    stable = [v for v in versions if v["version_type"] == "release"] or versions
    return stable[min(index, len(stable) - 1)]


def modrinth_file(version: dict) -> Path:
    file = next((f for f in version["files"] if f["primary"]), version["files"][0])
    return download(file["url"], file["filename"], file["hashes"].get("sha1"))


def resolve_mods(slugs: list[str], loader: str, minecraft: str, skip: set[str]) -> dict[str, Path]:
    """Downloads mods and their required dependencies (unless listed in skip)."""
    resolved: dict[str, Path] = {}
    queue = list(slugs)
    seen: set[str] = set()
    while queue:
        slug = queue.pop(0)
        if slug in seen:
            continue
        seen.add(slug)
        version = modrinth_version(slug, loader, minecraft)
        resolved[slug] = modrinth_file(version)
        for dependency in version["dependencies"]:
            if dependency["dependency_type"] != "required" or not dependency.get("project_id"):
                continue
            project = http_json(f"https://api.modrinth.com/v2/project/{dependency['project_id']}")
            if project["slug"] not in skip:
                queue.append(project["slug"])
    return resolved


def install_modpack(slug: str, loader: str, minecraft: str, directory: Path, remove: list[str]) -> list[str]:
    """Installs the server side of a Modrinth modpack; returns the names of the files removed on purpose."""
    version = modrinth_version(slug, loader, minecraft)
    archive = modrinth_file(version)
    removed = []
    with zipfile.ZipFile(archive) as pack:
        index = json.loads(pack.read("modrinth.index.json"))
        for file in index["files"]:
            if file.get("env", {}).get("server") == "unsupported":
                continue
            name = Path(file["path"]).name
            if any(token.lower() in name.lower() for token in remove):
                removed.append(name)
                continue
            target = directory / file["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(download(file["downloads"][0], name, file["hashes"].get("sha1")), target)
        for member in pack.namelist():
            for prefix in ("overrides/", "server-overrides/"):
                if member.startswith(prefix) and not member.endswith("/"):
                    target = directory / member[len(prefix):]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(pack.read(member))
    return removed


def paper_api(minecraft: str) -> Path:
    """Paper API jar for compiling the fixture plugin."""
    base = f"https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/{minecraft}-R0.1-SNAPSHOT"
    request = urllib.request.Request(f"{base}/maven-metadata.xml", headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        metadata = response.read().decode()
    stamp = re.search(r"<timestamp>([^<]+)</timestamp>", metadata).group(1)
    build = re.search(r"<buildNumber>([^<]+)</buildNumber>", metadata).group(1)
    return download(f"{base}/paper-api-{minecraft}-R0.1-{stamp}-{build}.jar")


def build_paper_fixture(minecraft: str) -> Path:
    """Compiles lab/fixtures/paper into a plugin jar, in a Java container."""
    sources = sorted(p for p in (LAB_DIR / "fixtures" / "paper").rglob("*") if p.is_file())
    digest = hashlib.sha1(b"".join(p.read_bytes() for p in sources)).hexdigest()[:10]
    output = CACHE / "fixtures" / f"CrashSleuthFixture-{minecraft}-{digest}.jar"
    if output.exists():
        return output
    work = CACHE / "fixtures" / f"build-{minecraft}"
    shutil.rmtree(work, ignore_errors=True)
    shutil.copytree(LAB_DIR / "fixtures" / "paper", work / "src-root")
    shutil.copy(paper_api(minecraft), work / "paper-api.jar")
    script = ("mkdir -p out && javac -nowarn -proc:none -d out -cp paper-api.jar $(find src-root/src -name '*.java') "
              "&& cp src-root/plugin.yml out/ && cd out && jar cf ../fixture.jar .")
    subprocess.run(["docker", "run", "--rm", "-v", f"{work}:/w", "-w", "/w", "eclipse-temurin:21-jdk", "sh", "-c", script],
                   check=True, capture_output=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(work / "fixture.jar", output)
    return output


# ---------------------------------------------------------------------------------------- scenarios

@dataclass
class Expectation:
    situation: str
    culprit: str | None = None  # substring expected in one culprit id or name
    primary: bool = True        # must be the first finding


@dataclass
class Scenario:
    name: str
    description: str
    platform: str               # vanilla, paper, purpur, fabric, neoforge, forge
    minecraft: str
    java: int
    mods: list[str] = field(default_factory=list)       # Modrinth slugs (mods or plugins)
    skip: list[str] = field(default_factory=list)       # dependencies deliberately left out
    extra: list[dict] = field(default_factory=list)     # {"slug", "loader", "minecraft"?, "index"?}: extra files as they are
    modpack: str | None = None                          # Modrinth modpack slug, server side installed
    remove: list[str] = field(default_factory=list)     # modpack files removed on purpose (name substrings)
    fixture: str | None = None                          # fixture plugin mode (Paper): enable-npe, task-exception, main-thread-hang
    after_ready: int = 0                                # seconds to keep the server running once it is ready
    memory: str = "2G"
    timeout: int = 420
    crashes: bool = False                               # expected to crash without any explanation in its logs
    flaky: bool = False                                 # crashes only sometimes: the first start may succeed
    bisect: dict | None = None                          # {"culprits": [...], "max_runs"?}: culprit search expected result
    expect: Expectation | None = None                   # None: must start cleanly with no finding (unless crashes)


def load_scenarios() -> list[Scenario]:
    data = json.loads((LAB_DIR / "scenarios.json").read_text())
    scenarios = []
    for item in data:
        expect = item.pop("expect", None)
        scenarios.append(Scenario(**item, expect=Expectation(**expect) if expect else None))
    return scenarios


# ---------------------------------------------------------------------------------------- running

def prepare(scenario: Scenario, directory: Path) -> list[str]:
    """Builds the server directory and returns the command to run inside the container."""
    directory.mkdir(parents=True)
    (directory / "eula.txt").write_text("eula=true\n")
    # Default world generation: a flat world without generator settings makes the server log an error of its own.
    (directory / "server.properties").write_text("online-mode=false\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\n")
    java = f"-Xms256M -Xmx{scenario.memory}"
    mods_dir = directory / ("plugins" if scenario.platform in ("paper", "purpur") else "mods")

    if scenario.platform == "vanilla":
        shutil.copy(vanilla_server(scenario.minecraft), directory / "server.jar")
        command = f"java {java} -jar server.jar nogui"
    elif scenario.platform in ("paper", "purpur"):
        jar = paper_server(scenario.minecraft) if scenario.platform == "paper" else purpur_server(scenario.minecraft)
        shutil.copy(jar, directory / "server.jar")
        command = f"java {java} -jar server.jar nogui"
    elif scenario.platform == "fabric":
        shutil.copy(fabric_server(scenario.minecraft), directory / "server.jar")
        command = f"java {java} -jar server.jar nogui"
    elif scenario.platform in ("neoforge", "forge"):
        installer, version = neoforge_installer(scenario.minecraft) if scenario.platform == "neoforge" else forge_installer(scenario.minecraft)
        shutil.copy(installer, directory / "installer.jar")
        (directory / "user_jvm_args.txt").write_text(java + "\n")
        command = "java -jar installer.jar --installServer > installer.log 2>&1 && rm -f installer.jar installer.jar.log && sh run.sh nogui"
    else:
        raise ValueError(scenario.platform)

    loader = {"paper": "paper", "purpur": "paper"}.get(scenario.platform, scenario.platform)
    if scenario.mods:
        mods_dir.mkdir(exist_ok=True)
        for slug, path in resolve_mods(scenario.mods, loader, scenario.minecraft, set(scenario.skip)).items():
            if slug not in scenario.skip:
                shutil.copy(path, mods_dir / path.name)
    for item in scenario.extra:
        mods_dir.mkdir(exist_ok=True)
        version = modrinth_version(item["slug"], item["loader"], item.get("minecraft", scenario.minecraft), item.get("index", 0))
        path = modrinth_file(version)
        shutil.copy(path, mods_dir / path.name)
    if scenario.modpack:
        removed = install_modpack(scenario.modpack, loader, scenario.minecraft, directory, scenario.remove)
        (directory / "removed-on-purpose.txt").write_text("\n".join(removed) + "\n")
    if scenario.fixture:
        mods_dir.mkdir(exist_ok=True)
        shutil.copy(build_paper_fixture(scenario.minecraft), mods_dir / "CrashSleuthFixture-1.0.0.jar")
        (directory / "fixture-mode.txt").write_text(scenario.fixture + "\n")
    return ["sh", "-c", command]


def run_server(scenario: Scenario, directory: Path, command: list[str]) -> tuple[str, float]:
    """Runs the server until it is ready, crashes, or times out. Returns the outcome and duration."""
    image = f"eclipse-temurin:{scenario.java}-jdk"
    name = f"crashsleuth-lab-{scenario.name}"
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    start = time.time()
    process = subprocess.Popen(
        ["docker", "run", "--rm", "--name", name, "-i", "-v", f"{directory}:/srv", "-w", "/srv", "-e", "HOME=/srv",
         "--memory", "6g", "--user", f"{os.getuid()}:{os.getgid()}", image, *command],
        stdin=subprocess.PIPE, stdout=open(directory / "console.log", "wb"), stderr=subprocess.STDOUT,
    )
    outcome = "timeout"
    while time.time() - start < scenario.timeout:
        if process.poll() is not None:
            outcome = "exited"
            break
        console = (directory / "console.log").read_text(errors="replace")
        if DONE.search(console):
            outcome = "ready"
            # Some problems only show up once the server runs (plugin tasks, main thread blocked).
            deadline = time.time() + scenario.after_ready
            while time.time() < deadline and process.poll() is None:
                time.sleep(2)
            break
        time.sleep(2)
    if process.poll() is None:
        if outcome == "ready":
            try:
                process.stdin.write(b"stop\n")
                process.stdin.flush()
                process.wait(timeout=60)
            except Exception:
                pass
        subprocess.run(["docker", "rm", "-f", name], capture_output=True)
        process.wait()
    return outcome, time.time() - start


def pick_log(directory: Path) -> Path:
    """The most useful file to analyse: newest crash report, else latest.log, else the console."""
    crashes = sorted((directory / "crash-reports").glob("*.txt"), key=lambda p: p.stat().st_mtime)
    if crashes:
        return crashes[-1]
    latest = directory / "logs" / "latest.log"
    console = directory / "console.log"
    if latest.exists() and latest.stat().st_size > 0:
        # Some fatal errors (wrong Java, early loader failures) only reach the console.
        if console.exists() and "Exception" in console.read_text(errors="replace") and "Exception" not in latest.read_text(errors="replace"):
            return console
        return latest
    return console


def cli_json(cli: str, *arguments: str) -> dict:
    result = subprocess.run([cli, *arguments], capture_output=True, text=True, timeout=180)
    if result.returncode != 0:
        raise RuntimeError(result.stderr or result.stdout)
    return json.loads(result.stdout)


def analyse(cli: str, directory: Path, log: Path) -> tuple[dict, dict]:
    """Diagnosis of the server folder (installed jars) with the chosen log, and the inventory itself."""
    report = cli_json(cli, "analyze", str(directory), str(log), "--json")
    inventory = cli_json(cli, "inventory", str(directory))
    return report, inventory


def verdict(scenario: Scenario, outcome: str, report: dict) -> tuple[bool, str]:
    findings = report.get("findings", [])
    summary = ", ".join(
        f"{f['situation']}[{'/'.join(c['id'] for c in f.get('culprits', []))}]" for f in findings[:3]
    ) or "no finding"
    if scenario.expect is None and scenario.flaky:
        return True, f"started={outcome} (random crash), {summary}"
    if scenario.expect is None and scenario.crashes:
        return outcome != "ready", f"started={outcome} (crash expected), {summary}"
    if scenario.expect is None:
        ok = outcome == "ready" and not findings
        return ok, f"started={outcome}, {summary}"
    candidates = findings[:1] if scenario.expect.primary else findings
    for finding in candidates:
        if finding["situation"] != scenario.expect.situation:
            continue
        if scenario.expect.culprit is None:
            return True, summary
        needle = scenario.expect.culprit.lower()
        if any(needle in (c.get("id") or "").lower() or needle in (c.get("name") or "").lower() for c in finding.get("culprits", [])):
            return True, summary
    return False, f"started={outcome}, expected {scenario.expect.situation}[{scenario.expect.culprit}], got {summary}"


def bisect(cli_home: str, scenario: Scenario, directory: Path) -> tuple[bool, str, dict | None]:
    """Runs the culprit search on the server folder, inside a Java container, and checks its answer."""
    expected = [name.lower() for name in scenario.bisect["culprits"]]
    command = [
        "docker", "run", "--rm", "--memory", "6500m", "--user", f"{os.getuid()}:{os.getgid()}", "-e", "HOME=/tmp",
        "-v", f"{cli_home}:/opt/crashsleuth:ro", "-v", f"{RUNS}:{RUNS}", f"eclipse-temurin:{scenario.java}-jdk",
        "/opt/crashsleuth/bin/crashsleuth", "bisect", str(directory), "--json", "--work", "/tmp/bisect",
        "--max-runs", str(scenario.bisect.get("max_runs", 30)), "--timeout", str(max(5, scenario.timeout // 60)),
        *scenario.bisect.get("args", []),
    ]
    started = time.time()
    result = subprocess.run(command, capture_output=True, text=True, timeout=4 * 3600)
    (directory / "bisect.log").write_text(result.stderr)
    if result.returncode != 0:
        return False, f"bisect error: {result.stderr[-400:]}", None
    answer = json.loads(result.stdout)
    found = [c.lower() for c in answer["culprits"]]
    ok = answer["complete"] and len(found) == len(expected) and all(any(e in f for f in found) for e in expected)
    detail = f"bisect: {answer['labels']} in {len(answer['runs'])} launches, {(time.time() - started) / 60:.1f} min"
    return ok, detail, answer


def anonymise(text: str) -> str:
    # IPv4 addresses only: versions such as jei-19.50.0.414 or 1.21.1.2 must survive.
    text = re.sub(r"(?<=/)\d{1,3}(?:\.\d{1,3}){3}\b|\b\d{1,3}(?:\.\d{1,3}){3}(?=:\d)", "0.0.0.0", text)
    return text.replace(str(RUNS), "/srv")


def run(names: list[str], cli: str, keep: bool) -> int:
    scenarios = {s.name: s for s in load_scenarios()}
    selected = list(scenarios.values()) if names == ["all"] else [scenarios[n] for n in names]
    failures = 0
    for scenario in selected:
        directory = RUNS / scenario.name
        shutil.rmtree(directory, ignore_errors=True)
        print(f"== {scenario.name}: {scenario.description}", flush=True)
        answer = None
        try:
            command = prepare(scenario, directory)
            outcome, duration = run_server(scenario, directory, command)
            log = pick_log(directory)
            report, inventory = analyse(cli, directory, log)
            ok, detail = verdict(scenario, outcome, report)
            if scenario.bisect:
                print(f"   analysis {'PASS' if ok else 'FAIL'}: {detail}", flush=True)
                found, detail, answer = bisect(os.environ.get("CRASHSLEUTH_HOME", "/tmp/crashsleuth-cli"), scenario, directory)
                ok = ok and found
        except Exception as error:  # a broken scenario must not stop the others
            ok, detail, duration, log, inventory = False, f"lab error: {error}", 0.0, None, None
        failures += 0 if ok else 1
        print(f"   {'PASS' if ok else 'FAIL'} ({duration:.0f} s) {detail}", flush=True)
        if log is not None:
            target = CORPUS / scenario.name
            # A case is replaced as a whole: files of an older run must not stay behind.
            shutil.rmtree(target, ignore_errors=True)
            target.mkdir(parents=True, exist_ok=True)
            (target / log.name).write_text(anonymise(log.read_text(errors="replace")))
            if answer is not None:
                (target / "bisect.json").write_text(json.dumps(answer, indent=1) + "\n")
            if inventory is not None:
                (target / "inventory.json").write_text(anonymise(json.dumps(inventory, indent=1)) + "\n")
            (target / "expected.json").write_text(json.dumps(
                {"situation": scenario.expect.situation if scenario.expect else None,
                 "culprit": scenario.expect.culprit if scenario.expect else None,
                 "logs": [log.name], **({"crashes": True} if scenario.crashes else {})}, indent=2) + "\n")
        if not keep:
            shutil.rmtree(directory, ignore_errors=True)
    print(f"\n{len(selected) - failures}/{len(selected)} scenarios passed")
    return 1 if failures else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list")
    runner = sub.add_parser("run")
    runner.add_argument("scenarios", nargs="+")
    runner.add_argument("--cli", default=os.environ.get("CRASHSLEUTH_CLI", "crashsleuth"))
    runner.add_argument("--keep", action="store_true", help="keep the server directories")
    args = parser.parse_args()
    if args.command == "list":
        for scenario in load_scenarios():
            print(f"{scenario.name:40} {scenario.description}")
        return 0
    return run(args.scenarios, args.cli, args.keep)


if __name__ == "__main__":
    sys.exit(main())
