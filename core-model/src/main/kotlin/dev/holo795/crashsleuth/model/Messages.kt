package dev.holo795.crashsleuth.model

import java.util.Locale
import java.util.MissingResourceException
import java.util.ResourceBundle

/** Human texts for situations and advice, in English by default and in French. */
class Messages(val locale: Locale = Locale.getDefault()) {
    // No fallback to the system locale: asking for English on a French system must give English.
    private val bundle: ResourceBundle = ResourceBundle.getBundle(
        "crashsleuth/messages",
        locale,
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )

    fun get(key: String, vararg args: Any?): String {
        val pattern = try {
            bundle.getString(key)
        } catch (_: MissingResourceException) {
            return key
        }
        // Plain {0}, {1}... replacement: MessageFormat treats apostrophes as quotes, and French is full of them.
        return args.foldIndexed(pattern) { index, text, arg -> text.replace("{$index}", arg?.toString() ?: "") }
    }

    fun title(situation: Situation): String = get("situation.${situation.name}.title")

    fun advice(situation: Situation, vararg args: Any?): String = get("situation.${situation.name}.advice", *args)

    companion object {
        fun forLanguage(language: String?): Messages =
            if (language.isNullOrBlank()) Messages() else Messages(Locale.forLanguageTag(language))
    }
}
