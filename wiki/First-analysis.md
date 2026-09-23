# Your first analysis

Open CrashSleuth and drop something on it. That is the whole of it.

![The home screen](images/home.png)

## What you can drop on it

| You have | Drop this | What it adds |
| --- | --- | --- |
| A server that will not start | the **server folder** | logs *and* the jars, so it can see what is installed |
| A game that crashes | the **game folder** (`.minecraft`, or the instance folder of your launcher) | same, on the client side |
| A modpack | the `.mrpack` or the CurseForge `.zip` | checked before you ever install it |
| Just a crash report | `crash-2026-…-server.txt` | the trace, read and attributed |
| Just a log | `latest.log`, or the console your panel shows | what the log proves |
| A JVM crash | `hs_err_pid1234.log` | the native library or driver behind it |
| Lag you cannot explain | a **spark profile** | the plugin or mod holding the main thread |
| A loose pile of jars | the **mods** or **plugins** folder | dependencies, pairs and duplicates |
| A link someone sent you | paste it in **"A report someone shared?"** | their report, nothing downloaded |

**Give it the folder whenever you can.** A log alone says what happened; a folder also lets it see what is
installed, so it can tell you about a missing dependency, an incompatible pair or a duplicate jar *before*
anything crashes.

## The Server / Player switch

Under the drop area there is a **Server / Player** switch. It only matters for a modpack or a bare list of
mods, where nothing in the files says which side they are for: a mod that only works in the game client is
a fatal mistake on a server and perfectly normal on a player's machine.

Folders and logs are recognised on their own — the switch is ignored for them.

## What happens next

Nothing is uploaded, nothing is changed. CrashSleuth reads:

- every recent log in the folder, **including `console.log`** next to it — the launcher's or panel's own
  console, where a graphics driver refusing the window or a JVM that will not start ends up, none of which
  ever reaches `latest.log`;
- the crash reports, newest first;
- the jars: their metadata, their dependencies, their mixins;
- the world: `level.dat`, the region files (read the way the game reads them), the player saves;
- the configuration files that matter, `server.properties`, `paper-global.yml`, `velocity.toml` and friends.

Then it shows you a report. [How to read it →](Reading-a-report)

## If it finds nothing

That happens, and it is on purpose: CrashSleuth would rather say nothing than name the wrong mod. When the
report is empty you have two ways forward:

- **Let it find the culprit by launching** — the button on the right of the report. It starts a copy of your
  server or game with fewer mods each time and works it out. [How that works →](Finding-the-culprit)
- **Send the log** so a rule can be written for it. [How →](Contributing)
