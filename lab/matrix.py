#!/usr/bin/env python3
"""Writes lab/matrix.json: every server platform on every line of Minecraft versions from 1.19 to the
latest, each with a clean start and a real missing dependency. Only the versions each platform really
publishes are kept (asked to their official sources).

    python3 lab/matrix.py && python3 lab/lab.py run matrix --cli ...
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import lab  # noqa: E402

# One version per line: the Java switch (1.20.5), the last Forge 1.20.1 era, the Paper remapping change,
# each 1.21 drop, and the 26.x numbering.
LINES = ["1.19.2", "1.19.4", "1.20.1", "1.20.4", "1.20.6", "1.21.1", "1.21.4", "1.21.8", "1.21.11", "26.1.2", "26.2", "26.3"]
CLIENT_LINES = ["1.19.4", "1.20.1", "1.20.4", "1.21.1", "1.21.4", "1.21.11", "26.1.2", "26.3"]
SPIGOT_LINES = ["1.19.4", "1.20.4", "1.21.1", "1.21.11", "26.3"]  # compiled by BuildTools: a few are enough


def releases_since_1_19() -> list[str]:
    manifest = lab.http_json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
    released = [v["id"] for v in manifest["versions"] if v["type"] == "release"]
    return list(reversed(released[: released.index("1.19") + 1]))


def available(platform: str, version: str, attempts: int = 3) -> bool:
    """Asked to the platform's own source; a request that fails is retried, so a hiccup does not drop a version."""
    import time
    for attempt in range(attempts):
        try:
            return _available(platform, version)
        except Exception:
            if attempt + 1 == attempts:
                return False
            time.sleep(2 * (attempt + 1))
    return False


def _available(platform: str, version: str) -> bool:
    try:
        if platform in ("paper", "folia"):
            lab.http_json(f"https://fill.papermc.io/v3/projects/{platform}/versions/{version}/builds/latest")
        elif platform == "purpur":
            lab.http_json(f"https://api.purpurmc.org/v2/purpur/{version}")["builds"]["latest"]
        elif platform == "fabric":
            return bool(lab.http_json(f"https://meta.fabricmc.net/v2/versions/loader/{version}"))
        elif platform == "quilt":
            return bool(lab.http_json(f"https://meta.quiltmc.org/v3/versions/loader/{version}"))
        elif platform == "neoforge":
            return neoforge_exists(version)
        elif platform == "forge":
            return forge_exists(version)
        return True
    except Exception:
        return False


def maven_versions(url: str) -> list[str]:
    import re
    import urllib.request
    request = urllib.request.Request(url, headers={"User-Agent": lab.USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return re.findall(r"<version>([^<]+)</version>", response.read().decode())


def neoforge_exists(version: str) -> bool:
    if version == "1.20.1":
        return any(v.startswith("1.20.1-") for v in lab.neoforge_versions("forge"))
    parts = version.split(".")
    prefix = f"{parts[1]}.{parts[2] if len(parts) > 2 else 0}." if parts[0] == "1" else version + "."
    return any(v.startswith(prefix) and "beta" not in v and "alpha" not in v for v in lab.neoforge_versions())


def forge_exists(version: str) -> bool:
    return any(v.startswith(version + "-") for v in maven_versions("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"))


def scenario(platform: str, version: str, kind: str) -> dict:
    base = {"platform": platform, "minecraft": version, "java": lab.java_for(version), "timeout": 600}
    if kind == "baseline":
        return {**base, "name": f"matrix-{platform}-{version}-baseline", "description": f"{platform.capitalize()} {version}, clean start: no finding"}
    return {**base, "name": f"matrix-{platform}-{version}-missing-dependency", "description": f"{platform.capitalize()} {version}, a real mod or plugin without its required dependency",
            "auto_missing": True, "expect": {"situation": "DEP_MISSING"}}


def main() -> None:
    scenarios = [scenario("vanilla", v, "baseline") for v in releases_since_1_19()]
    for platform in ("paper", "purpur", "folia", "fabric", "quilt", "neoforge", "forge"):
        for version in LINES:
            if not available(platform, version):
                print(f"skip {platform} {version} (not published)")
                continue
            scenarios.append(scenario(platform, version, "baseline"))
            # Folia refuses plugins that do not say they support it: its own case, not a missing dependency.
            if platform != "folia":
                scenarios.append(scenario(platform, version, "missing"))
    for version in SPIGOT_LINES:
        scenarios += [scenario("spigot", version, "baseline"), scenario("spigot", version, "missing")]
    (lab.LAB_DIR / "matrix.json").write_text(json.dumps(scenarios, indent=1) + "\n")
    print(f"{len(scenarios)} scenarios in lab/matrix.json")
    clients = []
    for loader in ("vanilla", "fabric", "neoforge", "forge"):
        for version in CLIENT_LINES:
            if loader in ("neoforge", "forge") and not available(loader, version):
                continue
            base = {"minecraft": version, "loader": loader, "java": lab.java_for(version)}
            clients.append({**base, "name": f"matrix-client-{loader}-{version}-baseline", "description": f"{loader.capitalize()} {version} client, clean start: no finding"})
            if loader == "fabric":
                clients.append({**base, "name": f"matrix-client-fabric-{version}-missing-dependency",
                                "description": f"Fabric {version} client, a real mod without its required dependency", "auto_missing": True, "expect": {"situation": "DEP_MISSING"}})
    (lab.LAB_DIR / "client-matrix.json").write_text(json.dumps(clients, indent=1) + "\n")
    print(f"{len(clients)} scenarios in lab/client-matrix.json")


if __name__ == "__main__":
    main()
