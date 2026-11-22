package com.morgana.domain.game

import java.math.BigInteger

/** A formula that cannot be read: [position] is the 0-based offset into the source, or -1 when it applies to the whole formula. */
class FormulaException(message: String, val position: Int = -1) : Exception(message)

/** A formula that could not be evaluated for the given inputs (overflow, division by zero, an unknown variable, ...). */
class FormulaEvaluationException(message: String) : Exception(message)

/**
 * A formula in the game definition's small expression language: a pure function of named integer inputs.
 *
 * ```
 * raw = a.str*2 + weapon + a.agi/3;
 * mit = d.def + armor/2 + d.vit/4;
 * base = max(1, raw - mit);
 * crit ? max(2, (3*base + 1)/2) : base
 * ```
 *
 * - **Values** are 64-bit integers. Comparisons and logic give 0 or 1; any non-zero value is true. There is no floating point.
 * - **Operators**, high to low: unary `-` `!`; `*` `/` `%`; `+` `-`; `<` `<=` `>` `>=`; `==` `!=`; `&&`; `||`; `c ? x : y`.
 *   `/` and `%` truncate toward zero, like Kotlin. `&&`, `||` and `?:` evaluate only what they need.
 * - **Steps:** any number (up to [MAX_STEPS]) of `name = expression;` before the final expression, each usable by what follows.
 * - **Functions:** `min`, `max` (one or more arguments), `clamp(x, lo, hi)`, `abs`, `mulDiv(a, b, c)` (`a*b/c`, truncating, exact
 *   intermediate product), `pow(base, exp)` (`exp` from 0 to 16) and `isqrt` (integer square root).
 * - **Names** are `identifier` or `prefix.identifier` (for example `a.str`); the host supplies their values.
 * - **Errors** (overflow, division by zero, an unknown name) throw [FormulaEvaluationException]; nothing ever wraps around.
 * - **Bounded:** at most [MAX_SOURCE_LENGTH] characters, [MAX_NODES] nodes, [MAX_STEPS] steps and [MAX_DEPTH] nesting levels, with no
 *   loops, so the cost of an evaluation is small and the same every time.
 */
