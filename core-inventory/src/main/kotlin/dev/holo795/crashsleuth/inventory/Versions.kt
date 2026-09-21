package dev.holo795.crashsleuth.inventory

/**
 * Version comparison and range matching, for the two syntaxes found in mod metadata: Fabric/Quilt
 * ("&gt;=1.21 &lt;1.21.2", "~1.20", "1.21.x", lists of alternatives) and Maven ("[1.21.1,1.22)").
 * Anything that cannot be parsed matches: a wrong guess must never accuse a mod.
 */
object Versions {
    /** Numeric parts of a version, pre-release and build suffixes removed ("1.21-rc.1" gives [1, 21]). */
    private fun parts(version: String): List<Int>? {
        val core = version.trim().removePrefix("v").substringBefore('+').substringBefore('-')
        if (core.isEmpty()) return null
        return core.split('.').map { it.toIntOrNull() ?: return null }
    }

    private fun isPreRelease(version: String) = version.substringBefore('+').contains('-')

    fun compare(a: String, b: String): Int? {
        val left = parts(a) ?: return null
        val right = parts(b) ?: return null
        for (i in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (difference != 0) return difference
        }
        // Same numbers: a pre-release comes before the release.
        return when {
            isPreRelease(a) == isPreRelease(b) -> 0
            isPreRelease(a) -> -1
            else -> 1
        }
    }

    /** True when [version] satisfies [range], or when either cannot be understood. */
    fun matches(version: String, range: String?): Boolean {
        if (range.isNullOrBlank()) return true
        if (parts(version) == null) return true
        val text = range.trim()
        return if (text.startsWith("[") || text.startsWith("(")) maven(version, text) else semver(version, text)
    }

    private fun semver(version: String, range: String): Boolean =
        range.split("||").any { alternative ->
            alternative.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.all { predicate(version, it) }
        }

    private fun predicate(version: String, predicate: String): Boolean {
        if (predicate == "*" || predicate.equals("x", ignoreCase = true)) return true
        val operator = Regex("^(>=|<=|>|<|=|~|\\^)?").find(predicate)!!.value
        val target = predicate.removePrefix(operator)
        if (target.contains('x', ignoreCase = true) || target.contains('*')) {
            // 1.21.x: every version starting with 1.21
            val prefix = target.split('.').takeWhile { it != "x" && it != "X" && it != "*" }
            val actual = parts(version) ?: return true
            return prefix.withIndex().all { (i, part) -> part.toIntOrNull()?.let { actual.getOrElse(i) { 0 } == it } ?: true }
        }
        val order = compare(version, target) ?: return true
        return when (operator) {
            ">=" -> order >= 0
            "<=" -> order <= 0
            ">" -> order > 0
            "<" -> order < 0
            "~" -> order >= 0 && below(version, target, keep = 2)
            "^" -> order >= 0 && below(version, target, keep = 1)
            else -> order == 0
        }
    }

    /** ~1.20.1 allows 1.20.x, ^1.20.1 allows 1.x. */
    private fun below(version: String, target: String, keep: Int): Boolean {
        val bound = parts(target) ?: return true
        val actual = parts(version) ?: return true
        return bound.take(minOf(keep, bound.size)).withIndex().all { (i, part) -> actual.getOrElse(i) { 0 } == part }
    }

    /**
     * Forge and NeoForge in practice accept a Minecraft patch release outside the declared range (JEI for
     * 1.21.1 declares "[1.21, 1.21.1)" and loads fine on 1.21.1), so the release family is accepted too.
     */
    private fun maven(version: String, range: String): Boolean {
        val intervals = Regex("""[\[(][^\])]*[\])]""").findAll(range).map { it.value }.toList()
        val family = parts(version)?.take(2)?.joinToString(".")
        return intervals.any { interval(version, it) || (family != null && interval(family, it)) }
    }

    private fun interval(version: String, interval: String): Boolean {
        val inclusiveLow = interval.startsWith("[")
        val inclusiveHigh = interval.endsWith("]")
        val body = interval.substring(1, interval.length - 1)
        if (!body.contains(',')) return body.isBlank() || compare(version, body) == 0
        val low = body.substringBefore(',').trim()
        val high = body.substringAfter(',').trim()
        if (low.isNotEmpty()) {
            val order = compare(version, low) ?: return true
            if (order < 0 || (order == 0 && !inclusiveLow)) return false
        }
        if (high.isNotEmpty()) {
            val order = compare(version, high) ?: return true
            if (order > 0 || (order == 0 && !inclusiveHigh)) return false
        }
        return true
    }
}
