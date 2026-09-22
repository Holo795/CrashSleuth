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


def fabric_server(version: str, loader: str | None = None) -> Path:
    loader = loader or http_json(f"https://meta.fabricmc.net/v2/versions/loader/{version}")[0]["loader"]["version"]
    installer = http_json("https://meta.fabricmc.net/v2/versions/installer")[0]["version"]
    url = f"https://meta.fabricmc.net/v2/versions/loader/{version}/{loader}/{installer}/server/jar"
    return download(url, f"fabric-server-{version}-{loader}.jar")


def neoforge_versions(artifact: str = "neoforge") -> list[str]:
    """NeoForge's own API: its maven-metadata.xml answers 404 now and then (seen on 22/09/2026)."""
    try:
        return http_json(f"https://maven.neoforged.net/api/maven/versions/releases/net%2Fneoforged%2F{artifact}")["versions"]
    except Exception:
        return maven_versions(f"https://maven.neoforged.net/releases/net/neoforged/{artifact}/maven-metadata.xml")


def maven_versions(metadata_url: str) -> list[str]:
    request = urllib.request.Request(metadata_url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return re.findall(r"<version>([^<]+)</version>", response.read().decode())


def maven_latest(metadata_url: str, prefix: str) -> str:
    request = urllib.request.Request(metadata_url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        versions = re.findall(r"<version>([^<]+)</version>", response.read().decode())
    matching = [v for v in versions if v.startswith(prefix) and "beta" not in v and "alpha" not in v]
    return matching[-1]


def neoforge_installer(minecraft: str) -> tuple[Path, str]:
    if minecraft == "1.20.1":
        # NeoForge began as a fork of Forge 1.20.1, published as net.neoforged:forge.
        version = [v for v in neoforge_versions("forge") if v.startswith("1.20.1-")][-1]
        return download(f"https://maven.neoforged.net/releases/net/neoforged/forge/{version}/forge-{version}-installer.jar"), version
    # 1.21.1 -> 21.1., 1.21 -> 21.0., 26.2 -> 26.2.
    parts = minecraft.split(".")
    prefix = (f"{parts[1]}.{parts[2] if len(parts) > 2 else 0}." if parts[0] == "1" else minecraft + ".")
    version = [v for v in neoforge_versions() if v.startswith(prefix) and "beta" not in v and "alpha" not in v][-1]
    url = f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{version}/neoforge-{version}-installer.jar"
    return download(url), version


def java_for(minecraft: str) -> int:
    """Java the game itself needs: 17 up to 1.20.4, 21 from 1.20.5, 25 for 26.x."""
    parts = [int(p) for p in minecraft.split(".")]
    if parts[0] >= 26:
        return 25
    return 21 if (parts[1], parts[2] if len(parts) > 2 else 0) >= (20, 5) else 17


def spigot_server(version: str) -> Path:
    """Spigot is only published as source: SpigotMC's BuildTools compiles it (once, in a container with git)."""
    target = CACHE / "files" / f"spigot-{version}.jar"
    if target.exists():
        return target
    work = CACHE / "buildtools" / version
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    shutil.copy(download("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar", "BuildTools.jar"), work / "BuildTools.jar")
    image = f"crashsleuth-buildtools-{java_for(version)}"
    subprocess.run(["docker", "build", "-q", "-t", image, "-"], input=f"FROM eclipse-temurin:{java_for(version)}-jdk\nRUN apt-get update && apt-get install -y --no-install-recommends git && rm -rf /var/lib/apt/lists/*\n",
                   text=True, capture_output=True, check=True)
    # MAVEN_OPTS: without a home for this user, Maven installs into a folder called "?" and cannot find it again.
    result = subprocess.run(["docker", "run", "--rm", "-v", f"{work}:/work", "-w", "/work", "--user", f"{os.getuid()}:{os.getgid()}", "-e", "HOME=/work",
                             "-e", "MAVEN_OPTS=-Duser.home=/work", image, "java", "-jar", "BuildTools.jar", "--rev", version, "--output-dir", "/work/out"],
                            capture_output=True, text=True, timeout=3600)
    built = sorted((work / "out").glob("spigot-*.jar"))
    if not built:
        raise RuntimeError(f"BuildTools could not build Spigot {version}: {result.stdout[-500:]}")
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(built[-1], target)
    shutil.rmtree(work, ignore_errors=True)
    return target


def quilt_install(version: str, directory: Path) -> None:
    """Quilt's own installer puts the loader and the game server in the folder."""
    installer_version = maven_latest("https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/maven-metadata.xml", "")
    installer = download(f"https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/{installer_version}/quilt-installer-{installer_version}.jar")
    java = os.environ.get("JAVA_HOME", "") + "/bin/java" if os.environ.get("JAVA_HOME") else "java"
    subprocess.run([java, "-jar", str(installer), "install", "server", version, "--download-server", f"--install-dir={directory}"],
                   capture_output=True, text=True, check=True, timeout=900)


def forge_installer(minecraft: str) -> tuple[Path, str]:
    version = maven_latest("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml", minecraft + "-")
    url = f"https://maven.minecraftforge.net/net/minecraftforge/forge/{version}/forge-{version}-installer.jar"
    return download(url), version


# ---------------------------------------------------------------------------------------- Modrinth

def modrinth_version(slug: str, loader: str, minecraft: str, index: int = 0) -> dict:
    """Latest release (or the index-th one) of a project for a loader and Minecraft version.
    "sodium@mc1.21.1-0.6" pins the newest version whose number starts with what follows the @."""
    slug, _, pin = slug.partition("@")
    query = urllib.parse.urlencode({"loaders": json.dumps([loader]), "game_versions": json.dumps([minecraft])})
    versions = http_json(f"https://api.modrinth.com/v2/project/{slug}/version?{query}")
    if not versions:
        raise LookupError(f"{slug} has no {loader} build for {minecraft}")
    stable = [v for v in versions if v["version_type"] == "release"] or versions
    if pin:
        stable = [v for v in versions if v["version_number"].startswith(pin)]
        if not stable:
            raise LookupError(f"{slug} has no {loader} version starting with {pin} for {minecraft}")
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
        if slug.partition("@")[0] in seen:
            continue
        seen.add(slug.partition("@")[0])
        version = modrinth_version(slug, loader, minecraft)
        resolved[slug.partition("@")[0]] = modrinth_file(version)
        for dependency in version["dependencies"]:
            if dependency["dependency_type"] != "required" or not dependency.get("project_id"):
                continue
            project = http_json(f"https://api.modrinth.com/v2/project/{dependency['project_id']}")
            if project["slug"] not in skip:
                queue.append(project["slug"])
    return resolved


# Mods and plugins that declare a required dependency on Modrinth, tried in order for each loader and version.
MISSING_CANDIDATES = {
    "bukkit": ["worldguard", "multiverse-portals", "multiverse-inventories"],
    "fabric": ["jade", "appleskin", "farmers-delight-refabricated", "waystones", "iris"],
    "quilt": ["jade", "appleskin", "farmers-delight-refabricated", "waystones", "iris"],
    "neoforge": ["sophisticated-backpacks", "iron-jetpacks", "mekanism-generators"],
    "forge": ["sophisticated-backpacks", "iron-jetpacks", "mekanism-generators"],
}


def required_on_server(jar: Path, dependency: dict) -> bool:
    """Reads the jar itself: a mod's dependency can be declared for the client only (FancyMenu needs Melody
    there), and then a server does not care that it is missing."""
    import tomllib
    ids = {dependency["slug"], dependency["slug"].replace("-", ""), dependency.get("id", "")}
    with zipfile.ZipFile(jar) as archive:
        names = set(archive.namelist())
        for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
            if name not in names:
                continue
            data = tomllib.loads(archive.read(name).decode("utf-8", "replace"))
            for entries in data.get("dependencies", {}).values():
                for entry in entries:
                    if entry.get("modId", "").lower() in {i.lower() for i in ids if i}:
                        side = entry.get("side", "BOTH").upper()
                        mandatory = entry.get("mandatory", entry.get("type", "required") == "required")
                        return mandatory and side in ("BOTH", "SERVER")
            return False
        if "fabric.mod.json" in names:
            mod = json.loads(archive.read("fabric.mod.json").decode("utf-8", "replace"))
            return mod.get("environment", "*") in ("*", "server")
    return True


def auto_missing(loader: str, minecraft: str, server: bool = True) -> tuple[str, str]:
    """The first candidate with a build for this version and a required dependency: (mod, dependency slug)."""
    family = "bukkit" if loader in ("paper", "spigot", "folia", "purpur") else loader
    for slug in MISSING_CANDIDATES[family]:
        if server and family != "bukkit" and http_json(f"https://api.modrinth.com/v2/project/{slug}").get("server_side") == "unsupported":
            continue
        try:
            version = modrinth_version(slug, loader, minecraft)
        except LookupError:
            continue
        for dependency in version["dependencies"]:
            if dependency["dependency_type"] != "required" or not dependency.get("project_id"):
                continue
            project = http_json(f"https://api.modrinth.com/v2/project/{dependency['project_id']}")
            if server and not required_on_server(modrinth_file(version), project):
                continue
            return slug, project["slug"]
    # Nothing in the short list fits this version: ask Modrinth for mods of this loader and take the first that fits.
    facets = json.dumps([[f"categories:{loader}"], [f"versions:{minecraft}"], ["project_type:mod"]])
    query = urllib.parse.urlencode({"facets": facets, "limit": 30, "index": "downloads"})
    for hit in http_json(f"https://api.modrinth.com/v2/search?{query}")["hits"]:
        slug = hit["slug"]
        if server and hit.get("server_side") == "unsupported":
            continue
        try:
            version = modrinth_version(slug, loader, minecraft)
        except LookupError:
            continue
        for dependency in version["dependencies"]:
            if dependency["dependency_type"] != "required" or not dependency.get("project_id"):
                continue
            project = http_json(f"https://api.modrinth.com/v2/project/{dependency['project_id']}")
            if server and not required_on_server(modrinth_file(version), project):
                continue
            return slug, project["slug"]
    raise LookupError(f"no {loader} mod with a required dependency for {minecraft}")


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


def build_fabric_fixture(java_home: Path | None = None) -> Path:
    """Compiles lab/fixtures/fabric against Fabric Loader and the mixin library only (it needs no
    Minecraft class): the same test mod for a real game and for a real server."""
    source_dir = LAB_DIR / "fixtures" / "fabric"
    sources = sorted(p for p in source_dir.rglob("*") if p.is_file())
    digest = hashlib.sha1(b"".join(p.read_bytes() for p in sources)).hexdigest()[:10]
    output = CACHE / "fixtures" / f"crashsleuth-fixture-fabric-{digest}.jar"
    if output.exists():
        return output
    loader = json.load(urllib.request.urlopen(urllib.request.Request("https://meta.fabricmc.net/v2/versions/loader", headers={"User-Agent": USER_AGENT})))[0]["version"]
    loader_jar = download(f"https://maven.fabricmc.net/net/fabricmc/fabric-loader/{loader}/fabric-loader-{loader}.jar")
    mixin_version = maven_latest("https://maven.fabricmc.net/net/fabricmc/sponge-mixin/maven-metadata.xml", "")
    mixin_jar = download(f"https://maven.fabricmc.net/net/fabricmc/sponge-mixin/{mixin_version}/sponge-mixin-{mixin_version}.jar")
    work = CACHE / "fixtures" / f"fabric-build-{digest}"
    shutil.rmtree(work, ignore_errors=True)
    (work / "out").mkdir(parents=True)
    shutil.copytree(source_dir / "src", work / "src")
    shutil.copy(loader_jar, work / "loader.jar")
    shutil.copy(mixin_jar, work / "mixin.jar")
    if java_home:  # a computer with a JDK at hand (the game side of the lab)
        java_files = [str(p) for p in (work / "src").rglob("*.java")]
        subprocess.run([str(java_home / "bin" / "javac"), "--release", "17", "-nowarn", "-proc:none", "-d", str(work / "out"),
                        "-cp", f"{work / 'loader.jar'}{os.pathsep}{work / 'mixin.jar'}", *java_files], check=True)
    else:  # the server side builds everything in a container, like the plugin
        script = ("javac -nowarn -proc:none --release 17 -d out -cp loader.jar:mixin.jar $(find src -name '*.java')")
        subprocess.run(["docker", "run", "--rm", "-v", f"{work}:/w", "-w", "/w", "eclipse-temurin:21-jdk", "sh", "-c", script],
                       check=True, capture_output=True)
    for name in ("fabric.mod.json", "crashsleuth_fixture.mixins.json"):
        shutil.copy(source_dir / name, work / "out" / name)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as jar:
        for item in sorted((work / "out").rglob("*")):
            if item.is_file():
                jar.write(item, item.relative_to(work / "out").as_posix())
    return output


def build_paper_fixture(minecraft: str) -> Path:
    """Compiles lab/fixtures/paper into a plugin jar, in a Java container."""
    sources = sorted(p for folder in ("paper", "paper-stubs") for p in (LAB_DIR / "fixtures" / folder).rglob("*") if p.is_file())
    digest = hashlib.sha1(b"".join(p.read_bytes() for p in sources)).hexdigest()[:10]
    output = CACHE / "fixtures" / f"CrashSleuthFixture-{minecraft}-{digest}.jar"
    if output.exists():
        return output
    work = CACHE / "fixtures" / f"build-{minecraft}"
    shutil.rmtree(work, ignore_errors=True)
    shutil.copytree(LAB_DIR / "fixtures" / "paper", work / "src-root")
    shutil.copy(paper_api(minecraft), work / "paper-api.jar")
    shutil.copytree(LAB_DIR / "fixtures" / "paper-stubs", work / "stubs")
    # The stubs are only on the compile classpath: the jar references them without shipping them.
    script = ("mkdir -p out stubs-out && javac -nowarn -proc:none -d stubs-out $(find stubs -name '*.java') "
              "&& javac -nowarn -proc:none -d out -cp paper-api.jar:stubs-out $(find src-root/src -name '*.java') "
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
    extra: list[dict] = field(default_factory=list)     # {"slug", "loader", "minecraft"?, "index"?} or {"url", "file"?}: extra files as they are
    modpack: str | None = None                          # Modrinth modpack slug, server side installed
    remove: list[str] = field(default_factory=list)     # modpack files removed on purpose (name substrings)
    fixture: str | None = None                          # fixture mode: Paper plugin (enable-npe, lag...) or Fabric mod (entity-tick...)
    after_ready: int = 0                                # seconds to keep the server running once it is ready
    memory: str = "2G"
    timeout: int = 420
    crashes: bool = False                               # expected to crash without any explanation in its logs
    loader_version: str | None = None                   # Fabric Loader version (default: latest)
    jvm_extra: str = ""                                 # extra JVM options on the start command
    pre: str = ""                                       # shell run in the container before the server (a port taken, a lock held)
    docker_args: list[str] = field(default_factory=list)  # extra docker run options (a tiny disk, for instance)
    seed: dict | None = None                            # {"platform", "minecraft"}: a server of another version creates the world first
    mutate: list[dict] = field(default_factory=list)    # after a first clean start: {"garble"|"truncate"|"delete"|"write": path, ...}
    log: str | None = None                              # file to analyse instead of the usual pick (console.log: all a panel shows)
    console: list[dict] = field(default_factory=list)   # once ready: {"after": seconds, "command": "spark profiler start"}
    auto_missing: bool = False                          # a real mod or plugin of this version, without a required dependency
    properties: str = ""                                # extra server.properties lines (a fixed level-seed)
    check_before: bool = False                          # judge the analysis made before the start (the server rewrites what it finds)
    flaky: bool = False                                 # crashes only sometimes: the first start may succeed
    bisect: dict | None = None                          # {"culprits": [...], "max_runs"?}: culprit search expected result
    expect: Expectation | None = None                   # None: must start cleanly with no finding (unless crashes)
    source: str | None = None                           # a real report of this problem on the web (issue, forum, thread)
    fix: str | None = None                              # what really fixed it for those people, in one line


def load_scenarios() -> list[Scenario]:
    data = json.loads((LAB_DIR / "scenarios.json").read_text())
    # The version matrix (lab/matrix.py): every platform on every line of Minecraft versions.
    if (LAB_DIR / "matrix.json").exists():
        data += json.loads((LAB_DIR / "matrix.json").read_text())
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
    (directory / "server.properties").write_text("online-mode=false\nspawn-protection=0\nview-distance=4\nsimulation-distance=4\n" + scenario.properties)
    java = f"-Xms256M -Xmx{scenario.memory} {scenario.jvm_extra}".strip()
    mods_dir = directory / ("plugins" if scenario.platform in ("paper", "purpur", "spigot", "folia") else "mods")

    if scenario.platform == "vanilla":
        shutil.copy(vanilla_server(scenario.minecraft), directory / "server.jar")
        command = f"java {java} -jar server.jar nogui"
    elif scenario.platform in ("paper", "purpur", "folia", "spigot"):
        jar = {"paper": lambda: paper_server(scenario.minecraft), "folia": lambda: paper_server(scenario.minecraft, "folia"),
               "purpur": lambda: purpur_server(scenario.minecraft), "spigot": lambda: spigot_server(scenario.minecraft)}[scenario.platform]()
        shutil.copy(jar, directory / "server.jar")
        command = f"java {java} -jar server.jar nogui"
    elif scenario.platform == "quilt":
        quilt_install(scenario.minecraft, directory)
        command = f"java {java} -jar quilt-server-launch.jar nogui"
    elif scenario.platform == "fabric":
        shutil.copy(fabric_server(scenario.minecraft, scenario.loader_version), directory / "server.jar")
        command = f"java {java} -jar server.jar nogui"
    elif scenario.platform in ("neoforge", "forge"):
        installer, version = neoforge_installer(scenario.minecraft) if scenario.platform == "neoforge" else forge_installer(scenario.minecraft)
        shutil.copy(installer, directory / "installer.jar")
        (directory / "user_jvm_args.txt").write_text(java + "\n")
        # Installed once: a second start (after files were broken on purpose) goes straight to the server.
        command = "( [ ! -f installer.jar ] || ( java -jar installer.jar --installServer > installer.log 2>&1 && rm -f installer.jar installer.jar.log ) ) && sh run.sh nogui"
    else:
        raise ValueError(scenario.platform)

    # Quilt runs Fabric mods, and most Quilt users install them: Modrinth lists them as Fabric.
    loader = {"paper": "paper", "purpur": "paper", "spigot": "spigot", "folia": "folia", "quilt": "fabric"}.get(scenario.platform, scenario.platform)
    if scenario.auto_missing:
        mod, dependency = auto_missing(loader, scenario.minecraft)
        scenario.mods = [mod]
        scenario.skip = [dependency]
        (directory / "missing-on-purpose.txt").write_text(f"{mod} without {dependency}\n")
    if scenario.mods:
        mods_dir.mkdir(exist_ok=True)
        for slug, path in resolve_mods(scenario.mods, loader, scenario.minecraft, set(scenario.skip)).items():
            if slug not in scenario.skip:
                shutil.copy(path, mods_dir / path.name)
    for item in scenario.extra:
        mods_dir.mkdir(exist_ok=True)
        # Real cases often name a jar that is not on Modrinth (a GitHub release, a build of a forum thread).
        if item.get("url"):
            path = download(item["url"], item.get("file"))
        else:
            version = modrinth_version(item["slug"], item["loader"], item.get("minecraft", scenario.minecraft), item.get("index", 0))
            path = modrinth_file(version)
        shutil.copy(path, mods_dir / (item.get("file") or path.name))
    if scenario.modpack:
        removed = install_modpack(scenario.modpack, loader, scenario.minecraft, directory, scenario.remove)
        (directory / "removed-on-purpose.txt").write_text("\n".join(removed) + "\n")
    if scenario.fixture:
        mods_dir.mkdir(exist_ok=True)
        # A plugin on the Bukkit side, a Fabric mod on the loader side: the same modes, both real.
        if scenario.platform in ("fabric", "quilt"):
            shutil.copy(build_fabric_fixture(), mods_dir / "crashsleuth-fixture-1.0.0.jar")
        else:
            shutil.copy(build_paper_fixture(scenario.minecraft), mods_dir / "CrashSleuthFixture-1.0.0.jar")
        (directory / "fixture-mode.txt").write_text(scenario.fixture + "\n")
    if scenario.pre:
        shutil.copy(LAB_DIR / "fixtures" / "tools" / "Hold.java", directory / "Hold.java")
        command = f"{scenario.pre} ; {command}"
    return ["sh", "-c", command]


def apply_mutations(scenario: Scenario, directory: Path) -> None:
    """Breaks files on purpose once a first clean start has created them."""
    for action in scenario.mutate:
        kind = next(k for k in ("garble", "truncate", "delete", "write", "corrupt_chunks") if k in action)
        target = directory / action[kind]
        if kind == "corrupt_chunks":
            for region in sorted(target.glob("*.mca")):
                corrupt_chunks(region, action.get("every", 1), action.get("how", "payload"))
        elif kind == "garble":
            text = target.read_text(errors="replace")
            target.write_text(text[: len(text) // 2] + "\n=== not valid [[ {\n" + text[len(text) // 2:])
        elif kind == "truncate":
            target.write_bytes(target.read_bytes()[: action.get("bytes", 20)])
        elif kind == "delete":
            if target.is_dir():
                shutil.rmtree(target)
            else:
                target.unlink(missing_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(action["text"])


def corrupt_chunks(region: Path, every: int, how: str) -> None:
    """Damages chunks of an Anvil region file the way a crash or a bad disk does: garbage inside the
    compressed data ("payload"), an unknown compression type ("compression"), or a location past the
    end of the file ("offset")."""
    data = bytearray(region.read_bytes())
    for index in range(0, 1024, every):
        entry = int.from_bytes(data[index * 4:index * 4 + 3], "big")
        if entry == 0 or len(data) < 8192:
            continue
        start = entry * 4096
        if how == "offset":
            data[index * 4:index * 4 + 3] = (len(data) // 4096 + 50).to_bytes(3, "big")
        elif how == "compression":
            data[start + 4] = 42
        else:
            length = int.from_bytes(data[start:start + 4], "big")
            middle = start + 5 + length // 3
            data[middle:middle + 64] = bytes((i * 37 + 11) % 256 for i in range(64))
    region.write_bytes(bytes(data))


def prepare_command_without_pre(command: list[str], pre: str) -> list[str]:
    return [command[0], command[1], command[2].replace(f"{pre} ; ", "", 1)]


def seed_world(scenario: Scenario, directory: Path) -> None:
    """Creates the world with another server version, then leaves only the world behind."""
    seed = Scenario(name=f"{scenario.name}-seed", description="seed", platform=scenario.seed["platform"],
                    minecraft=scenario.seed["minecraft"], java=scenario.seed.get("java", 21), timeout=600)
    seed_dir = directory.parent / f"{scenario.name}-seed"
    shutil.rmtree(seed_dir, ignore_errors=True)
    outcome, _ = run_server(seed, seed_dir, prepare(seed, seed_dir))
    if outcome != "ready":
        raise RuntimeError(f"seed server did not start ({outcome})")
    shutil.copytree(seed_dir / "world", directory / "world")
    shutil.rmtree(seed_dir, ignore_errors=True)


def run_server(scenario: Scenario, directory: Path, command: list[str]) -> tuple[str, float]:
    """Runs the server until it is ready, crashes, or times out. Returns the outcome and duration."""
    image = f"eclipse-temurin:{scenario.java}-jdk"
    name = f"crashsleuth-lab-{scenario.name}"
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    start = time.time()
    process = subprocess.Popen(
        ["docker", "run", "--rm", "--name", name, "-i", "-v", f"{directory}:/srv", "-w", "/srv", "-e", "HOME=/srv",
         "--memory", "6g", "--user", f"{os.getuid()}:{os.getgid()}", *scenario.docker_args, image, *command],
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
            ready_at = time.time()
            deadline = ready_at + scenario.after_ready
            pending = sorted(scenario.console, key=lambda c: c["after"])
            while time.time() < deadline and process.poll() is None:
                while pending and time.time() - ready_at >= pending[0]["after"]:
                    try:
                        process.stdin.write((pending.pop(0)["command"] + "\n").encode())
                        process.stdin.flush()
                    except Exception:
                        pass
                time.sleep(1)
            if process.poll() is not None:
                outcome = "exited"
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
    else:
        (directory / "exit-code.txt").write_text(f"{process.returncode}\n")
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
    import fnmatch
    # "all", exact names, or patterns such as "matrix-paper-*".
    selected = list(scenarios.values()) if names == ["all"] else [
        s for name in names for s in (scenarios.values() if any(c in name for c in "*?[") else [scenarios[name]])
        if not any(c in name for c in "*?[") or fnmatch.fnmatch(s.name, name)
    ]
    failures = 0
    for scenario in selected:
        directory = RUNS / scenario.name
        shutil.rmtree(directory, ignore_errors=True)
        print(f"== {scenario.name}: {scenario.description}", flush=True)
        answer = None
        try:
            command = prepare(scenario, directory)
            if scenario.seed:
                seed_world(scenario, directory)
            if scenario.mutate:
                # A first clean start creates the files that are then broken on purpose.
                first = Scenario(**{**scenario.__dict__, "mutate": [], "pre": "", "name": scenario.name + "-first"})
                first_outcome, _ = run_server(first, directory, command if not scenario.pre else prepare_command_without_pre(command, scenario.pre))
                if first_outcome != "ready":
                    raise RuntimeError(f"first start did not succeed ({first_outcome})")
                for leftover in ("logs", "crash-reports"):
                    shutil.rmtree(directory / leftover, ignore_errors=True)
                apply_mutations(scenario, directory)
            before = cli_json(cli, "analyze", str(directory), "--json") if scenario.check_before else None
            before_inventory = cli_json(cli, "inventory", str(directory)) if scenario.check_before else None
            outcome, duration = run_server(scenario, directory, command)
            log = directory / scenario.log if scenario.log else pick_log(directory)
            report, inventory = analyse(cli, directory, log)
            if before is not None:
                # What the files said before the server touched them.
                report, inventory = before, before_inventory
                outcome = "exited" if scenario.expect else outcome
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
            # A spark profile saved during the run is kept too: it is replayed with the log.
            profiles = sorted((directory / "plugins" / "spark").glob("*.sparkprofile"))[-1:]
            for profile in profiles:
                shutil.copy(profile, target / profile.name)
            (target / "expected.json").write_text(json.dumps(
                {"situation": scenario.expect.situation if scenario.expect else None,
                 "culprit": scenario.expect.culprit if scenario.expect else None,
                 "logs": [log.name], **({"profiles": [p.name for p in profiles]} if profiles else {}),
                 **({"crashes": True} if scenario.crashes else {}),
                 # Where this problem was really reported, and what fixed it for those people.
                 **({"source": scenario.source} if scenario.source else {}),
                 **({"fix": scenario.fix} if scenario.fix else {})}, indent=2) + "\n")
        if not keep:
            shutil.rmtree(directory, ignore_errors=True)
    print(f"\n{len(selected) - failures}/{len(selected)} scenarios passed")
    return 1 if failures else 0


def write_sources() -> int:
    """Writes docs/REAL_CASES.md from the corpus: the problems real people reported, replayed here."""
    rows = []
    for case in sorted(CORPUS.iterdir()):
        expected = case / "expected.json"
        if not expected.is_file():
            continue
        data = json.loads(expected.read_text())
        if not data.get("source"):
            continue
        rows.append((case.name, data.get("situation") or "clean start", data.get("culprit") or "", data["source"], data.get("fix", "")))
    doc = LAB_DIR.parent / "docs" / "REAL_CASES.md"
    lines = ["# Problems real people had, replayed here", "",
             "Every case below was reported by someone on the web, rebuilt in the lab with the same versions,",
             "and is replayed by the tests. The last column is what fixed it for them; CrashSleuth is expected",
             "to lead to the same answer on its own.", "",
             "| Case | What CrashSleuth must find | Culprit | Reported at | What fixed it |",
             "| --- | --- | --- | --- | --- |"]
    for name, situation, culprit, source, fix in rows:
        lines.append(f"| `{name}` | {situation} | {culprit} | {source} | {fix} |")
    lines += ["", f"{len(rows)} cases.", ""]
    doc.write_text("\n".join(lines))
    print(f"{doc}: {len(rows)} cases")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list")
    sub.add_parser("sources")
    runner = sub.add_parser("run")
    runner.add_argument("scenarios", nargs="+")
    runner.add_argument("--cli", default=os.environ.get("CRASHSLEUTH_CLI", "crashsleuth"))
    runner.add_argument("--keep", action="store_true", help="keep the server directories")
    args = parser.parse_args()
    if args.command == "sources":
        return write_sources()
    if args.command == "list":
        for scenario in load_scenarios():
            print(f"{scenario.name:40} {scenario.description}")
        return 0
    return run(args.scenarios, args.cli, args.keep)


if __name__ == "__main__":
    sys.exit(main())
