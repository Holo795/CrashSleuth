package dev.holo795.crashsleuth.app

import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

/**
 * Turns the names of game code in a trace into Mojang's own names: Fabric logs say `class_310.method_22681`
 * (intermediary), vanilla logs say `fgo.b` (obfuscated). Mojang publishes its mappings for every version and
 * Fabric its intermediary names; both are downloaded once into the cache.
 */
class Mappings private constructor(
    /** Obfuscated or intermediary class name (dots) to Mojang's name. */
    private val classes: Map<String, String>,
    /** Intermediary class name (dots) to its obfuscated name. */
    private val obfuscatedOf: Map<String, String>,
    /** Intermediary method or field name (method_22681, field_1724) to Mojang's name. */
    private val members: Map<String, String>,
    /** Intermediary method name to "obfuscated class.obfuscated method", to find it by line. */
    private val memberKeys: Map<String, String>,
    /** Obfuscated class, then obfuscated method name, to Mojang's name (all overloads, joined by /). */
    private val obfuscatedMethods: Map<String, Map<String, String>>,
    /** "Obfuscated class.method": the source lines of each Mojang method sharing that name. */
    private val lines: Map<String, List<Pair<IntRange, String>>>,
) {
    /** Replaces every name it knows in a log or a trace; the rest of the text is left as it is. */
    fun translate(text: String): String {
        val frames = FRAME.replace(text) { frame(it) ?: it.value }
        val named = INTERMEDIARY_CLASS.replace(frames) { classes[it.value.replace('/', '.')] ?: shortClass(it.value) ?: it.value }
        return INTERMEDIARY_MEMBER.replace(named) { members[it.value] ?: it.value }
    }

    /**
     * One stack frame, `at knot/net.minecraft.class_310.method_22681(class_310.java:2542)` or `at fgo.b(SourceFile:76)`:
     * overloads share one obfuscated name, and the line of the frame says which method it is.
     */
    private fun frame(match: MatchResult): String? {
        val (prefix, owner, method, file, lineText) = match.destructured
        val line = lineText.toIntOrNull()
        fun byLine(key: String?) = if (line == null || key == null) null else lines[key]?.firstOrNull { line in it.first }?.second
        val intermediary = owner.contains("class_")
        val obfOwner = if (intermediary) obfuscatedOf[owner] else owner.takeIf { it in classes && !it.contains('.') }
        obfOwner ?: return null
        val namedOwner = classes[if (intermediary) owner else obfOwner] ?: return null
        val namedMethod = when {
            intermediary && method.startsWith("method_") -> byLine(memberKeys[method]) ?: members[method] ?: method
            !intermediary -> byLine("$obfOwner.$method") ?: obfuscatedMethods[obfOwner]?.get(method) ?: method
            else -> method
        }
        val namedFile = if (file.startsWith("class_") || file == "SourceFile") namedOwner.substringAfterLast('.').substringBefore('$') + ".java" else file
        return "$prefix$namedOwner.$namedMethod($namedFile${lineText.takeIf { it.isNotEmpty() }?.let { ":$it" } ?: ""})"
    }

    /** `class_310` alone, without its package, as Fabric prints it in some messages. */
    private fun shortClass(name: String): String? = if (name.contains('.') || name.contains('/')) null else classes["net.minecraft.$name"]

    companion object {
        private val INTERMEDIARY_CLASS = Regex("""(?:net[./]minecraft[./])?class_\d+(?:[${'$'}]class_\d+)*""")
        private val INTERMEDIARY_MEMBER = Regex("""\b(?:method|field|comp)_\d+\b""")
        private val FRAME = Regex("""(\bat\s+(?:[\w.-]+/+)?)([\w${'$'}.]+)\.([\w${'$'}<>]+)\(([^:)\s]*)(?::(\d+))?\)""")

        private val http by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build() }

        private fun get(url: String): ByteArray {
            val response = http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", "Holo795/CrashSleuth").timeout(Duration.ofMinutes(2)).build(), HttpResponse.BodyHandlers.ofByteArray())
            check(response.statusCode() == 200) { "$url answered ${response.statusCode()}" }
            return response.body()
        }

        private fun cached(file: Path, download: () -> ByteArray): String {
            if (!file.exists()) {
                file.parent.createDirectories()
                val part = file.resolveSibling(file.fileName.toString() + ".part")
                part.writeBytes(download())
                Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return file.readText()
        }

        /** Mojang's mappings of the client (which also covers the server's classes) and Fabric's intermediary names. */
        fun load(minecraft: String, cache: Path = dev.holo795.crashsleuth.runner.ClientInstaller.defaultCache().resolve("mappings")): Mappings {
            val mojang = cached(cache.resolve("$minecraft-client.txt")) {
                val manifest = String(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
                val url = Regex(""""id":\s*"${Regex.escape(minecraft)}"[^}]*?"url":\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
                    ?: error("Unknown Minecraft version $minecraft")
                val version = String(get(url))
                val mappings = Regex(""""client_mappings":\s*\{[^}]*"url":\s*"([^"]+)"""").find(version)?.groupValues?.get(1)
                    ?: error("Mojang publishes no mappings for $minecraft")
                get(mappings)
            }
            val intermediary = runCatching {
                cached(cache.resolve("$minecraft-intermediary.tiny")) {
                    val jar = get("https://maven.fabricmc.net/net/fabricmc/intermediary/$minecraft/intermediary-$minecraft-v2.jar")
                    ZipInputStream(ByteArrayInputStream(jar)).use { zip ->
                        generateSequence { zip.nextEntry }.first { it.name == "mappings/mappings.tiny" }
                        zip.readBytes()
                    }
                }
            }.getOrNull()
            return parse(mojang, intermediary)
        }

        /** [mojang]: ProGuard text (`named -> obfuscated:`); [intermediary]: tiny v2 (official, intermediary). */
        fun parse(mojang: String, intermediary: String?): Mappings {
            val obfToNamed = HashMap<String, String>()
            val methods = HashMap<String, HashMap<String, String>>()
            val lines = HashMap<String, MutableList<Pair<IntRange, String>>>()
            var current: String? = null
            mojang.lineSequence().forEach { line ->
                if (line.startsWith("#") || line.isBlank()) return@forEach
                if (!line.startsWith(" ")) {
                    val named = line.substringBefore(" -> ")
                    val obf = line.substringAfter(" -> ").removeSuffix(":")
                    obfToNamed[obf] = named
                    current = obf
                } else if (line.contains('(')) {
                    // "    12:30:void run() -> b". Overloads can share one obfuscated name: all their names are kept.
                    val name = line.trim().substringBefore('(').substringAfterLast(' ')
                    val obf = line.substringAfter(" -> ").trim()
                    current?.let { owner ->
                        methods.getOrPut(owner) { HashMap() }.merge(obf, name) { old, new -> if (new in old.split('/')) old else "$old/$new" }
                        val numbers = line.trim().split(':')
                        if (numbers.size >= 3) {
                            val from = numbers[0].toIntOrNull()
                            val to = numbers[1].toIntOrNull()
                            if (from != null && to != null) lines.getOrPut("$owner.$obf") { mutableListOf() }.add(from..to to name)
                        }
                    }
                } else {
                    val name = line.trim().substringBefore(" -> ").substringAfterLast(' ')
                    val obf = line.substringAfter(" -> ").trim()
                    current?.let { methods.getOrPut(it) { HashMap() }.putIfAbsent("field:$obf", name) }
                }
            }
            val classes = HashMap<String, String>(obfToNamed)
            val obfuscatedOf = HashMap<String, String>()
            val members = HashMap<String, String>()
            val memberKeys = HashMap<String, String>()
            var owner: String? = null
            intermediary?.lineSequence()?.forEach { line ->
                val parts = line.split('\t')
                when {
                    line.startsWith("c\t") -> {
                        owner = parts[1].replace('/', '.')
                        obfuscatedOf[parts[2].replace('/', '.')] = owner!!
                        obfToNamed[owner!!]?.let { classes[parts[2].replace('/', '.')] = it }
                    }
                    line.startsWith("\tm\t") && parts.size >= 5 -> owner?.let { o ->
                        memberKeys[parts[4]] = "$o.${parts[3]}"
                        methods[o]?.get(parts[3])?.let { members[parts[4]] = it }
                    }
                    line.startsWith("\tf\t") && parts.size >= 5 -> owner?.let { o -> methods[o]?.get("field:${parts[3]}")?.let { members[parts[4]] = it } }
                }
            }
            return Mappings(classes, obfuscatedOf, members, memberKeys, methods, lines)
        }
    }
}
