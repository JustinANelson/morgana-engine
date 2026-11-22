package com.morgana.domain.game

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FormulaTest {

    private fun eval(source: String, vararg vars: Pair<String, Long>): Long {
        val values = vars.toMap()
        return Formula.parse(source).evaluate { values[it] }
    }

    private fun parseError(source: String): FormulaException = assertThrows(FormulaException::class.java) { Formula.parse(source) }

    private fun evalError(source: String, vararg vars: Pair<String, Long>): FormulaEvaluationException =
        assertThrows(FormulaEvaluationException::class.java) { eval(source, *vars) }

    // ---- arithmetic and precedence ---------------------------------------------------------------------------

    @Test
    fun `operators follow the usual precedence and associate to the left`() {
        assertEquals(14, eval("2 + 3 * 4"))
        assertEquals(20, eval("(2 + 3) * 4"))
        assertEquals(6, eval("-2 * -3"))
        assertEquals(3, eval("10 - 4 - 3"))
        assertEquals(2, eval("2 * 3 % 4"))
        assertEquals(1, eval("1 + 2 == 3"))
        assertEquals(1, eval("1 < 2 == 1"))
    }

    @Test
    fun `division and remainder truncate toward zero like Kotlin`() {
        assertEquals(-3, eval("-7 / 2"))
        assertEquals(-3, eval("7 / -2"))
        assertEquals(3, eval("7 / 2"))
        assertEquals(-1, eval("-7 % 2"))
        assertEquals(1, eval("7 % -2"))
        assertEquals(0, eval("5 / 10"))
    }

    @Test
    fun `comparisons and logic give 0 or 1 and any non-zero value is true`() {
        assertEquals(1, eval("3 < 4")); assertEquals(0, eval("3 >= 4")); assertEquals(1, eval("4 <= 4")); assertEquals(1, eval("5 > 4"))
        assertEquals(1, eval("1 == 1")); assertEquals(0, eval("1 != 1"))
        assertEquals(1, eval("!0")); assertEquals(0, eval("!5")); assertEquals(1, eval("!!7"))
        assertEquals(0, eval("1 && 0")); assertEquals(1, eval("2 && 3")); assertEquals(1, eval("0 || 7")); assertEquals(0, eval("0 || 0"))
        assertEquals(1, eval("1 || 0 && 0"), "&& binds tighter than ||")
    }

    @Test
    fun `the conditional picks a branch and nests to the right`() {
        assertEquals(10, eval("1 ? 10 : 20"))
        assertEquals(20, eval("0 ? 10 : 20"))
        assertEquals(3, eval("0 ? 1 : 0 ? 2 : 3"))
        assertEquals(2, eval("0 ? 1 : 1 ? 2 : 3"))
    }

    @Test
    fun `only the needed side is evaluated`() {
        assertEquals(5, eval("1 ? 5 : 1 / 0"))
        assertEquals(0, eval("0 && 1 / 0"))
        assertEquals(1, eval("1 || 1 / 0"))
    }

    // ---- functions ------------------------------------------------------------------------------------------

    @Test
    fun `min, max, clamp and abs`() {
        assertEquals(2, eval("min(5, 2, 9)")); assertEquals(9, eval("max(5, 2, 9)")); assertEquals(7, eval("max(7)"))
        assertEquals(5, eval("clamp(5, 0, 10)")); assertEquals(0, eval("clamp(-3, 0, 10)")); assertEquals(10, eval("clamp(99, 0, 10)"))
        assertEquals(4, eval("abs(-4)")); assertEquals(4, eval("abs(4)"))
    }

    @Test
    fun `mulDiv uses an exact intermediate product`() {
        assertEquals(10, eval("mulDiv(6, 7, 4)"))
        assertEquals(-10, eval("mulDiv(-6, 7, 4)"), "truncates toward zero")
        val big = 1_000_000_000_000L
        assertEquals(big, eval("mulDiv(a, a, a)", "a" to big), "a*a overflows a Long but the result fits")
        evalError("mulDiv(1, 2, 0)")
        evalError("mulDiv(a, a, 1)", "a" to big)
    }

    @Test
    fun `pow takes a small non-negative exponent and isqrt is exact`() {
        assertEquals(1024, eval("pow(2, 10)")); assertEquals(1, eval("pow(5, 0)")); assertEquals(-8, eval("pow(-2, 3)"))
        assertEquals(1, eval("pow(1, 16)"))
        evalError("pow(2, 17)"); evalError("pow(2, -1)"); evalError("pow(10, 16) * 1000")
        for (x in 0L..5000L) {
            var brute = 0L
            while ((brute + 1) * (brute + 1) <= x) brute++
            assertEquals(brute, eval("isqrt(x)", "x" to x), "isqrt($x)")
        }
        assertEquals(3_037_000_499L, eval("isqrt(9223372036854775807)"))
        assertEquals(3_037_000_499L, eval("isqrt(9223372030926249001)"))
        assertEquals(3_037_000_498L, eval("isqrt(9223372030926249000)"))
        evalError("isqrt(-1)")
    }

    // ---- steps and names -------------------------------------------------------------------------------------

    @Test
    fun `steps are usable by later steps and the result`() {
        assertEquals(8, eval("x = 2; y = x * 3; y + x"))
        assertEquals(15, eval("a = a.str + 1; a * 3", "a.str" to 4))
    }

    @Test
    fun `the formula lists the names it reads but not the steps it defines`() {
        val formula = Formula.parse("raw = a.str * 2 + weapon; mit = d.def; max(1, raw - mit) + weapon")
        assertEquals(listOf("a.str", "weapon", "d.def"), formula.variables)
        assertEquals(listOf("raw", "mit"), formula.stepNames)
    }

    @Test
    fun `an unknown name is an evaluation error, not a zero`() {
        assertTrue(evalError("missing + 1").message!!.contains("missing"))
    }

    // ---- errors ----------------------------------------------------------------------------------------------

    @Test
    fun `arithmetic never wraps around and zero division is reported`() {
        evalError("1 / 0"); evalError("5 % 0")
        evalError("9223372036854775807 + 1"); evalError("-9223372036854775807 - 2")
        evalError("a * 2", "a" to Long.MAX_VALUE); evalError("a / -1", "a" to Long.MIN_VALUE)
        evalError("-a", "a" to Long.MIN_VALUE); evalError("abs(a)", "a" to Long.MIN_VALUE)
        evalError("clamp(1, 5, 2)")
    }

    @Test
    fun `syntax errors name the position of the problem`() {
        assertEquals(0, parseError("foo(1)").position)
        assertEquals(2, parseError("1 $ 2").position)
        parseError("3a").let { assertEquals(0, it.position); assertTrue(it.message!!.contains("letters")) }
        assertTrue(parseError("(1 + 2").message!!.contains("')'"))
        assertTrue(parseError("1 ? 2").message!!.contains("':'"))
        assertTrue(parseError("1 2").message!!.contains("unexpected"))
        assertTrue(parseError("1 +").message!!.contains("end of the formula"))
        assertTrue(parseError("a.b.c").message!!.contains("only one '.'"))
        assertTrue(parseError("a.").message!!.contains("after '.'"))
        assertTrue(parseError("99999999999999999999").message!!.contains("too large"))
        assertTrue(parseError("").message!!.contains("empty"))
        assertTrue(parseError("1 & 2").message!!.contains("unexpected character"))
        assertTrue(parseError("1 = 2").message!!.contains("unexpected"))
    }

    @Test
    fun `functions check their argument counts at parse time`() {
        assertTrue(parseError("clamp(1, 2)").message!!.contains("clamp takes 3"))
        assertTrue(parseError("abs(1, 2)").message!!.contains("abs takes 1"))
        assertTrue(parseError("min()").message!!.contains("at least 1"))
        assertTrue(parseError("mulDiv(1)").message!!.contains("mulDiv takes 3"))
    }

    @Test
    fun `step names must be plain, unique and not function names`() {
        assertTrue(parseError("x = 1; x = 2; x").message!!.contains("twice"))
        assertTrue(parseError("max = 1; max").message!!.contains("function name"))
        assertTrue(parseError("a.b = 1; 2").message!!.contains("cannot contain '.'"))
        assertTrue(parseError("x = 1 x").message!!.contains("';'"))
    }

    // ---- limits ----------------------------------------------------------------------------------------------

    @Test
    fun `size, step and nesting limits are enforced`() {
        assertTrue(parseError("1" + " ".repeat(Formula.MAX_SOURCE_LENGTH)).message!!.contains("longer than"))
        assertTrue(parseError((1..150).joinToString(" + ") { "1" }).message!!.contains("too large"))
        assertTrue(parseError("(".repeat(60) + "1" + ")".repeat(60)).message!!.contains("nested too deeply"))
        val seventeenSteps = (1..17).joinToString(" ") { "s$it = $it;" } + " 1"
        assertTrue(parseError(seventeenSteps).message!!.contains("17") || parseError(seventeenSteps).message!!.contains("steps"))
        val sixteenSteps = (1..16).joinToString(" ") { "s$it = $it;" } + " s16"
        assertEquals(16, eval(sixteenSteps))
        assertTrue(Formula.parse("1 + 2 * 3").nodeCount in 1..Formula.MAX_NODES)
    }

    @Test
    fun `evaluation is deterministic`() {
        val formula = Formula.parse("raw = a.str * 2 + weapon; raw > 50 ? raw / 3 : raw % 7")
        val env = mapOf("a.str" to 17L, "weapon" to 25L)
        val first = formula.evaluate { env[it] }
        repeat(1000) { assertEquals(first, formula.evaluate { env[it] }) }
    }
}
