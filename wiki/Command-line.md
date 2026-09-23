# Command line

The same engine as the app, without a window. This is what you install on a server, in a container, or
behind an assistant.

```bash
crashsleuth --help
```

| Command | What it does |
| --- | --- |
| [`analyze`](#analyze) | read a folder, a crash report or a log, and say what is wrong |
| [`bisect`](#bisect) | launch the server or game with fewer mods until the culprit is alone |
| [`inventory`](#inventory) | list the mods and plugins with their metadata, as JSON |
| [`readable`](#readable) | print a log with the game's obfuscated names translated |
| [`mixins`](#mixins) | which mod changes which method of the game, and where they collide |
| [`mcp`](#mcp) | serve CrashSleuth to an assistant |
| [`run-client`](#run-client) | start the game once and say whether it started |

---

## analyze

```bash
crashsleuth analyze /srv/minecraft
crashsleuth analyze crash-2026-09-23_01.11.37-server.txt
crashsleuth analyze ~/.minecraft --server /srv/minecraft
```

Takes one or more targets: a server folder, a game folder, a modpack, a crash report, a `latest.log`, an
`hs_err_pid` file, a spark profile.

| Option | What it adds |
| --- | --- |
| `--json` | the report as JSON, for a script |
| `--lang en` / `--lang fr` | the language of the report |
| `--share` | print a link holding the whole report; nothing is uploaded ([how](Sharing-a-report)) |
| `--server <path>` | compare a player's folder with the server they are joining |
| `--side server` / `--side client` | which side a modpack is checked for |
| `--online` | ask Modrinth, **by file hash only**, whether newer versions exist |
| `--readable` | translate the game's obfuscated names in the evidence (downloads mappings once) |
| `--explain` | ask a model **running on your own computer** to put it in plain words |

`--online`, `--readable` and `--explain` are the only ones that touch the network.
[What is sent, exactly →](Privacy)

## bisect

```bash
crashsleuth bisect /srv/minecraft --with-world --repeat 3
crashsleuth bisect ~/.minecraft --client --minecraft 1.21.1 --loader fabric
```

Launches copies of your server or game with fewer mods each time, and names what has to be there for the
crash to happen. Your files are never changed. [The whole page on it →](Finding-the-culprit)

## inventory

```bash
crashsleuth inventory /srv/minecraft
```

Every jar with its id, version, loader, declared dependencies and the Minecraft versions it says it
supports — as JSON. Useful in a script, or to see what CrashSleuth thinks is installed when its answer
surprises you.

## readable

```bash
crashsleuth readable crash-2026-09-23-client.txt
```

Obfuscated and intermediary names — `fgo.b`, `class_310.method_22681` — are replaced by the names Mojang
publishes, so a trace becomes something you can read. The mappings come from Mojang and Fabric, are
downloaded once for the version in the log, and are then kept in the cache.

## mixins

```bash
crashsleuth mixins /srv/minecraft
```

Which mod rewrites which method of the game, and which ones are fighting over the same one. This is the
list behind the "two mods changed the same thing" findings.

## mcp

```bash
crashsleuth mcp
```

Speaks the Model Context Protocol on standard input and output, so an assistant can analyse a folder, list
what is installed, compare a player with a server, read a configuration file (with secrets hidden) and ask
which settings exist. [How to wire it up →](Assistants-MCP)

## run-client

```bash
crashsleuth run-client ~/.minecraft --minecraft 1.21.1 --loader fabric
```

Starts the game once and says whether it reached the title screen. The window is hidden by default — the
verdict comes from the logs — and `--show` leaves it in front if you want to watch. `--join host:port`
sends it to a server as soon as it has started, `--join world:<save>` opens a world instead.

This is the building block `bisect --client` uses; on its own it answers "does this modpack even start?".

## Exit codes

`0` when it ran, whatever it found. A non-zero code means the command itself failed — a folder that does not
exist, a download that did not work — not that your server is broken.
