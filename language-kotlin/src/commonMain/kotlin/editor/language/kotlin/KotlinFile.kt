package editor.language.kotlin

private val completionKeywords = (hardKeywords - "typeof" + listOf(
    "import", "override", "private", "protected", "public", "internal", "open", "abstract", "data", "sealed", "enum",
    "companion", "inline", "suspend", "lateinit", "const", "constructor", "init", "by", "where", "catch", "finally",
)).sorted()

private val namingKeywords = setOf("val", "var", "fun", "class", "interface", "object", "typealias", "package")

/**
 * The analysis of one Kotlin source text: what to highlight, what is declared where and what refers to it.
 *
 * It is made for an editor, so it works on any text, complete or not, and never throws.
 */
class KotlinFile(val text: String) {
    private val tokens = Lexer(text).tokenize()
    private val analyzer = Analyzer(tokens)
    private val highlightKinds = Highlight.entries

    /** Everything the file declares, in source order. */
    val symbols: List<Symbol> = analyzer.symbols.sortedBy { it.nameStart }

    /** The declarations that make up the outline of the file. */
    val structure: List<Symbol> = symbols.filter { it.isStructural }

    /** Calls [sink] for every highlighted piece that overlaps the range from [from] to [to]. */
    fun highlights(from: Int, to: Int, sink: (start: Int, end: Int, highlight: Highlight) -> Unit) {
        var token = tokens.firstEndingAfter(from)
        while (token < tokens.count && tokens.starts[token] < to) {
            val highlight = analyzer.highlights[token]
            if (highlight >= 0) sink(tokens.starts[token], tokens.ends[token], highlightKinds[highlight])
            token++
        }
    }

    /** Index of the identifier at [offset] or directly in front of it, or -1. */
    private fun identifierAt(offset: Int): Int {
        val after = tokens.firstEndingAfter(offset)
        if (after < tokens.count && tokens.starts[after] <= offset && tokens.kinds[after] == TokenKind.IDENTIFIER) return after
        val before = after - 1
        if (before >= 0 && tokens.ends[before] == offset && tokens.kinds[before] == TokenKind.IDENTIFIER) return before
        return -1
    }

    /** The symbol named at [offset], by its declaration or by a reference to it. */
    fun symbolAt(offset: Int): Symbol? = identifierAt(offset).let { if (it < 0) null else analyzer.resolved[it] }

    /** The caret at [offset] is on the name in the declaration of the symbol, not on a use of it. */
    fun isDeclarationAt(offset: Int): Boolean {
        val token = identifierAt(offset)
        return token >= 0 && analyzer.resolved[token]?.token == token
    }

    /** The declaration and all uses of [symbol] in source order. */
    fun occurrences(symbol: Symbol): List<Occurrence> {
        val result = ArrayList<Occurrence>(symbol.references.size + 1)
        result += Occurrence(symbol.nameStart, symbol.nameEnd, declaration = true, write = symbol.kind == SymbolKind.PROPERTY || symbol.kind == SymbolKind.LOCAL_VARIABLE)
        for (token in symbol.references) {
            result += Occurrence(tokens.starts[token], tokens.ends[token], declaration = false, write = analyzer.isWrite(token))
        }
        result.sortBy { it.start }
        return result
    }

    /** The innermost function, property or type of the outline whose declaration contains [offset]. */
    fun enclosing(offset: Int): Symbol? = structure.lastOrNull { offset >= it.start && offset <= it.end }

    /**
     * The range to fold for the line from [lineStart] to [lineEnd]: a block or a comment that starts on the line
     * and ends on a later one, or the lines of imports after the first.
     */
    fun foldRange(lineStart: Int, lineEnd: Int): IntRange? {
        var token = tokens.firstEndingAfter(lineStart)
        var result: IntRange? = null
        while (token < tokens.count && tokens.starts[token] < lineEnd) {
            val kind = tokens.kinds[token]
            if ((kind == TokenKind.BLOCK_COMMENT || kind == TokenKind.DOC_COMMENT) && tokens.starts[token] >= lineStart && text.startsWith("/*", tokens.starts[token])) {
                val end = commentEnd(token)
                if (end - 2 > lineEnd) return (tokens.starts[token] + (if (kind == TokenKind.DOC_COMMENT) 3 else 2))..(end - 2)
            }
            token++
        }
        for (pair in analyzer.braces) {
            val open = tokens.starts[pair[0]]
            if (open < lineStart) continue
            if (open >= lineEnd) break
            if (tokens.starts[pair[1]] > lineEnd) result = (open + 1)..tokens.starts[pair[1]]
        }
        return result ?: importsRange(lineStart, lineEnd)
    }

