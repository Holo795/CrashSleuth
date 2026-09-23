#!/usr/bin/env python3
"""CrashSleuth real-world lab, connections between a player and a server.

Runs on a computer with a screen and Docker: real servers (Paper, Velocity, Fabric) start in Docker on a
private network, the real game client starts on this computer and joins them, then CrashSleuth analyses
the logs of every side, and compares the player's and the server's files.

Usage:
    python3 lab/net_lab.py run all --cli cli/build/install/crashsleuth/bin/crashsleuth --java-home $JAVA_HOME
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lab  # noqa: E402

RUNS = Path(os.environ.get("CRASHSLEUTH_LAB_RUNS", "/tmp/crashsleuth-lab-runs")) / "net"
NETWORK = "crashsleuth-net"
PROXY_PORT = 25577
SERVER_PORT = 25565


def docker(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], capture_output=True, text=True, check=check)


def start_container(name: str, directory: Path, command: str, publish: int | None, java: int = 21) -> None:
    docker("rm", "-f", name, check=False)
    args = ["run", "-d", "--name", name, "--network", NETWORK, "--network-alias", name, "-v", f"{directory}:/srv", "-w", "/srv",
            "-e", "HOME=/srv", "--user", f"{os.getuid()}:{os.getgid()}", "--memory", "3g"]
    if publish:
        args += ["-p", f"127.0.0.1:{publish}:{publish}"]
    # exec: the server gets the stop signal itself and saves the world and the players.
    docker(*args, f"eclipse-temurin:{java}-jdk", "sh", "-c", f"exec {command} >> console.log 2>&1")


def wait_for(directory: Path, pattern: str, container: str, seconds: int = 300) -> bool:
    deadline = time.time() + seconds
    while time.time() < deadline:
        console = directory / "console.log"
        if console.exists() and pattern in console.read_text(errors="replace"):
            return True
        running = docker("inspect", "-f", "{{.State.Running}}", container, check=False).stdout.strip()
        if running != "true":
            return False
        time.sleep(2)
    return False


def paper_backend(directory: Path, secret: str | None, mods: list[str]) -> None:
    directory.mkdir(parents=True)
    shutil.copy(lab.paper_server("1.21.1"), directory / "server.jar")
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text(f"online-mode=false\nserver-port={SERVER_PORT}\nview-distance=4\nsimulation-distance=4\n")
    if secret is not None:
        (directory / "config").mkdir()
        (directory / "config" / "paper-global.yml").write_text(f"proxies:\n  velocity:\n    enabled: true\n    online-mode: false\n    secret: '{secret}'\n")
    if mods:
        (directory / "plugins").mkdir()
        for slug, path in lab.resolve_mods(mods, "paper", "1.21.1", set()).items():
            shutil.copy(path, directory / "plugins" / path.name)


def velocity_proxy(directory: Path, secret: str, backend: str, mode: str = "modern") -> None:
    directory.mkdir(parents=True)
    shutil.copy(lab.paper_server("3.4.0", "velocity"), directory / "velocity.jar")
    (directory / "forwarding.secret").write_text(secret)
    (directory / "velocity.toml").write_text(
        'config-version = "2.7"\n'
        f'bind = "0.0.0.0:{PROXY_PORT}"\n'
        'online-mode = false\n'
        f'player-info-forwarding-mode = "{mode}"\n'
        'forwarding-secret-file = "forwarding.secret"\n'
        '[servers]\n'
        f'lobby = "{backend}:{SERVER_PORT}"\n'
        'try = ["lobby"]\n'
        '[forced-hosts]\n'
    )


def bungee_proxy(directory: Path, project: str, backend: str) -> None:
    """BungeeCord (md-5's own build server) or Waterfall (PaperMC), with one server behind it."""
    directory.mkdir(parents=True)
    jar = lab.download("https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar", "BungeeCord.jar") \
        if project == "bungeecord" else lab.paper_server("1.20", "waterfall")
    shutil.copy(jar, directory / f"{project}.jar")
    (directory / "config.yml").write_text(
        "online_mode: false\nip_forward: true\n"
        "listeners:\n- host: 0.0.0.0:25577\n  motd: lab\n  max_players: 10\n  priorities:\n  - lobby\n  query_enabled: false\n"
        f"servers:\n  lobby:\n    address: {backend}:{SERVER_PORT}\n    motd: lobby\n    restricted: false\n"
    )


def velocity4_proxy(directory: Path, secret: str, backend: str) -> None:
    velocity_proxy(directory, secret, backend)
    versions = lab.http_json("https://fill.papermc.io/v3/projects/velocity")["versions"]
    latest = next(v for v in versions["4.0.0"])
    shutil.copy(lab.paper_server(latest, "velocity"), directory / "velocity.jar")


def fabric_server(directory: Path, mods: list[str]) -> None:
    directory.mkdir(parents=True)
    shutil.copy(lab.fabric_server("1.21.1"), directory / "server.jar")
    (directory / "eula.txt").write_text("eula=true\n")
    (directory / "server.properties").write_text(f"online-mode=false\nserver-port={SERVER_PORT}\nview-distance=4\nsimulation-distance=4\n")
    (directory / "mods").mkdir()
    for slug, path in lab.resolve_mods(mods, "fabric", "1.21.1", set()).items():
        shutil.copy(path, directory / "mods" / path.name)


def client_folder(directory: Path, mods: list[str]) -> None:
    (directory / "mods").mkdir(parents=True)
    for slug, path in lab.resolve_mods(mods, "fabric", "1.21.1", set()).items():
        shutil.copy(path, directory / "mods" / path.name)
    (directory / "options.txt").write_text("onboardAccessibility:false\nsoundCategory_master:0.0\ntutorialStep:none\nskipMultiplayerWarning:true\n")


def analyse(cli: str, folder: Path, *extra: str) -> dict:
    result = subprocess.run([cli, "analyze", str(folder), "--json", *extra], capture_output=True, text=True, timeout=300)
    return json.loads(result.stdout)


def summary(report: dict) -> str:
    return ", ".join(f"{f['situation']}[{'/'.join(c['id'] for c in f['culprits'])}]" for f in report["findings"][:3]) or "no finding"


def join(cli: str, java: str, folder: Path, loader: str, port: int) -> str:
    result = subprocess.run([cli, "run-client", str(folder), "--minecraft", "1.21.1", "--loader", loader, "--java", java,
                             "--join", f"127.0.0.1:{port}", "--settle", "5", "--timeout", "4"], capture_output=True, text=True, timeout=600)
    return result.stdout.split()[0] if result.stdout else "ERROR"


SCENARIOS = {
    "join-ok": "Vanilla client joins Paper 1.21.1 through Velocity with the right forwarding secret",
    "proxy-secret-mismatch": "Velocity and Paper do not share the same forwarding secret",
    "proxy-backend-down": "Velocity points to a backend server that is not running",
    "mod-missing-on-client": "Fabric server with Farmer's Delight, the player only has Fabric API",
    "playerdata-corrupt": "Paper 1.21.1: the player joins, the server stops, the player's save is damaged, the player joins again",
    "bungeecord-join-ok": "Vanilla client joins Paper 1.21.1 through BungeeCord",
    "bungeecord-backend-down": "BungeeCord points to a server that is not running",
    "waterfall-backend-down": "Waterfall points to a server that is not running",
    "velocity4-join-ok": "Vanilla client joins Paper 1.21.1 through Velocity 4 (Java 25)",
    "velocity4-backend-down": "Velocity 4 points to a server that is not running",
    "real-velocity-empty-secret": "Velocity with modern forwarding and an empty forwarding.secret (reported on the Paper forums)",
    "real-velocity-forwarding-off": "Velocity forwarding nothing, in front of a server that waits for it",
    "real-bungee-forwarding-one-sided": "BungeeCord forwards, but the server behind it was never told to accept it",
    "real-online-mode-behind-proxy": "A server behind Velocity left at online-mode=true",
    "real-velocity-config-version-raised": "Velocity 4 with config-version raised by hand, so the setting it would have converted is read as it stands",
}


def run_scenario(name: str, cli: str, java: str) -> tuple[bool, str]:
    base = RUNS / name
    shutil.rmtree(base, ignore_errors=True)
    player = base / "player"
    docker("network", "create", NETWORK, check=False)
    try:
        if name in ("join-ok", "proxy-secret-mismatch", "proxy-backend-down"):
            backend, proxy = base / "backend", base / "proxy"
            if name != "proxy-backend-down":
                paper_backend(backend, "right-secret", [])
                start_container("cs-backend", backend, "java -Xmx1G -jar server.jar nogui", None)
                if not wait_for(backend, "Done (", "cs-backend"):
                    return False, "backend did not start"
            velocity_proxy(proxy, "right-secret" if name == "join-ok" else "wrong-secret", "cs-backend")
            start_container("cs-proxy", proxy, "java -Xmx512M -jar velocity.jar", PROXY_PORT)
            if not wait_for(proxy, "Done (", "cs-proxy"):
                return False, "proxy did not start: " + (proxy / "console.log").read_text(errors="replace")[-300:]
            client_folder(player, [])
            outcome = join(cli, java, player, "vanilla", PROXY_PORT)
            time.sleep(3)
            reports = {"player": analyse(cli, player), "proxy": analyse(cli, proxy)}
            if backend.exists():
                reports["backend"] = analyse(cli, backend)
            text = f"join={outcome} | " + " | ".join(f"{side}: {summary(report)}" for side, report in reports.items())
            situations = {f["situation"] for report in reports.values() for f in report["findings"]}
            if name == "join-ok":
                return outcome == "READY" and not situations, text
            if name == "proxy-secret-mismatch":
                return outcome != "READY" and "PROXY_FORWARDING" in situations, text
            return outcome != "READY" and "PROXY_BACKEND" in situations, text
        if name == "mod-missing-on-client":
            server = base / "server"
            fabric_server(server, ["fabric-api", "farmers-delight-refabricated"])
            start_container("cs-fabric", server, "java -Xmx2G -jar server.jar nogui", SERVER_PORT)
            if not wait_for(server, "Done (", "cs-fabric"):
                return False, "server did not start"
            client_folder(player, ["fabric-api"])
            outcome = join(cli, java, player, "fabric", SERVER_PORT)
            time.sleep(3)
            reports = {"player": analyse(cli, player), "server": analyse(cli, server), "compare": analyse(cli, player, "--server", str(server))}
            text = f"join={outcome} | " + " | ".join(f"{side}: {summary(report)}" for side, report in reports.items())
            compare = reports["compare"]["findings"]
            found = any(f["situation"] == "MOD_MISMATCH" and any("farmers" in c["id"] for c in f["culprits"]) for f in compare)
            logged = any(f["situation"] in ("MOD_MISMATCH", "REGISTRY_MISMATCH") for f in reports["player"]["findings"])
            return outcome != "READY" and found and logged, text
        if name.startswith(("bungeecord-", "waterfall-", "velocity4-")):
            project = name.split("-")[0]
            backend, proxy = base / "backend", base / "proxy"
            if name.endswith("join-ok"):
                paper_backend(backend, "right-secret" if project == "velocity4" else None, [])
                if project != "velocity4":
                    (backend / "spigot.yml").write_text("settings:\n  bungeecord: true\n")
                start_container("cs-backend", backend, "java -Xmx1G -jar server.jar nogui", None)
                if not wait_for(backend, "Done (", "cs-backend"):
                    return False, "backend did not start"
            if project == "velocity4":
                velocity4_proxy(proxy, "right-secret", "cs-backend")
                start_container("cs-proxy", proxy, "java -Xmx512M -jar velocity.jar", PROXY_PORT, java=25)
            else:
                bungee_proxy(proxy, project, "cs-backend")
                start_container("cs-proxy", proxy, f"java -Xmx512M -jar {project}.jar", PROXY_PORT)
            if not wait_for(proxy, "Listening on", "cs-proxy") and not wait_for(proxy, "Done (", "cs-proxy", 5):
                return False, "proxy did not start: " + (proxy / "console.log").read_text(errors="replace")[-300:]
            client_folder(player, [])
            outcome = join(cli, java, player, "vanilla", PROXY_PORT)
            time.sleep(3)
            reports = {"player": analyse(cli, player), "proxy": analyse(cli, proxy)}
            if backend.exists():
                reports["backend"] = analyse(cli, backend)
            text = f"join={outcome} | " + " | ".join(f"{side}: {summary(report)}" for side, report in reports.items())
            situations = {f["situation"] for report in reports.values() for f in report["findings"]}
            if name.endswith("join-ok"):
                return outcome == "READY" and not situations, text
            # Waterfall leaves the player waiting without a word: only its end of life can be said.
            expected = "OUTDATED" if project == "waterfall" else "PROXY_BACKEND"
            return outcome != "READY" and expected in situations, text
        if name == "real-velocity-config-version-raised":
            # https://github.com/PaperMC/Velocity/issues/1876 : raising config-version skips the
            # migration that turns ping-passthrough into a table, and the proxy refuses to start.
            proxy = base / "proxy"
            proxy.mkdir(parents=True)
            shutil.copy(lab.paper_server("4.2.0", "velocity"), proxy / "velocity.jar")
            (proxy / "forwarding.secret").write_text("right-secret")
            (proxy / "velocity.toml").write_text(
                'config-version = "3.0"\n'
                f'bind = "0.0.0.0:{PROXY_PORT}"\n'
                'online-mode = false\n'
                'player-info-forwarding-mode = "none"\n'
                'ping-passthrough = "all"\n'
                '\n[servers]\nlobby = "127.0.0.1:25566"\ntry = ["lobby"]\n'
                '\n[advanced]\n\n[query]\nenabled = false\n'
            )
            start_container("cs-proxy", proxy, "java -Xmx512M -jar velocity.jar", PROXY_PORT, java=25)
            started = wait_for(proxy, "Done (", "cs-proxy", 30)
            report = analyse(cli, proxy)
            text = f"started={started} | proxy: {summary(report)}"
            situations = {f["situation"] for f in report["findings"]}
            return not started and "CONFIG_BROKEN" in situations, text
        if name == "real-velocity-forwarding-off":
            # https://github.com/PaperMC/Velocity/issues/1347 : the proxy was left in "none" mode.
            backend, proxy = base / "backend", base / "proxy"
            paper_backend(backend, "right-secret", [])
            start_container("cs-backend", backend, "java -Xmx1G -jar server.jar nogui", None)
            if not wait_for(backend, "Done (", "cs-backend"):
                return False, "backend did not start"
            velocity_proxy(proxy, "right-secret", "cs-backend", mode="none")
            start_container("cs-proxy", proxy, "java -Xmx512M -jar velocity.jar", PROXY_PORT)
            if not wait_for(proxy, "Done (", "cs-proxy"):
                return False, "proxy did not start"
            client_folder(player, [])
            outcome = join(cli, java, player, "vanilla", PROXY_PORT)
            time.sleep(3)
            reports = {"player": analyse(cli, player), "proxy": analyse(cli, proxy), "backend": analyse(cli, backend)}
            text = f"join={outcome} | " + " | ".join(f"{side}: {summary(report)}" for side, report in reports.items())
            situations = {f["situation"] for report in reports.values() for f in report["findings"]}
            return outcome != "READY" and "PROXY_FORWARDING" in situations, text
        if name in ("real-bungee-forwarding-one-sided", "real-online-mode-behind-proxy"):
            backend, proxy = base / "backend", base / "proxy"
            if name == "real-online-mode-behind-proxy":
                # https://www.gameserverkings.com/knowledge-base/minecraft/error-failed-to-verify-username/
                paper_backend(backend, "right-secret", [])
                properties = (backend / "server.properties").read_text().replace("online-mode=false", "online-mode=true")
                (backend / "server.properties").write_text(properties)
            else:
                # https://docs.papermc.io/velocity/faq/ : one side forwards, the other does not.
                paper_backend(backend, None, [])
                (backend / "spigot.yml").write_text("settings:\n  bungeecord: false\n")
            start_container("cs-backend", backend, "java -Xmx1G -jar server.jar nogui", None)
            if not wait_for(backend, "Done (", "cs-backend"):
                return False, "backend did not start"
            if name == "real-online-mode-behind-proxy":
                velocity_proxy(proxy, "right-secret", "cs-backend")
                start_container("cs-proxy", proxy, "java -Xmx512M -jar velocity.jar", PROXY_PORT)
                ready = wait_for(proxy, "Done (", "cs-proxy")
            else:
                bungee_proxy(proxy, "bungeecord", "cs-backend")
                start_container("cs-proxy", proxy, "java -Xmx512M -jar bungeecord.jar", PROXY_PORT)
                ready = wait_for(proxy, "Listening on", "cs-proxy") or wait_for(proxy, "Done (", "cs-proxy", 5)
            if not ready:
                return False, "proxy did not start"
            client_folder(player, [])
            outcome = join(cli, java, player, "vanilla", PROXY_PORT)
            time.sleep(3)
            reports = {"player": analyse(cli, player), "proxy": analyse(cli, proxy), "backend": analyse(cli, backend)}
            text = f"join={outcome} | " + " | ".join(f"{side}: {summary(report)}" for side, report in reports.items())
            situations = {f["situation"] for report in reports.values() for f in report["findings"]}
            return outcome != "READY" and "PROXY_FORWARDING" in situations, text
        if name == "real-velocity-empty-secret":
            # https://forums.papermc.io/threads/how-to-solve-unable-to-read-load-save-your-velocity-toml.339/
            proxy = base / "proxy"
            velocity_proxy(proxy, "", "cs-backend")
            start_container("cs-proxy", proxy, "java -Xmx512M -jar velocity.jar", PROXY_PORT)
            wait_for(proxy, "must not be empty", "cs-proxy", 60)
            time.sleep(2)
            report = analyse(cli, proxy)
            text = f"proxy: {summary(report)}"
            return any(f["situation"] == "PROXY_FORWARDING" for f in report["findings"]), text
        if name == "playerdata-corrupt":
            server = base / "server"
            paper_backend(server, None, [])
            start_container("cs-backend", server, "java -Xmx1G -jar server.jar nogui", SERVER_PORT)
            if not wait_for(server, "Done (", "cs-backend"):
                return False, "server did not start"
            client_folder(player, [])
            first = join(cli, java, player, "vanilla", SERVER_PORT)
            docker("stop", "-t", "60", "cs-backend")
            saves = sorted((server / "world" / "playerdata").glob("*.dat*"))
            if first != "READY" or not saves:
                return False, f"first join={first}, player files={[s.name for s in saves]}"
            for save in saves:
                save.write_bytes(save.read_bytes()[:30])
            logs = server / "logs" / "latest.log"
            before = analyse(cli, server)
            docker("start", "cs-backend")
            if not wait_for(server, "Done (", "cs-backend"):
                return False, "server did not start again"
            second = join(cli, java, player, "vanilla", SERVER_PORT)
            time.sleep(3)
            after = analyse(cli, server)
            text = f"join={first}/{second} | before start: {summary(before)} | after: {summary(after)} | log: {logs.exists()}"
            found = lambda report: any(f["situation"] == "CORRUPT_PLAYERDATA" for f in report["findings"])
            return found(before) and found(after), text
        return False, "unknown scenario"
    finally:
        for container in ("cs-backend", "cs-proxy", "cs-fabric"):
            docker("rm", "-f", container, check=False)
        keep(name, base)


# What the log of each side must give when replayed without the files (None: no finding at all).
EXPECTED = {
    "join-ok": {"player": None, "proxy": None, "backend": None},
    "proxy-secret-mismatch": {"player": "PROXY_FORWARDING", "proxy": "PROXY_FORWARDING", "backend": "PROXY_FORWARDING"},
    "proxy-backend-down": {"player": "PROXY_BACKEND", "proxy": "PROXY_BACKEND"},
    "mod-missing-on-client": {"player": "REGISTRY_MISMATCH", "server": None},
    "playerdata-corrupt": {"server": "CORRUPT_PLAYERDATA"},
    "bungeecord-join-ok": {"player": None, "proxy": None, "backend": None},
    # Refused while logging in, the game writes nothing about it in its log.
    "bungeecord-backend-down": {"player": None, "proxy": "PROXY_BACKEND"},
    "waterfall-backend-down": {"player": None, "proxy": None},
    "velocity4-join-ok": {"player": None, "proxy": None, "backend": None},
    "velocity4-backend-down": {"player": "PROXY_BACKEND", "proxy": "PROXY_BACKEND"},
    "real-velocity-empty-secret": {"proxy": "PROXY_FORWARDING"},
    # All three sides say it, including the player's own log: that is what a player pastes.
    "real-velocity-forwarding-off": {"backend": "PROXY_FORWARDING", "proxy": "PROXY_FORWARDING", "player": "PROXY_FORWARDING"},
    "real-bungee-forwarding-one-sided": {"backend": "PROXY_FORWARDING", "proxy": "PROXY_FORWARDING"},
    # The server behind the proxy is never told why the proxy gave up: only the proxy knows.
    "real-online-mode-behind-proxy": {"backend": None, "proxy": "PROXY_FORWARDING"},
    # The proxy never starts, so it is the only side with anything to say.
    "real-velocity-config-version-raised": {"proxy": "CONFIG_BROKEN"},
}


# Where a case was reported, and what fixed it for those people.
SOURCES = {
    "real-velocity-forwarding-off": ("https://github.com/PaperMC/Velocity/issues/1347",
                                     "Set player-info-forwarding-mode to modern on the proxy, and save the file before restarting."),
    "real-bungee-forwarding-one-sided": ("https://docs.papermc.io/velocity/faq/",
                                         "Turn IP forwarding on on both sides, or on neither: the proxy and the server must agree."),
    "real-online-mode-behind-proxy": ("https://www.gameserverkings.com/knowledge-base/minecraft/error-failed-to-verify-username/",
                                      "A server behind a proxy runs with online-mode=false; the proxy does the checking."),
    "real-velocity-empty-secret": ("https://forums.papermc.io/threads/how-to-solve-unable-to-read-load-save-your-velocity-toml.339/",
                                   "Write the secret inside the file named by forwarding-secret-file; the setting is a path, not the secret."),
    "real-velocity-config-version-raised": ("https://github.com/PaperMC/Velocity/issues/1876",
                                            "Leave config-version alone: it is what tells Velocity which old settings it still has to convert."),
}


def keep(name: str, base: Path) -> None:
    """The log of every side becomes a corpus case of its own, anonymised like the other labs."""
    import client_lab  # noqa: E402  (shares the path cleaning)
    for side, situation in EXPECTED[name].items():
        target = lab.CORPUS / f"net-{name}-{side}"
        shutil.rmtree(target, ignore_errors=True)
        # A proxy that dies while reading its own settings writes nothing but its console.
        log = next((p for p in (base / side / "logs" / "latest.log", base / side / "proxy.log.0", base / side / "console.log") if p.exists()), None)
        if log is None:
            continue
        target.mkdir(parents=True)
        (target / "latest.log").write_text(client_lab.private(lab.anonymise(log.read_text(errors="replace")).replace(str(base), "/lab")))
        source, fix = SOURCES.get(name, (None, None))
        (target / "expected.json").write_text(json.dumps(
            {"situation": situation, "logs": ["latest.log"],
             **({"source": source, "fix": fix} if source else {})}, indent=2) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    runner = sub.add_parser("run")
    runner.add_argument("scenarios", nargs="+")
    runner.add_argument("--cli", required=True)
    runner.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    args = parser.parse_args()
    java = str(Path(args.java_home) / "bin" / "java")
    names = list(SCENARIOS) if args.scenarios == ["all"] else args.scenarios
    failures = 0
    for name in names:
        print(f"== {name}: {SCENARIOS[name]}", flush=True)
        try:
            ok, detail = run_scenario(name, args.cli, java)
        except Exception as error:
            ok, detail = False, f"lab error: {error}"
        failures += 0 if ok else 1
        print(f"   {'PASS' if ok else 'FAIL'} {detail}", flush=True)
    docker("network", "rm", NETWORK, check=False)
    print(f"\n{len(names) - failures}/{len(names)} network scenarios passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
