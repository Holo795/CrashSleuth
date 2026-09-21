# Prior art (reviewed 2026-09-21)

No existing tool combines log analysis, pre-launch checks, **unattended** culprit search with real launches, plugin-server support, multi-mod conflict detection and hang/lag interpretation.

## Log and crash analysis
- **mclo.gs + codex-minecraft** (Aternos, MIT): paste site with rule-based analysis; mod, plugin and loader rules. No bisection. https://github.com/aternosorg/codex-minecraft
- **Crash Assistant** (Fabric, Quilt, Forge, NeoForge mod): catches early crashes, known-issue explanations, uploads, some auto-fixes. Client-oriented, no bisection. https://github.com/KostromDan/Crash-Assistant
- **Not Enough Crashes** (MIT): back to title screen, deobfuscated traces, suspected mods including mixin appliers. Client, no bisection. https://github.com/natanfudge/Not-Enough-Crashes
- **Forge/NeoForge "Suspected Mods"**: lists mods present in the stack; blind to conflicts that only touch vanilla code.
- **MCLA** (AGPL-3.0, inactive), **HMCL** built-in analyzer (GPL-3.0), **PCL Log Analyzer** (Windows, PCL2 only).
- Closed web analyzers, some with LLMs: MCDoctor, MC Crash Reader, MC Toolbox, others.

## Culprit search (bisection)
- **Prism Launcher** PR #5855: relaunches automatically, dependency-aware, but the user answers yes/no after each run. Client instances only.
- **mod-bisect-tool** (MPL-2.0): Fabric, Quilt, (Neo)Forge with dependency resolver; manual launches, halving only.
- **FabricBinarySearchTool** (MIT, archived): Fabric only, manual, single culprit.
- **Quilt Bisect**: automatic restarts and multi-mod combinations, but Quilt client 1.20.1-1.20.2 only, all rights reserved, unmaintained.
- **Plugin servers**: no tool, manual guides only.

## Pre-launch checks
- Missing Mods Checker, Minecraft-Mod-Dependency-Checker, LiteByte Modpack Matcher: dependencies only; no version ranges, loader, client/server side, Java version, mixin conflicts or hash checks together.

## Hangs and lag
- **spark** (GPL-3.0) and Paper's watchdog produce profiles and thread dumps for humans to read; nothing interprets them automatically.

## Gaps CrashSleuth targets
1. Unattended bisection that decides pass/fail by itself (crash file, exit code, fatal log line, timeout, success line).
2. Plugin and proxy servers treated like modpacks.
3. Delta debugging for conflicts between several mods.
4. A combined pre-launch linter usable in CI.
5. Automatic reading of thread dumps and spark profiles.
6. An open, versioned signature database (codex-minecraft rules importable).
