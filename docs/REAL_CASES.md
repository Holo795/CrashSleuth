# Problems real people had, replayed here

Every case below was reported by someone on the web, rebuilt in the lab with the same versions,
and is replayed by the tests. The last column is what fixed it for them; CrashSleuth is expected
to lead to the same answer on its own.

| Case | What CrashSleuth must find | Culprit | Reported at | What fixed it |
| --- | --- | --- | --- | --- |
| `client-real-client-fabric-api-wrong-point-release` | WRONG_MC | fabric-api | https://github.com/orgs/FabricMC/discussions/4034 | Take the Fabric API built for the exact point release you play. |
| `client-real-client-iris-needs-older-sodium` | DEP_VERSION | sodium | https://github.com/IrisShaders/Iris/issues/3136 | Iris 1.8.8 only works with Sodium 0.6.x: install that branch, or a newer Iris. |
| `net-real-velocity-empty-secret-proxy` | PROXY_FORWARDING |  | https://forums.papermc.io/threads/how-to-solve-unable-to-read-load-save-your-velocity-toml.339/ | Write the secret inside the file named by forwarding-secret-file; the setting is a path, not the secret. |
| `real-fabric-api-missing-journeymap` | DEP_MISSING | fabric | https://github.com/itzg/docker-minecraft-server/discussions/2661 | Install the Fabric API jar: nothing pulls it in for you. |
| `real-fabric-api-wrong-minecraft` | WRONG_MC | fabric-api | https://github.com/orgs/FabricMC/discussions/4403 | Download the Fabric API built for the Minecraft version you run. |
| `real-folia-plugin-unsupported` | PLUGIN_API | InvSee | https://github.com/Jannyboy11/InvSee-plus-plus/issues/87 | Only plugins whose plugin.yml says folia-supported: true load on Folia; ask the author or run Paper. |
| `real-jei-loader-too-old` | DEP_VERSION | fabric | https://github.com/mezz/JustEnoughItems/issues/3748 | Upgrade Fabric Loader to 0.16.3 or later. |
| `real-kotlin-language-provider-missing` | DEP_MISSING | kotlinforforge | https://github.com/thedarkcolour/KotlinForForge/issues/154 | Install the Kotlin for Forge language provider the mod asks for. |
| `real-modernfix-loader-too-old` | DEP_VERSION | fabric | https://github.com/orgs/FabricMC/discussions/4410 | Run the Fabric installer again and pick loader 0.16.10 or later. |
| `real-paper-needs-java21` | JAVA_VERSION | java | https://github.com/itzg/docker-minecraft-server/issues/2905 | Run Minecraft 1.20.5 and later with Java 21. |
| `real-plugin-api-too-new` | PLUGIN_API | InvSee | https://github.com/Spottedleaf/OldGenerator/issues/9 | Run a server at least as recent as the plugin's api-version, or take an older build of the plugin. |
| `real-plugin-missing-vault` | DEP_MISSING | Vault | https://github.com/Lenni0451/SpigotPluginManager/issues/26 | Install the plugin it names (Vault): a hard dependency must be there before it loads. |
| `real-protocollib-too-old` | WRONG_MC | ProtocolLib | https://github.com/dmulloy2/ProtocolLib/issues/3073 | Update ProtocolLib: it reads the server's packet registry, which changes with every Minecraft version. |
| `real-sodium-extra-on-server` | CLIENT_ONLY_ON_SERVER | sodium | https://github.com/AllTheMods/ATM-10/issues/402 | Take the client-only rendering mods out of the server's mods folder. |
| `real-viaversion-warning-only` | clean start |  | https://github.com/ViaVersion/ViaVersion/issues/3917 | Nothing to fix: ViaVersion only lets newer clients in; older ones need ViaBackwards. |

15 cases.
