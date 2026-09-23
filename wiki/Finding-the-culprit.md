# Finding the culprit by launching

Sometimes the log names nobody. The crash is inside the game's own code, the trace is all vanilla, and every
answer on the internet is "remove mods until it stops".

That is exactly what CrashSleuth does — except it does it for you, and it does not ask you anything.

## What it actually does

It copies your server (or game folder) into a working directory, starts it with **half the mods**, sees
whether it still breaks, and narrows down from there. When it is done it names the mod, or the **pair** of
mods, that has to be present for the problem to happen.

**Your files are never changed.** Everything happens on copies, in a temporary folder that is deleted
afterwards unless you ask to keep it.

It decides for itself whether a launch failed. You never answer "did it crash?".

## In the app

Open a report and click **Find the culprit by launching**, on the right.

If the panel says *"The search needs a server or game folder"*, you gave it a lone log file. Point it at the
folder instead and the button becomes available.

## From the command line

```bash
crashsleuth bisect /srv/minecraft
```

The options you are most likely to need:

| Option | For |
| --- | --- |
| `--with-world` | crashes that only happen once a world is loaded (the worlds are copied into each launch) |
| `--repeat 3` | crashes that only happen sometimes; each set is tried several times |
| `--parallel 2` | two launches at once — each one needs its own memory |
| `--memory 6G` | when the server's own setting is not what you want |
| `--client` | search in the game **client** instead: it installs Minecraft itself and opens small windows |
| `--max-runs 20` | put a ceiling on how long it may take |
| `--keep` | keep the working folder to look at the logs afterwards |

## It understands dependencies

A naive bisection removes a library and blames it for every mod that needed it. This one reads the
dependency graph first: a mod is never tested without what it requires, so the answer is a real culprit and
not a library everything depended on.

## It finds pairs

Some crashes need **two** mods present at once, and neither is at fault alone. A bisection that only ever
removes half the list can miss those forever. When removing one half and the other half both stop the crash,
CrashSleuth knows it is looking at a pair and goes after it.

## On the game client

```bash
crashsleuth bisect ~/.minecraft --client --minecraft 1.21.1 --loader fabric
```

It installs the Minecraft version it needs on its own — you do not have to have played it. The windows it
opens **never come to the front**: hidden on macOS, minimised without focus on Windows, a virtual screen on
Linux. You can keep working while it runs.

## How long it takes

Each launch costs what your server costs to start, so a search is a handful of minutes for a small server
and can be much longer for a heavy modpack. The number of launches grows with the *logarithm* of the number
of mods: 200 mods is around 8 launches, not 200.

`--parallel` shortens it if you have the memory; `--max-runs` stops it if you do not have the time.
