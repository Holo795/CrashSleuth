# What leaves your computer

**Nothing, unless you ask.** There is no account, no telemetry, no crash reporting, no analytics, and no
server of ours anywhere. The app does not even check its own version until you have said it may.

Here is the complete list of things that can reach the network, what each one sends, and how to stop it.

## Nothing at all, by default

Open the app, drop a server folder on it, read the report: not one byte leaves the machine. The whole
analysis — logs, jars, mixins, world files, configuration — happens locally.

## The five things that can, and only when asked

### 1. Looking for a newer CrashSleuth

**Asked first.** The home screen shows one line — *"Look on GitHub for new versions of CrashSleuth?"* — and
does nothing until you answer. Say no and it never asks again.

If you say yes: a plain `GET` to `api.github.com` for the latest tag of this repository, at most once a day.
Nothing about you, your files or your server is sent. It never downloads or installs anything; it tells you
a version exists and gives you the link.

A build from sources has no version, so the question is not even shown.

### 2. `--online`: are my mods up to date?

Sends the **SHA-1 hash of each jar file** to Modrinth, which answers with the newest version it knows for
that file. The jar itself is never uploaded, and no file names, paths or identifiers go with it.

Only happens with `--online`, or the equivalent button in the app.

### 3. `--readable`: translating the game's names

Downloads Minecraft's official mappings from `piston-meta.mojang.com` (Mojang) and Fabric's from
`meta.fabricmc.net`, for the version found in your log. It sends the version number and nothing else, and
keeps the file so it only happens once.

### 4. `--share`: a report as a link

**Nothing is uploaded, including here.** Making the link is arithmetic on your own machine; the report
lives inside the link, after the `#`, which browsers never send to any server.
[The whole explanation →](Sharing-a-report#why-nothing-is-uploaded)

### 5. `--explain`: plain words from a local model

Talks to a model **running on your own computer** — Ollama, or any OpenAI-compatible server you point
`CRASHSLEUTH_AI_URL` at. Nothing goes to a company's API.

Even locally it is careful: only an **anonymised summary** of the report is passed, and the model is only
allowed to name settings CrashSleuth already knows. If it names anything else, that is flagged in the
answer rather than repeated as advice.

## What the culprit search does

`bisect` launches copies of your own server or game. Those launches are the server doing what it normally
does — which, for a modded server, may mean the mods themselves talking to their own services. CrashSleuth
adds nothing to that, and installs game versions from Mojang's and the loaders' official sources when it
needs them.

**Your files are never modified.** Everything runs on copies in a temporary folder.

## Where things are stored on your machine

| What | Where |
| --- | --- |
| Your settings and recent analyses | `~/.crashsleuth/desktop.json` |
| Downloaded mappings, game files | `~/.cache/crashsleuth/` (or `$CRASHSLEUTH_CACHE`) |

Delete either at any time; the app rebuilds what it needs.

## Cutting it off entirely

Block the app in your firewall, or simply never use `--online`, `--readable` or `--explain` and answer *no*
to the version question. Everything else keeps working: the analysis has never needed the network.
