package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.inventory.Inventory
import dev.holo795.crashsleuth.inventory.JarEntry
import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Platform
import dev.holo795.crashsleuth.model.Situation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * Asks Modrinth, by the SHA-1 of each jar, which version is installed and whether a newer one exists
 * for this loader and game version. Only hashes are sent; it runs when the user asks for it.
 */
class ModrinthCheck(private val post: (String, String) -> String = ::httpPost) {
    data class Update(val jar: JarEntry, val project: String, val installed: String, val latest: String)

    fun check(inventory: Inventory, minecraft: String?): List<Update> {
        val jars = inventory.jars.filter { it.path != null && it.error == null }
        val hashes = jars.associateBy { sha1(Path.of(it.path!!)) }
        if (hashes.isEmpty()) return emptyList()
        val known = Json.parseToJsonElement(post("/v2/version_files", body(hashes.keys, null, null))).jsonObject
        // A game folder without launcher files: the mods themselves say which loader they are for.
        val loader = loaderOf(inventory.platform) ?: jars.flatMap { it.formats }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key?.let {
            when (it) {
                dev.holo795.crashsleuth.inventory.MetadataFormat.FABRIC -> "fabric"
                dev.holo795.crashsleuth.inventory.MetadataFormat.QUILT -> "quilt"
                dev.holo795.crashsleuth.inventory.MetadataFormat.NEOFORGE -> "neoforge"
                dev.holo795.crashsleuth.inventory.MetadataFormat.FORGE -> "forge"
                dev.holo795.crashsleuth.inventory.MetadataFormat.BUKKIT, dev.holo795.crashsleuth.inventory.MetadataFormat.PAPER -> "paper"
                else -> null
            }
        }
        val latest = if (loader == null || minecraft == null) JsonObject(emptyMap()) else
            Json.parseToJsonElement(post("/v2/version_files/update", body(known.keys, loader, minecraft))).jsonObject
        return known.mapNotNull { (hash, version) ->
            val installed = version.jsonObject
            val newest = latest[hash]?.jsonObject ?: return@mapNotNull null
            if (newest["id"] == installed["id"]) return@mapNotNull null
            Update(
                jar = hashes.getValue(hash),
                project = installed["project_id"]?.jsonPrimitive?.content ?: "",
                installed = installed["version_number"]?.jsonPrimitive?.content ?: "?",
                latest = newest["version_number"]?.jsonPrimitive?.content ?: "?",
            )
        }
    }

    /** Updates as findings: information, never a cause by themselves. */
    fun findings(updates: List<Update>): List<Finding> = updates.map { update ->
        val mod = update.jar.mods.firstOrNull()
        Finding(
            Situation.OUTDATED, Confidence.LOW,
            listOf(Culprit(if (update.jar.folder == "plugins") CulpritKind.PLUGIN else CulpritKind.MOD, mod?.id ?: update.jar.file, mod?.name, update.installed, update.jar.file)),
            evidence = listOf("${update.jar.folder}/${update.jar.file}: ${update.installed} → ${update.latest} (modrinth.com/project/${update.project})"),
            details = mapOf("latest" to update.latest, "project" to update.project),
        )
    }

    private fun body(hashes: Collection<String>, loader: String?, minecraft: String?): String = buildJsonObject {
        putJsonArray("hashes") { hashes.forEach { add(JsonPrimitive(it)) } }
        put("algorithm", "sha1")
        if (loader != null) putJsonArray("loaders") { add(JsonPrimitive(loader)) }
        if (minecraft != null) putJsonArray("game_versions") { add(JsonPrimitive(minecraft)) }
    }.toString()

    companion object {
        fun loaderOf(platform: Platform): String? = when (platform) {
            Platform.FABRIC -> "fabric"
            Platform.QUILT -> "quilt"
            Platform.NEOFORGE -> "neoforge"
            Platform.FORGE -> "forge"
            Platform.PAPER, Platform.PURPUR -> "paper"
            Platform.SPIGOT -> "spigot"
            Platform.FOLIA -> "folia"
            else -> null
        }

        fun sha1(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build() }

        fun httpPost(path: String, body: String): String {
            val request = HttpRequest.newBuilder(URI("https://api.modrinth.com$path"))
                .header("User-Agent", "Holo795/CrashSleuth (github.com/Holo795/CrashSleuth)")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(1))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "Modrinth answered ${response.statusCode()}" }
            return response.body()
        }
    }
}
