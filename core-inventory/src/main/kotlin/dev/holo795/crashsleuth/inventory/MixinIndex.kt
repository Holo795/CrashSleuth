package dev.holo795.crashsleuth.inventory

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.tomlj.Toml
import org.tomlj.TomlTable
import java.nio.file.Path
import java.util.jar.Manifest
import java.util.zip.ZipFile

/** How a mixin changes a method of the game. */
@Serializable
enum class InjectionKind { OVERWRITE, INJECT, REDIRECT, MODIFY_ARG, MODIFY_VARIABLE, MODIFY_CONSTANT, WRAP_OPERATION, MODIFY_EXPRESSION, MODIFY_RETURN, WRAP_METHOD, WRAP_CONDITION }

/** One change of one mixin: which target method, and for call-site injections, which call. */
@Serializable
data class Injection(
    val kind: InjectionKind,
    /** Target class, internal name (net/minecraft/world/level/Level). */
    val targetClass: String,
    /** Target method name, without descriptor. */
    val method: String,
    /** Call site, for redirects and wraps (`net/minecraft/Foo;bar()V`). */
    val at: String? = null,
    /** 0 when the mod accepts that the injection finds nothing. */
    val require: Int? = null,
    val mixin: String,
    val modId: String,
)

/**
 * Who changes which method of the game through mixins, read from the bytecode of the mixin classes
 * (ASM), without loading anything.
 */
class MixinIndex(val injections: List<Injection>) {
    /** Mods that change [className] (dotted or internal), with the methods they touch. */
    fun modsTouching(className: String, method: String? = null): List<String> {
        val internal = className.replace('.', '/')
        return injections.filter { it.targetClass == internal && (method == null || it.method == method) }.map { it.modId }.distinct()
    }

    /** The same method replaced by several mods: only one replacement is applied. */
    fun overwriteConflicts(): List<List<Injection>> =
        injections.filter { it.kind == InjectionKind.OVERWRITE }
            .groupBy { it.targetClass to it.method }.values
            .filter { same -> same.map { it.modId }.distinct().size > 1 }

    /**
     * The same call in the same method redirected by several mods: once the first redirect replaced
     * the call, the second finds nothing to redirect, and fails unless it accepts that (require = 0).
     */
    fun redirectConflicts(): List<List<Injection>> =
        injections.filter { it.kind == InjectionKind.REDIRECT && it.at != null }
            .groupBy { Triple(it.targetClass, it.method, it.at) }.values
            .filter { same -> same.map { it.modId }.distinct().size > 1 && same.count { (it.require ?: 1) > 0 } >= 1 }

