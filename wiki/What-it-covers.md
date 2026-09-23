# What it covers

## Platforms

**Servers** — vanilla, Paper, Spigot, Purpur, Folia, Fabric, Quilt, NeoForge, Forge.
**Proxies** — Velocity, BungeeCord, Waterfall.
**Clients** — vanilla, Fabric, NeoForge, Forge.

Every one of those is started for real in the lab, on every line of Minecraft versions **from 1.19 to the
latest**, including the 26.x numbering. [**Supported versions**](Supported-versions) has the exact table,
platform by platform, generated from what the lab actually starts.

## Systems

Linux, Windows and macOS, tested for real on all three: the analyses give the same answers on Windows as on
macOS (Windows line endings included), the game launches work, and the app itself runs. Servers are launched
on Linux in the lab.

Game windows opened by the tool **never come to the front**: hidden on macOS, minimised without focus on
Windows, a virtual screen on Linux.

## What it can tell you about

**Before anything crashes** — missing or out-of-range dependencies, a loader too old, mods that declare each
other incompatible, the wrong loader or the wrong folder, duplicates, client-only mods on a server, the
wrong Java, plugins built against another server's internals, corrupt jars, broken configuration files (with
the line), the EULA, worlds saved by a newer version, a locked or damaged `level.dat`, damaged chunks and
entities, damaged player saves.

**From the logs** — crashes, startup failures, hangs, deadlocks, lag, proxy and connection problems, the
launcher's or panel's own console, mixin conflicts, registry mismatches between client and server.

**While playing** — a crash during the tick of an entity or a block names that entity or block, where it
stands, and the mod behind it. A crash while the world is drawn names what was being drawn and the mod in
the way.

**By launching** — when none of the above is enough. [The culprit search →](Finding-the-culprit)

## What it refuses to say

Most lines that name a mod name the one that **noticed**, not the one at fault, and the usual advice is
often wrong. This is the part of the tool that took the most work:

| It does not say | Because |
| --- | --- |
| The mixin that failed is the culprit | It names the mod that **lost**; the one that got there first is named instead |
| The mod in `Could not pass event … to X` | X registered the listener; if none of X is in the trace, the code that threw is named |
| The mod that wrote the `breaks` rule | The mod to change is the one being **refused**, not the one refusing |
| The entrypoint owner on Fabric's crash screen | The `Caused by` chain is read instead — its own author renamed his class over this |
| Forge's `Suspected Mods` as an answer | Its authors say it is everything in the stack; it is offered as a lead |
| "The server is stuck" on a watchdog warning | A watchdog firing while the thread waits means the clock jumped |
| "Give it more RAM" | Never from a lag warning, and never while an environment variable overrides the memory you set |
| "Update the plugin" for a database that did not answer | The plugin is not at fault; the address, port or credentials are |
| "Fix your datapacks" | Printed for a mod's own sealed data, and for a world back from a newer version |
| "Your card has no OpenGL" | On a recent card that is XWayland, not the card |
| "Out of stack space means more RAM" | It is LWJGL's native scratch space; `-Xmx` does not touch it |
| A missing `java.util.List.removeFirst()` is a broken mod | It is Java 21 being absent |
| The game's line about updating graphics drivers | It is printed for every window failure; it is boilerplate |

And it stays **completely silent** about missing models, resource packs shipped inside mods, connections
that never said hello (port scanners), and illegal-reflective-access warnings. Those are noise, and two of
them were rules of its own that the tests caught misfiring on perfectly healthy games.

## Where the limits are

- It reads what is written. A mod that crashes without logging anything needs the culprit search.
- The culprit search needs to be able to **launch** your server or game; a lone log file is not enough.
- A conclusion is only as good as the folder you point it at: give it the real one, not a copy with half
  the mods removed.
- New Minecraft versions change messages. When one is not understood yet, it says so instead of guessing —
  and [that is worth sending in](Contributing).
