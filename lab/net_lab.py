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
    docker(*args, f"eclipse-temurin:{java}-jdk", "sh", "-c", f"{command} > console.log 2>&1")


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


def velocity_proxy(directory: Path, secret: str, backend: str) -> None:
    directory.mkdir(parents=True)
    shutil.copy(lab.paper_server("3.4.0", "velocity"), directory / "velocity.jar")
    (directory / "forwarding.secret").write_text(secret)
    (directory / "velocity.toml").write_text(
        'config-version = "2.7"\n'
        f'bind = "0.0.0.0:{PROXY_PORT}"\n'
        'online-mode = false\n'
        'player-info-forwarding-mode = "modern"\n'
        'forwarding-secret-file = "forwarding.secret"\n'
        '[servers]\n'
        f'lobby = "{backend}:{SERVER_PORT}"\n'
        'try = ["lobby"]\n'
        '[forced-hosts]\n'
    )


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
}


def keep(name: str, base: Path) -> None:
    """The log of every side becomes a corpus case of its own, anonymised like the other labs."""
    import client_lab  # noqa: E402  (shares the path cleaning)
    for side, situation in EXPECTED[name].items():
        target = lab.CORPUS / f"net-{name}-{side}"
        shutil.rmtree(target, ignore_errors=True)
        log = base / side / "logs" / "latest.log"
        if not log.exists():
            continue
        target.mkdir(parents=True)
        (target / "latest.log").write_text(client_lab.private(lab.anonymise(log.read_text(errors="replace")).replace(str(base), "/lab")))
        (target / "expected.json").write_text(json.dumps({"situation": situation, "logs": ["latest.log"]}, indent=2) + "\n")


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
