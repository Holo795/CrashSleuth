package dev.holo795.crashsleuth.inventory

import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Side
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.writeBytes

/**
 * Inventory of a modpack that is not installed: a Modrinth `.mrpack`, a CurseForge zip, or a zip of a
 * server folder. Modrinth files are downloaded once into a cache and checked against their hashes.
 */
class PackScanner(
    private val cache: Path = defaultCache(),
    private val download: (URI) -> ByteArray = ::httpGet,
) {
    fun scan(pack: Path, side: Side = Side.SERVER): Inventory = ZipFile(pack.toFile()).use { zip ->
        when {
            zip.getEntry("modrinth.index.json") != null -> modrinth(zip, side)
            zip.getEntry("manifest.json") != null -> curseForge(zip, side)
            else -> folderZip(pack)
        }
    }

    private fun text(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun modrinth(zip: ZipFile, side: Side): Inventory {
        val index = Json.parseToJsonElement(text(zip, "modrinth.index.json")).jsonObject
        val dependencies = index["dependencies"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content }
        val (platform, loaderVersion) = when {
            "neoforge" in dependencies -> Platform.NEOFORGE to dependencies["neoforge"]
            "forge" in dependencies -> Platform.FORGE to dependencies["forge"]
            "quilt-loader" in dependencies -> Platform.QUILT to dependencies["quilt-loader"]
            "fabric-loader" in dependencies -> Platform.FABRIC to dependencies["fabric-loader"]
            else -> Platform.UNKNOWN to null
        }
        val sideKey = if (side == Side.CLIENT) "client" else "server"
        val skipped = mutableListOf<String>()
        val jars = index.getValue("files").jsonArray.mapNotNull { element ->
            val file = element.jsonObject
            val path = file.getValue("path").jsonPrimitive.content
            val folder = path.substringBefore('/')
            if (folder !in FOLDERS || !path.endsWith(".jar")) return@mapNotNull null
            if ((file["env"] as? JsonObject)?.get(sideKey)?.jsonPrimitive?.content == "unsupported") return@mapNotNull null
            val sha1 = file["hashes"]?.jsonObject?.get("sha1")?.jsonPrimitive?.content
            val url = file["downloads"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            val name = path.substringAfterLast('/')
            if (url == null || sha1 == null || !trusted(url)) {
                skipped += name
                return@mapNotNull null
            }
            val local = fetch(URI(url), sha1) ?: return@mapNotNull JarEntry(name, folder, error = "download failed or file differs from the pack (sha1)")
            JarScanner.scan(local, folder).copy(file = name)
        } + overrides(zip, listOf("overrides/", if (side == Side.CLIENT) "client-overrides/" else "server-overrides/"))
        return Inventory(platform, dependencies["minecraft"], loaderVersion, side, jars, skipped)
    }

    private fun curseForge(zip: ZipFile, side: Side): Inventory {
        val manifest = Json.parseToJsonElement(text(zip, "manifest.json")).jsonObject
        val minecraft = manifest["minecraft"]?.jsonObject
        val loader = minecraft?.get("modLoaders")?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
        val platform = when {
            loader == null -> Platform.UNKNOWN
            loader.startsWith("neoforge") -> Platform.NEOFORGE
            loader.startsWith("forge") -> Platform.FORGE
            loader.startsWith("fabric") -> Platform.FABRIC
            loader.startsWith("quilt") -> Platform.QUILT
            else -> Platform.UNKNOWN
        }
        // CurseForge only serves files through its API, which needs a key: the listed mods cannot be read.
        val listed = manifest["files"]?.jsonArray?.size ?: 0
        val prefix = (manifest["overrides"]?.jsonPrimitive?.content ?: "overrides") + "/"
        return Inventory(
            platform = platform,
            minecraftVersion = minecraft?.get("version")?.jsonPrimitive?.content,
            loaderVersion = loader?.substringAfter('-'),
            side = side,
            jars = overrides(zip, listOf(prefix)),
            skipped = List(listed) { "curseforge:${manifest.getValue("files").jsonArray[it].jsonObject["projectID"]?.jsonPrimitive?.content}" },
        )
    }

    /** Jars of mods/ and plugins/ shipped inside the pack itself. */
    private fun overrides(zip: ZipFile, prefixes: List<String>): List<JarEntry> {
        val directory = Files.createTempDirectory("crashsleuth-pack")
        try {
            return zip.entries().asSequence().mapNotNull { entry ->
                val prefix = prefixes.firstOrNull { entry.name.startsWith(it) } ?: return@mapNotNull null
                val path = entry.name.removePrefix(prefix)
                val folder = path.substringBefore('/')
                if (folder !in FOLDERS || path.count { it == '/' } != 1 || !path.endsWith(".jar")) return@mapNotNull null
                val local = directory.resolve(path.substringAfterLast('/'))
                local.writeBytes(zip.getInputStream(entry).use { it.readBytes() })
                JarScanner.scan(local, folder)
            }.toList()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    /** A zip of a whole server or instance folder, possibly inside one top-level directory. */
    private fun folderZip(pack: Path): Inventory {
        val directory = Files.createTempDirectory("crashsleuth-folder")
        try {
            ZipFile(pack.toFile()).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    val target = directory.resolve(entry.name).normalize()
                    // Never write outside the temporary folder (zip slip).
                    if (!target.startsWith(directory)) return@forEach
                    target.parent.createDirectories()
                    zip.getInputStream(entry).use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
                }
            }
            val root = Files.list(directory).use { it.toList() }.singleOrNull()?.takeIf { it.isDirectory() && !FOLDERS.contains(it.name) } ?: directory
            return InstanceScanner.scan(root)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun fetch(uri: URI, sha1: String): Path? {
        val target = cache.resolve("$sha1.jar")
        if (target.exists()) return target
        val bytes = runCatching { download(uri) }.getOrNull() ?: return null
        if (sha1(bytes) != sha1.lowercase()) return null
        cache.createDirectories()
        val part = cache.resolve("$sha1.part")
        part.writeBytes(bytes)
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    companion object {
        private val FOLDERS = setOf("mods", "plugins")

        /** Hosts the Modrinth pack format allows downloads from. */
        private val TRUSTED_HOSTS = setOf("cdn.modrinth.com", "github.com", "raw.githubusercontent.com", "gitlab.com")

        fun trusted(url: String) = runCatching { URI(url) }.getOrNull()?.let { it.scheme == "https" && it.host in TRUSTED_HOSTS } == true

        fun defaultCache(): Path = Path.of(System.getProperty("user.home"), ".cache", "crashsleuth", "files")

        private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        private val client by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build() }

        fun httpGet(uri: URI): ByteArray {
            val request = HttpRequest.newBuilder(uri).header("User-Agent", "Holo795/CrashSleuth (github.com/Holo795/CrashSleuth)")
                .timeout(Duration.ofMinutes(5)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $uri" }
            return response.body()
        }

        fun isPack(path: Path): Boolean = path.name.endsWith(".mrpack", ignoreCase = true) || path.name.endsWith(".zip", ignoreCase = true)
    }
}
