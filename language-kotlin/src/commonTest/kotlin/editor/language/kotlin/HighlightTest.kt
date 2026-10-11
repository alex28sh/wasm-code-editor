package editor.language.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HighlightTest {
    private val dollar = "$"

    @Test
    fun sampleDocument() {
        val text = """
            /** Greets [name]. */
            fun greet(name: String): String = "Hello, ${dollar}name!"

            fun main() {
                println(greet("Wasm"))
            }
        """.trimIndent()

        assertEquals(
            listOf(
                "/** Greets [:DOC_COMMENT", "name:DOC_LINK", "]. */:DOC_COMMENT",
                "fun:KEYWORD", "greet:FUNCTION_DECLARATION", "name:PARAMETER",
                "\"Hello, :STRING", "$:TEMPLATE", "name:PARAMETER", "!\":STRING",
                "fun:KEYWORD", "main:FUNCTION_DECLARATION",
                "println:FUNCTION_CALL", "greet:TOP_LEVEL_FUNCTION_CALL", "\"Wasm\":STRING",
            ),
            highlighted(text),
        )
    }

    @Test
    fun numbers() {
        assertEquals(
            listOf("val:KEYWORD", "a:PROPERTY", "0xFF_FFL:NUMBER", "1_000:NUMBER", "0b1010:NUMBER", "1.5e-3f:NUMBER", "2u:NUMBER", "3.14:NUMBER"),
            highlighted("val a = 0xFF_FFL + 1_000 + 0b1010 + 1.5e-3f + 2u + 3.14"),
        )
    }

    @Test
    fun aRangeAndAMemberOfANumberAreNotFractions() {
        assertEquals(listOf("1:NUMBER", "10:NUMBER"), highlighted("1..10"))
        assertEquals(listOf("1:NUMBER", "toString:FUNCTION_CALL"), highlighted("1.toString()"))
    }

    @Test
    fun stringEscapesAndTemplates() {
        assertEquals(
            listOf("\"a:STRING", "\\n:STRING_ESCAPE", "\\u0041:STRING_ESCAPE", "$dollar{:TEMPLATE", "1:NUMBER", "2:NUMBER", "}:TEMPLATE", " $dollar \":STRING"),
            highlighted("\"a\\n\\u0041$dollar{1 + 2} $dollar \""),
        )
    }

    @Test
    fun templatesNest() {
        val text = "\"a$dollar{if (x) \"b$dollar{y}\" else { \"c\" }}d\""

        assertEquals(
            listOf(
                "\"a:STRING", "$dollar{:TEMPLATE", "if:KEYWORD", "\"b:STRING", "$dollar{:TEMPLATE", "}:TEMPLATE", "\":STRING",
                "else:KEYWORD", "\"c\":STRING", "}:TEMPLATE", "d\":STRING",
            ),
            highlighted(text),
        )
    }

    @Test
    fun rawStringsHaveNoEscapesAndEndAtTheLastThreeQuotes() {
        val quotes = "\"\"\""
        val text = "val a = $quotes one\\n \"two\" $dollar{b}$quotes\"\nval c = 1"

        assertEquals(
            listOf("val:KEYWORD", "a:PROPERTY", "$quotes one\\n \"two\" :STRING", "$dollar{:TEMPLATE", "}:TEMPLATE", "$quotes\":STRING", "val:KEYWORD", "c:PROPERTY", "1:NUMBER"),
            highlighted(text),
        )
    }

    @Test
    fun anUnterminatedStringEndsWithItsLine() {
        assertEquals(listOf("val:KEYWORD", "a:PROPERTY", "\"open:STRING", "val:KEYWORD", "b:PROPERTY", "2:NUMBER"), highlighted("val a = \"open\nval b = 2"))
    }

    @Test
    fun characters() {
        assertEquals(listOf("'a':STRING", "'\\'':STRING", "'\\u0041':STRING"), highlighted("'a' + '\\'' + '\\u0041'"))
    }

    @Test
    fun comments() {
        assertEquals(listOf("// line:COMMENT", "/* a /* nested */ b */:COMMENT", "1:NUMBER", "/**/:COMMENT"), highlighted("// line\n/* a /* nested */ b */ 1 /**/"))
    }

    @Test
    fun documentationTagsAndLinks() {
        val text = """
            /**
             * Joins [first] and [Pair.second], see [the docs](http://example.com).
             * @param first the first
             * @return both
             */
            fun join(first: String, second: String) = first + second
        """.trimIndent()

        assertEquals(Highlight.DOC_LINK, highlightOf(text, "first"))
        assertEquals(Highlight.DOC_LINK, highlightOf(text, "second"))
        assertEquals(Highlight.DOC_TAG, highlightOf(text, "@param"))
        assertEquals(Highlight.DOC_LINK, highlightOf(text, "first", 2))
        assertEquals(Highlight.DOC_TAG, highlightOf(text, "@return"))
        assertNull(highlightOf(text, "the docs"))
        assertNull(highlightOf(text, "both"))
    }

    @Test
    fun annotationsAndLabels() {
        val text = """
            @file:JvmName("Names")
            @Suppress("unused") @kotlin.Deprecated("old")
            fun each(items: List<Int>) {
                items.forEach loop@{
                    if (it == 0) return@loop
                }
                outer@ for (item in items) break@outer
            }
        """.trimIndent()

        assertEquals(Highlight.ANNOTATION, highlightOf(text, "@file:JvmName"))
        assertEquals(Highlight.ANNOTATION, highlightOf(text, "@Suppress"))
        assertEquals(Highlight.ANNOTATION, highlightOf(text, "@kotlin.Deprecated"))
        assertEquals(Highlight.LABEL, highlightOf(text, "loop@"))
        assertEquals(Highlight.LABEL, highlightOf(text, "@loop"))
        assertEquals(Highlight.LABEL, highlightOf(text, "outer@"))
        assertEquals(Highlight.LABEL, highlightOf(text, "@outer"))
        assertEquals(Highlight.IMPLICIT_PARAMETER, highlightOf(text, "it", 3))
    }

    @Test
    fun modifiersAreKeywordsOnlyInFrontOfDeclarations() {
        val text = """
            data class Point(private val x: Int, internal var y: Int)
            sealed interface Shape
            enum class Color { RED }
            abstract class Base {
                protected open val data: Int = 1
                private lateinit var value: String
                override fun toString(): String = value + data
                companion object {
                    const val LIMIT = 1
                }
                inline fun <reified T> cast(vararg items: Any, noinline block: () -> T) = block()
            }
        """.trimIndent()

        for (modifier in listOf("data", "private", "internal", "sealed", "enum", "abstract", "protected", "open", "lateinit", "override", "companion", "const", "inline", "reified", "vararg", "noinline")) {
            assertEquals(Highlight.KEYWORD, highlightOf(text, modifier), modifier)
        }
        // The same words used as names.
        assertEquals(Highlight.PROPERTY, highlightOf(text, "data", 2))
        assertEquals(Highlight.MUTABLE_PROPERTY, highlightOf(text, "value"))
        assertEquals(Highlight.MUTABLE_PROPERTY, highlightOf(text, "value", 2))
        assertEquals(Highlight.PROPERTY, highlightOf(text, "data", 3))
    }

    @Test
    fun aNameThatLooksLikeAModifierInFrontOfTheNextDeclarationStaysAName() {
        val text = """
            fun set(value: Int) {
                val copy = value
                fun inner() = copy
            }
        """.trimIndent()

        assertEquals(Highlight.PARAMETER, highlightOf(text, "value", 2))
    }

    @Test
    fun softKeywords() {
        val text = """
            import kotlin.math.max as maximum

            class Lazy(source: List<Int>) : List<Int> by source {
                constructor() : this(emptyList())

                var count: Int = 0
                    get() = field + 1
                    private set

                init {
                    count = 1
                }

                val first by lazy { 1 }

                fun <T> check(item: T) where T : Any {
                    try {
                    } catch (error: Exception) {
                    } finally {
                    }
                }
            }
        """.trimIndent()

        for (word in listOf("import", "by", "constructor", "get", "field", "private", "set", "init", "where", "catch", "finally")) {
            assertEquals(Highlight.KEYWORD, highlightOf(text, word), word)
        }
        assertEquals(Highlight.KEYWORD, highlightOf(text, "by", 2))
        assertEquals(Highlight.KEYWORD, highlightOf(text, "as"))
    }

    @Test
    fun softKeywordsUsedAsNames() {
        val text = """
            fun by(get: Int, set: Int, init: Int) = get + set + init
            val where = by(1, 2, 3)
        """.trimIndent()

        assertEquals(Highlight.FUNCTION_DECLARATION, highlightOf(text, "by"))
        assertEquals(Highlight.PARAMETER, highlightOf(text, "get"))
        assertEquals(Highlight.PARAMETER, highlightOf(text, "init", 2))
        assertEquals(Highlight.PROPERTY, highlightOf(text, "where"))
        assertEquals(Highlight.TOP_LEVEL_FUNCTION_CALL, highlightOf(text, "by", 2))
    }

    @Test
    fun declarationsAndUsesByKind() {
        val text = """
            typealias Names = List<String>

            object Registry {
                val names = mutableListOf<String>()
                fun add(name: String) = names.add(name)
            }

            enum class Level { LOW, HIGH }

            class Counter<T>(start: Int, val step: Int = 1) {
                var total = start

                fun next(item: T): Int {
                    var local = total
                    val fixed = step
                    local += fixed
                    total = local
                    return helper(count = local)
                }

                private fun helper(count: Int) = count
            }

            fun use(counter: Counter<String>, names: Names) {
                Registry.add("a")
                counter.next("b")
                val level = Level.HIGH
            }
        """.trimIndent()

        assertEquals(Highlight.CLASS, highlightOf(text, "Names"))
        assertEquals(Highlight.INSTANCE, highlightOf(text, "Registry"))
        assertEquals(Highlight.PROPERTY, highlightOf(text, "names"))
        assertEquals(Highlight.FUNCTION_DECLARATION, highlightOf(text, "add"))
        assertEquals(Highlight.PROPERTY, highlightOf(text, "names", 2))
        assertEquals(Highlight.INSTANCE, highlightOf(text, "LOW"))
        assertEquals(Highlight.CLASS, highlightOf(text, "Counter"))
        assertEquals(Highlight.TYPE_PARAMETER, highlightOf(text, "T"))
        assertEquals(Highlight.PARAMETER, highlightOf(text, "start"))
        assertEquals(Highlight.PROPERTY, highlightOf(text, "step"))
        assertEquals(Highlight.MUTABLE_PROPERTY, highlightOf(text, "total"))
        assertEquals(Highlight.PARAMETER, highlightOf(text, "start", 2))
        assertEquals(Highlight.TYPE_PARAMETER, highlightOf(text, "T", 2))
        assertEquals(Highlight.MUTABLE_LOCAL_VARIABLE, highlightOf(text, "local"))
        assertEquals(Highlight.MUTABLE_PROPERTY, highlightOf(text, "total", 2))
        assertEquals(Highlight.LOCAL_VARIABLE, highlightOf(text, "fixed"))
        assertEquals(Highlight.PROPERTY, highlightOf(text, "step", 2))
        assertEquals(Highlight.MUTABLE_LOCAL_VARIABLE, highlightOf(text, "local", 2))
        assertEquals(Highlight.FUNCTION_CALL, highlightOf(text, "helper"))
        assertEquals(Highlight.NAMED_ARGUMENT, highlightOf(text, "count"))
        assertEquals(Highlight.FUNCTION_DECLARATION, highlightOf(text, "helper", 2))
        assertEquals(Highlight.CLASS, highlightOf(text, "Counter", 2))
        assertEquals(Highlight.CLASS, highlightOf(text, "Names", 2))
        assertEquals(Highlight.INSTANCE, highlightOf(text, "Registry", 2))
        assertEquals(Highlight.FUNCTION_CALL, highlightOf(text, "add", 2))
        assertEquals(Highlight.FUNCTION_CALL, highlightOf(text, "next", 2))
        assertEquals(Highlight.INSTANCE, highlightOf(text, "HIGH", 2))
    }

    @Test
    fun onlyTheRequestedRangeIsReported() {
        val text = "val a = 1\nval b = 2\nval c = 3"
        val pieces = ArrayList<String>()

        KotlinFile(text).highlights(text.offsetOf("val", 2), text.offsetOf("2") + 1) { start, end, _ -> pieces += text.substring(start, end) }

        assertEquals(listOf("val", "b", "2"), pieces)
    }

    @Test
    fun aTokenReachingIntoTheRangeIsReportedWhole() {
        val text = "/* a long\ncomment */ val a = 1"
        val pieces = ArrayList<String>()

        KotlinFile(text).highlights(text.offsetOf("comment"), text.length) { start, end, _ -> pieces += text.substring(start, end) }

        assertEquals("/* a long\ncomment */", pieces.first())
    }
}
