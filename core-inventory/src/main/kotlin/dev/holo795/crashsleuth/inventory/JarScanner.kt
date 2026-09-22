package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Side
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.io.path.fileSize
import kotlin.io.path.name

/** Reads the metadata of a jar (and of the jars nested inside it) without loading any class. */
object JarScanner {
    private const val MAX_DEPTH = 3

    fun scan(path: Path, folder: String): JarEntry {
        val size = runCatching { path.fileSize() }.getOrDefault(0)
        return try {
            ZipFile(path.toFile()).use { zip ->
                val files = zip.entries().asSequence().filterNot { it.isDirectory }
                    .associate { entry -> entry.name to { zip.getInputStream(entry).use(InputStream::readBytes) } }
                val content = read(files, 0)
                JarEntry(path.name, folder, size, content.mods, content.nested, javaVersion = javaVersion(zip, content.mods.flatMap { it.entrypoints }), path = path.toAbsolutePath().toString())
            }
        } catch (error: Exception) {
            JarEntry(path.name, folder, size, error = error.message ?: error.javaClass.simpleName)
        }
    }

    private const val MAX_CLASSES = 4000

    /**
     * Java release the jar needs: the one of its entry class when the metadata names it (plugin main
     * class, Fabric entrypoints), else the most common one. Not the highest: many jars ship optional
     * adapters for newer games (DecentHolograms 2.10 has Java 25 classes for Minecraft 26 and runs on 21).
     * Multi-release classes (META-INF/versions/N) are left out.
     */
    private fun javaVersion(zip: ZipFile, entries: List<String>): Int? {
        fun version(name: String): Int? = zip.getEntry(name)?.let { entry ->
            zip.getInputStream(entry).use { input ->
                val header = input.readNBytes(8)
                val magic = header.size == 8 && header[0] == 0xCA.toByte() && header[1] == 0xFE.toByte() &&
                    header[2] == 0xBA.toByte() && header[3] == 0xBE.toByte()
                if (magic) (((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)) - 44 else null
            }
        }
        entries.map { it.replace('.', '/') + ".class" }.mapNotNull(::version).maxOrNull()?.let { return it }
        return zip.entries().asSequence()
            .filter { it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
            .take(MAX_CLASSES)
            .mapNotNull { version(it.name) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    private class Content(val mods: List<ModMetadata>, val nested: List<ModMetadata>)

    private fun read(files: Map<String, () -> ByteArray>, depth: Int): Content {
        fun text(name: String) = files[name]?.invoke()?.toString(Charsets.UTF_8)?.removePrefix("﻿")
        val manifest = files["META-INF/MANIFEST.MF"]?.invoke()?.let { runCatching { Manifest(ByteArrayInputStream(it)) }.getOrNull() }
        val jarVersion = manifest?.mainAttributes?.getValue("Implementation-Version")

        val mods = buildList {
            text("fabric.mod.json")?.let { parseSafely { fabric(it) } }?.let(::add)
            text("quilt.mod.json")?.let { parseSafely { quilt(it) } }?.let(::add)
            text("META-INF/neoforge.mods.toml")?.let { parseSafely { forgeToml(it, MetadataFormat.NEOFORGE, jarVersion) } }?.let(::addAll)
            text("META-INF/mods.toml")?.let { parseSafely { forgeToml(it, MetadataFormat.FORGE, jarVersion) } }?.let(::addAll)
            text("plugin.yml")?.let { parseSafely { bukkit(it) } }?.let(::add)
            text("paper-plugin.yml")?.let { parseSafely { paper(it) } }?.let(::add)
            text("bungee.yml")?.let { parseSafely { simpleYaml(it, MetadataFormat.BUNGEE) } }?.let(::add)
            text("velocity-plugin.json")?.let { parseSafely { velocity(it) } }?.let(::add)
        }

        val nested = if (depth >= MAX_DEPTH) emptyList() else files.keys
            .filter { it.endsWith(".jar") && (it.startsWith("META-INF/jars/") || it.startsWith("META-INF/jarjar/")) }
            .flatMap { name ->
                val inner = runCatching { entriesOf(files.getValue(name)()) }.getOrNull() ?: return@flatMap emptyList()
                val content = read(inner, depth + 1)
                content.mods + content.nested
            }
        return Content(mods, nested)
    }

    private fun entriesOf(bytes: ByteArray): Map<String, () -> ByteArray> {
        val result = LinkedHashMap<String, () -> ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && (entry.name.endsWith(".jar") || entry.name in METADATA_FILES)) {
                    val data = zip.readBytes()
                    result[entry.name] = { data }
                }
            }
        }
        return result
    }

    private val METADATA_FILES = setOf(
        "fabric.mod.json", "quilt.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml",
        "META-INF/MANIFEST.MF", "plugin.yml", "paper-plugin.yml", "bungee.yml", "velocity-plugin.json",
    )

    /** Broken metadata is common in the wild; one unreadable file must not hide the rest of the jar. */
    private inline fun <T> parseSafely(block: () -> T): T? = runCatching(block).getOrNull()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json { isLenient = true; allowComments = true; allowTrailingComma = true; ignoreUnknownKeys = true }

    private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun fabric(text: String): ModMetadata {
        val root = json.parseToJsonElement(text).jsonObject
        fun deps(key: String, required: Boolean) = (root[key] as? JsonObject).orEmpty().map { (id, range) ->
            Dependency(id, rangeText(range), required)
        }
        return ModMetadata(
            format = MetadataFormat.FABRIC,
            id = root.getValue("id").jsonPrimitive.content,
            name = root["name"].string(),
            version = root["version"].string(),
            environment = side(root["environment"].string()),
            dependencies = deps("depends", true) + deps("recommends", false),
            breaks = deps("breaks", true),
            provides = (root["provides"] as? JsonArray).orEmpty().mapNotNull { it.string() },
            entrypoints = (root["entrypoints"] as? JsonObject).orEmpty().values.flatMap { value ->
                (value as? JsonArray).orEmpty().mapNotNull { it.string() ?: (it as? JsonObject)?.get("value").string() }
            }.map { it.substringBefore("::") },
        )
    }

    /** Fabric ranges are a string or a list of alternatives. */
    private fun rangeText(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull { it.string() }.joinToString(" || ")
        else -> null
    }

    private fun quilt(text: String): ModMetadata {
        val root = json.parseToJsonElement(text).jsonObject
        val loader = root.getValue("quilt_loader").jsonObject
        val metadata = loader["metadata"] as? JsonObject
        fun deps(key: String) = (loader[key] as? JsonArray).orEmpty().mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> Dependency(element.content)
                is JsonObject -> Dependency(
                    id = element["id"].string() ?: return@mapNotNull null,
                    versionRange = element["versions"]?.let(::rangeText),
                    required = element["optional"]?.jsonPrimitive?.booleanOrNull != true,
                )
                else -> null
            }
        }
        return ModMetadata(
            format = MetadataFormat.QUILT,
            id = loader.getValue("id").jsonPrimitive.content,
            name = metadata?.get("name").string(),
            version = loader["version"].string(),
            environment = side((root["minecraft"] as? JsonObject)?.get("environment").string()),
            dependencies = deps("depends"),
            breaks = deps("breaks"),
            provides = (loader["provides"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("id").string() ?: it.string() },
        )
    }

