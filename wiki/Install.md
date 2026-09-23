# Installation

Every file below is built by GitHub from a tag, on a clean machine, and listed on the
[latest release](https://github.com/Holo795/CrashSleuth/releases/latest) with its SHA-256 checksum in
`SHA256SUMS.txt`.

Nothing is bundled with the app that phones home. See [what leaves your computer](Privacy).

## Windows

1. Download `CrashSleuth-<version>-windows-x64.msi`.
2. Run it. It installs for your user only — no administrator password.
3. Windows shows **"Windows protected your PC"** the first time, because the installer is not signed with
   a paid certificate. Click **More info**, then **Run anyway**.

The app is then in the Start menu under **CrashSleuth**.

## macOS

1. Download `CrashSleuth-<version>-macos-arm64.dmg`. It is built for **Apple silicon** (M1 and later);
   on an older Intel Mac, use the [portable version](#portable--any-system-with-java-21) instead, because
   GitHub no longer offers Intel macOS machines to build on.
2. Open the `.dmg` and drag **CrashSleuth** into **Applications**.
3. The first launch is refused: *"CrashSleuth cannot be opened because the developer cannot be verified."*
   Open **System Settings → Privacy & Security**, scroll to the bottom, and click **Open Anyway**.
   From then on it opens normally.

That warning is macOS telling you the app is not notarised by a paid Apple developer account. It is not a
judgement about the app.

## Linux

```bash
sudo apt install ./CrashSleuth-<version>-linux-x64.deb
```

Works on Debian, Ubuntu, Mint and their relatives. The app appears in your menu under **CrashSleuth**.

On other distributions, use the portable version below.

## Portable — any system with Java 21

No installer, nothing written outside the folder you unzip it into.

```bash
unzip CrashSleuth-portable-<version>.zip
java -cp "lib/*" dev.holo795.crashsleuth.desktop.MainKt
```

You need a Java 21 or newer runtime on the machine (`java -version` to check). This is also how the app is
tried on a system it was not built on.

## Command line

The same tool without a window: the one to install on a server, in a container, or for an assistant.

```bash
unzip CrashSleuth-cli-<version>.zip
./crashsleuth-<version>/bin/crashsleuth analyze /srv/minecraft
```

It also needs Java 21. Put `bin/` on your `PATH` if you want to type `crashsleuth` from anywhere.
Everything it can do is on the [command line page](Command-line).

## Building it yourself

```bash
git clone https://github.com/Holo795/CrashSleuth.git
cd CrashSleuth
./gradlew build            # compiles and runs the whole test suite
./gradlew :cli:installDist # cli/build/install/crashsleuth/bin/crashsleuth
./gradlew :desktop:run     # the app
```

JDK 21 is the only requirement. `./gradlew :desktop:packageMsi` (or `packageDmg`, `packageDeb`) builds the
installer for the system you are on — jpackage cannot cross-build, which is why the release is built on
three runners.

## Updating

CrashSleuth never checks its own version and never updates itself. To move to a newer one, download it from
the [releases page](https://github.com/Holo795/CrashSleuth/releases) and install it over the old one —
the Windows installer replaces the previous version in place, and on macOS you drag the new app over the
old one.

(The "check for updates" the app *does* offer is about **your mods**, not about itself: see
[`--online`](Command-line#analyze) and [what leaves your computer](Privacy).)
