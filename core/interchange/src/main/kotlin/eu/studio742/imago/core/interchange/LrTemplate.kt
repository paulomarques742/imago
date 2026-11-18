package eu.studio742.imago.core.interchange

/**
 * Reads the old preset format, a Lua table, into the fields the XMP converter already understands.
 *
 * It never runs anything: this is a reader for the data subset of the language — tables, strings,
 * numbers, booleans and comments — and a function call, a variable or an operator makes the file
 * unreadable. A file from a stranger gets the same limits as an XMP packet: bounded depth and size.
 */
private const val MAX_DEPTH = 24
private const val MAX_NODES = 200_000

private sealed interface Lua {
    data class Text(val value: String) : Lua
    data class Number(val text: String) : Lua
    data class Bool(val value: Boolean) : Lua
    data object Nil : Lua
    class Table(val items: List<Lua>, val named: Map<String, Lua>) : Lua
}

private class LuaRefused : Exception()

private class LuaReader(private val text: String) {
    private var at = 0
    private var nodes = 0

    fun file(): Lua.Table {
        skip()
        val name = identifier()
        skip()
        if (name.isEmpty() || !take('=')) throw LuaRefused()
        val table = value(0) as? Lua.Table ?: throw LuaRefused()
        skip()
        take(';')
        skip()
        if (at != text.length) throw LuaRefused()
        return table
    }

    private fun value(depth: Int): Lua {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES) throw LuaRefused()
        skip()
        val c = peek() ?: throw LuaRefused()
        return when {
            c == '{' -> table(depth)
            c == '"' || c == '\'' -> Lua.Text(quoted(c))
            c == '[' -> Lua.Text(longString())
            c == '-' || c == '.' || c.isDigit() -> Lua.Number(number())
            else -> when (identifier()) {
                "true" -> Lua.Bool(true)
                "false" -> Lua.Bool(false)
                "nil" -> Lua.Nil
                else -> throw LuaRefused()
            }
        }
    }

    private fun table(depth: Int): Lua.Table {
        take('{')
        val items = mutableListOf<Lua>()
        val named = linkedMapOf<String, Lua>()
        while (true) {
            skip()
            if (take('}')) break
            val c = peek() ?: throw LuaRefused()
            when {
                c == '[' && !isLongStringStart() -> {
                    take('[')
                    val key = when (val k = value(depth + 1)) {
                        is Lua.Text -> k.value
                        is Lua.Number -> k.text
                        else -> throw LuaRefused()
                    }
                    skip()
                    if (!take(']')) throw LuaRefused()
                    skip()
                    if (!take('=')) throw LuaRefused()
                    named[key] = value(depth + 1)
                }
                c.isLetter() || c == '_' -> {
                    val start = at
                    val name = identifier()
                    skip()
                    if (peek() == '=' && text.getOrNull(at + 1) != '=') {
                        take('=')
                        named[name] = value(depth + 1)
                    } else {
                        // A bare word as a value: true, false, nil, or something this reader refuses.
                        at = start
                        items += value(depth + 1)
                    }
                }
                else -> items += value(depth + 1)
            }
            skip()
            if (!take(',') && !take(';')) {
                skip()
                if (peek() != '}') throw LuaRefused()
            }
        }
        return Lua.Table(items, named)
    }

    private fun quoted(quote: Char): String {
        take(quote)
        val out = StringBuilder()
        while (true) {
            val c = text.getOrNull(at++) ?: throw LuaRefused()
            when {
                c == quote -> return out.toString()
                c == '\n' -> throw LuaRefused()
                c == '\\' -> {
                    val e = text.getOrNull(at++) ?: throw LuaRefused()
                    when {
                        e == 'n' -> out.append('\n')
                        e == 't' -> out.append('\t')
                        e == 'r' -> out.append('\r')
                        e.isDigit() -> {
                            var code = e.digitToInt()
                            repeat(2) { if (text.getOrNull(at)?.isDigit() == true) code = code * 10 + text[at++].digitToInt() }
                            if (code > 0xFFFF) throw LuaRefused()
                            out.append(code.toChar())
                        }
                        else -> out.append(e)
                    }
                }
                else -> out.append(c)
            }
        }
    }

    private fun isLongStringStart(): Boolean {
        if (peek() != '[') return false
        var i = at + 1
        while (text.getOrNull(i) == '=') i++
        return text.getOrNull(i) == '['
    }

    private fun longString(): String {
        if (!isLongStringStart()) throw LuaRefused()
        take('[')
        var level = 0
        while (take('=')) level++
        take('[')
        val close = "]" + "=".repeat(level) + "]"
        val end = text.indexOf(close, at)
        if (end < 0) throw LuaRefused()
        val body = text.substring(at, end).removePrefix("\r").removePrefix("\n")
        at = end + close.length
        return body
    }

    private fun number(): String {
        val start = at
        if (peek() == '-') at++
        while (peek()?.let { it.isDigit() || it == '.' } == true) at++
        if (peek() == 'e' || peek() == 'E') {
            at++
            if (peek() == '+' || peek() == '-') at++
            while (peek()?.isDigit() == true) at++
        }
        val result = text.substring(start, at)
        if (result.toDoubleOrNull() == null) throw LuaRefused()
        return result
    }

    private fun identifier(): String {
        val start = at
        while (peek()?.let { it.isLetterOrDigit() || it == '_' } == true) at++
        return text.substring(start, at)
    }

    private fun skip() {
        while (true) {
            val c = peek() ?: return
            when {
                c.isWhitespace() || c == '﻿' -> at++
                c == '-' && text.getOrNull(at + 1) == '-' -> {
                    at += 2
                    if (isLongStringStart()) {
                        longString()
                    } else {
                        while (peek()?.let { it != '\n' } == true) at++
                    }
                }
                else -> return
            }
        }
    }

    private fun peek(): Char? = text.getOrNull(at)

    private fun take(c: Char): Boolean = if (peek() == c) { at++; true } else false
}

