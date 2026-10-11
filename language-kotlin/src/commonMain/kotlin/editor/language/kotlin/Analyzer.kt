package editor.language.kotlin

private val modifiers = setOf(
    "public", "private", "protected", "internal", "open", "abstract", "final", "override", "data", "sealed", "enum",
    "inner", "companion", "inline", "suspend", "operator", "infix", "tailrec", "external", "const", "lateinit",
    "annotation", "value", "expect", "actual",
)
private val parameterModifiers = setOf("vararg", "noinline", "crossinline")
private val declarationKeywords = setOf("class", "interface", "object", "fun", "val", "var", "typealias")

/** Tokens after which a statement goes on in the next line. */
private val continuingOperators = setOf(
    "+", "-", "*", "/", "%", "&&", "||", "?:", "==", "!=", "===", "!==", "<=", ">=", "+=", "-=", "*=", "/=", "%=", "..", "..<",
)

/** Tokens with which a statement of the previous line goes on. */
private val leadingContinuations = setOf("?:", "&&", "||")

private const val ROLE_REFERENCE = 0
private const val ROLE_DECLARATION = 1
private const val ROLE_SOFT_KEYWORD = 2

/** A name that is not looked up, like a segment of a package name. */
private const val ROLE_PLAIN = 3
private const val ROLE_NAMED_ARGUMENT = 4

/**
 * Finds the declarations of a file and what every identifier refers to.
 *
 * This is not a compiler: there are no types, so names are resolved by where they are declared.
 * A name after a dot is resolved only when what is in front of it is known,
 * or when exactly one member of the file has that name.
 */
internal class Analyzer(private val tokens: Tokens) {
    /** Indices of the tokens that are code: everything but comments and names inside them. */
    private val code: IntArray
    private val size: Int
    private val match: IntArray
    private val role: IntArray
    private val symbolAt: Array<Symbol?>
    private val scopeAt: Array<Scope?>
    private val ownedBraces = HashSet<Int>()
    private val scopes = ArrayList<Scope>()

    val symbols = ArrayList<Symbol>()

    /** For every token, the symbol it declares or refers to. */
    val resolved = arrayOfNulls<Symbol>(tokens.count)

    /** For every token, the ordinal of its [Highlight] or -1. */
    val highlights = IntArray(tokens.count) { -1 }

    /** Pairs of token indices of brackets that belong together and are worth folding. */
    val braces = ArrayList<IntArray>()

    private val fileScope: Scope
    private val members = HashMap<String, MutableList<Symbol>>()

    init {
        var significant = 0
        val indices = IntArray(tokens.count)
        for (index in 0 until tokens.count) {
            if (!tokens.isComment(index) && tokens.flags[index] and FLAG_IN_DOC == 0) indices[significant++] = index
        }
        code = indices
        size = significant
        match = IntArray(size) { -1 }
        role = IntArray(size)
        symbolAt = arrayOfNulls(size)
        scopeAt = arrayOfNulls(size)
        fileScope = Scope(0, size + 1, ScopeKind.FILE)
        scopes += fileScope

        matchBrackets()
        for (index in 0 until size) declare(index)
        buildScopeTree()
        markModifiers()
        resolveReferences()
        resolveDocumentation()
        highlight()
    }

    // Access to significant tokens.

    private fun kind(index: Int): Int = if (index in 0 until size) tokens.kinds[code[index]] else -1
    private fun textIs(index: Int, expected: String): Boolean = index in 0 until size && tokens.textIs(code[index], expected)
    private fun textOf(index: Int): String = tokens.textOf(code[index])
    private fun lineBreakBefore(index: Int): Boolean = index in 0 until size && tokens.flags[code[index]] and FLAG_LINE_BREAK_BEFORE != 0
    private fun isKeyword(index: Int, word: String): Boolean = kind(index) == TokenKind.KEYWORD && textIs(index, word)
    private fun isIdentifier(index: Int): Boolean = kind(index) == TokenKind.IDENTIFIER
    private fun isWord(index: Int, word: String): Boolean = kind(index) == TokenKind.IDENTIFIER && textIs(index, word)

    private fun isOpening(kind: Int) =
        kind == TokenKind.LPAREN || kind == TokenKind.LBRACKET || kind == TokenKind.LBRACE || kind == TokenKind.TEMPLATE_START

    private fun isClosing(kind: Int) =
        kind == TokenKind.RPAREN || kind == TokenKind.RBRACKET || kind == TokenKind.RBRACE || kind == TokenKind.TEMPLATE_END

    private fun closes(opening: Int, closing: Int) = when (opening) {
        TokenKind.LPAREN -> closing == TokenKind.RPAREN
        TokenKind.LBRACKET -> closing == TokenKind.RBRACKET
        TokenKind.LBRACE -> closing == TokenKind.RBRACE
        else -> closing == TokenKind.TEMPLATE_END
    }

    private fun matchBrackets() {
        val open = IntArray(size)
        var depth = 0
        for (index in 0 until size) {
            val kind = kind(index)
            if (isOpening(kind)) {
                open[depth++] = index
            } else if (isClosing(kind)) {
                // A closing bracket without an opening one of its kind is skipped, so that one typo does not unpair the rest.
                var candidate = depth - 1
                while (candidate >= 0 && !closes(kind(open[candidate]), kind)) candidate--
                if (candidate >= 0) {
                    match[open[candidate]] = index
                    match[index] = open[candidate]
                    depth = candidate
                }
            }
        }
        for (index in 0 until size) {
            if (kind(index) == TokenKind.LBRACE && match[index] > index) braces += intArrayOf(code[index], code[match[index]])
        }
    }

