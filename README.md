# CrashSleuth

**Find out why Minecraft crashes, and who is to blame.**

CrashSleuth diagnoses crashes, startup failures, hangs, lag and connection problems for **players and server admins**, on **vanilla, plugin servers, proxies and modpacks**. It reads your logs and files, checks your mods or plugins before launch, and when that is not enough, **launches the game or server by itself**, removing mods or plugins step by step until it names the culprit. No need to answer "did it crash?" after every run.

> Status: in development, usable from the command line and the desktop app. See the [specification (French)](docs/SPEC.fr.md) and the [prior art review](docs/PRIOR_ART.md).

## What it does

- **Analyze** a server folder, a game folder, a modpack (Modrinth `.mrpack`, CurseForge zip), a folder of loose mods or plugins, a crash report, a log, a `hs_err_pid` file or a spark profile: cause, culprit, what to do.
- **Check the files before any crash**: missing or out-of-range dependencies, loader too old, mods declaring each other incompatible, wrong loader or folder, duplicates, client-only mods on a server, Java version, plugins built against another server's internals, corrupted jars, broken configuration files (TOML, JSON, YAML, with the line), EULA, worlds saved by a newer version, locked or damaged `level.dat`, damaged chunks and entities (region files read like the game reads them, each chunk named by position with the file to restore), damaged player saves.
- **Compare a player with a server**: the mods that add content must be the same on both sides; performance mods on one side are left alone (`analyze <game> --server <server>`).
- **Connections and proxies**: Velocity and BungeeCord, forwarding secret mismatch, backend server down, registry mismatch, disconnect reasons.
- **Hangs and lag**: Paper watchdog dumps, deadlocks (JVM thread dumps and Paper's watchdog), repeated "Can't keep up" warnings, and **spark profiles**: the plugin or mod taking the main thread, with its share and heaviest method.
- **While playing**: a crash during the tick of an entity or of a block names that entity or block, where it stands, and the mod behind it, on a server as well as in the game; a crash while the world is drawn names what the game was drawing (chunk sections, a block model, an entity, a screen) and the mod that was in the way.
- **Reads the launcher's or panel's console too** (`console.log` next to the game's own log): a graphics driver that refuses the window, a JVM that will not start, a loader that dies first — none of that ever reaches `latest.log`.
- **Pairs people found out the hard way**: two mods a maintainer says cannot live together, or a mod that needs another without declaring it, are named before anything crashes, with the link where it was established.
- **Client side**: resource packs that cannot be opened or hold broken models, shader packs that do not compile (Iris), missing native libraries.
- **Find the culprit** automatically: dependency-aware bisection with real launches, on servers and on the game client (vanilla, Fabric, NeoForge), including conflicts that only happen when two mods are installed together and crashes that only happen sometimes.
- **Readable traces**: `class_310.method_22681` and `fgo.b` become Mojang's names (mappings downloaded once from Mojang and Fabric).
- **Updates**: ask Modrinth, by file hash only, which mods have a newer version.
- **Share** a report as a link: the report is inside the part of the link after the `#`, which browsers never send anywhere; nothing is uploaded and no port is opened.
- **Plain-language explanation** by a model running on your own computer (Ollama, or any local OpenAI-compatible server), optional; only an anonymised summary is sent to it, and it is only allowed to name settings CrashSleuth knows. Anything else it names is flagged.
- **A tool for assistants** (MCP): `crashsleuth mcp` lets an assistant analyse a folder, list what is installed, compare a player with a server, read a configuration file (secrets hidden) and ask for the settings that exist with their accepted values, instead of guessing them.

**Checked against problems real people had**: [docs/REAL_CASES.md](docs/REAL_CASES.md) lists reports from GitHub issues and forums, rebuilt in the lab with the same versions and the same jars; CrashSleuth has to reach the answer those people ended up with.

### What it refuses to say

Most lines that name a mod name the one that *noticed*, not the one at fault, and the usual advice is often wrong. CrashSleuth is built against that:

- a mixin that could not be applied names the mod that **lost**; the one that got there first is only in the class name of the mixin that merged, and that is who gets named;
- `Could not pass event … to X` names whoever registered the listener; if none of X is in the trace, the code that really threw is named instead;
- when a loader refuses to start because one mod declares it breaks another, the mod to change is the one being refused, not the one that wrote the rule;
- Forge's `Suspected Mods` is a list of everything in the stack, by its own authors' description, and is presented as a lead;
- a watchdog that fires while the server thread is waiting for its next tick means the clock jumped, not that anything is stuck, and a short watchdog warning is not a crash;
- "give it more RAM" is not said when an environment variable is overriding the memory that was set, and never from a lag warning alone;
- the game's own line about updating graphics drivers is printed for every window failure and is treated as boilerplate;
- a plugin that never loaded is not always the broken one: Paper rewrites every plugin before loading any, so one jar it cannot read blocks them all, and that one jar is what gets named;
- a plugin that says the server version is wrong is reading it wrong: when a shaded library announces it will “assume the Server version is V_1_8_8”, everything it does next is built on that, and the crash that follows is named as a consequence, not a cause;
- a plugin whose database never answered is not out of date: “update the plugin” is not said when the failure is an address, a port or a credential;
- a plugin that gave up is not always loud: CoreProtect writes `CoreProtect was unable to start.` at INFO level and the server goes on to print `Done`, and LuckPerms writes `Successfully enabled` after its database never answered — the level a line carries is never read as the weight of what it says;
- missing models, resource packs that come inside mods, connections that never said hello, and illegal-reflective-access warnings are not reported at all.

Everything is **local by default**: nothing leaves the computer unless you ask (update check, share link, mappings download).

## Systems

Linux, Windows and macOS. Tested for real on the three so far: the analyses (the whole corpus gives the same answers on Windows as on macOS, Windows line endings included), the game client launches (vanilla, Fabric, NeoForge) and the client culprit search. Servers run on Linux in the lab. The desktop app is tested on the three too (its screens can also be drawn without any window, `dev.holo795.crashsleuth.desktop.RenderKt <folder> <out>`). The game windows of test launches never come to the front: hidden on macOS, minimised without focus on Windows, a virtual screen (Xvfb) on Linux.

## Platforms

Tested in the lab, on every line of versions from **1.19 to the latest**: vanilla, Paper, Spigot, Purpur, Folia, Fabric, Quilt, NeoForge and Forge servers; Velocity, BungeeCord and Waterfall proxies; vanilla, Fabric, NeoForge and Forge clients.

## Desktop app

Drop a folder, a modpack or a log on the window, read the diagnosis, and start the culprit search with one button. Under the drop area, a Server / Player choice applies to modpacks and lists of mods; folders and logs say it themselves.

```
./gradlew :desktop:run
./gradlew :desktop:packageDistributionForCurrentOS
```

## Command line

Java 21 required:

```
./gradlew :cli:installDist
crashsleuth=cli/build/install/crashsleuth/bin/crashsleuth

# a whole server or game folder: its files, mods, plugins, worlds and most recent logs
$crashsleuth analyze path/to/server
# a crash report, a log or a spark profile, in French, as JSON, with readable names, with a share link
$crashsleuth analyze crash-report.txt --lang fr --readable --share
$crashsleuth analyze profile.sparkprofile --json
# a modpack, without installing it
$crashsleuth analyze pack.mrpack --side client
# a player's game against the server it joins, plus newer versions on Modrinth
$crashsleuth analyze path/to/game --server path/to/server --online
# find the culprit by launching a copy of the server, or of the game
$crashsleuth bisect path/to/server
$crashsleuth bisect path/to/game --client --minecraft 1.21.1 --loader neoforge
# start the game once, join a server or open a save, and say how it went
$crashsleuth run-client path/to/game --minecraft 1.21.1 --loader fabric --join localhost:25565
# serve CrashSleuth to an assistant that speaks MCP (analyze, inventory, compare, readable,
# config_read, known_settings), over the standard input and output
$crashsleuth mcp
# a log with Mojang's names; mixins and where they collide; installed files as JSON
$crashsleuth readable latest.log
$crashsleuth mixins path/to/server
$crashsleuth inventory path/to/server
```

`bisect` works on a copy: your folder is never changed. It decides by itself whether each launch started, crashed or froze, tests the mods named by the analysis first, then narrows the set down with delta debugging. Dependencies are always kept with the mods that need them. `--repeat 5` handles crashes that do not happen every time, `--parallel 2` runs several launches at once.

Known log messages live in a data file, [`signatures.json`](core-logs/src/main/resources/crashsleuth/signatures.json): adding a case needs no code. Part of them are adapted from [codex-minecraft](https://github.com/aternosorg/codex-minecraft) (MIT, Aternos GmbH).

## Real-world lab

CrashSleuth is tested against real servers and real games, not only unit tests. Every log it was checked on is kept in `lab/corpus` with the installed files and the expected answer, and replayed by the test suite on every change. Healthy servers and games must produce no finding.

- `lab/lab.py`: real vanilla, Paper, Purpur, Fabric, NeoForge and Forge servers from official sources, real mods and plugins from Modrinth, broken on purpose (missing dependency, wrong Java, broken configuration, damaged chunks, lag, deadlock, container too small...), run in Docker.
- `lab/client_lab.py`: the real game client on a computer with a screen (Fabric and NeoForge mods, resource packs, shader packs, culprit searches).
- `lab/net_lab.py`: real servers and proxies in Docker, joined by the real game.
- `lab/fixtures`: small test plugins and mods that misbehave on purpose, for cases real mods cannot provide.

```
python3 lab/lab.py list
python3 lab/lab.py run all --cli cli/build/install/crashsleuth/bin/crashsleuth
```

## License

[MIT](LICENSE)

---

# CrashSleuth (français)

**Trouver pourquoi Minecraft plante, et qui est en cause.**

CrashSleuth diagnostique les crashs, les échecs de démarrage, les gels, le lag et les problèmes de connexion, pour les **joueurs comme pour les admins de serveurs**, en **vanilla, sur serveurs à plugins, proxys et modpacks**. Il lit vos journaux et vos fichiers (mods, plugins, configurations, mondes), vérifie tout avant le lancement et, si ça ne suffit pas, **relance lui-même le jeu ou le serveur** en retirant des mods ou des plugins jusqu'à désigner le coupable, sans vous demander « ça a planté ? » après chaque essai.

- Application de bureau et ligne de commande (`--lang fr`), pour **Linux, Windows et macOS**. Déjà testés pour de vrai sur les trois : les analyses, les lancements du jeu (vanilla, Fabric, NeoForge) et la recherche du coupable côté client ; l'application de bureau aussi. Les fenêtres des lancements de test ne passent jamais au premier plan.
- Tout reste **sur votre ordinateur** par défaut ; rien ne part sans que vous le demandiez (mises à jour Modrinth par empreinte seulement, lien de partage qui contient le rapport, correspondances de noms téléchargées une fois).
- Explication en termes simples par une IA qui tourne sur votre ordinateur (Ollama), en option : elle ne peut citer que des réglages connus de CrashSleuth, et tout le reste est signalé.
- `crashsleuth mcp` : CrashSleuth comme outil pour un assistant (MCP), qui peut alors analyser un dossier, lire la configuration (secrets masqués) et demander les réglages qui existent au lieu de les deviner.

Voir la [spécification](docs/SPEC.fr.md) et l'[état de l'existant](docs/PRIOR_ART.md). Licence [MIT](LICENSE).
