package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.insideOf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import org.tomlj.Toml
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/** What a world's level.dat says, and whether it can be opened at all. */
@Serializable
data class WorldInfo(
    val name: String,
    /** Game version that last saved the world ("1.21.4"). */
    val versionName: String? = null,
    val dataVersion: Int? = null,
    /** Why level.dat cannot be read, when it cannot. */
    val unreadable: String? = null,
    val hasBackup: Boolean = false,
    /** Another running server holds the world. */
    val locked: Boolean = false,
    /** Chunks of its region files the game cannot read (at most 50 listed). */
    val damagedChunks: List<DamagedChunk> = emptyList(),
    val chunksChecked: Int = 0,
    /** False when the world was too large to be read within the time given. */
    val chunksComplete: Boolean = true,
    /** Player files the game cannot read: that player loses inventory and position when joining. */
    val damagedPlayers: List<DamagedPlayer> = emptyList(),
)

@Serializable
data class DamagedPlayer(
    val file: String,
    val name: String? = null,
    val reason: String,
    val hasBackup: Boolean = false,
    /** Already set aside by the game: the player started over, the damaged save is kept for a restore. */
    val setAside: Boolean = false,
)

/** A configuration file its loader will refuse. */
@Serializable
data class ConfigError(val file: String, val line: Int? = null, val message: String)

/** Server and instance files other than jars: worlds, EULA, configuration. */
@Serializable
data class ServerFiles(
    val worlds: List<WorldInfo> = emptyList(),
    /** null when there is no eula.txt. */
    val eulaAccepted: Boolean? = null,
    val configErrors: List<ConfigError> = emptyList(),
    /** Jars next to the server's files: the server software itself (paper-1.21.1.jar, waterfall.jar...). */
    val rootJars: List<String> = emptyList(),
)

object ServerFilesScanner {
    private const val MAX_CONFIG_SIZE = 2L * 1024 * 1024
    private const val MAX_CONFIGS = 3000

    /** Time all the worlds of a folder may take to be read, chunk by chunk. */
    private const val WORLD_BUDGET_MILLIS = 8_000L

    fun scan(root: Path): ServerFiles {
        val deadline = System.currentTimeMillis() + WORLD_BUDGET_MILLIS
        return scanWith(root) { world(it, deadline) }
    }

    private fun scanWith(root: Path, world: (Path) -> WorldInfo): ServerFiles = ServerFiles(
        worlds = worldFolders(root).map(world),
        eulaAccepted = root.resolve("eula.txt").takeIf { it.exists() }?.let { file ->
            properties(file)?.getProperty("eula")?.trim()?.equals("true", ignoreCase = true) ?: false
        },
        configErrors = configErrors(root),
        rootJars = runCatching { Files.list(root).use { s -> s.filter { it.name.endsWith(".jar") }.map { it.name }.sorted().limit(20).toList() } }.getOrDefault(emptyList()),
    )

    private fun properties(file: Path): Properties? = runCatching { Properties().apply { Files.newBufferedReader(file).use(::load) } }.getOrNull()

    /** The world of server.properties (and its nether and end), and the saves of a game folder. */
    private fun worldFolders(root: Path): List<Path> = buildList {
        val level = root.resolve("server.properties").takeIf { it.exists() }?.let { properties(it)?.getProperty("level-name") }?.ifBlank { null } ?: "world"
        // Paper, Spigot and Purpur keep the nether and the end as worlds of their own.
        listOf(level, "${level}_nether", "${level}_the_end").map(root::resolve)
            .filter { it.resolve("level.dat").exists() || it.resolve("level.dat_old").exists() }.forEach(::add)
        root.resolve("saves").takeIf { it.isDirectory() }?.let { saves ->
            Files.list(saves).use { stream -> stream.filter { it.resolve("level.dat").exists() }.sorted().limit(20).toList() }.forEach(::add)
        }
    }

    private fun world(directory: Path, deadline: Long): WorldInfo {
        val levelDat = directory.resolve("level.dat")
        val backup = directory.resolve("level.dat_old").exists()
        val parsed = runCatching { levelDat.inputStream().use(Nbt::readGzip) }
        val root = parsed.getOrNull()
        return WorldInfo(
            name = directory.name,
            versionName = root?.let { Nbt.path(it, "Data", "Version", "Name") as? String },
            dataVersion = root?.let { (Nbt.path(it, "Data", "DataVersion") as? Int) ?: (Nbt.path(it, "Data", "Version", "Id") as? Int) },
            unreadable = if (!levelDat.exists()) "level.dat is missing" else parsed.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            hasBackup = backup,
            locked = locked(directory.resolve("session.lock")),
        ).let { info ->
            val chunks = RegionScanner.scan(directory, (deadline - System.currentTimeMillis()).coerceAtLeast(0))
            info.copy(damagedChunks = chunks.damaged, chunksChecked = chunks.checked, chunksComplete = chunks.complete, damagedPlayers = players(directory))
        }
    }

