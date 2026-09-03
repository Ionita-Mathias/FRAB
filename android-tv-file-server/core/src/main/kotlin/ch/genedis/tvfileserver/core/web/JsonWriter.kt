package ch.genedis.tvfileserver.core.web

/**
 * Minimal streaming JSON writer.
 *
 * The core module deliberately avoids a JSON library: the payloads are small and entirely
 * server-generated, and a reflection-free writer keeps the APK small and R8-friendly.
 * The writer tracks nesting so separators are emitted correctly.
 */
class JsonWriter(private val sb: StringBuilder = StringBuilder(256)) {

    private val stack = ArrayList<Scope>(8)

    private class Scope(val isObject: Boolean) {
        var empty = true
    }

    fun beginObject(): JsonWriter {
        prepareValue()
        sb.append('{')
        stack.add(Scope(isObject = true))
        return this
    }

    fun endObject(): JsonWriter {
        val scope = stack.removeLastOrNull() ?: error("endObject without beginObject")
        check(scope.isObject) { "endObject closing an array" }
        sb.append('}')
        return this
    }

    fun beginArray(): JsonWriter {
        prepareValue()
        sb.append('[')
        stack.add(Scope(isObject = false))
        return this
    }

    fun endArray(): JsonWriter {
        val scope = stack.removeLastOrNull() ?: error("endArray without beginArray")
        check(!scope.isObject) { "endArray closing an object" }
        sb.append(']')
        return this
    }

    /** Writes an object member name. Must be followed by exactly one value. */
    fun name(name: String): JsonWriter {
        val scope = stack.lastOrNull() ?: error("name outside of an object")
        check(scope.isObject) { "name inside an array" }
        if (!scope.empty) sb.append(',')
        scope.empty = false
        appendQuoted(name)
        sb.append(':')
        pendingName = true
        return this
    }

    fun value(value: String?): JsonWriter {
        if (value == null) return nullValue()
        prepareValue()
        appendQuoted(value)
        return this
    }

    fun value(value: Long): JsonWriter {
        prepareValue()
        sb.append(value)
        return this
    }

    fun value(value: Int): JsonWriter = value(value.toLong())

    fun value(value: Boolean): JsonWriter {
        prepareValue()
        sb.append(if (value) "true" else "false")
        return this
    }

    fun nullValue(): JsonWriter {
        prepareValue()
        sb.append("null")
        return this
    }

    /** Inserts an already-serialised JSON fragment. The caller guarantees it is valid. */
    fun rawValue(json: String): JsonWriter {
        prepareValue()
        sb.append(json)
        return this
    }

    override fun toString(): String = sb.toString()

    private var pendingName = false

    /** Emits the separator required before the next value. */
    private fun prepareValue() {
        if (pendingName) {
            pendingName = false
            return
        }
        val scope = stack.lastOrNull() ?: return
        check(!scope.isObject) { "object value written without a name" }
        if (!scope.empty) sb.append(',')
        scope.empty = false
    }

    private fun appendQuoted(text: String) {
        sb.append('"')
        appendEscaped(sb, text)
        sb.append('"')
    }

    companion object {

        /** Escapes [text] for inclusion in a JSON string, without the surrounding quotes. */
        fun escape(text: String): String {
            val builder = StringBuilder(text.length + 16)
            appendEscaped(builder, text)
            return builder.toString()
        }

        /** Builds a JSON object. */
        inline fun obj(build: JsonWriter.() -> Unit): String {
            val writer = JsonWriter()
            writer.beginObject()
            writer.build()
            writer.endObject()
            return writer.toString()
        }

        /** Builds a JSON array. */
        inline fun arr(build: JsonWriter.() -> Unit): String {
            val writer = JsonWriter()
            writer.beginArray()
            writer.build()
            writer.endArray()
            return writer.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()

        private fun appendEscaped(builder: StringBuilder, text: String) {
            for (ch in text) {
                when {
                    ch == '"' -> builder.append("\\\"")
                    ch == '\\' -> builder.append("\\\\")
                    ch == '\n' -> builder.append("\\n")
                    ch == '\r' -> builder.append("\\r")
                    ch == '\t' -> builder.append("\\t")
                    ch == '\b' -> builder.append("\\b")
                    ch == '\u000C' -> builder.append("\\f")
                    // File names are attacker-supplied. Escaping the HTML-significant
                    // characters keeps the payload valid JSON while making it inert even if
                    // it is ever inlined into a page instead of being fetched as JSON.
                    // U+2028 / U+2029 are valid JSON but terminate JavaScript string literals.
                    ch.code < 0x20 || ch == '<' || ch == '>' || ch == '&' ||
                        ch == '\u2028' || ch == '\u2029' -> {
                        builder.append("\\u")
                        builder.append(HEX[(ch.code shr 12) and 0xF])
                        builder.append(HEX[(ch.code shr 8) and 0xF])
                        builder.append(HEX[(ch.code shr 4) and 0xF])
                        builder.append(HEX[ch.code and 0xF])
                    }
                    else -> builder.append(ch)
                }
            }
        }
    }
}
