package editor.language.kotlin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResolveTest {
    private val dollar = "$"

    @Test
    fun aParameterIsFoundFromTheBodyTheTemplateAndTheDocumentation() {
        val text = """
            /** Greets [name]. */
            fun greet(name: String): String = "Hello, ${dollar}name!"
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("2 1 2", file.occurrenceLines("name"))
        assertEquals(SymbolKind.PARAMETER, file.symbolAt(text.offsetOf("name", 3))?.kind)
        assertTrue(file.isDeclarationAt(text.offsetOf("name", 2)))
        assertFalse(file.isDeclarationAt(text.offsetOf("name", 3)))
    }

    @Test
    fun theCaretMayStandAtEitherEndOfAName() {
        val text = "fun greet() = 1\nval a = greet()"
        val file = KotlinFile(text)
        val use = text.offsetOf("greet", 2)

        assertEquals("greet", file.symbolAt(use)?.name)
        assertEquals("greet", file.symbolAt(use + 5)?.name)
        assertNull(file.symbolAt(use + 6))
        assertNull(file.symbolAt(text.offsetOf("=")))
    }

    @Test
    fun aLocalVariableShadowsAParameterOnlyAfterItsDeclaration() {
        val text = """
            fun shadow(value: Int): Int {
                val before = value
                val value = before + 1
                return value
            }
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("1 2", file.occurrenceLines("value"))
        assertEquals("3 4", file.occurrenceLines("value", 3))
    }

    @Test
    fun blocksLimitTheirDeclarations() {
        val text = """
            fun blocks(flag: Boolean) {
                if (flag) {
                    val inner = 1
                    println(inner)
                }
                println(inner)
            }
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("3 4", file.occurrenceLines("inner"))
        assertNull(file.symbolAt(text.offsetOf("inner", 3)))
    }

    @Test
    fun membersAreVisibleBeforeTheirDeclaration() {
        val text = """
            class Order {
                fun total() = price * count()
                val price = 2
                fun count() = 3
            }
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("3 2", file.occurrenceLines("price"))
        assertEquals("4 2", file.occurrenceLines("count"))
    }

    @Test
    fun aCallPrefersAFunctionAndAValueAProperty() {
        val text = """
            val size = 1
            fun size() = 2
            val a = size
            val b = size()
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals(SymbolKind.PROPERTY, file.symbolAt(text.offsetOf("size", 3))?.kind)
        assertEquals(SymbolKind.FUNCTION, file.symbolAt(text.offsetOf("size", 4))?.kind)
    }

    @Test
    fun lambdaParametersLoopVariablesAndDestructuring() {
        val text = """
            fun walk(map: Map<String, List<Int>>) {
                map.forEach { key, values ->
                    for (value in values) println(key + value)
                }
                for ((name, items) in map) println(name + items)
                val (first, second) = map.entries.first()
                println(first.toString() + second)
                map.mapValues { (_, list): Map.Entry<String, List<Int>> -> list.size }
            }
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("2 3", file.occurrenceLines("key"))
        assertEquals("2 3", file.occurrenceLines("values"))
        assertEquals("3 3", file.occurrenceLines("value "))
        assertEquals("5 5", file.occurrenceLines("name"))
        assertEquals("5 5", file.occurrenceLines("items"))
        assertEquals("6 7", file.occurrenceLines("first"))
        assertEquals("6 7", file.occurrenceLines("second"))
        assertEquals("8 8", file.occurrenceLines("list"))
        assertEquals(SymbolKind.PARAMETER, file.symbolAt(text.offsetOf("key"))?.kind)
        assertEquals(SymbolKind.LOCAL_VARIABLE, file.symbolAt(text.offsetOf("value "))?.kind)
        // The type in the parameter list is not a parameter.
        assertNull(file.symbolAt(text.offsetOf("Entry")))
    }

    @Test
    fun whenBranchesAreNotLambdaParameters() {
        val text = """
            fun name(code: Int, other: Int) = when (code) {
                other -> "same"
                else -> "different"
            }
        """.trimIndent()

        assertEquals("1 2", KotlinFile(text).occurrenceLines("other"))
    }

    @Test
    fun catchParametersAndWhenSubjects() {
        val text = """
            fun parse(text: String): Int {
                try {
                    return text.toInt()
                } catch (error: NumberFormatException) {
                    println(error)
                }
                return when (val length = text.length) {
                    0 -> 0
                    else -> length
                }
            }
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("4 5", file.occurrenceLines("error"))
        assertEquals("7 9", file.occurrenceLines("length"))
    }

    @Test
    fun constructorParametersAndProperties() {
        val text = """
            class Point(x: Int, val y: Int) {
                val doubled = x * 2
                fun sum() = doubled + y
            }
            val point = Point(x = 1, y = 2)
            val y = point.y
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("1 2 5", file.occurrenceLines("x"))
        // The member is not mixed up with the top-level property of the same name.
        assertEquals("1 3 5", file.occurrenceLines("y"))
        assertEquals(SymbolKind.PROPERTY, file.symbolAt(text.offsetOf("y"))?.kind)
        assertEquals(SymbolKind.PARAMETER, file.symbolAt(text.offsetOf("x"))?.kind)
        assertEquals("1 5", file.occurrenceLines("Point"))
    }

    @Test
    fun membersOfSupertypesDeclaredInTheFile() {
        val text = """
            open class Animal(val name: String) {
                open fun sound() = "..."
            }
            interface Named {
                fun label() = "named"
            }
            class Dog : Animal("dog"), Named {
                override fun sound() = name + label() + super.sound()
            }
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("1 8", file.occurrenceLines("name"))
        assertEquals("5 8", file.occurrenceLines("label"))
        assertEquals("2 8", file.occurrenceLines("sound"))
        assertEquals("1 7", file.occurrenceLines("Animal"))
        assertEquals("4 7", file.occurrenceLines("Named"))
    }

    @Test
    fun thisAndQualifiedMembers() {
        val text = """
            object Config {
                val limit = 10
            }
            enum class Mode { FAST, SLOW }
            class Job(limit: Int) {
                val limit = limit
                fun own() = limit
                fun over() = this.limit > Config.limit && Mode.FAST != Mode.SLOW
            }
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("2 8", file.occurrenceLines("limit"))
        // The parameter is visible in the initializer, the property everywhere else.
        assertEquals("5 6", file.occurrenceLines("limit", 2))
        assertEquals("6 7 8", file.occurrenceLines("limit", 3))
        assertEquals("4 8", file.occurrenceLines("FAST"))
        assertEquals("4 8 8", file.occurrenceLines("Mode"))
    }

    @Test
    fun aMemberAfterADotIsFoundOnlyWhenItsNameIsUnique() {
        val text = """
            class A { val unique = 1; val shared = 1 }
            class B { val shared = 2 }
            fun use(a: A, b: B, list: List<Int>) = a.unique + a.shared + b.shared + list.size
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("1 3", file.occurrenceLines("unique"))
        assertNull(file.symbolAt(text.offsetOf("shared", 3)))
        assertNull(file.symbolAt(text.offsetOf("size")))
    }

    @Test
    fun extensionFunctionsAndProperties() {
        val text = """
            fun String.shout() = uppercase() + "!"
            val List<Int>.middle: Int get() = this[size / 2]
            fun <T> List<T>.second(): T = this[1]
            val a = "a".shout() + listOf(1).middle + listOf(2).second()
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("1 4", file.occurrenceLines("shout"))
        assertEquals("2 4", file.occurrenceLines("middle"))
        assertEquals("3 4", file.occurrenceLines("second"))
        assertEquals("3 3 3", file.occurrenceLines("T"))
    }

    @Test
    fun typeParametersOfClassesAndFunctions() {
        val text = """
            class Box<T>(val item: T) {
                fun <R> map(transform: (T) -> R): Box<R> = Box(transform(item))
            }
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("1 1 2", file.occurrenceLines("T"))
        assertEquals("2 2 2", file.occurrenceLines("R"))
        assertEquals("2 2", file.occurrenceLines("transform"))
    }

    @Test
    fun importsAndAliases() {
        val text = """
            package editor.sample

            import kotlin.math.max
            import kotlin.collections.List as Items

            val biggest = max(1, 2)
            val items: Items<Int> = listOf()
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("3 6", file.occurrenceLines("max"))
        assertEquals("4 7", file.occurrenceLines("Items"))
        assertNull(file.symbolAt(text.offsetOf("sample")))
        assertNull(file.symbolAt(text.offsetOf("math")))
    }

    @Test
    fun namedArgumentsReferToParameters() {
        val text = """
            fun area(width: Int, height: Int) = width * height
            val a = area(width = 2, height = 3)
            val width = 5
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("1 1 2", file.occurrenceLines("width"))
        assertEquals("3", file.occurrenceLines("width", 4))
    }

    @Test
    fun expressionBodiesMayRunOverSeveralLines() {
        val text = """
            fun sign(number: Int) = if (number > 0)
                number
            else
                -number

            fun chain(items: List<Int>) = items
                .filter { it > 0 }
                .map { item -> item + items.size }

            val number = 0
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("1 1 2 4", file.occurrenceLines("number"))
        assertEquals("6 6 8", file.occurrenceLines("items"))
        assertEquals("10", file.occurrenceLines("number", 5))
    }

    @Test
    fun writesAreToldFromReads() {
        val text = """
            fun count(): Int {
                var total = 0
                total += 1
                total = total + 1
                total++
                return total
            }
        """.trimIndent()
        val file = KotlinFile(text)
        val occurrences = file.occurrences(file.symbolAt(text.offsetOf("total"))!!)

        assertEquals(listOf(true, true, true, false, true, false), occurrences.map { it.write })
        assertEquals(listOf(true, false, false, false, false, false), occurrences.map { it.declaration })
    }

    @Test
    fun namesInBackticks() {
        val text = "fun `my test`() = 1\nval a = `my test`()"
        val file = KotlinFile(text)

        assertEquals("my test", file.symbolAt(text.offsetOf("`my test`", 2))?.name)
        assertEquals("1 2", file.occurrenceLines("`my test`"))
    }

    @Test
    fun nothingIsResolvedInsideStringsAndComments() {
        val text = "val name = 1\nval a = \"name\" // name"
        val file = KotlinFile(text)

        assertNull(file.symbolAt(text.offsetOf("name", 2) + 1))
        assertNull(file.symbolAt(text.offsetOf("name", 3) + 1))
        assertEquals("1", file.occurrenceLines("name"))
    }

    @Test
    fun membersOfTheCompanionObject() {
        val text = """
            class Circle(val radius: Double) {
                fun area() = PI * radius * radius

                companion object {
                    const val PI = 3.14
                    fun unit() = Circle(1.0)
                }
            }
            val area = Circle.unit().area() * Circle.PI
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("5 2 9", file.occurrenceLines("PI"))
        assertEquals("6 9", file.occurrenceLines("unit"))
    }

    @Test
    fun aCallThroughAnUnknownReceiverGoesToTheOverriddenDeclaration() {
        val text = """
            interface Shape { fun area(): Double }
            open class Circle : Shape { override fun area() = 1.0 }
            class Disc : Circle() { override fun area() = 2.0 }
            class Other { fun size() = 1 }
            class Unrelated { fun size() = 2 }
            fun total(shapes: List<Shape>, other: Other) = shapes.sumOf { it.area() } + other.size()
        """.trimIndent()
        val file = KotlinFile(text)

        assertEquals("1 6", file.occurrenceLines("area"))
        assertEquals("2", file.occurrenceLines("area", 2))
        // Two unrelated classes declare it, so the call could mean either.
        assertNull(file.symbolAt(text.offsetOf("size", 3)))
    }
}
