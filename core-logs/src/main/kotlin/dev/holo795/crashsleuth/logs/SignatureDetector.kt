package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Situation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One known log message: a regular expression and what it means. Signatures are data
 * (crashsleuth/signatures.json), so adding a case needs no code.
 */
data class Signature(
    val id: String,
    val situation: Situation,
    val confidence: Confidence,
    val pattern: Regex,
    val culprits: List<CulpritRule> = emptyList(),
    /** Detail name to a group number, or to a fixed text. */
    val details: Map<String, Any> = emptyMap(),
    /** Message key of a more precise advice than the situation's. */
    val advice: String? = null,
) {
    data class CulpritRule(
        val kind: CulpritKind,
        val group: Int? = null,
        val nameGroup: Int? = null,
        /** Group holding the culprit's installed version. */
        val versionGroup: Int? = null,
        val literal: String? = null,
        /** The group is a jar file name: turn it into an id. */
        val fromJar: Boolean = false,
    )
}

class SignatureDetector(private val signatures: List<Signature>) : Detector {
    override fun detect(document: LogDocument, environment: Environment): List<Finding> =
        signatures.flatMap { signature ->
            document.findAll(signature.pattern).map { match -> finding(signature, match) }.distinct().toList()
        }

    private fun finding(signature: Signature, match: MatchResult): Finding {
        fun group(index: Int?) = index?.let { match.groups[it]?.value?.trim() }?.takeIf { it.isNotEmpty() }
        val culprits = signature.culprits.mapNotNull { rule ->
            val raw = rule.literal ?: group(rule.group) ?: return@mapNotNull null
            val id = if (rule.fromJar) Attribution.idFromJar(raw) else raw
            Culprit(rule.kind, id, name = group(rule.nameGroup), version = group(rule.versionGroup), file = if (rule.fromJar) raw else null)
        }
        val details = signature.details.mapNotNull { (key, value) ->
            val text = if (value is Int) group(value) else value.toString()
            text?.let { key to it }
        }.toMap() + listOfNotNull(signature.advice?.let { "adviceKey" to it }, "signature" to signature.id)
        return Finding(
            situation = signature.situation,
            confidence = signature.confidence,
            culprits = culprits,
            evidence = listOf(match.value.lines().joinToString(" ") { it.trim() }.trim()),
            details = details,
        )
    }

    companion object {
        /** The signatures shipped with CrashSleuth. */
        val BUILT_IN: SignatureDetector by lazy { SignatureDetector(load(resource("crashsleuth/signatures.json"))) }

        private fun resource(name: String): String =
            SignatureDetector::class.java.classLoader.getResourceAsStream(name)!!.use { it.readBytes().toString(Charsets.UTF_8) }

        fun load(json: String): List<Signature> =
            Json.parseToJsonElement(json).jsonObject.getValue("signatures").jsonArray.map { element ->
                val node = element.jsonObject
                fun text(key: String) = (node[key] as? JsonPrimitive)?.content
                Signature(
                    id = text("id")!!,
                    situation = Situation.valueOf(text("situation")!!),
                    confidence = Confidence.valueOf(text("confidence") ?: "HIGH"),
                    pattern = Regex(text("pattern")!!),
                    culprits = node["culprits"]?.jsonArray.orEmpty().map { rule ->
                        val fields = rule.jsonObject
                        Signature.CulpritRule(
                            kind = CulpritKind.valueOf(fields["kind"]?.jsonPrimitive?.content ?: "UNKNOWN"),
                            group = fields["group"]?.jsonPrimitive?.intOrNull,
                            nameGroup = fields["name"]?.jsonPrimitive?.intOrNull,
                            versionGroup = fields["version"]?.jsonPrimitive?.intOrNull,
                            literal = fields["literal"]?.jsonPrimitive?.content,
                            fromJar = fields["fromJar"]?.jsonPrimitive?.booleanOrNull == true,
                        )
                    },
                    details = (node["details"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                        value.jsonPrimitive.let { if (it.isString) it.content else it.intOrNull ?: it.content }
                    },
                    advice = text("advice"),
                )
            }
    }
}