    companion object {
        private val KINDS = mapOf(
            "Lorg/spongepowered/asm/mixin/injection/Inject;" to InjectionKind.INJECT,
            "Lorg/spongepowered/asm/mixin/injection/Redirect;" to InjectionKind.REDIRECT,
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;" to InjectionKind.MODIFY_ARG,
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;" to InjectionKind.MODIFY_ARG,
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;" to InjectionKind.MODIFY_VARIABLE,
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;" to InjectionKind.MODIFY_CONSTANT,
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;" to InjectionKind.WRAP_OPERATION,
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;" to InjectionKind.MODIFY_EXPRESSION,
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;" to InjectionKind.MODIFY_RETURN,
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;" to InjectionKind.WRAP_METHOD,
            "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;" to InjectionKind.WRAP_CONDITION,
            "Lcom/llamalad7/mixinextras/injector/WrapWithCondition;" to InjectionKind.WRAP_CONDITION,
        )
        private const val MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;"
        private const val OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;"

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val json = Json { isLenient = true; allowComments = true; allowTrailingComma = true }

        fun build(jars: List<JarEntry>): MixinIndex =
            MixinIndex(jars.filter { it.path != null && it.mods.isNotEmpty() }.flatMap { jar -> runCatching { read(jar) }.getOrDefault(emptyList()) })

        /** Mixins of one jar, from the configs its metadata declares. */
        fun read(jar: JarEntry): List<Injection> = ZipFile(Path.of(jar.path!!).toFile()).use { zip ->
            fun text(name: String) = zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8).removePrefix("﻿") } }
            val modId = jar.mods.first().id
            val configs = buildSet {
                text("fabric.mod.json")?.let { runCatching { json.parseToJsonElement(it).jsonObject["mixins"] as? JsonArray }.getOrNull() }?.forEach { entry ->
                    when (entry) {
                        is JsonPrimitive -> add(entry.content)
                        is JsonObject -> (entry["config"] as? JsonPrimitive)?.content?.let(::add)
                        else -> {}
                    }
                }
                listOf("META-INF/neoforge.mods.toml", "META-INF/mods.toml").forEach { name ->
                    text(name)?.let(::tomlConfigs)?.forEach(::add)
                }
                zip.getEntry("META-INF/MANIFEST.MF")?.let { entry -> zip.getInputStream(entry).use { Manifest(it) } }
                    ?.mainAttributes?.getValue("MixinConfigs")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach(::add)
            }
            configs.flatMap { config ->
                val root = text(config)?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return@flatMap emptyList()
                val pkg = (root["package"] as? JsonPrimitive)?.content ?: return@flatMap emptyList()
                val names = listOf("mixins", "server", "client", "common").flatMap { key -> (root[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content } }
                names.flatMap { name ->
                    val entry = zip.getEntry("${pkg.replace('.', '/')}/${name.replace('.', '/')}.class") ?: return@flatMap emptyList()
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    runCatching { injections(bytes, modId, "$config:$name") }.getOrDefault(emptyList())
                }
            }
        }

        // Explicit types: tomlj annotates its results with checker-framework annotations that are not on the classpath.
        private fun tomlConfigs(text: String): List<String> {
            val array: org.tomlj.TomlArray = runCatching { Toml.parse(text).getArray("mixins") }.getOrNull() ?: return emptyList()
            return (0 until array.size()).mapNotNull { index ->
                val table: TomlTable? = array.get(index) as? TomlTable
                val config: String? = table?.getString("config")
                config
            }
        }

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

        private fun annotations(node: ClassNode) = node.visibleAnnotations.orEmpty() + node.invisibleAnnotations.orEmpty()

        private fun AnnotationNode.value(name: String): Any? {
            val values = values ?: return null
            for (i in values.indices step 2) if (values[i] == name) return values[i + 1]
            return null
        }

        private fun injections(bytes: ByteArray, modId: String, mixin: String): List<Injection> {
            val node = ClassNode(Opcodes.ASM9)
            ClassReader(bytes).accept(node, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
            val annotation = annotations(node).firstOrNull { it.desc == MIXIN } ?: return emptyList()
            val targets = ((annotation.value("value") as? List<*>).orEmpty().mapNotNull { (it as? Type)?.internalName } +
                (annotation.value("targets") as? List<*>).orEmpty().mapNotNull { (it as? String)?.replace('.', '/') }).distinct()
            if (targets.isEmpty()) return emptyList()
            return node.methods.flatMap { method ->
                (method.visibleAnnotations.orEmpty() + method.invisibleAnnotations.orEmpty()).flatMap { injection ->
                    if (injection.desc == OVERWRITE) {
                        return@flatMap targets.map { Injection(InjectionKind.OVERWRITE, it, method.name, mixin = mixin, modId = modId) }
                    }
                    val kind = KINDS[injection.desc] ?: return@flatMap emptyList()
                    val methods = when (val value = injection.value("method")) {
                        is List<*> -> value.mapNotNull { it as? String }
                        is String -> listOf(value)
                        else -> emptyList()
                    }.map { it.substringBefore('(').substringAfterLast(';').ifEmpty { it } }
                    val at = when (val value = injection.value("at")) {
                        is AnnotationNode -> value
                        is List<*> -> value.firstOrNull() as? AnnotationNode
                        else -> null
                    }?.value("target") as? String
                    val require = injection.value("require") as? Int
                    targets.flatMap { target -> methods.map { Injection(kind, target, it, at, require, mixin, modId) } }
                }
            }
        }
    }
}
