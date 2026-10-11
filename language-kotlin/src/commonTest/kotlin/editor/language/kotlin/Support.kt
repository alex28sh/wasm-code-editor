package editor.language.kotlin

/** The highlighted pieces of [text] as `piece:HIGHLIGHT`, in source order. */
fun highlighted(text: String): List<String> {
    val result = ArrayList<String>()
    KotlinFile(text).highlights(0, text.length) { start, end, highlight -> result += "${text.substring(start, end)}:$highlight" }
    return result
}

/** The highlight of the [occurrence]-th appearance of [piece] in [text], or null when it is not highlighted as a whole. */
fun highlightOf(text: String, piece: String, occurrence: Int = 1): Highlight? {
    val offset = text.offsetOf(piece, occurrence)
    var found: Highlight? = null
    KotlinFile(text).highlights(offset, offset + piece.length) { start, end, highlight ->
        if (start == offset && end == offset + piece.length) found = highlight
    }
    return found
}

fun String.offsetOf(piece: String, occurrence: Int = 1): Int {
    var offset = -1
    repeat(occurrence) {
        offset = indexOf(piece, offset + 1)
        require(offset >= 0) { "'$piece' occurs fewer than $occurrence times" }
    }
    return offset
}

/** The lines on which the symbol named at the [occurrence]-th [piece] occurs, the declaration first: `2 5 7`. */
fun KotlinFile.occurrenceLines(piece: String, occurrence: Int = 1): String {
    val symbol = symbolAt(text.offsetOf(piece, occurrence)) ?: return "unresolved"
    val lines = occurrences(symbol).map { text.lineOf(it.start) }
    val declaration = text.lineOf(symbol.nameStart)
    return (listOf(declaration) + (lines - declaration)).joinToString(" ")
}

fun String.lineOf(offset: Int): Int = substring(0, offset).count { it == '\n' } + 1
