package dev.holo795.crashsleuth.app

import dev.holo795.crashsleuth.model.Platform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Settings CrashSleuth knows for real, with the values each one accepts: what a person may be told to change,
 * and the only things an assistant is allowed to suggest. Every entry was used or seen in the lab.
 */
@Serializable
data class Setting(
    /** Where it lives: "config/paper-global.yml", "server.properties"... */
    val file: String,
    val key: String,
    /** The values it accepts; empty when it is free text or a number. */
    val values: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val what: String,
)

object KnownSettings {
    private val json = Json { ignoreUnknownKeys = true }

    val all: List<Setting> by lazy {
        json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(Setting.serializer()),
            KnownSettings::class.java.getResource("/crashsleuth/settings.json")!!.readText(),
        )
    }

    /** The settings that make sense for a platform (all of them when it is unknown). */
    fun forPlatform(platform: Platform?): List<Setting> {
        val name = platform?.name?.lowercase() ?: return all
        return all.filter { it.platforms.isEmpty() || name in it.platforms }
    }

    /** One line per setting, as an assistant should read them. */
    fun describe(settings: List<Setting> = all): String = settings.joinToString("\n") { setting ->
        val values = if (setting.values.isEmpty()) "" else " (values: ${setting.values.joinToString(", ")})"
        "${setting.file} -> ${setting.key}$values: ${setting.what}"
    }
}
