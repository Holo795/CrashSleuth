package dev.holo795.crashsleuth.app

/**
 * Removes from a text what points at a person before it leaves the computer (share links, a local AI):
 * the user's folder in paths on macOS, Linux and Windows, e-mail addresses, IP addresses and UUIDs.
 */
object Privacy {
    private val HOME = System.getProperty("user.home").orEmpty()
    private val USER_PATH = Regex("""(?i)(/Users/|/home/|[A-Z]:\\Users\\|[A-Z]:/Users/)[^/\\\s:'"]+""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")
    private val IPV4 = Regex("""\b(?!127\.0\.0\.1\b)(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val UUID = Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""")

    fun clean(text: String): String {
        var result = if (HOME.length > 3) text.replace(HOME, "~") else text
        result = USER_PATH.replace(result) { it.groupValues[1] + "<user>" }
        result = EMAIL.replace(result, "<email>")
        result = IPV4.replace(result, "<ip>")
        return UUID.replace(result, "<uuid>")
    }
}
