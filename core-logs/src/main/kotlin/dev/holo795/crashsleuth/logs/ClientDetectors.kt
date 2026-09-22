package dev.holo795.crashsleuth.logs

import dev.holo795.crashsleuth.model.Confidence
import dev.holo795.crashsleuth.model.Culprit
import dev.holo795.crashsleuth.model.CulpritKind
import dev.holo795.crashsleuth.model.Environment
import dev.holo795.crashsleuth.model.Finding
import dev.holo795.crashsleuth.model.Situation

/**
 * Resource packs the game cannot use. A file that is no pack: `Failed to open pack .../resourcepacks/x.zip`
 * (the game then removes it from the options). A broken model or blockstate: `Failed to load model
 * minecraft:models/block/stone.json`; the log does not say which pack holds it, so the packs the player
 * added (listed as `file/...` when the game reloads its resources) are named.
 */
object ResourcePackDetector : Detector {
    private val UNREADABLE = Regex("""Failed to open pack \S*?resourcepacks/([^/\s]+)""")
    private val BROKEN = Regex("""Failed to load (model|blockstate definition|block state) (\S+?)(?:\.json)?(?:\s|$)""")
    private val RELOAD = Regex("""Reloading ResourceManager: ([^\n]+)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> = buildList {
        document.findAll(UNREADABLE).distinctBy { it.groupValues[1] }.forEach { match ->
            add(Finding(Situation.RENDER, Confidence.CERTAIN, listOf(pack(match.groupValues[1])), listOf(match.value), mapOf("adviceKey" to "render.pack-unreadable")))
        }
        val broken = document.findAll(BROKEN).toList()
        if (broken.isNotEmpty()) {
            val playerPacks = document.findAll(RELOAD).lastOrNull()?.groupValues?.get(1).orEmpty()
                .split(',').map { it.trim() }.filter { it.startsWith("file/") }.map { it.removePrefix("file/") }
                .filterNot { name -> any { finding -> finding.culprits.any { it.id == name } } }
            if (playerPacks.isNotEmpty()) {
                add(Finding(
                    Situation.RENDER, if (playerPacks.size == 1) Confidence.HIGH else Confidence.MEDIUM, playerPacks.map(::pack),
                    broken.take(3).map { it.value.trim() }, mapOf("adviceKey" to "render.pack-broken", "count" to broken.size.toString()),
                ))
            }
        }
    }

    private fun pack(name: String) = Culprit(CulpritKind.SYSTEM, name, file = "resourcepacks/$name")
}

/**
 * A shader pack that does not compile. Iris: `Using shaderpack: x.zip`, then `Failed to create shader rendering
 * pipeline, disabling shaders!` with the program and the compiler's line. The game goes on without shaders.
 */
object ShaderPackDetector : Detector {
    private val USING = Regex("""Using shaderpack: ([^\n]+)""")
    private val FAILED = Regex("""Failed to create shader rendering pipeline, disabling shaders!""")
    private val COMPILE = Regex("""ShaderCompileException: (\w+): ([^\n]+)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val failure = document.find(FAILED) ?: return emptyList()
        val pack = document.findAll(USING).lastOrNull { it.range.first < failure.range.first }?.groupValues?.get(1)?.trim()
        val compile = document.find(COMPILE)
        return listOf(
            Finding(
                Situation.RENDER, if (pack != null) Confidence.CERTAIN else Confidence.HIGH,
                listOfNotNull(pack?.let { Culprit(CulpritKind.SYSTEM, it, file = "shaderpacks/$it") }),
                listOfNotNull(failure.value, compile?.value),
                mapOfNotNull("adviceKey" to "render.shaderpack", "program" to compile?.groupValues?.get(1), "method" to compile?.groupValues?.get(2)?.take(160)),
            ),
        )
    }
}

/**
 * A crash while the game draws the world or its interface. The crash report says what it was doing:
 * "Batching sections" (the blocks of a chunk, 1.21), "Tesselating block model" (older), "Rendering entity in
 * world", "Rendering screen". Whoever appears in the trace, other than the game itself, is the culprit.
 */
object ClientRenderCrashDetector : Detector {
    private val DESCRIPTION = Regex("""(?m)^Description:\s*(.+)$""")
    private val DRAWING = mapOf(
        "batching sections" to "blocks",
        "tesselating block model" to "blocks",
        "tesselating block in world" to "blocks",
        "rendering block entity" to "block entity",
        "rendering entity in world" to "entity",
        "rendering screen" to "screen",
        "rendering overlay" to "screen",
        "rendering item" to "item",
    )
    private val BLOCK_NAME = Regex("""Block: Block\{([\w.-]+:[\w./-]+)}""")
    private val BLOCK_LOCATION = Regex("""Block location:\s*World:\s*\((-?\d+),\s*(-?\d+),\s*(-?\d+)\)""")
    private val ENTITY_TYPE = Regex("""Entity Type:\s*([\w.-]+:[\w./-]+)""")

    override fun detect(document: LogDocument, environment: Environment): List<Finding> {
        val description = DESCRIPTION.find(document.text)?.groupValues?.get(1)?.trim() ?: return emptyList()
        val drawing = DRAWING.entries.firstOrNull { description.lowercase().startsWith(it.key) } ?: return emptyList()
        val trace = document.stackTraces.firstOrNull() ?: return emptyList()
        val culprits = Attribution.culprits(trace, environment, 2)
        val details = document.section("Block being tesselated") + document.section("Block being rendered") +
            document.section("Entity being rendered") + document.section("Block Entity Details")
        val what = details.joinToString("\n")
        return listOf(
            Finding(
                situation = Situation.RENDER,
                confidence = if (culprits.isEmpty()) Confidence.MEDIUM else Confidence.CERTAIN,
                culprits = culprits,
                evidence = listOfNotNull(
                    "Description: $description",
                    trace.root.headline,
                    BLOCK_NAME.find(what)?.let { name ->
                        val at = BLOCK_LOCATION.find(what)?.let { " at ${it.groupValues[1]}, ${it.groupValues[2]}, ${it.groupValues[3]}" }.orEmpty()
                        "${name.groupValues[1]}$at"
                    },
                    ENTITY_TYPE.find(what)?.groupValues?.get(1),
                ),
                details = mapOfNotNull("adviceKey" to "render.drawing", "drawing" to drawing.value, "phase" to description),
            ),
        )
    }
}