class Formula private constructor(
    val source: String,
    private val steps: List<Step>,
    private val result: Node,
    /** Names the formula reads that it does not define itself, in the order they first appear. */
    val variables: List<String>,
    /** Names the formula defines with `name = ...;`. */
    val stepNames: List<String>,
    val nodeCount: Int
) {
    /** Evaluates with [env] giving the value of every name the formula reads (null means unknown). */
    fun evaluate(env: (String) -> Long?): Long {
        val locals = HashMap<String, Long>()
        try {
            for (step in steps) locals[step.name] = eval(step.expr, locals, env)
            return eval(result, locals, env)
        } catch (e: ArithmeticException) {
            throw FormulaEvaluationException("arithmetic overflow in '$source'")
        }
    }

    private fun eval(node: Node, locals: Map<String, Long>, env: (String) -> Long?): Long = when (node) {
        is Num -> node.value
        is Name -> locals[node.name] ?: env(node.name) ?: throw FormulaEvaluationException("unknown variable '${node.name}' in '$source'")
        is Neg -> Math.negateExact(eval(node.operand, locals, env))
        is Not -> if (eval(node.operand, locals, env) == 0L) 1L else 0L
        is Cond -> if (eval(node.test, locals, env) != 0L) eval(node.yes, locals, env) else eval(node.no, locals, env)
        is Bin -> evalBinary(node, locals, env)
        is Call -> evalCall(node, locals, env)
    }

    private fun evalBinary(node: Bin, locals: Map<String, Long>, env: (String) -> Long?): Long {
        when (node.op) {
            Op.AND -> return if (eval(node.left, locals, env) != 0L && eval(node.right, locals, env) != 0L) 1L else 0L
            Op.OR -> return if (eval(node.left, locals, env) != 0L || eval(node.right, locals, env) != 0L) 1L else 0L
            else -> {}
        }
        val l = eval(node.left, locals, env)
        val r = eval(node.right, locals, env)
        return when (node.op) {
            Op.ADD -> Math.addExact(l, r)
            Op.SUB -> Math.subtractExact(l, r)
            Op.MUL -> Math.multiplyExact(l, r)
            Op.DIV -> {
                if (r == 0L) throw FormulaEvaluationException("division by zero in '$source'")
                if (l == Long.MIN_VALUE && r == -1L) throw ArithmeticException()
                l / r
            }
            Op.MOD -> {
                if (r == 0L) throw FormulaEvaluationException("remainder by zero in '$source'")
                l % r
            }
            Op.LT -> if (l < r) 1L else 0L
            Op.LE -> if (l <= r) 1L else 0L
            Op.GT -> if (l > r) 1L else 0L
            Op.GE -> if (l >= r) 1L else 0L
            Op.EQ -> if (l == r) 1L else 0L
            Op.NE -> if (l != r) 1L else 0L
            Op.AND, Op.OR -> error("handled above")
        }
    }

    private fun evalCall(node: Call, locals: Map<String, Long>, env: (String) -> Long?): Long {
        val args = LongArray(node.args.size) { eval(node.args[it], locals, env) }
        return when (node.fn) {
            Fn.MIN -> args.min()
            Fn.MAX -> args.max()
            Fn.CLAMP -> {
                if (args[1] > args[2]) throw FormulaEvaluationException("clamp with lo > hi in '$source'")
                args[0].coerceIn(args[1], args[2])
            }
            Fn.ABS -> {
                if (args[0] == Long.MIN_VALUE) throw ArithmeticException()
                if (args[0] < 0) -args[0] else args[0]
            }
            Fn.MUL_DIV -> {
                if (args[2] == 0L) throw FormulaEvaluationException("division by zero in mulDiv in '$source'")
                val exact = BigInteger.valueOf(args[0]).multiply(BigInteger.valueOf(args[1])).divide(BigInteger.valueOf(args[2]))
                if (exact.bitLength() > 63) throw ArithmeticException()
                exact.toLong()
            }
            Fn.POW -> {
                if (args[1] < 0 || args[1] > MAX_POW_EXPONENT) throw FormulaEvaluationException("pow exponent must be 0..$MAX_POW_EXPONENT in '$source'")
                var out = 1L
                repeat(args[1].toInt()) { out = Math.multiplyExact(out, args[0]) }
                out
            }
            Fn.ISQRT -> {
                if (args[0] < 0) throw FormulaEvaluationException("isqrt of a negative number in '$source'")
                integerSquareRoot(args[0])
            }
        }
    }

    private enum class Op { ADD, SUB, MUL, DIV, MOD, LT, LE, GT, GE, EQ, NE, AND, OR }

    private enum class Fn(val label: String, val minArgs: Int, val maxArgs: Int) {
        MIN("min", 1, Int.MAX_VALUE),
        MAX("max", 1, Int.MAX_VALUE),
        CLAMP("clamp", 3, 3),
        ABS("abs", 1, 1),
        MUL_DIV("mulDiv", 3, 3),
        POW("pow", 2, 2),
        ISQRT("isqrt", 1, 1)
    }

    private sealed interface Node
    private class Num(val value: Long) : Node
    private class Name(val name: String) : Node
    private class Neg(val operand: Node) : Node
    private class Not(val operand: Node) : Node
    private class Bin(val op: Op, val left: Node, val right: Node) : Node
    private class Cond(val test: Node, val yes: Node, val no: Node) : Node
    private class Call(val fn: Fn, val args: List<Node>) : Node
    private class Step(val name: String, val expr: Node)

    private enum class Kind { NUMBER, NAME, PLUS, MINUS, STAR, SLASH, PERCENT, LT, LE, GT, GE, EQ, NE, AND, OR, BANG, LPAREN, RPAREN, COMMA, QUESTION, COLON, SEMI, ASSIGN, END }

    private class Token(val kind: Kind, val text: String, val position: Int)

    private class Parser(private val source: String) {
        private val tokens = tokenize(source)
        private var index = 0
        private var depth = 0
        var nodes = 0
        private val defined = LinkedHashSet<String>()
        val freeVariables = LinkedHashSet<String>()

        private val peek: Token get() = tokens[index]
        private fun advance(): Token = tokens[index++]
        private fun fail(message: String, token: Token = peek): Nothing = throw FormulaException(message, token.position)

        private fun <T : Node> node(n: T): T {
            if (++nodes > MAX_NODES) throw FormulaException("formula is too large (more than $MAX_NODES parts)")
            return n
        }

        private fun expect(kind: Kind, what: String): Token {
            if (peek.kind != kind) fail("expected $what but found ${describe(peek)}")
            return advance()
        }

        private fun describe(t: Token) = if (t.kind == Kind.END) "the end of the formula" else "'${t.text}'"

        fun parseProgram(): Pair<List<Step>, Node> {
            val steps = mutableListOf<Step>()
            while (peek.kind == Kind.NAME && tokens[index + 1].kind == Kind.ASSIGN) {
                val nameToken = advance()
                advance() // '='
                if ('.' in nameToken.text) fail("a step name cannot contain '.'", nameToken)
                if (Fn.entries.any { it.label == nameToken.text }) fail("'${nameToken.text}' is a function name", nameToken)
                if (!defined.add(nameToken.text)) fail("'${nameToken.text}' is defined twice", nameToken)
                if (steps.size >= MAX_STEPS) fail("more than $MAX_STEPS steps", nameToken)
                val expr = parseExpression()
                expect(Kind.SEMI, "';' after the step '${nameToken.text}'")
                steps += Step(nameToken.text, expr)
            }
            val result = parseExpression()
            if (peek.kind != Kind.END) fail("unexpected ${describe(peek)}")
            return steps to result
        }

        private fun parseExpression(): Node {
            if (++depth > MAX_DEPTH) fail("formula is nested too deeply")
            val test = parseOr()
            val result = if (peek.kind == Kind.QUESTION) {
                advance()
                val yes = parseExpression()
                expect(Kind.COLON, "':' in a '?:' expression")
                val no = parseExpression()
                node(Cond(test, yes, no))
            } else {
                test
            }
            depth--
            return result
        }

        private fun parseOr(): Node {
            var left = parseAnd()
            while (peek.kind == Kind.OR) { advance(); left = node(Bin(Op.OR, left, parseAnd())) }
            return left
        }

        private fun parseAnd(): Node {
            var left = parseEquality()
            while (peek.kind == Kind.AND) { advance(); left = node(Bin(Op.AND, left, parseEquality())) }
            return left
        }

        private fun parseEquality(): Node {
            var left = parseRelational()
            while (peek.kind == Kind.EQ || peek.kind == Kind.NE) {
                val op = if (advance().kind == Kind.EQ) Op.EQ else Op.NE
                left = node(Bin(op, left, parseRelational()))
            }
            return left
        }

        private fun parseRelational(): Node {
            var left = parseAdditive()
            while (peek.kind in RELATIONAL) {
                val op = when (advance().kind) { Kind.LT -> Op.LT; Kind.LE -> Op.LE; Kind.GT -> Op.GT; else -> Op.GE }
                left = node(Bin(op, left, parseAdditive()))
            }
            return left
        }

        private fun parseAdditive(): Node {
            var left = parseMultiplicative()
            while (peek.kind == Kind.PLUS || peek.kind == Kind.MINUS) {
                val op = if (advance().kind == Kind.PLUS) Op.ADD else Op.SUB
                left = node(Bin(op, left, parseMultiplicative()))
            }
            return left
        }

        private fun parseMultiplicative(): Node {
            var left = parseUnary()
            while (peek.kind == Kind.STAR || peek.kind == Kind.SLASH || peek.kind == Kind.PERCENT) {
                val op = when (advance().kind) { Kind.STAR -> Op.MUL; Kind.SLASH -> Op.DIV; else -> Op.MOD }
                left = node(Bin(op, left, parseUnary()))
            }
            return left
        }

        private fun parseUnary(): Node {
            if (++depth > MAX_DEPTH) fail("formula is nested too deeply")
            val result = when (peek.kind) {
                Kind.MINUS -> { advance(); node(Neg(parseUnary())) }
                Kind.BANG -> { advance(); node(Not(parseUnary())) }
                else -> parsePrimary()
            }
            depth--
            return result
        }

        private fun parsePrimary(): Node {
            val token = advance()
            return when (token.kind) {
                Kind.NUMBER -> node(Num(token.text.toLongOrNull() ?: fail("the number ${token.text} is too large", token)))
                Kind.NAME -> if (peek.kind == Kind.LPAREN) parseCall(token) else parseName(token)
                Kind.LPAREN -> {
                    val inner = parseExpression()
                    expect(Kind.RPAREN, "')'")
                    inner
                }
                else -> fail("expected a number, a name or '(' but found ${describe(token)}", token)
            }
        }

        private fun parseName(token: Token): Node {
            if (token.text !in defined) freeVariables += token.text
            return node(Name(token.text))
        }

        private fun parseCall(nameToken: Token): Node {
            val fn = Fn.entries.firstOrNull { it.label == nameToken.text } ?: fail("unknown function '${nameToken.text}'", nameToken)
            advance() // '('
            val args = mutableListOf<Node>()
            if (peek.kind != Kind.RPAREN) {
                args += parseExpression()
                while (peek.kind == Kind.COMMA) { advance(); args += parseExpression() }
            }
            expect(Kind.RPAREN, "')' after the arguments of ${fn.label}")
            if (args.size < fn.minArgs || args.size > fn.maxArgs) {
                val wanted = if (fn.minArgs == fn.maxArgs) "${fn.minArgs}" else "at least ${fn.minArgs}"
                fail("${fn.label} takes $wanted argument(s) but got ${args.size}", nameToken)
            }
            return node(Call(fn, args))
        }

        companion object {
            private val RELATIONAL = setOf(Kind.LT, Kind.LE, Kind.GT, Kind.GE)
        }
    }

    companion object {
        const val MAX_SOURCE_LENGTH = 1000
        const val MAX_NODES = 200
        const val MAX_STEPS = 16
        const val MAX_DEPTH = 40
        const val MAX_POW_EXPONENT = 16L

        /** Reads a formula. Throws [FormulaException] with the position of the first problem. */
        fun parse(source: String): Formula {
            if (source.length > MAX_SOURCE_LENGTH) throw FormulaException("formula is longer than $MAX_SOURCE_LENGTH characters")
            if (source.isBlank()) throw FormulaException("formula is empty")
            val parser = Parser(source)
            val (steps, result) = parser.parseProgram()
            return Formula(source, steps, result, parser.freeVariables.toList(), steps.map { it.name }, parser.nodes)
        }

        private fun tokenize(source: String): List<Token> {
            val out = mutableListOf<Token>()
            var i = 0
            fun add(kind: Kind, text: String, at: Int) { out += Token(kind, text, at) }
            while (i < source.length) {
                val c = source[i]
                when {
                    c.isWhitespace() -> i++
                    c in '0'..'9' -> {
                        val start = i
                        while (i < source.length && source[i] in '0'..'9') i++
                        if (i < source.length && (source[i].isLetter() || source[i] == '_')) {
                            throw FormulaException("a number cannot be followed directly by letters", start)
                        }
                        add(Kind.NUMBER, source.substring(start, i), start)
                    }
                    c.isLetter() || c == '_' -> {
                        val start = i
                        fun word() { while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++ }
                        word()
                        if (i < source.length && source[i] == '.') {
                            i++
                            if (i >= source.length || !(source[i].isLetter() || source[i] == '_')) {
                                throw FormulaException("expected a name after '.'", i)
                            }
                            word()
                            if (i < source.length && source[i] == '.') throw FormulaException("a name can have only one '.'", i)
                        }
                        add(Kind.NAME, source.substring(start, i), start)
                    }
                    else -> {
                        val two = if (i + 1 < source.length) source.substring(i, i + 2) else ""
                        val twoKind = when (two) { "<=" -> Kind.LE; ">=" -> Kind.GE; "==" -> Kind.EQ; "!=" -> Kind.NE; "&&" -> Kind.AND; "||" -> Kind.OR; else -> null }
                        if (twoKind != null) {
                            add(twoKind, two, i); i += 2
                        } else {
                            val kind = when (c) {
                                '+' -> Kind.PLUS; '-' -> Kind.MINUS; '*' -> Kind.STAR; '/' -> Kind.SLASH; '%' -> Kind.PERCENT
                                '<' -> Kind.LT; '>' -> Kind.GT; '!' -> Kind.BANG; '(' -> Kind.LPAREN; ')' -> Kind.RPAREN
                                ',' -> Kind.COMMA; '?' -> Kind.QUESTION; ':' -> Kind.COLON; ';' -> Kind.SEMI; '=' -> Kind.ASSIGN
                                else -> throw FormulaException("unexpected character '$c'", i)
                            }
                            add(kind, c.toString(), i); i++
                        }
                    }
                }
            }
            add(Kind.END, "", source.length)
            return out
        }

        /** Largest `r` with `r * r <= x`, found by binary search so no floating point is involved. */
        private fun integerSquareRoot(x: Long): Long {
            var lo = 0L
            var hi = 3_037_000_499L // floor(sqrt(Long.MAX_VALUE))
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (mid * mid <= x) lo = mid else hi = mid - 1
            }
            return lo
        }
    }
}