    /** Index after the bracket closing the one at [open], or the end when it is not closed. */
    private fun afterMatch(open: Int): Int = if (match[open] > open) match[open] + 1 else size

    /** Index of `>` closing the `<` at [open], or -1 when this is not a list of type arguments. */
    private fun closingAngle(open: Int): Int {
        var depth = 0
        var index = open
        while (index < size) {
            when (kind(index)) {
                TokenKind.LT -> depth++
                TokenKind.GT -> if (--depth == 0) return index
                TokenKind.IDENTIFIER, TokenKind.COMMA, TokenKind.DOT, TokenKind.COLON, TokenKind.ANNOTATION -> {}
                TokenKind.KEYWORD -> if (!textIs(index, "in")) return -1
                TokenKind.OPERATOR -> if (!textIs(index, "?") && !textIs(index, "*")) return -1
                TokenKind.LPAREN, TokenKind.RPAREN, TokenKind.ARROW -> {}
                else -> return -1
            }
            index++
        }
        return -1
    }

    /**
     * Index of the first token that does not belong to the statement starting at [from].
     *
     * A statement ends at a line break unless an operator on either side of the break carries it on.
     */
    private fun statementEnd(from: Int): Int {
        var index = from
        while (index < size) {
            val kind = kind(index)
            if (index > from && lineBreakBefore(index) && !continues(index)) return index
            if (isClosing(kind) || kind == TokenKind.SEMICOLON) return index
            index = if (isOpening(kind)) afterMatch(index) else index + 1
        }
        return size
    }

    private fun continues(index: Int): Boolean {
        val kind = kind(index)
        if (kind == TokenKind.DOT || kind == TokenKind.COLON || kind == TokenKind.ASSIGN || kind == TokenKind.ARROW) return true
        if (kind == TokenKind.OPERATOR && textOf(index) in leadingContinuations) return true
        if (isKeyword(index, "else") || isKeyword(index, "as")) return true
        val previous = kind(index - 1)
        if (previous == TokenKind.COMMA || previous == TokenKind.DOT || previous == TokenKind.ASSIGN || previous == TokenKind.ARROW ||
            previous == TokenKind.COLON || previous == TokenKind.LT
        ) return true
        if (previous == TokenKind.OPERATOR && textOf(index - 1) in continuingOperators) return true
        // The body of `if (...)`, `while (...)` or `for (...)` written on the next line.
        if (previous == TokenKind.RPAREN && match[index - 1] >= 1) {
            val head = match[index - 1] - 1
            if (isKeyword(head, "if") || isKeyword(head, "while") || isKeyword(head, "for")) return true
        }
        return previous == TokenKind.KEYWORD && (textIs(index - 1, "else") || textIs(index - 1, "as") || textIs(index - 1, "in") || textIs(index - 1, "is"))
    }

    // Declarations.

    private fun symbol(index: Int, kind: SymbolKind, site: Int, mutable: Boolean = false): Symbol {
        val token = code[index]
        val name = tokens.textOf(token).removeSurrounding("`")
        val symbol = Symbol(name, kind, mutable, tokens.starts[token], tokens.ends[token], token, site)
        symbols += symbol
        role[index] = ROLE_DECLARATION
        symbolAt[index] = symbol
        return symbol
    }

    private fun scope(start: Int, end: Int, kind: ScopeKind, owner: Symbol? = null): Scope {
        val scope = Scope(start, end, kind)
        scope.owner = owner
        owner?.ownScope = scope
        scopes += scope
        return scope
    }

    private fun softKeyword(index: Int) {
        role[index] = ROLE_SOFT_KEYWORD
    }

    private fun declare(index: Int) {
        if (role[index] != ROLE_REFERENCE) return
        when (kind(index)) {
            TokenKind.KEYWORD -> when {
                textIs(index, "fun") -> function(index)
                textIs(index, "class") || textIs(index, "interface") || textIs(index, "object") ->
                    if (kind(index - 1) != TokenKind.COLON_COLON) type(index)
                textIs(index, "val") || textIs(index, "var") -> variable(index)
                textIs(index, "for") -> loop(index)
                textIs(index, "typealias") -> if (isIdentifier(index + 1)) {
                    symbol(index + 1, SymbolKind.TYPE_ALIAS, index).also { it.end = endOffset(statementEnd(index + 1)) }
                }
                textIs(index, "package") -> qualifiedName(index + 1)
            }
            TokenKind.IDENTIFIER -> when {
                textIs(index, "import") && (index == 0 || lineBreakBefore(index)) -> import(index)
                textIs(index, "catch") && kind(index - 1) == TokenKind.RBRACE && kind(index + 1) == TokenKind.LPAREN -> catch(index)
                textIs(index, "finally") && kind(index - 1) == TokenKind.RBRACE && kind(index + 1) == TokenKind.LBRACE -> softKeyword(index)
                textIs(index, "constructor") && kind(index + 1) == TokenKind.LPAREN -> constructor(index)
                textIs(index, "init") && kind(index + 1) == TokenKind.LBRACE && startsDeclaration(index) -> softKeyword(index)
                (textIs(index, "get") || textIs(index, "set")) && kind(index + 1) == TokenKind.LPAREN && startsDeclaration(index) -> accessor(index)
                // `private set` without a body.
                (textIs(index, "get") || textIs(index, "set")) && lineBreakBefore(index + 1) && !lineBreakBefore(index) &&
                    isIdentifier(index - 1) && textOf(index - 1) in modifiers -> softKeyword(index)
            }
            TokenKind.LBRACE -> if (index !in ownedBraces) block(index)
        }
    }