    private const val MAX_PLAYERS = 5000
    private val CORRUPTED = Regex("""([0-9a-f-]{36})_corrupted_([\d_-]+)\.dat""")

    /** playerdata/<uuid>.dat, named with usercache.json of the server when it knows the player. */
    private fun players(world: Path): List<DamagedPlayer> {
        val folder = world.resolve("playerdata").takeIf { it.isDirectory() } ?: return emptyList()
        val names = runCatching {
            json.parseToJsonElement(world.resolveSibling("usercache.json").readText()).let { it as kotlinx.serialization.json.JsonArray }.associate { entry ->
                val fields = entry as kotlinx.serialization.json.JsonObject
                (fields["uuid"] as kotlinx.serialization.json.JsonPrimitive).content to (fields["name"] as kotlinx.serialization.json.JsonPrimitive).content
            }
        }.getOrDefault(emptyMap())
        val files = Files.list(folder).use { stream -> stream.filter { it.name.endsWith(".dat") }.sorted().limit(MAX_PLAYERS.toLong()).toList() }
        return files.mapNotNull { file ->
            // The game itself sets a damaged save aside as <uuid>_corrupted_<date>.dat and starts the player over.
            CORRUPTED.matchEntire(file.name)?.let { set ->
                val (uuid, date) = set.destructured
                return@mapNotNull DamagedPlayer("playerdata/${file.name}", names[uuid], "the game found it damaged on $date and set it aside", setAside = true)
            }
            val problem = runCatching { file.inputStream().use(Nbt::readGzip) }.exceptionOrNull() ?: return@mapNotNull null
            DamagedPlayer("playerdata/${file.name}", names[file.name.removeSuffix(".dat")], problem.message ?: problem.javaClass.simpleName, file.resolveSibling("${file.name.removeSuffix(".dat")}.dat_old").exists())
        }.take(50)
    }

    /** A running server keeps an exclusive lock on session.lock. */
    private fun locked(file: Path): Boolean {
        if (!file.exists()) return false
        return try {
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                val lock = channel.tryLock() ?: return true
                lock.release()
                false
            }
        } catch (_: OverlappingFileLockException) {
            true
        } catch (_: java.nio.file.AccessDeniedException) {
            // Windows: the running server's lock keeps anyone else from even opening the file.
            true
        } catch (error: java.nio.file.FileSystemException) {
            error.reason?.contains("another process", ignoreCase = true) == true
        } catch (_: Exception) {
            false
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { isLenient = true; allowComments = true; allowTrailingComma = true }

    private val yaml = Load(LoadSettings.builder().setAllowDuplicateKeys(true).build())

    /** Config folders of mods and plugins, and the server's own files. */
    private fun configErrors(root: Path): List<ConfigError> {
        val files = buildList {
            listOf("config", "defaultconfigs", "plugins").map(root::resolve).filter { it.isDirectory() }.forEach { folder ->
                Files.walk(folder, 4).use { stream ->
                    stream.filter { it.isRegularFile() && it.name.substringAfterLast('.').lowercase() in setOf("toml", "json", "yml", "yaml") }
                        .filter { path -> path.none { it.name == ".paper-remapped" } }
                        .limit(MAX_CONFIGS.toLong()).toList()
                }.forEach(::add)
            }
            listOf("bukkit.yml", "spigot.yml", "commands.yml", "purpur.yml", "pufferfish.yml").map(root::resolve).filter { it.exists() }.forEach(::add)
        }
        return files.mapNotNull { file ->
            if (runCatching { file.fileSize() }.getOrDefault(0) > MAX_CONFIG_SIZE) return@mapNotNull null
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            check(file, text)?.let { (line, message) -> ConfigError(file.insideOf(root), line, message) }
        }
    }

    /** First error of a file, with its line when the parser gives it. */
    private fun check(file: Path, text: String): Pair<Int?, String>? {
        if (text.isBlank()) return null
        return when (file.name.substringAfterLast('.').lowercase()) {
            "toml" -> Toml.parse(text).errors().firstOrNull()?.let { it.position()?.line() to (it.message ?: "invalid TOML") }
            "json" -> runCatching { json.parseToJsonElement(text) }.exceptionOrNull()?.let { error ->
                val line = Regex("""line: (\d+)""").find(error.message ?: "")?.groupValues?.get(1)?.toIntOrNull()
                line to (error.message?.lines()?.firstOrNull() ?: "invalid JSON")
            }
            else -> runCatching { yaml.loadAllFromString(text).forEach { } }.exceptionOrNull()?.let { error ->
                val line = (error as? MarkedYamlEngineException)?.problemMark?.orElse(null)?.line?.plus(1)
                line to (error.message?.lines()?.firstOrNull { it.isNotBlank() } ?: "invalid YAML")
            }
        }
    }
}
