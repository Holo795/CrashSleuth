# CrashSleuth

**Find out why Minecraft crashes, and who is to blame.**

CrashSleuth diagnoses crashes, startup failures, hangs and lag for **players and server admins**, on **vanilla, plugin servers and modpacks**. It reads your logs, checks your mods or plugins before launch, and when that is not enough, **launches the game or server by itself**, removing mods or plugins step by step until it names the culprit. No need to answer "did it crash?" after every run.

> Status: in development. Log analysis, checks of a server folder or modpack, and the automatic culprit search on servers already work from the command line. See the [specification (French)](docs/SPEC.fr.md) and the [prior art review](docs/PRIOR_ART.md).

## What it will do

- **Analyze** a crash report or log in seconds: cause, culprit mod or plugin, fix.
- **Check** a mods folder, plugins folder or modpack before launch: missing or out-of-range dependencies, wrong loader, duplicates, client-only mods on a server, Java version, mixin conflicts, corrupted jars.
- **Find the culprit** automatically: dependency-aware bisection with real launches, including conflicts that only happen when two mods are installed together.
- **Explain hangs and lag** from thread dumps and spark profiles.
- Work **offline and locally by default**. Optional AI, never required.

## Platforms (first release)

Vanilla, Paper, Spigot, Purpur, NeoForge, Forge and Fabric, on the most used versions (1.20.1, 1.21.x, 26.x). Quilt, Folia, Velocity and BungeeCord come next.

## Usage (early preview)

Java 21 required:

```
./gradlew :cli:installDist
# a whole server or instance folder: its mods, plugins and most recent logs
cli/build/install/crashsleuth/bin/crashsleuth analyze path/to/server
# a single crash report or log
cli/build/install/crashsleuth/bin/crashsleuth analyze path/to/crash-report.txt --lang fr
cli/build/install/crashsleuth/bin/crashsleuth analyze hs_err_pid1234.log --json
# a modpack, without installing it (Modrinth .mrpack, CurseForge zip, zipped server)
cli/build/install/crashsleuth/bin/crashsleuth analyze pack.mrpack --side server
# find the culprit by launching a copy of the server with fewer mods or plugins
cli/build/install/crashsleuth/bin/crashsleuth bisect path/to/server --java /path/to/java
# which mods change which game methods through mixins, and where they collide
cli/build/install/crashsleuth/bin/crashsleuth mixins path/to/server
# the list of installed mods and plugins with their metadata, as JSON
cli/build/install/crashsleuth/bin/crashsleuth inventory path/to/server
```

`bisect` works on a copy: your server folder is never changed. It launches the server on a free port, decides by itself whether each launch started, crashed or froze, tests the mods named by the analysis first, then narrows the set down with delta debugging, which also finds crashes that only happen when two mods or plugins are installed together. Dependencies are always kept with the mods that need them. `--repeat 5` handles crashes that do not happen every time, `--parallel 2` runs several launches at once.

From the logs, it recognises missing and outdated dependencies (NeoForge, Forge, Fabric, Quilt, Paper, Spigot, Purpur), mods made for another Minecraft version or loader, incompatible mods, client-only mods on a server, duplicates, plugins that fail to load, to enable, or keep failing while the server runs, a main thread blocked by a plugin (Paper watchdog), wrong Java versions, mixin failures, crashes on a ticking entity or block (with its position), worlds opened with an older Minecraft or missing mods, out of memory errors, stack overflows and native Java crashes. Other errors are attributed to the mod or plugin found in the stack trace.

From the installed files or a modpack, before any crash, it finds duplicates, jars made for another loader or put in the wrong folder, missing dependencies (including jar-in-jar), mods made for another Minecraft version, jars compiled for a newer Java, known client-only mods on a server, plugins asking for a newer API and corrupted jars.

Known log messages live in a data file, [`signatures.json`](core-logs/src/main/resources/crashsleuth/signatures.json): adding a case needs no code. Part of them are adapted from [codex-minecraft](https://github.com/aternosorg/codex-minecraft) (MIT, Aternos GmbH).

## Real-world lab

CrashSleuth is tested against real servers, not only unit tests. `lab/lab.py` builds real vanilla, Paper, Purpur, Fabric, NeoForge and Forge servers from official sources, installs real mods and plugins from Modrinth, breaks them on purpose (missing dependency, wrong loader, client-only mod on a server, wrong Java, out of memory...), runs them in Docker with the right Java version, and checks the diagnosis. The resulting logs, with the inventory of installed jars, are kept in `lab/corpus` and replayed by the test suite on every change. Healthy servers must produce no finding.

```
python3 lab/lab.py list
python3 lab/lab.py run all --cli lab/crashsleuth-docker.sh
```

## License

[MIT](LICENSE)

---

# CrashSleuth (français)

**Trouver pourquoi Minecraft plante, et qui est en cause.**

CrashSleuth diagnostique les crashs, les échecs de démarrage, les gels et le lag, pour les **joueurs comme pour les admins de serveurs**, en **vanilla, sur serveurs à plugins et en modpacks**. Il lit vos journaux, vérifie vos mods ou plugins avant le lancement et, si ça ne suffit pas, **relance lui-même le jeu ou le serveur** en retirant des mods ou des plugins jusqu'à désigner le coupable, sans vous demander « ça a planté ? » après chaque essai.

> État : en développement. L'analyse des journaux, la vérification d'un dossier ou d'un modpack et la recherche automatique du coupable sur serveur fonctionnent déjà en ligne de commande (`crashsleuth analyze` et `crashsleuth bisect`, `--lang fr`). Voir la [spécification](docs/SPEC.fr.md) et l'[état de l'existant](docs/PRIOR_ART.md).

Licence [MIT](LICENSE).