    /** The token is the first one of its line, apart from modifiers and annotations. */
    private fun startsDeclaration(index: Int): Boolean {
        var first = index
        while (first > 0 && !lineBreakBefore(first) && (kind(first - 1) == TokenKind.ANNOTATION || isIdentifier(first - 1) && textOf(first - 1) in modifiers)) first--
        return first == 0 || lineBreakBefore(first) || kind(first - 1) == TokenKind.LBRACE || kind(first - 1) == TokenKind.SEMICOLON
    }

    private fun startOffset(index: Int): Int = if (index < size) tokens.starts[code[index]] else tokens.text.length

    /** Offset where the token before [index] ends. */
    private fun endOffset(index: Int): Int = if (index in 1..size) tokens.ends[code[index - 1]] else 0

    /** Offset of the first modifier or annotation in front of the declaration keyword at [index]. */
    private fun declarationStart(index: Int): Int {
        var first = index
        while (first > 0 && (kind(first - 1) == TokenKind.ANNOTATION || isIdentifier(first - 1) && textOf(first - 1) in modifiers)) first--
        return startOffset(first)
    }

    private fun signature(symbol: Symbol, from: Int, until: Int): String {
        val start = tokens.starts[code[from]]
        val end = endOffset(until)
        if (end <= start) return symbol.name
        val text = tokens.text.substring(start, end).split('\n').joinToString(" ") { it.trim() }.trim()
        return if (text.length > 120) text.take(117) + "..." else text
    }

    /** Declares the type parameters between `<` at [open] and its `>`; returns the index after them. */
    private fun typeParameters(open: Int): Int {
        val close = closingAngle(open)
        if (close < 0) return open
        var depth = 0
        for (index in open..close) {
            when (kind(index)) {
                TokenKind.LT -> depth++
                TokenKind.GT -> depth--
                TokenKind.IDENTIFIER -> if (depth == 1) {
                    var previous = index - 1
                    while (isWord(previous, "reified") || isWord(previous, "out") || isKeyword(previous, "in") || kind(previous) == TokenKind.ANNOTATION) previous--
                    if (kind(previous) == TokenKind.LT || kind(previous) == TokenKind.COMMA) {
                        for (modifier in previous + 1 until index) if (isIdentifier(modifier)) softKeyword(modifier)
                        symbol(index, SymbolKind.TYPE_PARAMETER, index)
                    }
                }
            }
        }
        return close + 1
    }

    /** Declares the parameters between the parentheses at [open]: names followed by a colon at the top level of the list. */
    private fun parameters(open: Int) {
        val close = if (match[open] > open) match[open] else size
        var index = open + 1
        while (index < close) {
            val kind = kind(index)
            if (isOpening(kind)) {
                index = afterMatch(index)
                continue
            }
            if (kind == TokenKind.IDENTIFIER && this.kind(index + 1) == TokenKind.COLON && role[index] == ROLE_REFERENCE) {
                var previous = index - 1
                while (previous > open && (kind(previous) == TokenKind.ANNOTATION || isIdentifier(previous) && (textOf(previous) in parameterModifiers || textOf(previous) in modifiers))) previous--
                if (previous == open || this.kind(previous) == TokenKind.COMMA) {
                    for (modifier in previous + 1 until index) if (isIdentifier(modifier)) softKeyword(modifier)
                    symbol(index, SymbolKind.PARAMETER, index)
                }
            }
            index++
        }
    }

    /** Index where the header starting at [from] ends: at its body, at `=`, at the comma after it, or at the end of its last line. */
    private fun headerEnd(from: Int): Int {
        var index = from
        var angles = 0
        var where = false
        while (index < size) {
            val kind = kind(index)
            when {
                kind == TokenKind.LBRACE || kind == TokenKind.ASSIGN -> return index
                index > from && lineBreakBefore(index) && !continues(index) && !isWord(index, "where") -> return index
                isClosing(kind) || kind == TokenKind.SEMICOLON -> return index
                kind == TokenKind.COMMA && angles <= 0 && !where -> return index
                kind == TokenKind.LT -> angles++
                kind == TokenKind.GT -> angles--
                isWord(index, "where") -> where = true
            }
            index = if (isOpening(kind)) afterMatch(index) else index + 1
        }
        return size
    }

    /** End of the body that starts at [header], which is `{`, `=` or the token after a declaration without a body. */
    private fun bodyEnd(header: Int): Int = when (kind(header)) {
        TokenKind.LBRACE -> {
            ownedBraces += header
            afterMatch(header)
        }
        TokenKind.ASSIGN -> statementEnd(header + 1)
        else -> header
    }

    private fun function(keyword: Int) {
        var index = keyword + 1
        if (kind(index) == TokenKind.LT) index = typeParameters(index)
        // The receiver type and the name: `Map<K, V>.Entry?.name`.
        while (index < size) {
            val kind = kind(index)
            index = when {
                kind == TokenKind.LT -> closingAngle(index).let { if (it < 0) return else it + 1 }
                kind == TokenKind.IDENTIFIER || kind == TokenKind.DOT || kind == TokenKind.OPERATOR && textIs(index, "?") -> index + 1
                else -> break
            }
        }
        val name = if (index - 1 > keyword && isIdentifier(index - 1) && role[index - 1] == ROLE_REFERENCE) index - 1 else -1
        if (kind(index) != TokenKind.LPAREN) {
            if (name >= 0) symbol(name, SymbolKind.FUNCTION, keyword)
            return
        }
        parameters(index)
        val header = headerEnd(afterMatch(index))
        val end = bodyEnd(header)
        val symbol = if (name >= 0) symbol(name, SymbolKind.FUNCTION, keyword) else null
        if (symbol != null) {
            symbol.signature = signature(symbol, name, header)
            symbol.start = declarationStart(keyword)
            symbol.end = endOffset(end)
        }
        scope(keyword + 1, end, ScopeKind.FUNCTION, symbol)
    }