/** The text of a `.lrtemplate`, or null when these bytes are not one. */
internal fun lrtemplateText(bytes: ByteArray): String? {
    val text = runCatching { bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("﻿") }.getOrNull() ?: return null
    return text.takeIf { LRTEMPLATE_START.containsMatchIn(it.take(256)) && "settings" in it }
}

private val LRTEMPLATE_START = Regex("""^\s*(--[^\n]*\n\s*)*s\s*=\s*\{""")

/** What a preset in the old format holds, in the fields of an XMP packet; null when it cannot be read. */
internal fun readLrtemplateFields(text: String): XmpFields? {
    val root = runCatching { LuaReader(text).file() }.getOrNull() ?: return null
    val value = root.named["value"] as? Lua.Table ?: return null
    val settings = value.named["settings"] as? Lua.Table ?: return null

    val values = linkedMapOf<String, String>()
    val lists = linkedMapOf<String, List<String>>()
    (root.named["title"] as? Lua.Text ?: root.named["internalName"] as? Lua.Text)?.let { values["Name"] = it.value }

    settings.named.forEach { (key, field) ->
        when (field) {
            is Lua.Text -> values[key] = field.value
            is Lua.Number -> values[key] = field.text
            is Lua.Bool -> values[key] = if (field.value) "True" else "False"
            is Lua.Nil -> Unit
            is Lua.Table -> lists[key] = field.listed(key)
        }
    }
    return XmpFields(values, lists)
}

/**
 * A curve is a flat run of numbers in this format and pairs in an XMP packet; every other table is only
 * ever asked how many things it holds, so a table inside a table is one empty item.
 */
private fun Lua.Table.listed(key: String): List<String> {
    val numbers = items.mapNotNull { (it as? Lua.Number)?.text }
    if (key.startsWith("ToneCurve") && numbers.size == items.size && items.size % 2 == 0) {
        return numbers.chunked(2) { (x, y) -> "$x, $y" }
    }
    val inner = items.map { (it as? Lua.Text)?.value ?: (it as? Lua.Number)?.text ?: "" }
    return if (inner.isEmpty() && named.isNotEmpty()) listOf("") else inner
}
