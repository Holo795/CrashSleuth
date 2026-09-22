package dev.holo795.crashsleuth.app

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MappingsTest {
    // The shape of Mojang's client.txt and of Fabric's intermediary tiny v2 file.
    private val mojang = """
        # {"fileName":"client.txt"}
        net.minecraft.client.Minecraft -> fgo:
            net.minecraft.client.player.LocalPlayer player -> t
            10:20:void run() -> b
            30:35:void tick(int) -> b
            40:41:boolean isRunning() -> c
        net.minecraft.nbt.CompoundTag -> ub:
            520:540:net.minecraft.nbt.Tag readNamedTagData(java.io.DataInput) -> a
    """.trimIndent()
    private val intermediary = "tiny\t2\t0\tofficial\tintermediary\n" +
        "c\tfgo\tnet/minecraft/class_310\n" +
        "\tm\t()V\tb\tmethod_1514\n" +
        "\tm\t(I)V\tb\tmethod_1574\n" +
        "\tf\tLfqx;\tt\tfield_1724\n" +
        "c\tub\tnet/minecraft/class_2487\n"

    private val mappings = Mappings.parse(mojang, intermediary)

    @Test
    fun `Fabric frames, the overload picked by the line`() {
        assertEquals(
            "\tat knot/net.minecraft.client.Minecraft.tick(Minecraft.java:33) ~[client-intermediary.jar:?]",
            mappings.translate("\tat knot/net.minecraft.class_310.method_1574(class_310.java:33) ~[client-intermediary.jar:?]"),
        )
        assertEquals("field player of net.minecraft.client.Minecraft", mappings.translate("field field_1724 of net.minecraft.class_310"))
    }

    @Test
    fun `vanilla obfuscated frames, other code left alone`() {
        assertEquals("\tat net.minecraft.nbt.CompoundTag.readNamedTagData(CompoundTag.java:524)", mappings.translate("\tat ub.a(SourceFile:524)"))
        assertEquals("\tat fgo.b(SourceFile:99)".let(mappings::translate), "\tat net.minecraft.client.Minecraft.run/tick(Minecraft.java:99)")
        assertEquals("\tat com.example.shop.Shop.main(Shop.java:9)", mappings.translate("\tat com.example.shop.Shop.main(Shop.java:9)"))
    }
}