    private fun type(keyword: Int) {
        val kind = when {
            textIs(keyword, "interface") -> SymbolKind.INTERFACE
            textIs(keyword, "object") -> SymbolKind.OBJECT
            else -> SymbolKind.CLASS
        }
        val name = if (isIdentifier(keyword + 1) && !isWord(keyword + 1, "constructor")) keyword + 1 else -1
        if (name < 0 && kind != SymbolKind.OBJECT) return
        var index = if (name >= 0) name + 1 else keyword + 1
        val scopeStart = index
        if (this.kind(index) == TokenKind.LT) index = typeParameters(index)
        while (!lineBreakBefore(index) && (this.kind(index) == TokenKind.ANNOTATION || isIdentifier(index) && (textOf(index) in modifiers || textIs(index, "constructor")))) {
            if (isIdentifier(index)) softKeyword(index)
            index++
        }
        if (this.kind(index) == TokenKind.LPAREN) {
            parameters(index)
            index = afterMatch(index)
        }
        val supertypesFrom = index
        val header = classHeaderEnd(index)
        val end = if (this.kind(header) == TokenKind.LBRACE) bodyEnd(header) else header
        val symbol = if (name >= 0) symbol(name, kind, keyword) else null
        if (symbol != null) {
            symbol.signature = signature(symbol, name, header)
            symbol.start = declarationStart(keyword)
            symbol.end = endOffset(end)
            symbol.supertypes = supertypes(supertypesFrom, header)
        }
        scope(scopeStart, end, ScopeKind.CLASS, symbol).companion = kind == SymbolKind.OBJECT && isWord(keyword - 1, "companion")
        if (this.kind(header) == TokenKind.LBRACE && isWord(keyword - 1, "enum")) enumEntries(header)
    }

    private fun classHeaderEnd(from: Int): Int {
        var index = from
        while (index < size) {
            val kind = kind(index)
            when {
                kind == TokenKind.LBRACE -> return index
                index > from && lineBreakBefore(index) && !continues(index) && !isWord(index, "where") -> return index
                isClosing(kind) || kind == TokenKind.SEMICOLON || kind == TokenKind.ASSIGN -> return index
            }
            index = if (isOpening(kind)) afterMatch(index) else index + 1
        }
        return size
    }

    /** Names of the types listed after the colon of a class header. */
    private fun supertypes(from: Int, until: Int): List<String> {
        if (kind(from) != TokenKind.COLON) return emptyList()
        val names = ArrayList<String>()
        var index = from + 1
        var angles = 0
        while (index < until) {
            val kind = kind(index)
            when {
                isOpening(kind) -> {
                    index = afterMatch(index)
                    continue
                }
                kind == TokenKind.LT -> angles++
                kind == TokenKind.GT -> angles--
                kind == TokenKind.IDENTIFIER && angles == 0 && this.kind(index + 1) != TokenKind.DOT && !isWord(index, "by") &&
                    !isWord(index - 1, "by") -> names += textOf(index)
            }
            index++
        }
        return names
    }

    private fun enumEntries(brace: Int) {
        val close = if (match[brace] > brace) match[brace] else size
        var index = brace + 1
        while (index < close) {
            while (kind(index) == TokenKind.ANNOTATION) index++
            if (!isIdentifier(index) || role[index] != ROLE_REFERENCE) return
            val next = kind(index + 1)
            if (next != TokenKind.COMMA && next != TokenKind.LPAREN && next != TokenKind.LBRACE && next != TokenKind.SEMICOLON && index + 1 != close) return
            val entry = symbol(index, SymbolKind.ENUM_ENTRY, index)
            index++
            if (kind(index) == TokenKind.LPAREN) index = afterMatch(index)
            if (kind(index) == TokenKind.LBRACE) {
                ownedBraces += index
                scope(index, afterMatch(index), ScopeKind.CLASS, entry)
                index = afterMatch(index)
            }
            entry.end = endOffset(index)
            if (kind(index) != TokenKind.COMMA) return
            index++
        }
    }

    private fun variable(keyword: Int) {
        val mutable = textIs(keyword, "var")
        var index = keyword + 1
        if (kind(index) == TokenKind.LPAREN) {
            val close = if (match[index] > index) match[index] else size
            for (name in index + 1 until close) {
                if (isIdentifier(name) && (kind(name - 1) == TokenKind.LPAREN || kind(name - 1) == TokenKind.COMMA)) {
                    symbol(name, SymbolKind.LOCAL_VARIABLE, keyword, mutable)
                }
            }
            return
        }
        if (kind(index) == TokenKind.LT) index = closingAngle(index).let { if (it < 0) return else it + 1 }
        // The receiver type and the name: `List<T>.last`.
        var name = -1
        while (isIdentifier(index)) {
            name = index++
            if (kind(index) == TokenKind.LT) {
                val close = closingAngle(index)
                if (close < 0 || kind(close + 1) != TokenKind.DOT) break
                index = close + 1
            }
            if (kind(index) == TokenKind.DOT && !lineBreakBefore(index + 1)) index++ else break
        }
        if (name < 0 || role[name] != ROLE_REFERENCE) return
        val symbol = symbol(name, SymbolKind.PROPERTY, keyword, mutable)
        val header = headerEnd(name + 1)
        symbol.signature = signature(symbol, name, header)
        symbol.start = declarationStart(keyword)
        symbol.end = endOffset(if (kind(header) == TokenKind.ASSIGN) statementEnd(header + 1) else header)
    }

