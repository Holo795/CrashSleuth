package dev.holo795.crashsleuth.runner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/** Everything needed to start a game client, resolved for this computer. */
data class ClientProfile(
    val minecraft: String,
    val mainClass: String,
    val classpath: List<Path>,
    val jvmArguments: List<String>,
    val gameArguments: List<String>,
    val assetsDirectory: Path,
    val assetIndex: String,
    val nativesDirectory: Path,
    val javaMajor: Int?,
)

/**
 * Installs a Minecraft client in the tool's own cache, from the official manifests (Mojang, Fabric),
 * never touching the player's launcher. Files are checked against their SHA-1.
 */
class ClientInstaller(
    private val cache: Path = defaultCache(),
    private val get: (URI) -> ByteArray = ::httpGet,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun install(minecraft: String, loader: String? = null, loaderVersion: String? = null, assets: Boolean = false): ClientProfile {
        val vanilla = vanillaJson(minecraft)
        val loaderJson = when (loader?.lowercase()) {
            null, "vanilla" -> null
            "fabric" -> fabricJson(minecraft, loaderVersion)
            "neoforge" -> neoforgeJson(minecraft, loaderVersion, vanilla)
            else -> error("Client launches support vanilla, Fabric and NeoForge for now, not $loader")
        }
        val libraries = merge(loaderJson?.get("libraries")?.jsonArray.orEmpty(), vanilla["libraries"]?.jsonArray.orEmpty())
        // listOf: a Path is itself Iterable, and "list + path" would add its name segments.
        val classpath = libraries.mapNotNull(::library) + listOf(clientJar(minecraft, vanilla))
        val assetIndex = vanilla.getValue("assetIndex").jsonObject
        val indexId = assetIndex.getValue("id").jsonPrimitive.content
        installAssets(assetIndex, indexId, full = assets)
        val natives = cache.resolve("natives/$minecraft").createDirectories()
        val arguments = vanilla["arguments"]?.jsonObject
        val loaderArguments = loaderJson?.get("arguments")?.jsonObject
        return ClientProfile(
            minecraft = minecraft,
            mainClass = (loaderJson ?: vanilla).getValue("mainClass").jsonPrimitive.content,
            classpath = classpath,
            jvmArguments = arguments(arguments?.get("jvm")) + arguments(loaderArguments?.get("jvm")),
            gameArguments = arguments(arguments?.get("game")) + arguments(loaderArguments?.get("game")),
            assetsDirectory = cache.resolve("assets"),
            assetIndex = indexId,
            nativesDirectory = natives,
            javaMajor = vanilla["javaVersion"]?.jsonObject?.get("majorVersion")?.jsonPrimitive?.content?.toIntOrNull(),
        )
    }

    /**
     * The command that starts the client in [gameDirectory], in a small window. Values of the
     * launcher placeholders are filled here; the player is offline and has no account.
     */
    fun command(profile: ClientProfile, gameDirectory: Path, java: String = "java", memory: String = "4G", extraJvm: List<String> = emptyList(), join: String? = null): List<String> {
        val values = mapOf(
            "natives_directory" to profile.nativesDirectory.toString(),
            "launcher_name" to "CrashSleuth",
            "launcher_version" to "1",
            "classpath" to profile.classpath.joinToString(File.pathSeparator),
            "classpath_separator" to File.pathSeparator,
            "library_directory" to cache.resolve("libraries").toString(),
            "auth_player_name" to "CrashSleuth",
            "version_name" to profile.minecraft,
            "game_directory" to gameDirectory.toString(),
            "assets_root" to profile.assetsDirectory.toString(),
            "assets_index_name" to profile.assetIndex,
            "auth_uuid" to "00000000-0000-4000-8000-000000000000",
            "auth_access_token" to "0",
            "clientid" to "0",
            "auth_xuid" to "0",
            "user_type" to "legacy",
            "version_type" to "release",
            "resolution_width" to "427",
            "resolution_height" to "240",
        )
        fun fill(argument: String) = PLACEHOLDER.replace(argument) { values[it.groupValues[1]] ?: it.value }
        val jvm = profile.jvmArguments.map(::fill).filterNot { PLACEHOLDER.containsMatchIn(it) }
        val game = profile.gameArguments.map(::fill)
        // Arguments whose placeholder has no value (quick play, demo) are dropped with their flag.
        val cleanGame = buildList {
            var i = 0
            while (i < game.size) {
                val argument = game[i]
                val next = game.getOrNull(i + 1)
                if (argument.startsWith("--") && next != null && PLACEHOLDER.containsMatchIn(next)) { i += 2; continue }
                if (!PLACEHOLDER.containsMatchIn(argument)) add(argument)
                i++
            }
        }
        // Quick Play (1.20+): the game joins a server ("host:port") or opens a save ("world:<folder>") once started.
        val quickPlay = when {
            join == null -> emptyList()
            join.startsWith("world:") -> listOf("--quickPlaySingleplayer", join.removePrefix("world:"))
            else -> listOf("--quickPlayMultiplayer", join)
        }
        return listOf(java, "-Xmx$memory", "-Dfabric.noGui=true") + extraJvm + jvm + profile.mainClass + cleanGame + quickPlay
    }

    /**
     * NeoForge's own installer, run headless into this cache (laid out like a launcher folder, never the
     * player's): it downloads its libraries and patches the game, then its profile is read like Fabric's.
     */
    private fun neoforgeJson(minecraft: String, loaderVersion: String?, vanilla: JsonObject): JsonObject {
        val version = loaderVersion ?: latestNeoForge(minecraft)
        val profile = cache.resolve("versions/neoforge-$version/neoforge-$version.json")
        if (!profile.exists()) {
            clientJar(minecraft, vanilla)
            val launcherProfiles = cache.resolve("launcher_profiles.json")
            if (!launcherProfiles.exists()) launcherProfiles.writeText("""{"profiles":{}}""")
            val work = Files.createTempDirectory("crashsleuth-neoforge")
            try {
                val installer = work.resolve("installer.jar")
                installer.writeBytes(get(URI("$NEOFORGE_MAVEN/$version/neoforge-$version-installer.jar")))
                val java = Path.of(System.getProperty("java.home"), "bin", if (File.separatorChar == '\\') "java.exe" else "java").toString()
                val process = ProcessBuilder(java, "-jar", installer.toString(), "--install-client", cache.toString())
                    .directory(work.toFile()).redirectErrorStream(true).redirectOutput(work.resolve("install.log").toFile()).start()
                check(process.waitFor() == 0 && profile.exists()) { "NeoForge $version installer failed: " + work.resolve("install.log").readText().lines().takeLast(5).joinToString(" ") }
            } finally {
                work.toFile().deleteRecursively()
            }
        }
        return json.parseToJsonElement(profile.readText()).jsonObject
    }

    /** NeoForge versions start with the game's minor and patch numbers: 1.21.1 is 21.1.x. */
    private fun latestNeoForge(minecraft: String): String {
        val parts = minecraft.split('.')
        val prefix = "${parts.getOrElse(1) { "0" }}.${parts.getOrElse(2) { "0" }}."
        val metadata = get(URI("$NEOFORGE_MAVEN/maven-metadata.xml")).toString(Charsets.UTF_8)
        return Regex("<version>([^<]+)</version>").findAll(metadata).map { it.groupValues[1] }
            .filter { it.startsWith(prefix) && !it.contains("beta") }.lastOrNull() ?: error("No NeoForge release for Minecraft $minecraft")
    }

    private fun vanillaJson(minecraft: String): JsonObject {
        val file = cache.resolve("versions/$minecraft/$minecraft.json")
        if (!file.exists()) {
            val manifest = json.parseToJsonElement(get(URI(MANIFEST)).toString(Charsets.UTF_8)).jsonObject
            val entry = manifest.getValue("versions").jsonArray.map { it.jsonObject }
                .firstOrNull { it.getValue("id").jsonPrimitive.content == minecraft } ?: error("Unknown Minecraft version $minecraft")
            file.parent.createDirectories()
            file.writeBytes(fetch(URI(entry.getValue("url").jsonPrimitive.content), entry["sha1"]?.jsonPrimitive?.content))
        }
        return json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun fabricJson(minecraft: String, loaderVersion: String?): JsonObject {
        val version = loaderVersion ?: json.parseToJsonElement(get(URI("https://meta.fabricmc.net/v2/versions/loader/$minecraft")).toString(Charsets.UTF_8))
            .jsonArray.first().jsonObject.getValue("loader").jsonObject.getValue("version").jsonPrimitive.content
        val file = cache.resolve("versions/fabric-$minecraft-$version.json")
        if (!file.exists()) {
            file.parent.createDirectories()
            file.writeBytes(get(URI("https://meta.fabricmc.net/v2/versions/loader/${enc(minecraft)}/${enc(version)}/profile/json")))
        }
        return json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    /** Loader libraries first: they replace the vanilla ones of the same group and name (ASM, for instance). */
    private fun merge(first: List<JsonElement>, second: List<JsonElement>): List<JsonObject> {
        fun key(library: JsonObject): String {
            val parts = library.getValue("name").jsonPrimitive.content.split(':')
            // Natives stay apart from their main artifact.
            return listOfNotNull(parts.getOrNull(0), parts.getOrNull(1), parts.getOrNull(3)).joinToString(":")
        }
        val seen = mutableSetOf<String>()
        return (first + second).map { it.jsonObject }.filter { seen.add(key(it)) }
    }

    /** Downloads a library if its rules allow it on this computer; returns its path. */
    private fun library(library: JsonObject): Path? {
        if (!allowed(library["rules"])) return null
        val name = library.getValue("name").jsonPrimitive.content
        val artifact = library["downloads"]?.jsonObject?.get("artifact")?.jsonObject
        val path = artifact?.get("path")?.jsonPrimitive?.content ?: mavenPath(name)
        val url = artifact?.get("url")?.jsonPrimitive?.content
            ?: ((library["url"]?.jsonPrimitive?.content ?: "https://libraries.minecraft.net/").trimEnd('/') + "/" + path)
        val target = cache.resolve("libraries").resolve(path)
        if (!target.exists()) {
            target.parent.createDirectories()
            target.writeBytes(fetch(URI(url), artifact?.get("sha1")?.jsonPrimitive?.content ?: library["sha1"]?.jsonPrimitive?.content))
        }
        return target
    }

    private fun mavenPath(name: String): String {
        val parts = name.split(':')
        val (group, artifact, version) = Triple(parts[0], parts[1], parts[2])
        val classifier = parts.getOrNull(3)?.let { "-$it" } ?: ""
        return "${group.replace('.', '/')}/$artifact/$version/$artifact-$version$classifier.jar"
    }

    private fun clientJar(minecraft: String, vanilla: JsonObject): Path {
        val client = vanilla.getValue("downloads").jsonObject.getValue("client").jsonObject
        val target = cache.resolve("versions/$minecraft/$minecraft.jar")
        if (!target.exists()) target.writeBytes(fetch(URI(client.getValue("url").jsonPrimitive.content), client["sha1"]?.jsonPrimitive?.content))
        return target
    }

    /**
     * The asset index is always installed; the objects (sounds, languages, about 700 MB) only when
     * asked: the game starts without them, which is all a crash test needs. The window icons and the
     * built-in packs, fonts and title screen images (5.6 MB) are always there: the game opens them at start and logs errors without them.
     */
    private fun installAssets(assetIndex: JsonObject, id: String, full: Boolean) {
        val file = cache.resolve("assets/indexes/$id.json")
        if (!file.exists()) {
            file.parent.createDirectories()
            file.writeBytes(fetch(URI(assetIndex.getValue("url").jsonPrimitive.content), assetIndex["sha1"]?.jsonPrimitive?.content))
        }
        json.parseToJsonElement(file.readText()).jsonObject.getValue("objects").jsonObject.forEach { (name, element) ->
            if (!full && !ALWAYS.any(name::startsWith)) return@forEach
            val hash = element.jsonObject.getValue("hash").jsonPrimitive.content
            val target = cache.resolve("assets/objects/${hash.take(2)}/$hash")
            if (!target.exists()) {
                target.parent.createDirectories()
                target.writeBytes(fetch(URI("https://resources.download.minecraft.net/${hash.take(2)}/$hash"), hash))
            }
        }
    }

    private val ALWAYS = listOf("icons/", "minecraft/resourcepacks/", "minecraft/font/", "minecraft/textures/gui/title/")

    private fun arguments(element: JsonElement?): List<String> = (element as? JsonArray).orEmpty().flatMap { argument ->
        when (argument) {
            is JsonPrimitive -> listOf(argument.content)
            is JsonObject -> if (allowed(argument["rules"])) when (val value = argument["value"]) {
                is JsonPrimitive -> listOf(value.content)
                is JsonArray -> value.map { it.jsonPrimitive.content }
                else -> emptyList()
            } else emptyList()
            else -> emptyList()
        }
    }

    /** Mojang rules: operating system, architecture, and launcher features (only the custom window size is on). */
    private fun allowed(rules: JsonElement?): Boolean {
        val list = (rules as? JsonArray)?.map { it.jsonObject } ?: return true
        var allowed = false
        for (rule in list) {
            val os = rule["os"]?.jsonObject
            val features = rule["features"]?.jsonObject
            val osMatches = os == null || ((os["name"]?.jsonPrimitive?.content?.let { it == OS_NAME } ?: true) &&
                (os["arch"]?.jsonPrimitive?.content?.let { ARCH.contains(it) } ?: true))
            val featuresMatch = features == null || features.all { (key, value) -> (key == "has_custom_resolution") == (value.jsonPrimitive.content == "true") }
            if (osMatches && featuresMatch) allowed = rule["action"]?.jsonPrimitive?.content == "allow"
        }
        return allowed
    }

    private fun fetch(uri: URI, sha1: String?): ByteArray {
        val bytes = get(uri)
        if (sha1 != null) check(sha1(bytes) == sha1.lowercase()) { "$uri differs from its published SHA-1" }
        return bytes
    }

    companion object {
        private const val NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge"
        private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        private val PLACEHOLDER = Regex("""\$\{([A-Za-z_]+)}""")
        private val OS_NAME = System.getProperty("os.name").lowercase().let {
            when {
                it.contains("mac") -> "osx"
                it.contains("win") -> "windows"
                else -> "linux"
            }
        }
        private val ARCH = System.getProperty("os.arch").lowercase().let { if (it == "aarch64" || it == "arm64") setOf("arm64", "aarch64") else setOf("x86_64", "x64", "amd64") }

        fun defaultCache(): Path = System.getenv("CRASHSLEUTH_CACHE")?.let { Path.of(it, "game") }
            ?: Path.of(System.getProperty("user.home"), ".cache", "crashsleuth", "game")

        private fun sha1(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

        private val client by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build() }

        fun httpGet(uri: URI): ByteArray {
            val request = HttpRequest.newBuilder(uri).header("User-Agent", "Holo795/CrashSleuth (github.com/Holo795/CrashSleuth)").timeout(Duration.ofMinutes(5)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() == 200) { "HTTP ${response.statusCode()} for $uri" }
            return response.body()
        }
    }
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
