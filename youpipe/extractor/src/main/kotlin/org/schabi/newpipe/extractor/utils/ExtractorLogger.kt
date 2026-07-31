package org.schabi.newpipe.extractor.utils

/**
 * Logging class for outputting logs from the extractor to the desired output<br></br>
 * Intended to be used in the same manner as Android's `Log`:<br></br>
 * `ExtractorLogger.d("Hello my name Jeff")`<br></br>
 * <br></br>
 * Also supports formatted arguments:<br></br>
 * `ExtractorLogger.d("Hello my name is {Name} {}", name, surname)`
 */
object ExtractorLogger {

    private val EMPTY_LOGGER: Logger = EmptyLogger()

    @Volatile
    private var logger: Logger = EMPTY_LOGGER

    /**
     * Set the Logger that you want the extractor logs to be logged to
     *
     * Provide an implementation of the [Logger] interface for each method and whenever the
     * extractor code calls `ExtractorLogger.d/w/e`
     * it will be routed through to `customLogger`
     *
     * For NewPipe, this should be set at the start of the application ideally in
     * `MainActivity.onCreate`, but absolutely before any extractor code can run
     */
    @JvmStatic
    fun setLogger(customLogger: Logger?) {
        logger = customLogger ?: EMPTY_LOGGER
    }

    enum class Level { DEBUG, WARN, ERROR }

    @Suppress("UNCHECKED_CAST")
    private fun log(level: Level, tag: String, message: String, t: Throwable?) {
        if (logger === EMPTY_LOGGER) return
        when (level) {
            Level.DEBUG -> if (t == null) logger.debug(tag, message) else logger.debug(tag, message, t)
            Level.WARN -> if (t == null) logger.warn(tag, message) else logger.warn(tag, message, t)
            Level.ERROR -> if (t == null) logger.error(tag, message) else logger.error(tag, message, t)
        }
    }

    private fun logFormat(level: Level, tag: String, t: Throwable?, template: String, vararg args: Any?) {
        if (logger === EMPTY_LOGGER) return
        log(level, tag, format(template, *args), t)
    }

    // DEBUG
    @JvmStatic
    fun d(tag: String, msg: String) {
        log(Level.DEBUG, tag, msg, null)
    }

    @JvmStatic
    fun d(tag: String, msg: String, t: Throwable) {
        log(Level.DEBUG, tag, msg, t)
    }

    @JvmStatic
    fun d(tag: String, template: String, vararg args: Any?) {
        logFormat(Level.DEBUG, tag, null, template, *args)
    }

    @JvmStatic
    fun d(tag: String, t: Throwable, template: String, vararg args: Any?) {
        logFormat(Level.DEBUG, tag, t, template, *args)
    }

    // WARN
    @JvmStatic
    fun w(tag: String, msg: String) {
        log(Level.WARN, tag, msg, null)
    }

    @JvmStatic
    fun w(tag: String, msg: String, t: Throwable) {
        log(Level.WARN, tag, msg, t)
    }

    @JvmStatic
    fun w(tag: String, template: String, vararg args: Any?) {
        logFormat(Level.WARN, tag, null, template, *args)
    }

    @JvmStatic
    fun w(tag: String, t: Throwable, template: String, vararg args: Any?) {
        logFormat(Level.WARN, tag, t, template, *args)
    }

    // ERROR
    @JvmStatic
    fun e(tag: String, msg: String) {
        log(Level.ERROR, tag, msg, null)
    }

    @JvmStatic
    fun e(tag: String, msg: String, t: Throwable) {
        log(Level.ERROR, tag, msg, t)
    }

    @JvmStatic
    fun e(tag: String, template: String, vararg args: Any?) {
        logFormat(Level.ERROR, tag, null, template, *args)
    }

    @JvmStatic
    fun e(tag: String, t: Throwable, template: String, vararg args: Any?) {
        logFormat(Level.ERROR, tag, t, template, *args)
    }

    /**
     * Simple string format method for easier logging in the form of
     * `ExtractorLogger.d("Hello my name {Name} {}", name, surname)`<br></br><br></br>
     * Braces can be escaped by double braces:
     * `{{ -> {` and `}} -> }`
     * @param template The template string to format
     * @param args Arguments to replace identifiers with in `template`
     * @return Formatted string with arguments replaced
     */
    private fun format(template: String?, vararg args: Any?): String {
        if (template == null || args.isEmpty()) {
            return template ?: ""
        }
        val result = StringBuilder(template.length + minOf(32, 16 * args.size))
        var cursorIndex = 0
        var argIndex = 0
        val n = template.length
        while (cursorIndex < n) {
            val ch = template[cursorIndex]

            if (ch == '{' && cursorIndex + 1 < n && template[cursorIndex + 1] == '{') {
                result.append('{')
                cursorIndex += 2
                continue
            }

            if (ch == '}' && cursorIndex + 1 < n && template[cursorIndex + 1] == '}') {
                result.append('}')
                cursorIndex += 2
                continue
            }

            if (ch == '{') {
                val closeBraceIndex = template.indexOf('}', cursorIndex + 1)
                if (closeBraceIndex < 0) {
                    result.append(template, cursorIndex, n)
                    break
                }
                if (argIndex < args.size) {
                    result.append(args[argIndex++])
                } else {
                    result.append(template, cursorIndex, closeBraceIndex + 1)
                }
                cursorIndex = closeBraceIndex + 1
                continue
            }

            result.append(ch)
            cursorIndex++
        }
        return result.toString()
    }

    private class EmptyLogger : Logger {
        override fun debug(tag: String, message: String) {}
        override fun debug(tag: String, message: String, throwable: Throwable) {}
        override fun warn(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable) {}
        override fun error(tag: String, message: String) {}
        override fun error(tag: String, message: String, throwable: Throwable) {}
    }

    class ConsoleLogger : Logger {
        override fun debug(tag: String, message: String) {
            println("[DEBUG][$tag] $message")
        }

        override fun debug(tag: String, message: String, throwable: Throwable) {
            debug(tag, message)
            throwable.printStackTrace(System.err)
        }

        override fun warn(tag: String, message: String) {
            println("[WARN ][$tag] $message")
        }

        override fun warn(tag: String, message: String, throwable: Throwable) {
            warn(tag, message)
            throwable.printStackTrace(System.err)
        }

        override fun error(tag: String, message: String) {
            System.err.println("[ERROR][$tag] $message")
        }

        override fun error(tag: String, message: String, throwable: Throwable) {
            System.err.println("[ERROR][$tag] $message")
            throwable.printStackTrace(System.err)
        }
    }
}