    private fun loop(keyword: Int) {
        val open = keyword + 1
        if (kind(open) != TokenKind.LPAREN) return
        val close = if (match[open] > open) match[open] else size
        var index = open + 1
        while (index < close && !isKeyword(index, "in")) {
            if (isIdentifier(index) && (kind(index - 1) == TokenKind.LPAREN || kind(index - 1) == TokenKind.COMMA)) {
                symbol(index, SymbolKind.LOCAL_VARIABLE, index)
            }
            index++
        }
        val body = afterMatch(open)
        scope(open, if (kind(body) == TokenKind.LBRACE) afterMatch(body) else statementEnd(body), ScopeKind.BLOCK)
    }

    private fun catch(word: Int) {
        softKeyword(word)
        val open = word + 1
        if (isIdentifier(open + 1) && kind(open + 2) == TokenKind.COLON) symbol(open + 1, SymbolKind.PARAMETER, open + 1)
        val body = afterMatch(open)
        scope(open, if (kind(body) == TokenKind.LBRACE) afterMatch(body) else body, ScopeKind.BLOCK)
    }

    private fun constructor(word: Int) {
        softKeyword(word)
        // The primary constructor is handled with its class.
        if (!startsDeclaration(word)) return
        parameters(word + 1)
        val header = headerEnd(afterMatch(word + 1))
        scope(word + 1, bodyEnd(header), ScopeKind.FUNCTION)
    }

    private fun accessor(word: Int) {
        softKeyword(word)
        val open = word + 1
        if (isIdentifier(open + 1) && (kind(open + 2) == TokenKind.RPAREN || kind(open + 2) == TokenKind.COLON)) {
            symbol(open + 1, SymbolKind.PARAMETER, open + 1)
        }
        val header = headerEnd(afterMatch(open))
        scope(open, bodyEnd(header), ScopeKind.FUNCTION)
    }

    private fun qualifiedName(from: Int): Int {
        var index = from
        while (index < size && (index == from || !lineBreakBefore(index)) && (isIdentifier(index) || kind(index) == TokenKind.DOT)) {
            if (isIdentifier(index)) role[index] = ROLE_PLAIN
            index++
        }
        return index
    }

    private fun import(word: Int) {
        softKeyword(word)
        val end = qualifiedName(word + 1)
        var name = end - 1
        if (isKeyword(end, "as") && isIdentifier(end + 1) && !lineBreakBefore(end)) name = end + 1
        if (name > word && isIdentifier(name)) {
            role[name] = ROLE_REFERENCE
            symbol(name, SymbolKind.IMPORT, word)
        }
    }

    private fun block(brace: Int) {
        val end = afterMatch(brace)
        val previous = brace - 1
        val whenBody = isKeyword(previous, "when") || kind(previous) == TokenKind.RPAREN && match[previous] >= 0 && isKeyword(match[previous] - 1, "when")
        if (whenBody || !lambdaParameters(brace)) scope(brace, end, ScopeKind.BLOCK) else scope(brace, end, ScopeKind.LAMBDA)
    }

    /** Declares the parameters of the lambda opened at [brace] when it names them: `{ key, value -> ... }`. */
    private fun lambdaParameters(brace: Int): Boolean {
        var arrow = brace + 1
        while (arrow < size) {
            val kind = kind(arrow)
            if (kind == TokenKind.ARROW) break
            val allowed = kind == TokenKind.IDENTIFIER || kind == TokenKind.COMMA || kind == TokenKind.COLON || kind == TokenKind.DOT ||
                kind == TokenKind.LT || kind == TokenKind.GT || kind == TokenKind.LPAREN || kind == TokenKind.RPAREN ||
                kind == TokenKind.OPERATOR && (textIs(arrow, "?") || textIs(arrow, "*"))
            if (!allowed || lineBreakBefore(arrow) && this.kind(arrow - 1) != TokenKind.COMMA && arrow > brace + 1) return false
            arrow++
        }
        if (arrow >= size || arrow == brace + 1) return arrow < size
        var inType = false
        var angles = 0
        for (index in brace + 1 until arrow) {
            when (kind(index)) {
                TokenKind.COLON -> inType = true
                TokenKind.LT -> angles++
                TokenKind.GT -> angles--
                TokenKind.COMMA -> if (angles == 0) inType = false
                TokenKind.RPAREN -> inType = false
                TokenKind.IDENTIFIER -> if (!inType && !textIs(index, "_")) {
                    val before = kind(index - 1)
                    if (before == TokenKind.LBRACE || before == TokenKind.COMMA || before == TokenKind.LPAREN) symbol(index, SymbolKind.PARAMETER, index)
                }
            }
        }
        return true
    }

    // Scopes.

    private fun buildScopeTree() {
        scopes.sortWith(compareBy<Scope> { it.start }.thenByDescending { it.end })
        val open = ArrayList<Scope>()
        var next = 0
        for (index in 0 until size) {
            while (open.isNotEmpty() && open.last().end <= index) open.removeLast()
            while (next < scopes.size && scopes[next].start <= index) {
                val scope = scopes[next++]
                if (scope.end <= index) continue
                while (open.isNotEmpty() && open.last().end < scope.end) open.removeLast()
                scope.parent = open.lastOrNull()
                open += scope
            }
            scopeAt[index] = open.lastOrNull() ?: fileScope
        }
        for (scope in scopes) if (scope.companion) scope.parent?.companions?.add(scope)
        for (symbol in symbols) {
            val scope = declaringScope(symbol)
            symbol.scope = scope
            if (symbol.kind == SymbolKind.PROPERTY && scope.ordered) symbol.kind = SymbolKind.LOCAL_VARIABLE
            scope.symbols.getOrPut(symbol.name) { ArrayList() } += symbol
            symbol.topLevel = scope.kind == ScopeKind.FILE
            var depth = 0
            var outer: Scope? = scope
            while (outer != null) {
                if (outer.kind == ScopeKind.CLASS) depth++
                outer = outer.parent
            }
            symbol.depth = depth
            if (symbol.member && (symbol.kind == SymbolKind.FUNCTION || symbol.kind == SymbolKind.PROPERTY || symbol.kind.isType || symbol.kind == SymbolKind.ENUM_ENTRY)) {
                members.getOrPut(symbol.name) { ArrayList() } += symbol
            }
        }
    }

