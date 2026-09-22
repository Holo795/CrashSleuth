package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.model.Messages
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Asks a model running on this computer to explain a report in plain words: Ollama (localhost:11434) or any
 * server speaking the OpenAI chat API (LM Studio, llama.cpp). Only an anonymised summary of the report is
 * sent, never the logs, and only to a local address.
 */
class LocalAi(
    val url: String = System.getenv("CRASHSLEUTH_AI_URL") ?: "http://localhost:11434",
    private val model: String? = System.getenv("CRASHSLEUTH_AI_MODEL"),
) {
    init {
        val host = URI(url).host.orEmpty()
        require(host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]") { "the AI must run on this computer, not at $host" }
    }

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val openAi get() = url.trimEnd('/').endsWith("/v1")

    /** The models the local server offers; empty when none runs. */
    fun models(): List<String> = runCatching {
        if (openAi) {
            Json.parseToJsonElement(get("/models")).jsonObject["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        } else {
            Json.parseToJsonElement(get("/api/tags")).jsonObject["models"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        }
    }.getOrDefault(emptyList())

    fun available() = models().isNotEmpty()

    /** What is sent: the findings as the report shows them, cleaned of paths, addresses and identifiers. */
    fun summary(analysis: Analysis, messages: Messages): String {
        val shared = ShareLink.build(analysis, messages)
        return buildString {
            appendLine("Game: ${shared.facts.joinToString(", ").ifEmpty { "unknown" }}; analysed: ${analysis.target.kind.name.lowercase()}")
            shared.findings.take(5).forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.title} (confidence ${item.confidence.lowercase()})")
                if (item.culprits.isNotEmpty()) appendLine("   Culprit: ${item.culprits.joinToString { listOfNotNull(it.name, it.version).joinToString(" ") }}")
                appendLine("   Advice: ${item.advice}")
                item.evidence.take(3).forEach { appendLine("   Evidence: $it") }
            }
            if (shared.findings.isEmpty()) appendLine("No known problem was found.")
            // Only the settings this diagnosis really talks about: the whole catalogue made small models
            // invent problems ("the EULA is refused") out of settings nobody mentioned.
            val mentioned = (shared.findings.flatMap { listOf(it.advice) + it.evidence }).joinToString(" ").lowercase()
            val settings = KnownSettings.forPlatform(analysis.report.environment.platform)
                .filter { it.file.lowercase() in mentioned || it.key.lowercase() in mentioned }
            if (settings.isNotEmpty()) {
                appendLine("The settings named above, with the values they accept (never write another value):")
                appendLine(KnownSettings.describe(settings))
            }
        }.let(Privacy::clean)
    }

    /** The explanation, in the language of [messages]. */
    fun explain(analysis: Analysis, messages: Messages): String {
        val installed = models()
        val chosen = model ?: PREFERRED.firstNotNullOfOrNull { wanted -> installed.firstOrNull { it.startsWith(wanted) } }
            ?: installed.firstOrNull { name -> AVOIDED.none(name::startsWith) } ?: installed.firstOrNull() ?: error("no local model is running at $url")
        val language = if (messages.locale.language == "fr") "French" else "English"
        val system = "You help Minecraft players and server owners. Explain the diagnosis below in plain $language, " +
            "in at most 6 short sentences: what happened, why, and the steps to fix it. Use only the facts, names, files and " +
            "settings written below: never name a setting, value, mode, version, mod or link that is not in them, and never " +
            "invent a value for a setting whose accepted values are listed. " +
            "Name a setting only when changing it is part of the fix. Plain text only, no Markdown, no lists with symbols, no emoji."
        val body = buildJsonObject {
            put("model", chosen)
            put("stream", false)
            // Reasoning models (Qwen 3) would first write their thoughts: only the answer is wanted.
            if (!openAi) put("think", false)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", system) })
                add(buildJsonObject { put("role", "user"); put("content", summary(analysis, messages)) })
            }
        }.toString()
        val reply = Json.parseToJsonElement(post(if (openAi) "/chat/completions" else "/api/chat", body)).jsonObject
        val text = if (openAi) {
            ((reply["choices"] as JsonArray)[0].jsonObject["message"] as JsonObject)["content"]
        } else {
            (reply["message"] as JsonObject)["content"]
        }
        // Small models keep some Markdown anyway: it is shown as plain text.
        val answer = (text as JsonPrimitive).content.replace(Regex("""(?s)<think>.*?</think>"""), "").trim()
            .replace("**", "").replace(Regex("""(?m)^#+\s*"""), "").replace("`", "")
        val invented = invented(answer, analysis)
        return if (invented.isEmpty()) answer else answer + "\n\n" + messages.get("ai.invented", invented.joinToString(", "))
    }

    /**
     * Settings the answer names that neither the report nor the catalogue knows. Small models do invent them
     * (one offered a Velocity forwarding mode that does not exist), and the person must not act on those.
     */
    fun invented(answer: String, analysis: Analysis): List<String> {
        val known = (KnownSettings.all.map { it.key } + KnownSettings.all.map { it.file } + KNOWN_WORDS).map { it.lowercase() }
        val fromReport = (analysis.report.findings.flatMap { it.evidence + it.details.values + it.culprits.mapNotNull { c -> c.id } } +
            analysis.inventory?.jars.orEmpty().flatMap { jar -> listOf(jar.file) + jar.mods.map { it.id } }).joinToString(" ").lowercase()
        return SETTING_LIKE.findAll(answer).map { it.value }.distinct()
            .filterNot { candidate ->
                val lower = candidate.lowercase()
                known.any { lower == it || lower.endsWith(".$it") || it.endsWith(".$lower") || it.contains(lower) } || lower in fromReport
            }
            .take(5).toList()
    }

    companion object {
        /** Words that look like settings but are ordinary file or folder names. */
        private val KNOWN_WORDS = listOf("server.properties", "config.yml", "velocity.toml", "spigot.yml", "eula.txt", "forwarding.secret",
            "paper-global.yml", "user_jvm_args.txt", "fabric.mod.json", "mods.toml", "latest.log", "level.dat", "session.lock", "crashsleuth.jar")

        /** "proxies.velocity.secret", "settings.bungeecord", "player-info-forwarding-mode": a setting, not a sentence. */
        private val SETTING_LIKE = Regex("""\b[a-z][\w-]*(?:[._][a-z][\w-]*){1,4}\b""")

        /**
         * Compared on real CrashSleuth reports (22/09/2026, French, a Mac): gemma3:4b answered in 3 to 6 s without inventing,
         * ministral-3:8b in 5 to 11 s; qwen3:4b wrote its reasoning in English and took up to 2 minutes.
         */
        val PREFERRED = listOf("gemma3:4b", "gemma3", "ministral-3", "mistral", "llama3.2", "phi4-mini")
        private val AVOIDED = listOf("qwen3", "deepseek-r1")
    }

    private fun get(path: String): String {
        val response = client.send(HttpRequest.newBuilder(URI(url.trimEnd('/') + path)).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "local AI answered ${response.statusCode()}" }
        return response.body()
    }

    private fun post(path: String, body: String): String {
        val request = HttpRequest.newBuilder(URI(url.trimEnd('/') + path)).timeout(Duration.ofMinutes(5))
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "local AI answered ${response.statusCode()}: ${response.body().take(200)}" }
        return response.body()
    }
}
