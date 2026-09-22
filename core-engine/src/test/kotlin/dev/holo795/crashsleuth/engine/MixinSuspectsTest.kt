package dev.holo795.crashsleuth.engine

import dev.holo795.crashsleuth.inventory.InstanceScanner
import dev.holo795.crashsleuth.inventory.InjectionKind
import dev.holo795.crashsleuth.inventory.MixinIndex
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals

class MixinSuspectsTest {
    @TempDir
    lateinit var server: Path

    /** A mixin class as a mod ships it: `@Mixin(Level.class)` with `@Inject(method = "tick")` and `@Overwrite`. */
    private fun mixinClass(name: String): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null)
        writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).apply {
            visitArray("value").apply { visit(null, Type.getObjectType("net/minecraft/world/level/Level")); visitEnd() }
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PRIVATE, "onTick", "()V", null, null).apply {
            visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", false).apply {
                visitArray("method").apply { visit(null, "tickBlockEntities"); visitEnd() }
                visitEnd()
            }
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "isClientSide", "()Z", null, null).apply {
            visitAnnotation("Lorg/spongepowered/asm/mixin/Overwrite;", false).visitEnd()
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun jar(name: String, files: Map<String, ByteArray>) {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out -> files.forEach { (path, content) -> out.putNextEntry(ZipEntry(path)); out.write(content); out.closeEntry() } }
        server.resolve("mods").createDirectories().resolve(name).writeBytes(bytes.toByteArray())
    }

    @Test
    fun `a crash in game code points to the mods that change that code`() {
        server.resolve("libraries/net/fabricmc/fabric-loader/0.16.10").createDirectories()
        server.resolve("versions/1.21.1").createDirectories()
        server.resolve("server.properties").writeText("")
        jar("fastlevel.jar", mapOf(
            "fabric.mod.json" to """{"id": "fastlevel", "version": "1.0", "mixins": ["fastlevel.mixins.json"]}""".toByteArray(),
            "fastlevel.mixins.json" to """{"package": "com.fast.mixin", "mixins": ["LevelMixin"]}""".toByteArray(),
            "com/fast/mixin/LevelMixin.class" to mixinClass("com/fast/mixin/LevelMixin"),
        ))
        jar("other.jar", mapOf("fabric.mod.json" to """{"id": "other", "version": "1.0"}""".toByteArray()))
        val inventory = InstanceScanner.scan(server)
        val index = MixinIndex.build(inventory.jars)
        assertEquals(setOf(InjectionKind.INJECT, InjectionKind.OVERWRITE), index.injections.map { it.kind }.toSet())
        assertEquals(listOf("fastlevel"), index.modsTouching("net.minecraft.world.level.Level", "tickBlockEntities"))

        val crash = """
            ---- Minecraft Crash Report ----
            Description: Exception in server tick loop

            java.lang.IllegalStateException: Block entity list modified during iteration
            	at net.minecraft.world.level.Level.tickBlockEntities(Level.java:480)
            	at net.minecraft.server.level.ServerLevel.tick(ServerLevel.java:400)
            	at net.minecraft.server.MinecraftServer.tickChildren(MinecraftServer.java:1000)
        """.trimIndent()
        val report = Diagnoser().diagnose(listOf(Diagnoser.Log("crash.txt", crash)), inventory) { index }
        assertEquals(listOf("fastlevel"), report.primary!!.culprits.map { it.id })
        assertEquals("mixins", report.primary!!.details["via"])
    }
}