    private fun declaringScope(symbol: Symbol): Scope {
        var scope = scopeAt[symbol.site] ?: fileScope
        // A function or a class is declared around the scope it opens, not inside it.
        while (scope.owner === symbol) scope = scope.parent ?: fileScope
        return scope
    }

    // Keywords that are identifiers elsewhere.

    private fun markModifiers() {
        var declarationFollows = false
        for (index in size - 1 downTo 0) {
            val kind = kind(index)
            declarationFollows = when {
                kind == TokenKind.KEYWORD -> textOf(index) in declarationKeywords
                kind == TokenKind.ANNOTATION -> declarationFollows
                kind == TokenKind.IDENTIFIER && role[index] == ROLE_SOFT_KEYWORD -> textIs(index, "constructor") || textIs(index, "get") || textIs(index, "set") || textIs(index, "init") || declarationFollows
                kind == TokenKind.IDENTIFIER && role[index] == ROLE_REFERENCE && declarationFollows && !lineBreakBefore(index + 1) &&
                    textOf(index) in modifiers && startsModifiers(index) -> {
                    softKeyword(index)
                    true
                }
                else -> false
            }
        }
        for (index in 0 until size) {
            if (!isIdentifier(index) || role[index] != ROLE_REFERENCE) continue
            val previous = kind(index - 1)
            val next = kind(index + 1)
            val keyword = when {
                textIs(index, "by") -> (previous == TokenKind.IDENTIFIER || previous == TokenKind.RPAREN || previous == TokenKind.GT || textIs(index - 1, "?")) &&
                    (next == TokenKind.IDENTIFIER || next == TokenKind.KEYWORD) && !lineBreakBefore(index + 1)
                textIs(index, "where") -> (previous == TokenKind.RPAREN || previous == TokenKind.GT || previous == TokenKind.IDENTIFIER) &&
                    next == TokenKind.IDENTIFIER && kind(index + 2) == TokenKind.COLON
                textIs(index, "out") -> (previous == TokenKind.LT || previous == TokenKind.COMMA) && next == TokenKind.IDENTIFIER
                textIs(index, "suspend") -> next == TokenKind.LPAREN && previous != TokenKind.DOT && (previous == TokenKind.COLON || previous == TokenKind.ARROW || previous == TokenKind.LPAREN || previous == TokenKind.COMMA || previous == TokenKind.LT)
                textIs(index, "dynamic") -> previous == TokenKind.COLON
                textIs(index, "field") -> previous != TokenKind.DOT && insideAccessor(index)
                else -> false
            }
            if (keyword) softKeyword(index)
        }
    }

    /** A modifier can stand at [index]: at the start of a line, of a block or of a parameter, or after another modifier. */
    private fun startsModifiers(index: Int): Boolean {
        if (index == 0 || lineBreakBefore(index)) return true
        val previous = kind(index - 1)
        return previous == TokenKind.ANNOTATION || previous == TokenKind.LBRACE || previous == TokenKind.SEMICOLON || previous == TokenKind.LPAREN ||
            previous == TokenKind.COMMA || previous == TokenKind.IDENTIFIER && textOf(index - 1) in modifiers
    }

    private fun insideAccessor(index: Int): Boolean {
        var scope = scopeAt[index]
        while (scope != null) {
            if (scope.kind == ScopeKind.FUNCTION && scope.owner == null && scope.start > 0 && (isWord(scope.start - 1, "get") || isWord(scope.start - 1, "set"))) {
                return lookup("field", index, callable = false) == null
            }
            scope = scope.parent
        }
        return false
    }

    // References.

    private fun isCallable(symbol: Symbol) = symbol.kind == SymbolKind.FUNCTION || symbol.kind == SymbolKind.CLASS

    /** The candidate that fits the use best; [membersOnly] leaves out the parameters of a constructor. */
    private fun pick(candidates: List<Symbol>, callable: Boolean, membersOnly: Boolean = false): Symbol? {
        var first: Symbol? = null
        for (candidate in candidates) {
            if (membersOnly && candidate.kind == SymbolKind.PARAMETER) continue
            if (isCallable(candidate) == callable) return candidate
            if (first == null) first = candidate
        }
        return first
    }

    private fun lookupIn(scope: Scope, name: String, index: Int, callable: Boolean, membersOnly: Boolean): Symbol? {
        val candidates = scope.symbols[name] ?: return null
        if (!scope.ordered) return pick(candidates, callable, membersOnly && scope.kind == ScopeKind.CLASS)
        // The closest declaration in front of the use; parameters and local functions count from their own position.
        var best: Symbol? = null
        var preferred: Symbol? = null
        val offset = tokens.starts[code[index]]
        for (candidate in candidates) {
            if (candidate.nameStart >= offset) continue
            best = candidate
            if (isCallable(candidate) == callable) preferred = candidate
        }
        return preferred ?: best
    }

