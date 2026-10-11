package editor.language.kotlin

/** Lexical classes of tokens; plain integers because the tokens are kept in arrays. */
internal object TokenKind {
    const val IDENTIFIER = 0
    const val KEYWORD = 1
    const val NUMBER = 2
    const val CHARACTER = 3

    /** A piece of a string literal, with its quotes when it has them. */
    const val STRING = 4
    const val ESCAPE = 5

    /** The `$` of `$name`. */
    const val TEMPLATE_MARK = 6
    const val TEMPLATE_START = 7
    const val TEMPLATE_END = 8
    const val LINE_COMMENT = 9
    const val BLOCK_COMMENT = 10

    /** A piece of a documentation comment that is neither a tag nor a link. */
    const val DOC_COMMENT = 11
    const val DOC_TAG = 12
    const val ANNOTATION = 13

    /** `name@` in front of a statement or `@name` after `return`, `this` and the like. */
    const val LABEL = 14
    const val OPERATOR = 15
    const val LPAREN = 16
    const val RPAREN = 17
    const val LBRACKET = 18
    const val RBRACKET = 19
    const val LBRACE = 20
    const val RBRACE = 21
    const val LT = 22
    const val GT = 23
    const val COMMA = 24

    /** `.` and `?.`. */
    const val DOT = 25
    const val COLON = 26
    const val COLON_COLON = 27
    const val SEMICOLON = 28
    const val ARROW = 29
    const val ASSIGN = 30
}

internal const val FLAG_LINE_BREAK_BEFORE = 1

/** The identifier is a link or a parameter name inside a documentation comment. */
internal const val FLAG_IN_DOC = 2

/** The tokens of a file in source order, without whitespace. */
internal class Tokens(val text: String, val count: Int, val starts: IntArray, val ends: IntArray, val kinds: IntArray, val flags: IntArray) {
    fun isComment(index: Int): Boolean =
        kinds[index].let { it == TokenKind.LINE_COMMENT || it == TokenKind.BLOCK_COMMENT || it == TokenKind.DOC_COMMENT || it == TokenKind.DOC_TAG }

    fun textOf(index: Int): String = text.substring(starts[index], ends[index])

    fun textIs(index: Int, expected: String): Boolean =
        ends[index] - starts[index] == expected.length && text.startsWith(expected, starts[index])

    /** Index of the first token ending after [offset], or [count]. */
    fun firstEndingAfter(offset: Int): Int {
        var low = 0
        var high = count
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ends[middle] <= offset) low = middle + 1 else high = middle
        }
        return low
    }
}

internal val hardKeywords = setOf(
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
    "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
    "when", "while",
)

private val labelledKeywords = setOf("return", "break", "continue", "this", "super")

private val docTagsWithName = setOf("@param", "@property", "@throws", "@exception", "@see", "@sample")

private val operators = listOf(
    "===", "!==", "..<", "...", "->", "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "%=",
    "..", "?:", "?.", "::", "!!",
)

internal fun isIdentifierStart(char: Char): Boolean = char == '_' || char.isLetter()

internal fun isIdentifierPart(char: Char): Boolean = char == '_' || char.isLetterOrDigit()

/**
 * Splits Kotlin source into tokens.
 *
 * It never fails: text that is not valid Kotlin, like a string left open while typing, still gets tokens up to its end.
 */
internal class Lexer(private val text: String) {
    private val length = text.length
    private var position = 0
    private var lineBreak = false

    private var count = 0
    private var starts = IntArray(256)
    private var ends = IntArray(256)
    private var kinds = IntArray(256)
    private var flags = IntArray(256)

    fun tokenize(): Tokens {
        while (position < length) {
            code(inTemplate = false)
            // A closing brace of a template without a string around it cannot happen; this is only a guard.
            if (position < length && text[position] == '}') add(TokenKind.RBRACE, position, ++position)
        }
        return Tokens(text, count, starts, ends, kinds, flags)
    }

    private fun add(kind: Int, start: Int, end: Int, flag: Int = 0) {
        if (end <= start) return
        if (count == starts.size) {
            val capacity = count * 2
            starts = starts.copyOf(capacity)
            ends = ends.copyOf(capacity)
            kinds = kinds.copyOf(capacity)
            flags = flags.copyOf(capacity)
        }
        starts[count] = start
        ends[count] = end
        kinds[count] = kind
        flags[count] = flag or (if (lineBreak) FLAG_LINE_BREAK_BEFORE else 0)
        lineBreak = false
        count++
    }

    private fun peek(offset: Int): Char = if (position + offset < length) text[position + offset] else '\u0000'

