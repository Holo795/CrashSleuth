# FAQ and troubleshooting

### It found nothing, but my server is clearly broken

Two things to try, in order:

1. **Give it the folder, not the log.** A log says what happened; a folder also shows what is installed, and
   that is where half the answers are.
2. **Let it find the culprit by launching.** [How →](Finding-the-culprit)

If neither works, the log is one it does not know yet — [sending it in](Contributing) is genuinely useful.

### Windows says "Windows protected your PC"

Because the installer is not signed with a paid certificate. **More info** → **Run anyway**.
[Details →](Install#windows)

### macOS says the developer cannot be verified

Same reason, Apple's version. **System Settings → Privacy & Security → Open Anyway**.
[Details →](Install#macos)

### Does it send my logs anywhere?

No. Nothing leaves the machine unless you ask, and the five things that *can* are listed one by one on
[what leaves your computer](Privacy).

### Is the share link uploaded somewhere?

No. The report is inside the link, after the `#`, which browsers never send to any server.
[The whole explanation →](Sharing-a-report#why-nothing-is-uploaded)

### It named a mod, but that mod is fine

It may well be right in a way that looks wrong: the mod that *fails* is often not the mod that is *broken* —
a mod can fail because another one changed the game underneath it. Open **Show the evidence** and read the
lines; the advice usually says which is which.

If it is simply wrong, that is worth an issue with the log. Wrong answers are how the good rules got written.

### The culprit search says it needs a folder

You gave it a single log file. Point it at the server or game folder and the button becomes available.

### The culprit search is taking forever

Each launch costs what your server costs to start. `--parallel 2` runs two at a time if you have the memory,
`--max-runs 20` puts a ceiling on it. A heavy modpack is genuinely slow — the number of launches is small,
the launches themselves are not.

### Can I run it on a headless server?

Yes — that is what the [command line](Command-line) is for. `crashsleuth analyze /srv/minecraft` needs no
display. The client-side parts (`--client`, `run-client`) use a virtual screen on Linux.

### Which Java do I need?

Java 21 or newer for the portable and command-line versions. The installers carry their own runtime, so
there is nothing to install.

### It says my Java is wrong but I installed the right one

Check what the *server* uses, not what your terminal uses. A panel, a `start.sh` or a systemd unit often
pins a different one. And `_JAVA_OPTIONS` in the environment silently overrides what the start command says
— CrashSleuth reports that one on its own when it sees it.

### Does it work on Bedrock?

No. Java Edition only: servers, proxies, modpacks and the Java client.

### Does it modify my server?

Never. The analysis only reads, and the culprit search works on copies in a temporary folder.

### How do I report a bug in CrashSleuth itself?

[An issue](https://github.com/Holo795/CrashSleuth/issues/new), with what you gave it and what it said.
If it is a wrong answer, the log matters more than anything else you can write.
