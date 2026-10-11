@file:Suppress("unused")

package sample

import kotlin.math.max

/**
 * A shape with a [name].
 * @param name how it is called
 * @see Circle
 */
abstract class Shape(val name: String) {
    abstract fun area(): Double

    override fun toString() = "$name of ${area()}\n"
}

data class Circle(private val radius: Double) : Shape("circle") {
    override fun area(): Double {
        var result = radius * radius
        result *= PI
        return result
    }

    companion object {
        const val PI = 3.14159
    }
}

enum class Scale(val factor: Int) { METER(1), KILOMETER(1_000) }

interface Named {
    val title: String get() = "untitled"
}

object Registry : Named {
    private val shapes = mutableListOf<Shape>()

    operator fun plusAssign(shape: Shape) {
        shapes += shape
    }

    val size: Int get() = shapes.size
}

sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data object Failure : Outcome<Nothing>
}

typealias Predicate<T> = (T) -> Boolean

@Target(AnnotationTarget.FUNCTION)
annotation class Marker(val label: String = "")

inline fun <reified T : Shape> List<Shape>.only(noinline accept: Predicate<T> = { true }): List<T> =
    filterIsInstance<T>().filter(accept)

@Marker("largest")
fun <T : Shape> largest(shapes: List<T>, limit: Int = 0xFF): T? {
    // The biggest one wins.
    loop@ for ((index, shape) in shapes.withIndex()) {
        if (index > limit) break@loop
        println(shape.area() + Scale.METER.factor)
    }
    return shapes.maxByOrNull { it.area() }
}

fun describe(outcome: Outcome<Int>): String = when (outcome) {
    is Outcome.Success -> "got ${outcome.value}"
    Outcome.Failure -> "nothing"
}

fun risky(text: String?): Int = try {
    text!!.length.also { check(it >= 0) { "negative" } }
} catch (e: NullPointerException) {
    -1
} finally {
    Registry += Circle(1.0)
}

fun main() {
    val circle = Circle(radius = 2.0)
    Registry += circle
    val squares = (1..3).map { it * it }.joinToString(separator = ", ") { number -> "#$number" }
    var total = 0L
    for (value in intArrayOf(1, 2, 3)) total += value
    while (total < 10) total++
    do total-- while (total > 8)
    val ratio = if (total > 0) max(1, 2) / 2.0f else 0f
    val pair = "key" to 'c'
    val (key, char) = pair
    val lazyValue by lazy { key + char }
    println(largest(listOf(circle)))
    println(listOf<Shape>(circle).only<Circle>().size)
    println(describe(Outcome.Success(risky("four"))) + describe(Outcome.Failure))
    println("$squares $ratio $lazyValue ${Registry.title} ${Registry.size} ${Scale.entries.last()}")
    println("""
        raw
    """.trimIndent())
}
