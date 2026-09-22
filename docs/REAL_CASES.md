# Problems real people had, replayed here

Every case below was reported by someone on the web, rebuilt in the lab with the same versions,
and is replayed by the tests. The last column is what fixed it for them; CrashSleuth is expected
to lead to the same answer on its own.

| Case | What CrashSleuth must find | Culprit | Reported at | What fixed it |
| --- | --- | --- | --- | --- |
| `client-real-client-create-standalone-flywheel` | DEP_VERSION | flywheel | https://github.com/Creators-of-Create/Create/issues/5215 | Delete the Flywheel jar you added: Create carries its own inside. |
| `client-real-client-dependency-never-published` | DEP_MISSING | flywheel | https://github.com/Asek3/Oculus/issues/804 | Install Flywheel, or remove the bridge mod that asks for it. |
| `client-real-client-dependency-that-does-not-exist` | DEP_MISSING | intermap | https://github.com/TheTypholorian/big_shot_lib/issues/4 | That library was never released: take a build of the mod from Modrinth instead. |
| `client-real-client-fabric-api-corrupt` | CORRUPT_JAR | fabric-api | https://github.com/orgs/FabricMC/discussions/3934 | Delete that jar and download it again: it is there but unreadable, so the loader calls it missing. |
| `client-real-client-fabric-api-wrong-point-release` | WRONG_MC | fabric-api | https://github.com/orgs/FabricMC/discussions/4034 | Take the Fabric API built for the exact point release you play. |
| `client-real-client-forge-config-truncated` | CONFIG_BROKEN |  | https://forums.minecraftforge.net/topic/120264-the-game-crashed-whilst-initializing-game-error-javalangexceptionininitializererror-null-exit-code-1/ | Delete config/forge-resource-caching.toml: Forge writes a new one. |
| `client-real-client-iris-needs-older-sodium` | DEP_VERSION | sodium | https://github.com/IrisShaders/Iris/issues/3136 | Iris 1.8.8 only works with Sodium 0.6.x: install that branch, or a newer Iris. |
| `client-real-client-opengl-too-old` | RENDER |  | https://github.com/PrismLauncher/PrismLauncher/issues/3264 | Minecraft has needed OpenGL 3.2 since 1.17: update the graphics driver, or play a version below 1.17. |
| `client-real-client-pack-format-too-old` | RENDER | old.zip | https://github.com/Godlander/objmc/issues/102 | Take the pack built for the Minecraft version you play: its pack_format says which one it is for. |
| `client-real-client-sodium-breaks-iris-recent` | MOD_CONFLICT | sodium | https://github.com/IrisShaders/Iris/issues/3349 | Update Iris past 1.10.7, or drop Sodium to a build that accepts it. |
| `net-real-bungee-forwarding-one-sided-backend` | PROXY_FORWARDING |  | https://docs.papermc.io/velocity/faq/ | Turn IP forwarding on on both sides, or on neither: the proxy and the server must agree. |
| `net-real-bungee-forwarding-one-sided-proxy` | PROXY_FORWARDING |  | https://docs.papermc.io/velocity/faq/ | Turn IP forwarding on on both sides, or on neither: the proxy and the server must agree. |
| `net-real-online-mode-behind-proxy-backend` | clean start |  | https://www.gameserverkings.com/knowledge-base/minecraft/error-failed-to-verify-username/ | A server behind a proxy runs with online-mode=false; the proxy does the checking. |
| `net-real-online-mode-behind-proxy-proxy` | PROXY_FORWARDING |  | https://www.gameserverkings.com/knowledge-base/minecraft/error-failed-to-verify-username/ | A server behind a proxy runs with online-mode=false; the proxy does the checking. |
| `net-real-velocity-empty-secret-proxy` | PROXY_FORWARDING |  | https://forums.papermc.io/threads/how-to-solve-unable-to-read-load-save-your-velocity-toml.339/ | Write the secret inside the file named by forwarding-secret-file; the setting is a path, not the secret. |
| `net-real-velocity-forwarding-off-backend` | PROXY_FORWARDING |  | https://github.com/PaperMC/Velocity/issues/1347 | Set player-info-forwarding-mode to modern on the proxy, and save the file before restarting. |
| `net-real-velocity-forwarding-off-player` | PROXY_FORWARDING |  | https://github.com/PaperMC/Velocity/issues/1347 | Set player-info-forwarding-mode to modern on the proxy, and save the file before restarting. |
| `net-real-velocity-forwarding-off-proxy` | PROXY_FORWARDING |  | https://github.com/PaperMC/Velocity/issues/1347 | Set player-info-forwarding-mode to modern on the proxy, and save the file before restarting. |
| `real-block-entity-tick-crash-mods` | TICK_BLOCK_ENTITY | toms_storage | https://github.com/tom5454/Toms-Storage/issues/753 | Update Tom's Storage to 2.8.1, or go back to the Sophisticated Core it was built against. |
| `real-bluemap-eventbus7` | UNCAUGHT_EXCEPTION | bluemap | https://github.com/BlueMap-Minecraft/BlueMap/issues/743 | Update BlueMap past 5.12: Forge EventBus 7 refuses its old listener. |
| `real-broken-version-range-in-metadata` | CORRUPT_JAR | tenshilib | https://github.com/Flemmli97/TenshiLib/issues/17 | Take the rebuilt jar (2.3.0.b): the range in its metadata was written wrong. |
| `real-chunks-in-wrong-file` | CORRUPT_CHUNK |  | https://github.com/Amulet-Team/Amulet-Core/issues/235 | Those chunks do not belong to that file: the server moves them, which loses what was there. |
| `real-connector-transform-failure` | MOD_CONFLICT | Connector | https://github.com/Sinytra/Connector/issues/2002 | Move to a later Connector build: this one cannot read that mod's mixin settings. |
| `real-corrupt-server-jar` | CORRUPT_JAR |  | https://github.com/itzg/docker-minecraft-server/discussions/2786 | Delete the jar and download it again: the file is incomplete. |
| `real-densefuel-language-provider-pinned` | DEP_VERSION | densefuel | https://github.com/legoaggelos/densefuel/issues/1 | Stay below NeoForge 21.1.235, or the mod must widen the javafml range it asks for. |
| `real-entity-pileup-lag` | LAG |  | https://forums.papermc.io/threads/server-lag-and-rollback-every-5-min.1041/ | Remove the crowd of entities (or the world holding it): chunks keep ticking even with nobody there. |
| `real-fabric-api-missing-journeymap` | DEP_MISSING | fabric | https://github.com/itzg/docker-minecraft-server/discussions/2661 | Install the Fabric API jar: nothing pulls it in for you. |
| `real-fabric-api-wrong-minecraft` | WRONG_MC | fabric-api | https://github.com/orgs/FabricMC/discussions/4403 | Download the Fabric API built for the Minecraft version you run. |
| `real-folia-plugin-unsupported` | PLUGIN_API | InvSee | https://github.com/Jannyboy11/InvSee-plus-plus/issues/87 | Only plugins whose plugin.yml says folia-supported: true load on Folia; ask the author or run Paper. |
| `real-java-options-override` | JVM_OPTIONS |  | https://www.minecraftforum.net/forums/support/java-edition-support/2296914-cant-allocate-more-ram-with-razer | Remove the _JAVA_OPTIONS variable (Razer Synapse and other tools set it): it is applied after the command line and wins. |
| `real-jei-loader-too-old` | DEP_VERSION | fabric | https://github.com/mezz/JustEnoughItems/issues/3748 | Upgrade Fabric Loader to 0.16.3 or later. |
| `real-kotlin-language-provider-missing` | DEP_MISSING | kotlinforforge | https://github.com/thedarkcolour/KotlinForForge/issues/154 | Install the Kotlin for Forge language provider the mod asks for. |
| `real-level-type-legacy` | clean start |  | https://github.com/itzg/docker-minecraft-server/issues/1445 | Nothing breaks: since 22w12a the value is minecraft:normal, and anything else falls back to it. |
| `real-library-too-old-for-bundle` | DEP_VERSION | collective | https://github.com/Serilum/.issue-tracker/issues/3229 | Install Collective 8.4, or go back to the bundle built for 8.3. |
| `real-midnightlib-metadata-broken` | CORRUPT_JAR | midnightlib | https://github.com/TeamMidnightDust/MidnightLib/issues/145 | Take 1.9.3.1: the range in the published jar was written wrong. |
| `real-modernfix-loader-too-old` | DEP_VERSION | fabric | https://github.com/orgs/FabricMC/discussions/4410 | Run the Fabric installer again and pick loader 0.16.10 or later. |
| `real-paper-needs-java21` | JAVA_VERSION | java | https://github.com/itzg/docker-minecraft-server/issues/2905 | Run Minecraft 1.20.5 and later with Java 21. |
| `real-plugin-api-too-new` | PLUGIN_API | InvSee | https://github.com/Spottedleaf/OldGenerator/issues/9 | Run a server at least as recent as the plugin's api-version, or take an older build of the plugin. |
| `real-plugin-api-too-new-26` | PLUGIN_API | namefilterhopper | https://github.com/kcbleeker/NameFilterHopper/issues/1 | Run a server at least as recent as the plugin's api-version, or take the build made for yours. |
| `real-plugin-missing-vault` | DEP_MISSING | Vault | https://github.com/Lenni0451/SpigotPluginManager/issues/26 | Install the plugin it names (Vault): a hard dependency must be there before it loads. |
| `real-protocollib-too-old` | WRONG_MC | ProtocolLib | https://github.com/dmulloy2/ProtocolLib/issues/3073 | Update ProtocolLib: it reads the server's packet registry, which changes with every Minecraft version. |
| `real-rcon-port-taken` | PORT_IN_USE |  | https://www.gameserverkings.com/knowledge-base/minecraft/setting-up-rcon/ | Give RCON a port of its own, different from server-port. |
| `real-rcon-without-password` | CONFIG_BROKEN |  | https://github.com/itzg/docker-minecraft-server/issues/518 | Set rcon.password: with an empty one, RCON does not start at all, which looks like a refused connection. |
| `real-recipe-old-result-format` | DATAPACK_BROKEN |  | https://github.com/misode/misode.github.io/issues/822 | In the result, write id instead of item: the game changed how an item stack is written in 1.20.5. |
| `real-sodium-extra-on-server` | CLIENT_ONLY_ON_SERVER | sodium | https://github.com/AllTheMods/ATM-10/issues/402 | Take the client-only rendering mods out of the server's mods folder. |
| `real-sodium-on-server` | CLIENT_ONLY_ON_SERVER | sodium | https://github.com/CaffeineMC/sodium/issues/3791 | Sodium only works in the game: take it out of the server's mods folder. |
| `real-velocity-support-without-secret` | PROXY_FORWARDING |  | https://github.com/PaperMC/Velocity/issues/1043 | Put the proxy's forwarding secret in proxies.velocity.secret, or turn Velocity support off. |
| `real-viaversion-warning-only` | clean start |  | https://github.com/ViaVersion/ViaVersion/issues/3917 | Nothing to fix: ViaVersion only lets newer clients in; older ones need ViaBackwards. |
| `real-world-duplicate-uid` | WORLD_DUPLICATE |  | https://github.com/Multiverse/Multiverse-Core/issues/1877 | Delete uid.dat in the copied world: it is what says which world it is. |
| `real-world-not-writable` | WORLD_LOCKED |  | https://github.com/itzg/docker-minecraft-server/issues/1080 | Give the folder to the user the server runs as (chown), or set the container's UID and GID to the owner's. |
| `real-worldgen-settings-deleted` | WORLD_CORRUPT |  | https://github.com/PaperMC/Paper/issues/14066 | Restore that file from a backup: the server cannot start without it, and --safeMode does not help. |

51 cases.