    /** Reads code up to the end of the text or, [inTemplate], up to the brace closing `${`. */
    private fun code(inTemplate: Boolean) {
        var depth = 0
        while (position < length) {
            val char = text[position]
            when {
                char == '\n' -> {
                    lineBreak = true
                    position++
                }
                char == ' ' || char == '\t' || char == '\r' -> position++
                char == '/' && peek(1) == '/' -> lineComment()
                char == '/' && peek(1) == '*' -> blockComment()
                char == '"' -> string()
                char == '\'' -> character()
                char in '0'..'9' -> number()
                char == '`' -> quotedIdentifier()
                isIdentifierStart(char) -> word()
                char == '@' -> at()
                char == '{' -> {
                    if (inTemplate) depth++
                    add(TokenKind.LBRACE, position, ++position)
                }
                char == '}' -> {
                    if (inTemplate) {
                        if (depth == 0) {
                            add(TokenKind.TEMPLATE_END, position, ++position)
                            return
                        }
                        depth--
                    }
                    add(TokenKind.RBRACE, position, ++position)
                }
                else -> operator()
            }
        }
    }

    private fun lineComment() {
        val start = position
        while (position < length && text[position] != '\n') position++
        add(TokenKind.LINE_COMMENT, start, position)
    }

    private fun blockComment() {
        val start = position
        val documentation = peek(2) == '*' && peek(3) != '/'
        position += 2
        var depth = 1
        var hasLineBreak = false
        var pieceStart = start
        var atLineStart = false
        val kind = if (documentation) TokenKind.DOC_COMMENT else TokenKind.BLOCK_COMMENT
        while (position < length && depth > 0) {
            val char = text[position]
            when {
                char == '/' && peek(1) == '*' -> {
                    depth++
                    position += 2
                }
                char == '*' && peek(1) == '/' -> {
                    depth--
                    position += 2
                }
                char == '\n' -> {
                    hasLineBreak = true
                    atLineStart = true
                    position++
                }
                documentation && char == '@' && isIdentifierStart(peek(1)) && (atLineStart || position == start + 3 || text[position - 1] == ' ') -> {
                    add(kind, pieceStart, position)
                    pieceStart = docTag()
                    atLineStart = false
                }
                documentation && char == '[' && isIdentifierStart(peek(1)) -> {
                    val linkEnd = docLinkEnd(position + 1)
                    if (linkEnd < 0) {
                        position++
                    } else {
                        add(kind, pieceStart, position + 1)
                        position++
                        pieceStart = docPath(linkEnd)
                    }
                    atLineStart = false
                }
                else -> {
                    if (char != ' ' && char != '\t' && char != '*') atLineStart = false
                    position++
                }
            }
        }
        add(kind, pieceStart, position)
        if (hasLineBreak) lineBreak = true
    }

    /** Reads `@tag` and, for the tags that take one, the name after it; returns where the plain comment goes on. */
    private fun docTag(): Int {
        val start = position
        position++
        while (position < length && isIdentifierPart(text[position])) position++
        add(TokenKind.DOC_TAG, start, position)
        if (text.substring(start, position) !in docTagsWithName) return position
        var name = position
        while (name < length && text[name] == ' ') name++
        if (name >= length || !isIdentifierStart(text[name])) return position
        var end = name
        while (end < length && (isIdentifierPart(text[end]) || text[end] == '.')) end++
        while (end > name && text[end - 1] == '.') end--
        add(TokenKind.DOC_COMMENT, position, name)
        position = name
        return docPath(end)
    }

    /** End of the dotted name starting at [from] if it is closed by `]` and is not a Markdown link, otherwise -1. */
    private fun docLinkEnd(from: Int): Int {
        var end = from
        while (end < length && (isIdentifierPart(text[end]) || text[end] == '.')) end++
        if (end >= length || text[end] != ']') return -1
        if (end + 1 < length && text[end + 1] == '(') return -1
        return end
    }

    /** Adds the identifiers of the dotted name from [position] to [end]; returns where the plain comment goes on. */
    private fun docPath(end: Int): Int {
        while (position < end) {
            val start = position
            while (position < end && text[position] != '.') position++
            add(TokenKind.IDENTIFIER, start, position, FLAG_IN_DOC)
            if (position < end) {
                add(TokenKind.DOC_COMMENT, position, position + 1)
                position++
            }
        }
        return position
    }

    private fun string() {
        val raw = peek(1) == '"' && peek(2) == '"'
        var pieceStart = position
        position += if (raw) 3 else 1
        while (position < length) {
            val char = text[position]
            when {
                char == '"' && !raw -> {
                    add(TokenKind.STRING, pieceStart, ++position)
                    return
                }
                char == '"' && peek(1) == '"' && peek(2) == '"' -> {
                    // Quotes in front of the closing three belong to the string.
                    while (position < length && text[position] == '"') position++
                    add(TokenKind.STRING, pieceStart, position)
                    return
                }
                char == '\n' && !raw -> {
                    add(TokenKind.STRING, pieceStart, position)
                    return
                }
                char == '\\' && !raw -> {
                    add(TokenKind.STRING, pieceStart, position)
                    val escapeLength = if (peek(1) == 'u') 6 else 2
                    val end = minOf(position + escapeLength, lineEnd(position))
                    add(TokenKind.ESCAPE, position, end)
                    position = end
                    pieceStart = position
                }
                char == '$' && peek(1) == '{' -> {
                    add(TokenKind.STRING, pieceStart, position)
                    add(TokenKind.TEMPLATE_START, position, position + 2)
                    position += 2
                    code(inTemplate = true)
                    pieceStart = position
                }
                char == '$' && (isIdentifierStart(peek(1)) || peek(1) == '`') -> {
                    add(TokenKind.STRING, pieceStart, position)
                    add(TokenKind.TEMPLATE_MARK, position, ++position)
                    if (text[position] == '`') quotedIdentifier() else plainWord()
                    pieceStart = position
                }
                else -> position++
            }
        }
        add(TokenKind.STRING, pieceStart, position)
    }

