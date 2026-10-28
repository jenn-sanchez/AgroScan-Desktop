package org.agroscan.project.features.telemetry

/**
 * Lector mínimo de JSON para los paquetes del teléfono: devuelve Map, List, String, Double, Boolean o null. Se escribió aquí
 * para no añadir una librería solo para esto; lanza [IllegalArgumentException] con cualquier texto que no sea JSON válido.
 */
internal object MiniJson {

    private const val MAX_DEPTH = 8   // los paquetes son planos (a lo sumo una lista); más profundidad es basura

    fun parse(text: String): Any? {
        val r = Reader(text)
        val value = r.value(0)
        r.skipSpaces()
        require(r.i == text.length) { "Texto sobrante después del JSON" }
        return value
    }

    private class Reader(val s: String) {
        var i = 0

        fun skipSpaces() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "JSON demasiado anidado" }
            skipSpaces()
            require(i < s.length) { "JSON incompleto" }
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> list(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("Carácter inesperado '$c' en $i")
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            i++   // {
            val out = LinkedHashMap<String, Any?>()
            skipSpaces()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipSpaces()
                require(peek() == '"') { "Se esperaba un nombre de campo en $i" }
                val key = string()
                skipSpaces()
                require(peek() == ':') { "Se esperaba ':' en $i" }
                i++
                out[key] = value(depth + 1)
                skipSpaces()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("Se esperaba ',' o '}' en $i")
                }
            }
        }

        private fun list(depth: Int): List<Any?> {
            i++   // [
            val out = ArrayList<Any?>()
            skipSpaces()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(value(depth + 1))
                skipSpaces()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("Se esperaba ',' o ']' en $i")
                }
            }
        }

        private fun string(): String {
            i++   // comilla de apertura
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "Texto sin cerrar" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "Escape incompleto" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "Escape \\u incompleto" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("Escape desconocido \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw IllegalArgumentException("Número inválido en $start")
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "Se esperaba '$word' en $i" }
            i += word.length
            return value
        }

        private fun peek(): Char = if (i < s.length) s[i] else throw IllegalArgumentException("JSON incompleto")
    }
}
