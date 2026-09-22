package dev.holo795.crashsleuth.logs

/** One frame of a Java stack trace, with the jar it came from when the log gives it. */
data class Frame(
    val className: String,
    val method: String,
    val source: String?,
    /** Jar name printed by Log4j after the frame, for example `~[MyPlugin-1.2.jar:?]`. */
    val jar: String?,
    val lineIndex: Int,
    /** Java module of the frame, for example `sodium_service` in `LAYER SERVICE/sodium_service@0.8.13/...`. */
    val module: String? = null,
    val moduleVersion: String? = null,
) {
    val packageName: String get() = className.substringBeforeLast('.', "")
}

/** An exception with its frames, and the chain of `Caused by`. */
data class StackTrace(
    val type: String,
    val message: String,
    val frames: List<Frame>,
    val lineIndex: Int,
    val cause: StackTrace? = null,
) {
    /** The deepest cause, which is usually the real error. */
    val root: StackTrace get() = cause?.root ?: this

    fun chain(): List<StackTrace> = generateSequence(this) { it.cause }.toList()

    val headline: String get() = if (message.isBlank()) type else "$type: $message"
}

/** A log or crash report split into lines, with its stack traces extracted. */
class LogDocument(rawText: String) {
    /** Text without terminal colour codes, which consoles often keep. */
    val text: String = ANSI.replace(rawText, "")
    val lines: List<String> = text.lines()

    val isCrashReport: Boolean = lines.take(5).any { it.contains("---- Minecraft Crash Report ----") }

    val stackTraces: List<StackTrace> by lazy { extractStackTraces() }

    fun find(regex: Regex): MatchResult? = regex.find(text)

    fun findAll(regex: Regex): Sequence<MatchResult> = regex.findAll(text)

    fun lineContaining(fragment: String): String? = lines.firstOrNull { it.contains(fragment) }?.trim()

    /** Value of a `Key: value` line of a crash report section, for example `Minecraft Version`. */
    fun field(name: String): String? {
        val prefix = "$name:"
        return lines.firstNotNullOfOrNull { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith(prefix)) trimmed.removePrefix(prefix).trim().ifEmpty { null } else null
        }
    }

    /** Lines of a crash report section such as `-- Entity being ticked --`, until the next section. */
    fun section(title: String): List<String> {
        val start = lines.indexOfFirst { it.trim() == "-- $title --" }
        if (start < 0) return emptyList()
        return lines.drop(start + 1).takeWhile { !it.trim().startsWith("-- ") && !it.trim().startsWith("---- ") }
    }

    private fun extractStackTraces(): List<StackTrace> {
        val traces = mutableListOf<StackTrace>()
        var index = 0
        while (index < lines.size) {
            val header = EXCEPTION_HEADER.matchEntire(stripPrefix(lines[index]))
            if (header == null || !looksLikeTraceStart(index)) {
                index++
                continue
            }
            val (trace, next) = readTrace(index)
            traces += trace
            index = next
        }
        return traces
    }

    /** An exception line counts only if a stack frame follows it. */
    private fun looksLikeTraceStart(index: Int): Boolean =
        lines.drop(index + 1).take(3).any { FRAME.containsMatchIn(it) }

    private fun readTrace(start: Int): Pair<StackTrace, Int> = readTrace(start, stripPrefix(lines[start]))

    /** Reads the exception whose header is [headerText], its frames, then its `Caused by` chain. */
    private fun readTrace(start: Int, headerText: String): Pair<StackTrace, Int> {
        val header = EXCEPTION_HEADER.matchEntire(headerText.trim())
        val frames = mutableListOf<Frame>()
        var index = start + 1
        while (index < lines.size) {
            val line = lines[index]
            val frame = FRAME.find(line)
            when {
                frame != null -> {
                    frames += parseFrame(frame, line, index)
                    index++
                }
                MORE.containsMatchIn(line) -> index++
                else -> break
            }
        }
        var cause: StackTrace? = null
        val causedBy = lines.getOrNull(index)?.let { CAUSED_BY.find(it) }
        if (causedBy != null) {
            val (nested, next) = readTrace(index, causedBy.groupValues[1])
            cause = nested
            index = next
        }
        return StackTrace(
            type = header?.groupValues?.get(1) ?: headerText.substringBefore(':').trim(),
            message = header?.groupValues?.get(2)?.trim() ?: headerText.substringAfter(':', "").trim(),
            frames = frames,
            lineIndex = start,
            cause = cause,
        ) to index
    }

    /**
     * `at com.foo.Bar.baz(Bar.java:1)`, `at java.base/java.lang.Thread.run(...)` or
     * `at LAYER SERVICE/sodium_service@0.8.13+mc1.21.1/net.caffeinemc.Foo.bar(...)`.
     */
    private fun parseFrame(frame: MatchResult, line: String, index: Int): Frame {
        val segments = frame.groupValues[1].split('/')
        val qualified = segments.last()
        val moduleSegment = segments.dropLast(1).lastOrNull()
        // Paper names plugin class loaders after their jar: `at MyPlugin-1.0.jar/com.foo.Bar.baz(...)`.
        val loaderJar = segments.dropLast(1).firstOrNull { it.endsWith(".jar") }
        val module = moduleSegment?.substringBefore('@')?.takeIf { it.isNotBlank() && !it.contains(' ') && loaderJar == null }
        val moduleVersion = moduleSegment?.takeIf { it.contains('@') }?.substringAfter('@')
        return Frame(
            className = qualified.substringBeforeLast('.'),
            method = qualified.substringAfterLast('.'),
            source = frame.groupValues[2].ifEmpty { null },
            jar = loaderJar ?: JAR.find(line.substring(frame.range.last))?.groupValues?.get(1)?.trim(),
            lineIndex = index,
            module = module,
            moduleVersion = moduleVersion,
        )
    }

    /** Removes the Log4j prefix such as `[12:00:00] [main/ERROR]: ` and `Exception in thread "main" `. */
    internal fun stripPrefix(line: String): String = THREAD_PREFIX.replace(LOG_PREFIX.replace(line, "").trim(), "").trim()

    companion object {
        private val LOG_PREFIX = Regex("""^\s*(\[[^]]*]\s*)*(?:(?<=])\s*:)?\s*""")
        private val EXCEPTION_HEADER = Regex("""((?:[a-zA-Z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error|Throwable|Failure|Crash[\w$]*))(?::\s*(.*))?""")
        // "knot//net.minecraft..." in Fabric crash reports: a module prefix can end with several slashes.
        private val FRAME = Regex("""^\s*at\s+((?:[\w .+@-]+/+)*[\w$.<>\[\]-]+)\((.*?)\)""")
        private val ANSI = Regex("""\u001B\[[0-9;]*m""")
        private val THREAD_PREFIX = Regex("""^Exception in thread "[^"]*"\s*""")
        private val JAR = Regex("""[~\[]\s*\[?([^\[\]:{}]+\.jar)""")
        private val MORE = Regex("""^\s*\.\.\.\s*\d+\s*more""")
        private val CAUSED_BY = Regex("""^\s*Caused by:\s*(.+)$""")
    }
}