    /**
     * A member with the given name of the class owning [scope] or of the classes it names as supertypes.
     *
     * [own] is false for `super`, which starts at the supertypes.
     */
    private fun inherited(scope: Scope, name: String, callable: Boolean, visited: HashSet<Scope>, own: Boolean = true): Symbol? {
        if (!visited.add(scope)) return null
        if (own) {
            scope.symbols[name]?.let { candidates -> pick(candidates, callable, membersOnly = true)?.let { return it } }
            for (companion in scope.companions) companion.symbols[name]?.let { candidates -> pick(candidates, callable)?.let { return it } }
        }
        for (supertype in scope.owner?.supertypes ?: return null) {
            val parent = members[supertype]?.firstOrNull { it.kind.isType }?.ownScope ?: continue
            inherited(parent, name, callable, visited)?.let { return it }
        }
        return null
    }

    private fun lookup(name: String, index: Int, callable: Boolean): Symbol? {
        var scope = scopeAt[index]
        // The parameters of a constructor are visible in initializers but not in member functions.
        var insideFunction = false
        while (scope != null) {
            lookupIn(scope, name, index, callable, membersOnly = insideFunction)?.let { return it }
            if (scope.kind == ScopeKind.CLASS) inherited(scope, name, callable, HashSet())?.let { return it }
            if (scope.kind == ScopeKind.FUNCTION) insideFunction = true
            scope = scope.parent
        }
        return null
    }

    private fun enclosingClass(index: Int): Scope? {
        var scope = scopeAt[index]
        while (scope != null && scope.kind != ScopeKind.CLASS) scope = scope.parent
        return scope
    }

    private fun memberOf(qualifier: Int, name: String, callable: Boolean): Symbol? {
        if (isKeyword(qualifier, "this") || isKeyword(qualifier, "super")) {
            return enclosingClass(qualifier)?.let { inherited(it, name, callable, HashSet(), own = isKeyword(qualifier, "this")) }
        }
        // A member of a type, an object or an enum entry named in front of the dot.
        val owner = if (qualifier >= 0) symbolAt[qualifier] else null
        if (owner != null && (owner.kind.isType || owner.kind == SymbolKind.ENUM_ENTRY)) {
            owner.ownScope?.let { scope -> inherited(scope, name, callable, HashSet())?.let { return it } }
        }
        // Without types, a member is found only when its name leaves no choice.
        val candidates = members[name] ?: return null
        val fitting = candidates.filter { !it.kind.isType || callable }
        return fitting.singleOrNull() ?: overridden(fitting)
    }

    /** The declaration all [candidates] override, when they are one member declared along a line of supertypes. */
    private fun overridden(candidates: List<Symbol>): Symbol? = candidates.firstOrNull { base ->
        val baseClass = base.scope?.owner ?: return@firstOrNull false
        candidates.all { it === base || it.scope?.owner?.let { owner -> extends(owner, baseClass, HashSet()) } == true }
    }

    private fun extends(type: Symbol, base: Symbol, visited: HashSet<Symbol>): Boolean {
        if (!visited.add(type)) return false
        for (name in type.supertypes) {
            val supertype = members[name]?.firstOrNull { it.kind.isType } ?: continue
            if (supertype === base || extends(supertype, base, visited)) return true
        }
        return false
    }

    private fun resolveReferences() {
        val open = IntArray(size + 1)
        var depth = 0
        for (index in 0 until size) {
            val kind = kind(index)
            if (isOpening(kind)) open[depth++] = index
            if (isClosing(kind) && match[index] >= 0) {
                while (depth > 0 && open[depth - 1] != match[index]) depth--
                if (depth > 0) depth--
            }
            if (kind == TokenKind.KEYWORD && (textIs(index, "this") || textIs(index, "super"))) continue
            if (kind != TokenKind.IDENTIFIER || role[index] != ROLE_REFERENCE) continue

            val name = textOf(index).removeSurrounding("`")
            val previous = kind(index - 1)
            val next = kind(index + 1)
            val callable = next == TokenKind.LPAREN || next == TokenKind.LT && closingAngle(index + 1).let { it > 0 && this.kind(it + 1) == TokenKind.LPAREN }

            val enclosing = if (depth > 0) open[depth - 1] else -1
            if (next == TokenKind.ASSIGN && enclosing >= 0 && kind(enclosing) == TokenKind.LPAREN && (previous == TokenKind.LPAREN || previous == TokenKind.COMMA) &&
                (isIdentifier(enclosing - 1) || kind(enclosing - 1) == TokenKind.GT || kind(enclosing - 1) == TokenKind.ANNOTATION || isKeyword(enclosing - 1, "this") || isKeyword(enclosing - 1, "super"))
            ) {
                role[index] = ROLE_NAMED_ARGUMENT
                // The parameter of the function or the constructor that is called.
                val callee = symbolAt[enclosing - 1]
                val parameter = callee?.ownScope?.symbols?.get(name)?.firstOrNull { it.kind == SymbolKind.PARAMETER || it.kind == SymbolKind.PROPERTY }
                if (parameter != null) refer(index, parameter)
                continue
            }

            val symbol = if (previous == TokenKind.DOT || previous == TokenKind.COLON_COLON && index >= 2 && (isIdentifier(index - 2) || isKeyword(index - 2, "this"))) {
                memberOf(index - 2, name, callable)
            } else {
                lookup(name, index, callable)
            }
            if (symbol != null) refer(index, symbol)
        }
    }

    private fun refer(index: Int, symbol: Symbol) {
        symbolAt[index] = symbol
        symbol.references += code[index]
    }

