package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.bisect.SearchResult
import dev.holo795.crashsleuth.model.Messages
import dev.holo795.crashsleuth.model.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * A report anyone can open in a browser, like spark's: the whole report travels in the link itself,
 * after the `#`, which browsers never send to any server. Nothing is uploaded or stored anywhere.
 */
object ShareLink {
    const val VIEWER = "https://holo795.github.io/CrashSleuth/r/"

    @Serializable
    data class Culprit(val name: String, val id: String? = null, val version: String? = null)

    @Serializable
    data class Item(
        val title: String,
        val confidence: String,
        val culprits: List<Culprit> = emptyList(),
        val advice: String,
        val evidence: List<String> = emptyList(),
    )

    @Serializable
    data class Installed(val name: String, val version: String? = null)

    @Serializable
    data class Search(val found: Boolean, val complete: Boolean, val culprits: List<String>, val launches: Int, val seconds: Long)

    /** What the viewer shows; texts are already written in the chosen language. */
    @Serializable
    data class Shared(
        val v: Int = 1,
        val lang: String,
        val name: String,
        val kind: String,
        val facts: List<String>,
        val findings: List<Item>,
        val installed: List<Installed> = emptyList(),
        val search: Search? = null,
        val at: Long = System.currentTimeMillis(),
    )

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    private const val MAX_EVIDENCE = 6
    private const val MAX_LINE = 300
    private const val MAX_INSTALLED = 400

    fun build(analysis: Analysis, messages: Messages, search: SearchResult? = null): Shared {
        val text = ReportText(messages)
        val environment = analysis.report.environment
        return Shared(
            lang = messages.locale.language.ifEmpty { "en" },
            name = analysis.target.name,
            kind = analysis.target.kind.name,
            facts = listOfNotNull(
                environment.platform.takeIf { it != Platform.UNKNOWN }?.let { "${it.displayName} ${environment.loaderVersion ?: ""}".trim() },
                (environment.minecraftVersion ?: analysis.target.minecraft)?.let { "Minecraft $it" },
                environment.javaVersion?.let { "Java $it" },
            ),
            findings = analysis.report.findings.take(8).map { finding ->
                Item(
                    title = text.title(finding),
                    confidence = finding.confidence.name,
                    culprits = text.culprits(finding).map { Culprit(it.label, it.id.takeIf { id -> id != it.label }, it.version) },
                    advice = Privacy.clean(text.advice(finding)),
                    evidence = finding.evidence.take(MAX_EVIDENCE).map { Privacy.clean(it).take(MAX_LINE) },
                )
            },
            installed = analysis.inventory?.jars.orEmpty().take(MAX_INSTALLED).map { jar ->
                val mod = jar.mods.firstOrNull()
                Installed(mod?.name ?: mod?.id ?: jar.file, mod?.version)
            },
            search = search?.let { result ->
                Search(result.reproduced, result.complete, result.labels, result.runs.size, result.runs.sumOf { it.seconds }.toLong())
            },
        )
    }

    /** The link: raw deflate, then base64url, after the viewer's address. */
    fun link(shared: Shared, viewer: String = VIEWER): String = "$viewer#r=" + encode(shared)

    fun encode(shared: Shared): String {
        val bytes = ByteArrayOutputStream()
        DeflaterOutputStream(bytes, Deflater(Deflater.BEST_COMPRESSION, true)).use { it.write(json.encodeToString(Shared.serializer(), shared).toByteArray()) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    fun decode(data: String): Shared {
        val bytes = InflaterInputStream(Base64.getUrlDecoder().decode(data).inputStream(), Inflater(true)).use { it.readBytes() }
        return json.decodeFromString(Shared.serializer(), bytes.toString(Charsets.UTF_8))
    }
}