    private fun forgeToml(text: String, format: MetadataFormat, jarVersion: String?): List<ModMetadata> {
        val toml = Toml.parse(text)
        val mods = toml.getArray("mods") ?: return emptyList()
        return tables(mods).mapNotNull { mod ->
            val id = mod.text("modId") ?: return@mapNotNull null
            val dependencies = toml.getArray(listOf("dependencies", id))?.let(::tables).orEmpty()
            val version = mod.text("version")?.let { if (it.contains("\${file.jarVersion}")) jarVersion else it }
            ModMetadata(
                format = format,
                id = id,
                name = mod.text("displayName"),
                version = version,
                dependencies = dependencies.mapNotNull { dep ->
                    val type = dep.text("type")?.lowercase()
                    if (type == "incompatible" || type == "discouraged") return@mapNotNull null
                    Dependency(
                        id = dep.text("modId") ?: return@mapNotNull null,
                        versionRange = dep.text("versionRange"),
                        // NeoForge says type="required", Forge says mandatory=true; an absent field means required.
                        required = when {
                            type != null -> type == "required"
                            dep.contains("mandatory") -> dep.flag("mandatory")
                            else -> true
                        },
                        side = side(dep.text("side")),
                    )
                },
                breaks = dependencies.filter { it.text("type")?.lowercase() == "incompatible" }.mapNotNull { dep ->
                    dep.text("modId")?.let { Dependency(it, dep.text("versionRange")) }
                },
            )
        }
    }

