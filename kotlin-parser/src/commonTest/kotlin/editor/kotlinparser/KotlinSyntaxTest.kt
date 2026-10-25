package editor.kotlinparser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinSyntaxTest {
    private val dollar = "$"

    @Test
    fun aValidFileHasNoErrors() {
        val syntax = KotlinSyntax.parse(
            """
            /** Greets [name]. */
            fun greet(name: String): String = "Hello, ${dollar}name!"

            fun main() {
                println(greet("Wasm"))
            }
            """.trimIndent()
        )

        assertEquals(emptyList(), syntax.errors.map { it.toString() })
        // Every file has a package directive and an import list, empty or not, as in the compiler.
        assertEquals(listOf("PACKAGE_DIRECTIVE", "IMPORT_LIST", "FUNCTION", "FUNCTION"), syntax.topLevel.map { it.first })
    }

    @Test
    fun theTreeHasTheCompilersNodeTypes() {
        val dump = KotlinSyntax.parse("fun greet(name: String) = \"Hello, ${dollar}name!\"").dump()

        assertTrue("VALUE_PARAMETER_LIST: (name: String)" in dump, dump)
        assertTrue("SHORT_STRING_TEMPLATE_ENTRY: ${dollar}name" in dump, dump)
        assertTrue("USER_TYPE: String" in dump, dump)
    }

    @Test
    fun aMissingParenthesisIsReportedWhereItIsMissing() {
        val text = "fun main() {\n    println(\"unclosed\"\n}\n"

        val errors = KotlinSyntax.parse(text).errors

        assertEquals(1, errors.size, errors.toString())
        assertEquals("Expecting ')'", errors.single().message)
        assertEquals(text.indexOf("\"unclosed\"") + "\"unclosed\"".length, errors.single().from)
    }

    @Test
    fun severalErrorsAreReportedInOrder() {
        val text = "class {\n}\nfun f( = 1\nval x = \n"

        val errors = KotlinSyntax.parse(text).errors

        assertTrue(errors.size >= 3, errors.toString())
        assertEquals(errors.sortedBy { it.from }, errors)
        assertTrue(errors.all { it.from <= it.to && it.to <= text.length }, errors.toString())
    }

    @Test
    fun anyTextCanBeParsed() {
        val sample = "class Box<T>(val item: T) { fun <R> map(f: (T) -> R): Box<R> = Box(f(item)) } \"open \${ when { } /* ` @ "
        for (end in 0..sample.length) KotlinSyntax.parse(sample.substring(0, end))
    }

    @Test
    fun aScriptIsParsedAsAScript() {
        val syntax = KotlinSyntax.parse("println(\"top-level statement\")", isScript = true)

        assertEquals(emptyList(), syntax.errors.map { it.toString() })
        assertEquals(listOf("PACKAGE_DIRECTIVE", "IMPORT_LIST", "SCRIPT"), syntax.topLevel.map { it.first })
    }
}
