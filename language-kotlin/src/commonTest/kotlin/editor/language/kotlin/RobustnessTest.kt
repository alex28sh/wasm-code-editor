package editor.language.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RobustnessTest {
    private val dollar = "$"
    private val text = """
        @file:Suppress("unused")
        package sample

        import kotlin.math.max as maximum

        /** Holds [items] of type [T]. @param items the items */
        open class Box<out T : Any>(private val items: List<T>, var label: String = "box") : Iterable<T> by items {
            constructor() : this(emptyList())

            val size: Int get() = items.size
            var count = 0
                private set

            init { count = items.size }

            fun describe(): String = ""${'"'}
                ${dollar}label: ${dollar}{items.joinToString { "<${dollar}it>" }}
            ""${'"'}.trimIndent()

            inline fun <reified R> filter(noinline test: (T) -> Boolean = { true }): List<R> = items.filter(test).filterIsInstance<R>()

            companion object Factory {
                const val LIMIT = 0xFF
                fun <T : Any> of(vararg items: T) = Box(items.toList())
            }
        }

        enum class Level(val weight: Int) { LOW(1), HIGH(2) { override fun toString() = "high" }; fun heavier(other: Level) = weight > other.weight }

        fun main(args: Array<String>) {
            val box = Box.of(1, 2, 3)
            loop@ for ((index, value) in box.withIndex()) {
                if (index > Box.LIMIT) break@loop
                when (val level = if (value > 1) Level.HIGH else Level.LOW) {
                    Level.LOW -> println('l')
                    else -> println(level.heavier(Level.LOW))
                }
            }
            try { box.filter<Int> { it > maximum(1, 2) } } catch (e: Exception) { throw e } finally { }
        }
    """.trimIndent()

    private fun check(source: String) {
        val file = KotlinFile(source)
        var last = 0
        file.highlights(0, source.length) { start, end, _ ->
            assertTrue(start >= last && end > start && end <= source.length, "piece $start..$end after $last in a text of ${source.length}")
            last = end
        }
        for (symbol in file.symbols) {
            assertEquals(symbol.name, source.substring(symbol.nameStart, symbol.nameEnd).removeSurrounding("`"))
            assertTrue(symbol.start <= symbol.nameStart && symbol.end >= symbol.nameEnd, "$symbol: ${symbol.start}..${symbol.end}")
            for (occurrence in file.occurrences(symbol)) {
                assertEquals(symbol.name, source.substring(occurrence.start, occurrence.end).removeSurrounding("`"))
            }
        }
        for (offset in 0..source.length step 7) {
            file.symbolAt(offset)
            file.enclosing(offset)
            file.completions(offset, "")
        }
        var lineStart = 0
        while (lineStart <= source.length) {
            val lineEnd = source.indexOf('\n', lineStart).let { if (it < 0) source.length else it }
            file.foldRange(lineStart, lineEnd)?.let { assertTrue(it.first >= lineStart && it.last <= source.length && it.last > lineEnd, "fold $it of line at $lineStart") }
            lineStart = lineEnd + 1
        }
    }

    @Test
    fun theWholeText() = check(text)

    @Test
    fun everyPrefixOfTheText() {
        for (length in 0..text.length) check(text.substring(0, length))
    }

    @Test
    fun everySuffixOfTheText() {
        for (start in 0..text.length step 3) check(text.substring(start))
    }

    @Test
    fun theTextWithOneCharacterRemoved() {
        for (index in text.indices step 2) check(text.removeRange(index, index + 1))
    }

    @Test
    fun unbalancedBrackets() {
        for (source in listOf("}", ")))", "{{{", "fun f(", "fun f() {", "class A<", "val x = \"${dollar}{", "/* open", "/** [a", "fun", "val", "class", "for (", "when (val", "a.b.", "@", "`open", "'", "${dollar}{}")) {
            check(source)
        }
    }

    @Test
    fun anEmptyText() {
        val file = KotlinFile("")

        assertEquals(emptyList(), file.symbols)
        assertEquals(emptyList(), file.completions(0, "x"))
    }

    @Test
    fun aLargeTextIsAnalyzedQuickly() {
        val large = (0 until 20_000).joinToString("\n") { "val line$it = $it" }

        val file = KotlinFile(large)

        assertEquals(20_000, file.structure.size)
        assertEquals("line19999", file.symbolAt(large.length - 10)?.name)
    }
}
