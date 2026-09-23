# Supported versions

This page is **generated from the lab**, not written by hand: every tick below is a server or a game that
was really started, and the page cannot show a version that nobody ran.

**The range is Minecraft 1.19 to 26.3**, including the 26.x numbering.

## What a tick means

| Mark | What was started |
| --- | --- |
| ✅ | two launches: a **clean** one, where CrashSleuth must say nothing, and one with a **dependency removed on purpose**, which it must name |
| ☑️ | the clean launch only — CrashSleuth stays silent on a healthy server |
| *(empty)* | not started: the platform does not publish that version, or it sits between two tested lines |

The clean launch matters as much as the other one: a tool that finds problems on healthy servers is worse
than no tool. Vanilla has nothing to remove, so for vanilla the clean launch *is* the whole test.

## Servers

Vanilla is started on **every** release. The other platforms are started on one version per line of
Minecraft — each point where the game, the loader or Java changed in a way that shows in the logs.

| Minecraft | Vanilla | Paper | Purpur | Spigot | Folia | Fabric | Quilt | NeoForge | Forge | Mohist | Youer | Arclight (Forge) | Arclight (NeoForge) | Arclight (Fabric) |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **1.19.2** | ☑️ | ✅ | ✅ |  |  | ✅ | ✅ |  | ✅ |  |  | ✅ |  |  |
| **1.19.3** | ☑️ |  |  |  |  |  |  |  |  |  |  | ✅ |  |  |
| **1.19.4** | ☑️ | ✅ | ✅ | ✅ | ☑️ | ✅ | ✅ |  | ✅ | ✅ |  | ✅ |  |  |
| **1.20.1** | ☑️ | ✅ | ✅ |  | ☑️ | ✅ | ✅ | ✅ | ✅ | ✅ |  | ✅ |  |  |
| **1.20.2** | ☑️ |  |  |  |  |  |  |  |  | ✅ |  | ✅ |  |  |
| **1.20.4** | ☑️ | ✅ | ✅ | ✅ | ☑️ | ✅ | ✅ | ✅ | ✅ |  |  | ✅ | ✅ | ✅ |
| **1.20.6** | ☑️ | ✅ | ✅ |  | ☑️ | ✅ | ✅ | ✅ | ✅ |  |  |  |  |  |
| **1.21.1** | ☑️ | ✅ | ✅ | ✅ |  | ✅ | ✅ | ✅ | ✅ |  | ✅ |  | ✅ | ✅ |
| **1.21.4** | ☑️ | ✅ | ✅ |  | ☑️ | ✅ | ✅ | ✅ | ✅ |  |  |  |  |  |
| **1.21.8** | ☑️ | ✅ | ✅ |  | ☑️ | ✅ | ✅ | ✅ | ✅ |  |  |  |  |  |
| **1.21.11** | ☑️ | ✅ | ✅ | ✅ | ☑️ | ✅ | ✅ | ✅ | ✅ |  |  |  |  |  |
| **26.1.2** | ☑️ | ✅ | ✅ |  | ☑️ | ✅ | ✅ | ✅ | ✅ |  |  |  |  |  |
| **26.2** | ☑️ | ✅ | ✅ |  | ☑️ | ✅ | ✅ | ✅ | ✅ |  | ✅ |  |  |  |
| **26.3** | ☑️ | ✅ | ✅ | ✅ |  | ✅ | ✅ |  | ✅ |  |  |  |  |  |

**Every vanilla release** from 1.19 to 26.3 is started on its own
(29 versions): 1.19, 1.19.1, 1.19.2, 1.19.3, 1.19.4, 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4, 1.20.5, 1.20.6, 1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, 26.3.

**Hybrid servers** — Mohist, Youer, Arclight (Forge), Arclight (NeoForge), Arclight (Fabric) — run mods and plugins at once; both folders are read, and the checks of both kinds apply.

### Published, but not started

These versions exist, yet the server they ship does not start in the lab, so nothing can be said about them. They come back in the table as soon as a working build is published.

- **Arclight (Forge) 1.21.1** — its release build (1.0.1) cannot apply its own changes to Forge and stops before loading anything, with no mod and no plugin (https://github.com/IzzelAliz/Arclight/issues/1927).
- **Mohist 1.19.2** — its newest build (391) loops on a library missing from its own jar and never starts.

## Game clients

The real game, started in a window that never comes to the front.

| Minecraft | Vanilla | Fabric | NeoForge | Forge |
| --- | :---: | :---: | :---: | :---: |
| **1.19.4** | ☑️ | ✅ |  | ☑️ |
| **1.20.1** | ☑️ | ✅ | ☑️ | ☑️ |
| **1.20.4** | ☑️ | ✅ | ☑️ | ☑️ |
| **1.21.1** | ☑️ | ✅ | ☑️ | ☑️ |
| **1.21.4** | ☑️ | ✅ | ☑️ | ☑️ |
| **1.21.11** | ☑️ | ✅ | ☑️ | ☑️ |
| **26.1.2** | ☑️ | ✅ | ☑️ | ☑️ |
| **26.3** | ☑️ | ✅ |  | ☑️ |

## Proxies

Velocity 3.4.0, Velocity 4.2.0, BungeeCord (latest build), Waterfall (latest build), each with a real player joining through it.

## Versions in between

A version that sits between two tested lines — 1.20.2, say — was not started on its own. It usually behaves
like its neighbours, because the messages CrashSleuth reads rarely change within a line, but that is an
expectation and not a proof. If something looks wrong on such a version, [the log is worth sending](Contributing).

## Outside the range

Before 1.19, CrashSleuth still reads the logs and will often be right, but nothing is started
to check it: older loaders word their errors differently and no rule is tested against them.

---

*216 launches in the matrix, regenerated with `python3 lab/versions.py`.*
