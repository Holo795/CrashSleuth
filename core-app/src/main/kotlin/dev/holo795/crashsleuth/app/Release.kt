package dev.holo795.crashsleuth.app

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Which version this is, and whether a newer one was published. The check is a plain request to GitHub
 * for the latest tag: nothing about this computer, this user or the files being analysed is sent, and it
 * only ever happens when someone asked for it (the app asks once, and remembers the answer).
 */
object Release {
    private const val LATEST = "https://api.github.com/repos/Holo795/CrashSleuth/releases/latest"
    const val PAGE = "https://github.com/Holo795/CrashSleuth/releases/latest"

    /**
     * The version written into the build, or null when this is not a released build: a working copy is a
     * snapshot, and nobody building from sources should be told that a release is newer than their own work.
     */
    val current: String? by lazy {
        Release::class.java.getResourceAsStream("/crashsleuth/version.txt")
            ?.use { it.readBytes().decodeToString().trim() }
            ?.takeIf { it.isNotEmpty() && it != "unspecified" && !it.contains("SNAPSHOT", ignoreCase = true) }
    }

    /** The newest published version, or null when the network is not there, which is not an error. */
    fun latest(): String? = runCatching {
        val request = HttpRequest.newBuilder(URI(LATEST))
            .header("User-Agent", "Holo795/CrashSleuth (github.com/Holo795/CrashSleuth)")
            .header("Accept", "application/vnd.github+json")
            .timeout(Duration.ofSeconds(10))
            .GET().build()
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        Regex(""""tag_name"\s*:\s*"v?([^"]+)"""").find(response.body())?.groupValues?.get(1)
    }.getOrNull()

    /**
     * True when [candidate] is a later version than [installed]. Numbers are compared as numbers, so 1.10
     * comes after 1.9; anything that is not a plain number (a `-beta`, a build hash) makes the version
     * older than the same one without it, the way people expect.
     */
    fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(version: String) = version.trim().removePrefix("v").split('.', '-', '+')
        val a = parts(candidate)
        val b = parts(installed)
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrNull(index)
            val right = b.getOrNull(index)
            val leftNumber = left?.toIntOrNull()
            val rightNumber = right?.toIntOrNull()
            when {
                leftNumber != null && rightNumber != null -> if (leftNumber != rightNumber) return leftNumber > rightNumber
                // 1.0.0 is newer than 1.0.0-beta: the one that stops here is the finished one.
                left == null -> return right?.toIntOrNull() == null
                right == null -> return left.toIntOrNull() != null
                left != right -> return left > right
            }
        }
        return false
    }
}
