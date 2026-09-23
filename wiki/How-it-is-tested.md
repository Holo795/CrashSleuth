# How it is tested

The point of this project is to be **right**, so most of the work is in proving that it is.

## Problems real people actually had

The heart of it: **65 reports** from GitHub issues and forums, each rebuilt in a lab with the same
Minecraft version, the same loader and the same jars — and CrashSleuth has to reach the answer those people
ended up with.

Every one is listed in [docs/REAL_CASES.md](https://github.com/Holo795/CrashSleuth/blob/main/docs/REAL_CASES.md)
with its source link and the fix that worked.

A few of them, to show what that means in practice:

- a Paper server told to use a database that never answers — CoreProtect says so at INFO level and the
  server prints `Done` as if all were well;
- a Fabric 26.2 server where one mod asks for biomes another never registered, and the game blames your
  datapacks;
- a plugin jar holding the same class twice, which stops **every** plugin from loading;
- a world written by a 26.1.2 server, opened by a 1.21.11 one;
- a resource pack whose core shader quietly switches off every pack you had selected;
- a server set to accept BungeeCord forwarding behind a Velocity proxy, kicked with a message naming a
  program that is installed nowhere.

## What happens when one does not reproduce

It is dropped, and said so. Several reports were tried and abandoned rather than staged: a plugin load
cycle that Paper 1.21.8 simply does not complain about (tried with `softdepend`, `loadbefore` and `depend`),
a Sodium `@Overwrite` crash, a truncated mod config NeoForge repairs by itself, and a few others.

A rule with a real source and a verbatim test but no replay stays in — described as exactly that.

## The corpus

Every replay leaves its logs behind. **399 of them** are replayed on every single build, on three operating
systems, and the answers must not move. That is what catches a new rule quietly breaking an old one — it
has happened, and that is the point.

## Rules are locked to their source

Each detection rule carries the report it came from, and a test built on the **verbatim text** of that
report. Not a paraphrase: the exact lines the person pasted.

## Healthy games, too

Just as important: perfectly fine servers and games are analysed, and the tool has to stay **silent**. Two
of its own rules were caught this way, firing on games where nothing was wrong at all, and were removed or
narrowed.

## Finding its own blind spots

A newer habit: take a pile of public reports — 134 of them so far — and run them through the tool exactly as
they were written, to see what it says nothing about. That turned up eleven wrong or missing answers,
including five where it was repeating the same bad advice the game gives, and two defects in the tool
itself.

## On every commit

`./gradlew build` on Linux, Windows and macOS: the whole test suite plus the 399-case corpus. The lab runs
(real servers and real game clients in Docker) are run by hand before a release, because they need Docker
and a lot of time.

## Doing it yourself

```bash
./gradlew build                      # tests and corpus
python3 lab/lab.py run 'real-*'      # replay the server cases (needs Docker)
python3 lab/client_lab.py run 'real-*'   # the client ones
python3 lab/net_lab.py run join-ok       # a proxy and a player
```
