package dev.holo795.crashsleuth.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {
    @TempDir
    lateinit var folder: Path

    private val server = McpServer()

    /** A path of this computer inside JSON: Windows separators have to be escaped. */
    private val here get() = folder.toString().replace("\\", "\\\\")

    private fun ask(line: String) = server.handle(Json.parseToJsonElement(line).jsonObject)

    private fun callText(name: String, arguments: String): String {
        val answer = ask("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""")!!
        return answer["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    }

    @Test
    fun `an assistant sees the tools and gets no answer to a notification`() {
        val tools = ask("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}""")!!["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(
            listOf("analyze", "inventory", "compare", "readable", "config_read", "known_pairs", "known_settings"),
            tools.map { it.jsonObject["name"]!!.jsonPrimitive.content },
        )
        assertNull(ask("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }

    @Test
    fun `the settings of a platform are the real ones`() {
        val velocity = callText("known_settings", """{"platform":"velocity"}""")
        assertTrue("player-info-forwarding-mode" in velocity, velocity)
        assertTrue("modern" in velocity && "bungeeguard" in velocity, velocity)
        assertTrue("settings.bungeecord" !in velocity, "a Paper setting has nothing to do in Velocity's list")
    }

    @Test
    fun `a configuration file is read as it is, without its secrets`() {
        folder.resolve("config").createDirectories()
        folder.resolve("config/paper-global.yml").writeText("proxies:\n  velocity:\n    enabled: true\n    secret: hunter2\n")
        val text = callText("config_read", """{"folder":"$here","file":"config/paper-global.yml"}""")
        assertTrue("enabled: true" in text, text)
        assertTrue("hunter2" !in text && "<hidden>" in text, text)
        val outside = ask("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"config_read","arguments":{"folder":"$here","file":"../secrets.yml"}}}""")!!
        assertTrue(outside.containsKey("error"), "$outside")
    }

    @Test
    fun `an unknown method and an unknown tool answer an error, never a crash`() {
        assertTrue(ask("""{"jsonrpc":"2.0","id":3,"method":"nope"}""")!!.containsKey("error"))
        assertTrue(ask("""{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"nope","arguments":{}}}""")!!.containsKey("error"))
    }

    @Test
    fun `an assistant can ask which mods are known not to work together`() {
        val answer = callText("known_pairs", "{}")
        assertTrue(answer.contains("jade") && answer.contains("notenoughcrashes"), answer)
        assertTrue(answer.contains("https://"), "each pair says where it was established")
    }
}