    private fun commentEnd(token: Int): Int {
        var last = token
        while (last + 1 < tokens.count && !text.startsWith("*/", tokens.ends[last] - 2) &&
            (tokens.isComment(last + 1) || tokens.flags[last + 1] and FLAG_IN_DOC != 0)
        ) last++
        return tokens.ends[last]
    }

    private fun isImportLine(lineStart: Int): Boolean {
        var start = lineStart
        while (start < text.length && (text[start] == ' ' || text[start] == '\t')) start++
        return text.startsWith("import ", start)
    }

    private fun importsRange(lineStart: Int, lineEnd: Int): IntRange? {
        if (!isImportLine(lineStart)) return null
        if (lineStart > 0 && isImportLine(text.lastIndexOf('\n', lineStart - 2) + 1)) return null
        var end = lineEnd
        while (end < text.length && isImportLine(end + 1)) {
            end = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
        }
        if (end == lineEnd) return null
        return (text.indexOf("import", lineStart) + "import ".length)..end
    }

    /** What can be written at [offset] in place of the [prefix] in front of it. */
    fun completions(offset: Int, prefix: String, limit: Int = 200): List<Completion> {
        val start = offset - prefix.length
        if (start < 0 || isText(start) || namesDeclaration(start)) return emptyList()
        var before = start - 1
        while (before >= 0 && (text[before] == ' ' || text[before] == '\t')) before--
        val afterDot = before >= 0 && text[before] == '.'
        val candidates = if (afterDot) analyzer.memberSymbols().filter { !it.topLevel || it.kind == SymbolKind.FUNCTION || it.kind == SymbolKind.PROPERTY } else analyzer.visibleSymbols(offset - prefix.length)
        val result = ArrayList<Completion>()
        for (symbol in candidates) {
            if (result.size >= limit) return result
            if (!symbol.name.startsWith(prefix, ignoreCase = true) || symbol.nameStart == start) continue
            result += Completion(symbol.name, completionKind(symbol), if (symbol.signature != symbol.name) symbol.signature else symbol.kind.title)
        }
        if (afterDot || prefix.isEmpty()) return result
        for (keyword in completionKeywords) {
            if (result.size >= limit) break
            if (keyword.startsWith(prefix)) result += Completion(keyword, "keyword", "")
        }
        return result
    }

    /** The offset is inside a comment, a string or a number, where names mean nothing. */
    private fun isText(offset: Int): Boolean {
        if (offset == 0) return false
        val token = tokens.firstEndingAfter(offset - 1)
        if (token >= tokens.count || tokens.starts[token] >= offset) return false
        if (tokens.flags[token] and FLAG_IN_DOC != 0) return true
        return when (tokens.kinds[token]) {
            TokenKind.STRING -> tokens.ends[token] > offset || !text.startsWith("\"", tokens.ends[token] - 1) || tokens.ends[token] - tokens.starts[token] < 2
            TokenKind.LINE_COMMENT, TokenKind.NUMBER, TokenKind.ESCAPE, TokenKind.CHARACTER, TokenKind.DOC_TAG -> true
            TokenKind.BLOCK_COMMENT, TokenKind.DOC_COMMENT -> tokens.ends[token] > offset || !text.startsWith("*/", tokens.ends[token] - 2)
            else -> false
        }
    }

    /** A new name is being written at [offset], as after `val` or `fun`; there is nothing to offer for it. */
    private fun namesDeclaration(offset: Int): Boolean {
        var token = tokens.firstEndingAfter(offset - 1)
        if (token >= tokens.count || tokens.starts[token] >= offset) token--
        while (token >= 0 && tokens.isComment(token)) token--
        if (token < 0 || tokens.ends[token] > offset || tokens.kinds[token] != TokenKind.KEYWORD) return false
        return tokens.textOf(token) in namingKeywords
    }

    private fun completionKind(symbol: Symbol): String = when (symbol.kind) {
        SymbolKind.CLASS, SymbolKind.OBJECT, SymbolKind.TYPE_ALIAS, SymbolKind.IMPORT -> "class"
        SymbolKind.INTERFACE -> "interface"
        SymbolKind.ENUM_ENTRY -> "enum"
        SymbolKind.TYPE_PARAMETER -> "type"
        SymbolKind.FUNCTION -> "function"
        SymbolKind.PROPERTY -> "property"
        SymbolKind.PARAMETER, SymbolKind.LOCAL_VARIABLE -> "variable"
    }
}