    // Explicit types: tomlj annotates its results with checker-framework annotations that are not on the classpath.
    private fun TomlTable.text(key: String): String? = getString(key)

    private fun TomlTable.flag(key: String): Boolean = getBoolean(key) == true

    private fun tables(array: TomlArray): List<TomlTable> = (0 until array.size()).mapNotNull { array.get(it) as? TomlTable }

    private val yaml = Load(LoadSettings.builder().build())

    @Suppress("UNCHECKED_CAST")
    private fun yamlMap(text: String): Map<String, Any?> = yaml.loadFromString(text) as? Map<String, Any?> ?: emptyMap()

    private fun Any?.stringList(): List<String> = when (this) {
        is List<*> -> mapNotNull { it?.toString() }
        is String -> listOf(this)
        else -> emptyList()
    }

    private fun bukkit(text: String): ModMetadata {
        val root = yamlMap(text)
        return ModMetadata(
            format = MetadataFormat.BUKKIT,
            id = root.getValue("name").toString(),
            version = root["version"]?.toString(),
            dependencies = root["depend"].stringList().map { Dependency(it) } +
                root["softdepend"].stringList().map { Dependency(it, required = false) },
            provides = root["provides"].stringList(),
            apiVersion = root["api-version"]?.toString(),
            entrypoints = listOfNotNull(root["main"]?.toString()),
        )
    }

    private fun paper(text: String): ModMetadata {
        val root = yamlMap(text)
        val dependencies = when (val declared = root["dependencies"]) {
            // Current format: dependencies.server.<Name>.required
            is Map<*, *> -> (declared["server"] as? Map<*, *>).orEmpty().map { (name, options) ->
                Dependency(name.toString(), required = (options as? Map<*, *>)?.get("required") != false)
            }
            // First format: a list of { name, required }
            is List<*> -> declared.mapNotNull { entry ->
                val options = entry as? Map<*, *> ?: return@mapNotNull null
                Dependency(options["name"]?.toString() ?: return@mapNotNull null, required = options["required"] != false)
            }
            else -> emptyList()
        }
        return ModMetadata(
            format = MetadataFormat.PAPER,
            id = root.getValue("name").toString(),
            version = root["version"]?.toString(),
            dependencies = dependencies,
            provides = root["provides"].stringList(),
            apiVersion = root["api-version"]?.toString(),
            entrypoints = listOfNotNull(root["main"]?.toString()),
        )
    }

    private fun simpleYaml(text: String, format: MetadataFormat): ModMetadata {
        val root = yamlMap(text)
        return ModMetadata(format, root.getValue("name").toString(), version = root["version"]?.toString())
    }

    private fun velocity(text: String): ModMetadata {
        val root = json.parseToJsonElement(text).jsonObject
        return ModMetadata(MetadataFormat.VELOCITY, root.getValue("id").jsonPrimitive.content, root["name"].string(), root["version"].string())
    }

    private fun side(value: String?): Side = when (value?.lowercase()) {
        "client" -> Side.CLIENT
        "server" -> Side.SERVER
        else -> Side.UNKNOWN
    }

    /** Lists the jars of a folder, ignoring disabled files and sub-folders (Paper keeps remapped copies there). */
    fun jarsIn(folder: Path): List<Path> =
        if (!Files.isDirectory(folder)) {
            emptyList()
        } else {
            Files.list(folder).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.name.endsWith(".jar", ignoreCase = true) }.sorted().toList()
            }
        }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
