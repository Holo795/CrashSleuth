package dev.holo795.crashsleuth.inventory

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
)

object ServerFilesScanner {
    private const val MAX_CONFIG_SIZE = 2L * 1024 * 1024
    private const val MAX_CONFIGS = 3000

    fun scan(root: Path): ServerFiles = ServerFiles(
        worlds = worldFolders(root).map(::world),
        eulaAccepted = root.resolve("eula.txt").takeIf { it.exists() }?.let { file ->
            properties(file)?.getProperty("eula")?.trim()?.equals("true", ignoreCase = true) ?: false
        },
        configErrors = configErrors(root),
    )

    private fun properties(file: Path): Properties? = runCatching { Properties().apply { Files.newBufferedReader(file).use(::load) } }.getOrNull()

    /** The world of server.properties (and its nether and end), and the saves of a game folder. */
    private fun worldFolders(root: Path): List<Path> = buildList {
        val level = root.resolve("server.properties").takeIf { it.exists() }?.let { properties(it)?.getProperty("level-name") }?.ifBlank { null } ?: "world"
        root.resolve(level).takeIf { it.resolve("level.dat").exists() || it.resolve("level.dat_old").exists() }?.let(::add)
        root.resolve("saves").takeIf { it.isDirectory() }?.let { saves ->
            Files.list(saves).use { stream -> stream.filter { it.resolve("level.dat").exists() }.sorted().limit(20).toList() }.forEach(::add)
        }
    }

    private fun world(directory: Path): WorldInfo {
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
        )
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
            check(file, text)?.let { (line, message) -> ConfigError(root.relativize(file).toString(), line, message) }
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
