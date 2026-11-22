package com.morgana.tilegen.spec

/**
 * Thrown when a JSON document or tileset specification fails parsing or validation.
 */
class SpecException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Lightweight, pure-Kotlin JSON AST and recursive-descent parser with zero external dependencies.
 */
internal sealed interface JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double, val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

internal class JsonParser(private val input: String) {
    private var pos = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (pos < input.length) {
            throw error("Unexpected trailing character '${input[pos]}'")
        }
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (pos >= input.length) throw error("Unexpected end of JSON input")
        return when (val ch = input[pos]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.Str(parseString())
            't', 'f' -> parseBoolean()
            'n' -> parseNull()
            '-', in '0'..'9' -> parseNumber()
            else -> throw error("Unexpected character '$ch'")
        }
    }

    private fun parseObject(): JsonValue.Obj {
        expect('{')
        skipWhitespace()
        val map = linkedMapOf<String, JsonValue>()
        if (peek() == '}') {
            pos++
            return JsonValue.Obj(map)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') throw error("Expected string key in object")
            val key = parseString()
            skipWhitespace()
            expect(':')
            val value = parseValue()
            map[key] = value
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonValue.Obj(map)
                }
                else -> throw error("Expected ',' or '}' in object")
            }
        }
    }

    private fun parseArray(): JsonValue.Arr {
        expect('[')
        skipWhitespace()
        val items = mutableListOf<JsonValue>()
        if (peek() == ']') {
            pos++
            return JsonValue.Arr(items)
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            when (peek()) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonValue.Arr(items)
                }
                else -> throw error("Expected ',' or ']' in array")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < input.length) {
            val ch = input[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= input.length) throw error("Unterminated escape sequence")
                    when (val esc = input[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > input.length) throw error("Invalid unicode escape")
                            val hex = input.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: throw error("Invalid unicode escape '\\u$hex'")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw error("Invalid escape '\\$esc'")
                    }
                }
                else -> {
                    if (ch.code < 0x20) throw error("Unescaped control character in string")
                    sb.append(ch)
                }
            }
        }
        throw error("Unterminated string literal")
    }

    private fun parseBoolean(): JsonValue.Bool =
        if (input.startsWith("true", pos)) {
            pos += 4
            JsonValue.Bool(true)
        } else if (input.startsWith("false", pos)) {
            pos += 5
            JsonValue.Bool(false)
        } else {
            throw error("Invalid boolean literal")
        }

    private fun parseNull(): JsonValue.Null =
        if (input.startsWith("null", pos)) {
            pos += 4
            JsonValue.Null
        } else {
            throw error("Invalid null literal")
        }

    private fun parseNumber(): JsonValue.Num {
        val start = pos
        if (peek() == '-') pos++
        if (pos >= input.length || input[pos] !in '0'..'9') throw error("Invalid number")
        if (input[pos] == '0') {
            pos++
        } else {
            while (pos < input.length && input[pos] in '0'..'9') pos++
        }
        if (pos < input.length && input[pos] == '.') {
            pos++
            if (pos >= input.length || input[pos] !in '0'..'9') throw error("Invalid fractional number")
            while (pos < input.length && input[pos] in '0'..'9') pos++
        }
        if (pos < input.length && (input[pos] == 'e' || input[pos] == 'E')) {
            pos++
            if (pos < input.length && (input[pos] == '+' || input[pos] == '-')) pos++
            if (pos >= input.length || input[pos] !in '0'..'9') throw error("Invalid exponent in number")
            while (pos < input.length && input[pos] in '0'..'9') pos++
        }
        val raw = input.substring(start, pos)
        val num = raw.toDoubleOrNull() ?: throw error("Invalid number '$raw'")
        return JsonValue.Num(num, raw)
    }

    private fun skipWhitespace() {
        while (pos < input.length && input[pos] in charArrayOf(' ', '\t', '\r', '\n')) {
            pos++
        }
    }

    private fun peek(): Char? = if (pos < input.length) input[pos] else null

    private fun expect(expected: Char) {
        if (pos >= input.length || input[pos] != expected) {
            throw error("Expected '$expected'")
        }
        pos++
    }

    private fun error(msg: String): SpecException {
        val prefix = input.substring(0, pos.coerceAtMost(input.length))
        val line = prefix.count { it == '\n' } + 1
        val col = prefix.length - prefix.lastIndexOf('\n')
        return SpecException("JSON parse error at line $line, column $col: $msg")
    }
}
