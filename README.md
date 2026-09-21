# CrashSleuth

**Find out why Minecraft crashes, and who is to blame.**

CrashSleuth diagnoses crashes, startup failures, hangs and lag for **players and server admins**, on **vanilla, plugin servers and modpacks**. It reads your logs, checks your mods or plugins before launch, and when that is not enough, **launches the game or server by itself**, removing mods or plugins step by step until it names the culprit. No need to answer "did it crash?" after every run.

> Status: early design. See the [specification (French)](docs/SPEC.fr.md) and the [prior art review](docs/PRIOR_ART.md).

## What it will do

- **Analyze** a crash report or log in seconds: cause, culprit mod or plugin, fix.
- **Check** a mods folder, plugins folder or modpack before launch: missing or out-of-range dependencies, wrong loader, duplicates, client-only mods on a server, Java version, mixin conflicts, corrupted jars.
- **Find the culprit** automatically: dependency-aware bisection with real launches, including conflicts that only happen when two mods are installed together.
- **Explain hangs and lag** from thread dumps and spark profiles.
- Work **offline and locally by default**. Optional AI, never required.

## Platforms (first release)

Vanilla, Paper, Spigot, Purpur, NeoForge, Forge and Fabric, on the most used versions (1.20.1, 1.21.x, 26.x). Quilt, Folia, Velocity and BungeeCord come next.

## License

[MIT](LICENSE)

---

# CrashSleuth (français)

**Trouver pourquoi Minecraft plante, et qui est en cause.**

CrashSleuth diagnostique les crashs, les échecs de démarrage, les gels et le lag, pour les **joueurs comme pour les admins de serveurs**, en **vanilla, sur serveurs à plugins et en modpacks**. Il lit vos journaux, vérifie vos mods ou plugins avant le lancement et, si ça ne suffit pas, **relance lui-même le jeu ou le serveur** en retirant des mods ou des plugins jusqu'à désigner le coupable, sans vous demander « ça a planté ? » après chaque essai.

> État : conception. Voir la [spécification](docs/SPEC.fr.md) et l'[état de l'existant](docs/PRIOR_ART.md).

Licence [MIT](LICENSE).
