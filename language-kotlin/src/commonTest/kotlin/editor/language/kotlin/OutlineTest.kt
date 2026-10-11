package editor.language.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlineTest {
    private val text = """
        package sample

        import kotlin.math.max
        import kotlin.math.min
        import kotlin.math.abs

        /**
         * A shape.
         */
        interface Shape {
            val name: String
            fun area(): Double
        }

        data class Circle(val radius: Double) : Shape {
            override val name = "circle"

            override fun area(): Double {
                val squared = radius * radius
                return PI * squared
            }

            companion object {
                const val PI = 3.14
            }
        }

        enum class Unit(val factor: Int) {
            METER(1),
            KILOMETER(1000);

            fun convert(value: Int) = value * factor
        }

        typealias Shapes = List<Shape>

        fun <T : Shape> largest(shapes: List<T>): T? =
            shapes.maxByOrNull { it.area() }

        val unit = Circle(1.0)
    """.trimIndent()
    private val file = KotlinFile(text)

    @Test
    fun structureListsTypesAndTheirMembersButNoLocals() {
        assertEquals(
            listOf(
                "0 interface Shape",
                "1 property name: String",
                "1 function area(): Double",
                "0 class Circle(val radius: Double) : Shape",
                "1 property radius: Double",
                "1 property name",
                "1 function area(): Double",
                "2 property PI",
                "0 class Unit(val factor: Int)",
                "1 property factor: Int",
                "1 enum entry METER",
                "1 enum entry KILOMETER",
                "1 function convert(value: Int)",
                "0 type alias Shapes",
                "0 function largest(shapes: List<T>): T?",
                "0 property unit",
            ),
            file.structure.map { "${it.depth} ${it.kind.title} ${it.signature}" },
        )
    }

    @Test
    fun theEnclosingDeclarationIsTheInnermostOne() {
        assertEquals("area", file.enclosing(text.offsetOf("squared"))?.name)
        assertEquals("Circle", file.enclosing(text.offsetOf("companion"))?.name)
        assertEquals("PI", file.enclosing(text.offsetOf("3.14"))?.name)
        assertEquals("largest", file.enclosing(text.offsetOf("maxByOrNull"))?.name)
        assertNull(file.enclosing(text.offsetOf("package")))
    }

    private fun folded(line: String): String? {
        val start = text.offsetOf(line)
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return file.foldRange(start, end)?.let { text.substring(it.first, it.last) }
    }

    @Test
    fun blocksFoldFromTheirBraceToTheClosingOne() {
        assertEquals("\n    val name: String\n    fun area(): Double\n", folded("interface Shape"))
        assertTrue(folded("override fun area")!!.trim().startsWith("val squared"))
        assertEquals("\n        const val PI = 3.14\n    ", folded("companion object"))
        assertNull(folded("val unit"))
        assertNull(folded("const val PI"))
    }

    @Test
    fun commentsAndImportsFold() {
        assertEquals("\n * A shape.\n ", folded("/**"))
        assertEquals("kotlin.math.max\nimport kotlin.math.min\nimport kotlin.math.abs", folded("import kotlin.math.max"))
        assertNull(folded("import kotlin.math.min"))
    }

    @Test
    fun completionOffersWhatIsVisibleAtTheCaret() {
        val inArea = text.offsetOf("return PI")
        val labels = file.completions(inArea, "").map { it.label }

        assertTrue(labels.containsAll(listOf("squared", "radius", "name", "area", "Circle", "Shape", "largest", "unit", "max")), labels.toString())
        // Declared in other scopes.
        assertTrue("shapes" !in labels && "value" !in labels && "factor" !in labels, labels.toString())
    }

    @Test
    fun completionFiltersByPrefixAndAddsKeywords() {
        val inArea = text.offsetOf("return PI")

        assertEquals(listOf("squared"), file.completions(inArea + 2, "sq").map { it.label })
        assertEquals(listOf("return"), file.completions(inArea + 3, "ret").map { it.label })
        assertEquals(listOf("Shape", "Shapes"), file.completions(inArea + 2, "Sha").map { it.label }.sorted())
    }

    @Test
    fun completionAfterADotOffersMembers() {
        val edited = text.replace("val unit = Circle(1.0)", "val unit = Circle(1.0).ar")
        val labels = KotlinFile(edited).completions(edited.length, "ar").map { it.label }

        assertEquals(listOf("area"), labels)
    }

    @Test
    fun nothingIsOfferedForANameThatIsBeingDeclared() {
        for (declaration in listOf("val fi", "var fi", "fun fi", "class fi", "object fi", "interface fi", "typealias fi")) {
            val edited = "val first = 1\n$declaration"

            assertEquals(emptyList(), KotlinFile(edited).completions(edited.length, "fi").map { it.label }, declaration)
        }
    }

    @Test
    fun nothingIsOfferedInsideCommentsStringsAndNumbers() {
        for (context in listOf("// fi", "/* fi", "/** fi", "\"fi", "\"\"\"fi", "val a = 0xfi", "'fi")) {
            val edited = "val first = 1\n$context"

            assertEquals(emptyList(), KotlinFile(edited).completions(edited.length, "fi").map { it.label }, context)
        }
    }

    @Test
    fun namesAreOfferedAgainAfterAClosedCommentOrString() {
        for (context in listOf("/* done */ fi", "val a = \"text\" + fi", "val a = \"\${fi")) {
            val edited = "val first = 1\n$context"

            assertEquals(listOf("first"), KotlinFile(edited).completions(edited.length, "fi").map { it.label }.filter { it != "finally" }, context)
        }
    }
}
