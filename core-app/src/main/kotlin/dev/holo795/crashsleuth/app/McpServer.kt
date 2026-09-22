package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Report
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.Writer
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

/**
 * CrashSleuth as a tool for an assistant (MCP, over the standard input and output). An assistant can analyse
 * a folder, read what is installed, look up a setting in the person's own files, and ask which settings and
 * values exist, instead of inventing them. Everything stays on this computer, and secrets are masked.
 */
class McpServer(private val workbench: Workbench = Workbench()) {
    private val messages = Messages.forLanguage(null)

    fun serve(input: BufferedReader, output: Writer) {
        input.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val request = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEach
            val answer = handle(request) ?: return@forEach
            synchronized(output) {
                output.write(answer.toString())
                output.write("\n")
                output.flush()
            }
        }
    }

    /** One request, one answer; notifications (no id) are answered with nothing. */
    fun handle(request: JsonObject): JsonObject? {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content.orEmpty()
        if (id == null || id is JsonNull) return null
        return runCatching {
            when (method) {
                "initialize" -> reply(id, buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") { put("name", "crashsleuth"); put("version", VERSION) }
                })
                "tools/list" -> reply(id, buildJsonObject { put("tools", TOOLS) })
                "tools/call" -> {
                    val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())
                    val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                    reply(id, text(call(params["name"]?.jsonPrimitive?.content.orEmpty(), arguments)))
                }
                "ping" -> reply(id, JsonObject(emptyMap()))
                else -> error(id, -32601, "unknown method $method")
            }
        }.getOrElse { failure -> error(id, -32000, failure.message ?: failure.javaClass.simpleName) }
    }

    private fun call(tool: String, arguments: JsonObject): String {
        fun path(name: String): Path = Path.of(arguments[name]?.jsonPrimitive?.content ?: error("$name is required"))
        return when (tool) {
            "analyze" -> summary(workbench.analyze(path("path")))
            "inventory" -> {
                val analysis = workbench.analyze(path("path"))
                val inventory = analysis.inventory ?: error("this is a log, not a folder or a pack")
                inventory.jars.joinToString("\n") { jar ->
                    val mod = jar.mods.firstOrNull()
                    "${jar.folder}/${jar.file}: ${mod?.id ?: "no metadata"} ${mod?.version.orEmpty()}".trim()
                }.ifEmpty { "nothing installed" }
            }
            "compare" -> summary(workbench.compare(workbench.analyze(path("game")), path("server")))
            "readable" -> {
                val file = path("path")
                val minecraft = arguments["minecraft"]?.jsonPrimitive?.content
                    ?: workbench.analyze(file).report.environment.minecraftVersion
                    ?: error("the log does not say its Minecraft version: pass minecraft")
                Mappings.load(minecraft).translate(file.readText()).take(MAX_TEXT)
            }
            "config_read" -> configRead(path("folder"), arguments["file"]?.jsonPrimitive?.content ?: error("file is required"))
            "known_settings" -> {
                val platform = arguments["platform"]?.jsonPrimitive?.content?.let { name ->
                    dev.holo795.crashsleuth.model.Platform.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
                }
                KnownSettings.describe(KnownSettings.forPlatform(platform))
            }
            else -> error("unknown tool $tool")
        }
    }

    /** A configuration file of the person, with anything secret masked. */
    private fun configRead(folder: Path, file: String): String {
        require(!file.contains("..")) { "the file must be inside the folder" }
        val target = folder.resolve(file)
        require(target.exists() && !target.isDirectory()) { "$file is not a file of this folder" }
        return target.readText().lineSequence().take(MAX_LINES).joinToString("\n") { line ->
            if (SECRET.containsMatchIn(line)) line.substringBefore(':') + ": <hidden>" else line
        }.let(Privacy::clean).take(MAX_TEXT)
    }

    private fun summary(analysis: Analysis): String {
        val text = ReportText(messages)
        val report: Report = analysis.report
        // The path is the assistant's own, kept as it is so it can ask again about the same folder; what
        // comes out of the logs is cleaned.
        val findings = buildString {
            if (report.findings.isEmpty()) appendLine("No known problem found.")
            report.findings.take(8).forEach { finding ->
                appendLine("- ${text.title(finding)} (${finding.confidence.name.lowercase()})")
                if (finding.culprits.isNotEmpty()) appendLine("  culprit: ${text.culpritList(finding)}")
                appendLine("  advice: ${text.advice(finding)}")
                finding.evidence.take(2).forEach { appendLine("  evidence: ${it.take(200)}") }
            }
        }.let(Privacy::clean)
        return buildString {
            appendLine("Target: ${analysis.target.kind.name.lowercase()} ${analysis.target.path}")
            appendLine("Game: ${listOfNotNull(report.environment.platform.displayName, report.environment.minecraftVersion, report.environment.javaVersion?.let { "Java $it" }).joinToString(" ")}")
            append(findings)
        }
    }

    private fun reply(id: JsonElement, result: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    private fun text(body: String) = buildJsonObject {
        putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", body) }) }
    }

    companion object {
        private const val VERSION = "0.1.0"
        private const val MAX_TEXT = 60_000
        private const val MAX_LINES = 500
        private val SECRET = Regex("""(?i)\b(secret|password|token|api[-_]?key|rcon\.password)\b\s*[:=]""")

        private fun tool(name: String, description: String, properties: JsonObject, required: List<String>) = buildJsonObject {
            put("name", name)
            put("description", description)
            putJsonObject("inputSchema") {
                put("type", "object")
                put("properties", properties)
                putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
            }
        }

        private fun string(what: String) = buildJsonObject { put("type", "string"); put("description", what) }

        val TOOLS: JsonArray = buildJsonArray {
            add(tool("analyze", "Diagnose a server folder, a game folder, a modpack, a crash report, a log or a spark profile: cause, culprit and what to do.",
                buildJsonObject { put("path", string("folder or file to analyse")) }, listOf("path")))
            add(tool("inventory", "List the mods and plugins installed in a folder or a modpack, with their versions.",
                buildJsonObject { put("path", string("folder or modpack")) }, listOf("path")))
            add(tool("compare", "Compare a player's game with the server it joins: the mods that add content must match.",
                buildJsonObject { put("game", string("the player's game folder")); put("server", string("the server folder")) }, listOf("game", "server")))
            add(tool("readable", "Print a log or crash report with the game's own names instead of the obfuscated ones.",
                buildJsonObject { put("path", string("log or crash report")); put("minecraft", string("Minecraft version, when the log does not say it")) }, listOf("path")))
            add(tool("config_read", "Read a configuration file of that folder, exactly as it is on disk; secrets are hidden.",
                buildJsonObject { put("folder", string("server or game folder")); put("file", string("file inside it, such as config/paper-global.yml")) }, listOf("folder", "file")))
            add(tool("known_settings", "The settings CrashSleuth knows for a platform, with the values each one accepts. Use it instead of guessing a setting name or a value.",
                buildJsonObject { put("platform", string("paper, velocity, bungeecord, neoforge, fabric...")) }, emptyList()))
        }
    }
}