    /** Names in documentation comments refer to the declaration that follows the comment and to what it declares. */
    private fun resolveDocumentation() {
        var declaration = 0
        val ordered = symbols.sortedBy { it.nameStart }
        var position = 0
        for (token in 0 until tokens.count) {
            if (tokens.flags[token] and FLAG_IN_DOC == 0 || tokens.kinds[token] != TokenKind.IDENTIFIER) continue
            val offset = tokens.starts[token]
            while (declaration < ordered.size && (ordered[declaration].nameStart < offset || !ordered[declaration].member)) declaration++
            while (position < size && tokens.starts[code[position]] < offset) position++
            val name = tokens.textOf(token)
            val documented = ordered.getOrNull(declaration)
            val symbol = when {
                documented == null -> null
                documented.name == name -> documented
                else -> documented.ownScope?.let { it.symbols[name]?.firstOrNull() ?: inherited(it, name, callable = false, HashSet()) }
            } ?: (if (position < size) lookup(name, position, callable = false) else fileScope.symbols[name]?.firstOrNull())
                ?: members[name]?.singleOrNull()
            if (symbol != null) {
                resolved[token] = symbol
                symbol.references += token
            }
        }
    }

    // Highlighting.

    private fun highlightOf(symbol: Symbol, declaration: Boolean): Highlight? = when (symbol.kind) {
        SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.TYPE_ALIAS -> Highlight.CLASS
        SymbolKind.OBJECT, SymbolKind.ENUM_ENTRY -> Highlight.INSTANCE
        SymbolKind.TYPE_PARAMETER -> Highlight.TYPE_PARAMETER
        SymbolKind.FUNCTION -> when {
            declaration -> Highlight.FUNCTION_DECLARATION
            symbol.topLevel -> Highlight.TOP_LEVEL_FUNCTION_CALL
            else -> Highlight.FUNCTION_CALL
        }
        SymbolKind.PROPERTY -> if (symbol.mutable) Highlight.MUTABLE_PROPERTY else Highlight.PROPERTY
        SymbolKind.PARAMETER -> Highlight.PARAMETER
        SymbolKind.LOCAL_VARIABLE -> if (symbol.mutable) Highlight.MUTABLE_LOCAL_VARIABLE else Highlight.LOCAL_VARIABLE
        SymbolKind.IMPORT -> null
    }

    private fun highlight() {
        for (token in 0 until tokens.count) {
            val highlight = when (tokens.kinds[token]) {
                TokenKind.KEYWORD -> Highlight.KEYWORD
                TokenKind.NUMBER -> Highlight.NUMBER
                TokenKind.STRING, TokenKind.CHARACTER -> Highlight.STRING
                TokenKind.ESCAPE -> Highlight.STRING_ESCAPE
                TokenKind.TEMPLATE_MARK, TokenKind.TEMPLATE_START, TokenKind.TEMPLATE_END -> Highlight.TEMPLATE
                TokenKind.LINE_COMMENT, TokenKind.BLOCK_COMMENT -> Highlight.COMMENT
                TokenKind.DOC_COMMENT -> Highlight.DOC_COMMENT
                TokenKind.DOC_TAG -> Highlight.DOC_TAG
                TokenKind.ANNOTATION -> Highlight.ANNOTATION
                TokenKind.LABEL -> Highlight.LABEL
                TokenKind.IDENTIFIER -> if (tokens.flags[token] and FLAG_IN_DOC != 0) Highlight.DOC_LINK else null
                else -> null
            }
            if (highlight != null) highlights[token] = highlight.ordinal
        }
        for (index in 0 until size) {
            if (!isIdentifier(index)) continue
            val symbol = symbolAt[index]
            val call = kind(index + 1) == TokenKind.LPAREN
            val highlight = when (role[index]) {
                ROLE_SOFT_KEYWORD -> Highlight.KEYWORD
                ROLE_NAMED_ARGUMENT -> Highlight.NAMED_ARGUMENT
                ROLE_DECLARATION -> symbol?.let { highlightOf(it, declaration = true) }
                ROLE_REFERENCE -> when {
                    symbol != null -> highlightOf(symbol, declaration = false) ?: if (call) Highlight.FUNCTION_CALL else null
                    textIs(index, "it") && kind(index - 1) != TokenKind.DOT -> Highlight.IMPLICIT_PARAMETER
                    call -> Highlight.FUNCTION_CALL
                    else -> null
                }
                else -> null
            }
            if (highlight != null) highlights[code[index]] = highlight.ordinal
            if (symbol != null) resolved[code[index]] = symbol
        }
    }

    /** The token at [token] is assigned to: `name = value`, `name += value`, `name++`. */
    fun isWrite(token: Int): Boolean {
        var next = token + 1
        while (next < tokens.count && tokens.isComment(next)) next++
        if (next >= tokens.count) return false
        if (tokens.kinds[next] == TokenKind.ASSIGN) return true
        if (tokens.kinds[next] != TokenKind.OPERATOR) return false
        val text = tokens.textOf(next)
        return text == "+=" || text == "-=" || text == "*=" || text == "/=" || text == "%=" || text == "++" || text == "--"
    }

    /** Declarations that can be named at [offset]: the closest ones first. */
    fun visibleSymbols(offset: Int): List<Symbol> {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (tokens.starts[code[middle]] < offset) low = middle + 1 else high = middle
        }
        val result = ArrayList<Symbol>()
        val seen = HashSet<String>()
        var scope = if (size == 0) fileScope else scopeAt[(low - 1).coerceAtLeast(0)]
        while (scope != null) {
            for ((name, candidates) in scope.symbols) {
                val symbol = (if (scope.ordered) candidates.lastOrNull { it.nameStart < offset } else candidates.first()) ?: continue
                if (seen.add(name)) result += symbol
            }
            scope = scope.parent
        }
        return result
    }

    fun memberSymbols(): List<Symbol> = members.values.map { it.first() }
}