    private fun lineEnd(from: Int): Int {
        val end = text.indexOf('\n', from)
        return if (end < 0) length else end
    }

    private fun character() {
        val start = position
        val limit = lineEnd(position)
        position++
        while (position < limit && text[position] != '\'') position += if (text[position] == '\\') 2 else 1
        position = minOf(position + 1, limit)
        add(TokenKind.CHARACTER, start, position)
    }

    private fun digits(hexadecimal: Boolean = false) {
        while (position < length) {
            val char = text[position]
            if (char in '0'..'9' || char == '_' || hexadecimal && (char in 'a'..'f' || char in 'A'..'F')) position++ else break
        }
    }

    private fun number() {
        val start = position
        if (text[position] == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            position += 2
            digits(hexadecimal = true)
        } else if (text[position] == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            position += 2
            digits()
        } else {
            digits()
            if (peek(0) == '.' && peek(1) in '0'..'9') {
                position++
                digits()
            }
            if ((peek(0) == 'e' || peek(0) == 'E') && (peek(1) in '0'..'9' || (peek(1) == '+' || peek(1) == '-') && peek(2) in '0'..'9')) {
                position += 2
                digits()
            }
        }
        while (position < length && text[position] in "uULfF") position++
        add(TokenKind.NUMBER, start, position)
    }

    private fun quotedIdentifier() {
        val start = position
        val limit = lineEnd(position)
        position++
        while (position < limit && text[position] != '`') position++
        position = minOf(position + 1, limit)
        add(TokenKind.IDENTIFIER, start, position)
    }

    private fun plainWord() {
        val start = position
        while (position < length && isIdentifierPart(text[position])) position++
        val keyword = text.substring(start, position) in hardKeywords
        add(if (keyword) TokenKind.KEYWORD else TokenKind.IDENTIFIER, start, position)
    }

    private fun word() {
        val start = position
        while (position < length && isIdentifierPart(text[position])) position++
        val keyword = text.substring(start, position) in hardKeywords
        if (!keyword && peek(0) == '@' && !isIdentifierStart(peek(1))) {
            add(TokenKind.LABEL, start, ++position)
        } else {
            add(if (keyword) TokenKind.KEYWORD else TokenKind.IDENTIFIER, start, position)
        }
    }

    private fun at() {
        val start = position
        val afterKeyword = count > 0 && ends[count - 1] == start && kinds[count - 1] == TokenKind.KEYWORD &&
            text.substring(starts[count - 1], start) in labelledKeywords
        position++
        if (position >= length || !isIdentifierStart(text[position])) {
            add(TokenKind.ANNOTATION, start, position)
            return
        }
        while (position < length && isIdentifierPart(text[position])) position++
        if (afterKeyword) {
            add(TokenKind.LABEL, start, position)
            return
        }
        // A use-site target as in `@file:JvmName`, then the rest of a qualified name.
        if (peek(0) == ':' && isIdentifierStart(peek(1))) position++
        while (position < length && (isIdentifierPart(text[position]) || text[position] == '.' && isIdentifierStart(peek(1)))) position++
        add(TokenKind.ANNOTATION, start, position)
    }

    private fun operator() {
        val start = position
        for (operator in operators) {
            if (text.startsWith(operator, position)) {
                position += operator.length
                val kind = when (operator) {
                    "->" -> TokenKind.ARROW
                    "?." -> TokenKind.DOT
                    "::" -> TokenKind.COLON_COLON
                    else -> TokenKind.OPERATOR
                }
                add(kind, start, position)
                return
            }
        }
        val kind = when (text[position]) {
            '(' -> TokenKind.LPAREN
            ')' -> TokenKind.RPAREN
            '[' -> TokenKind.LBRACKET
            ']' -> TokenKind.RBRACKET
            '<' -> TokenKind.LT
            '>' -> TokenKind.GT
            ',' -> TokenKind.COMMA
            '.' -> TokenKind.DOT
            ':' -> TokenKind.COLON
            ';' -> TokenKind.SEMICOLON
            '=' -> TokenKind.ASSIGN
            else -> TokenKind.OPERATOR
        }
        add(kind, start, ++position)
    }
}
