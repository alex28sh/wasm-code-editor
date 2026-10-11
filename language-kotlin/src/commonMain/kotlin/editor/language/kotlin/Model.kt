package editor.language.kotlin

/** How a piece of source is painted; [cssClass] is what the editor puts on it. */
enum class Highlight(val cssClass: String) {
    KEYWORD("kt-keyword"),
    STRING("kt-string"),
    STRING_ESCAPE("kt-escape"),

    /** `$`, `${` and the closing `}` of a string template. */
    TEMPLATE("kt-template"),
    NUMBER("kt-number"),
    COMMENT("kt-comment"),
    DOC_COMMENT("kt-doc"),
    DOC_TAG("kt-doc-tag"),
    DOC_LINK("kt-doc-link"),
    ANNOTATION("kt-annotation"),
    LABEL("kt-label"),
    CLASS("kt-class"),
    TYPE_PARAMETER("kt-type-parameter"),
    FUNCTION_DECLARATION("kt-function-declaration"),
    FUNCTION_CALL("kt-function-call"),

    /** A call of a function declared at the top level of the file. */
    TOP_LEVEL_FUNCTION_CALL("kt-top-level-call"),
    PROPERTY("kt-property"),
    MUTABLE_PROPERTY("kt-property kt-mutable"),
    PARAMETER("kt-parameter"),
    LOCAL_VARIABLE("kt-local"),
    MUTABLE_LOCAL_VARIABLE("kt-local kt-mutable"),

    /** Objects and enum entries. */
    INSTANCE("kt-instance"),
    NAMED_ARGUMENT("kt-named-argument"),

    /** The implicit parameter of a lambda. */
    IMPLICIT_PARAMETER("kt-it"),
}

enum class SymbolKind(val title: String) {
    CLASS("class"),
    INTERFACE("interface"),
    OBJECT("object"),
    ENUM_ENTRY("enum entry"),
    TYPE_ALIAS("type alias"),
    TYPE_PARAMETER("type parameter"),
    FUNCTION("function"),
    PROPERTY("property"),
    PARAMETER("parameter"),
    LOCAL_VARIABLE("variable"),
    IMPORT("import"),
    ;

    val isType: Boolean get() = this == CLASS || this == INTERFACE || this == OBJECT || this == TYPE_ALIAS
}

/** Something the file declares under a name. */
class Symbol internal constructor(
    val name: String,
    kind: SymbolKind,
    /** Declared with `var`. */
    val mutable: Boolean,
    val nameStart: Int,
    val nameEnd: Int,
    internal val token: Int,
    internal val site: Int,
) {
    var kind: SymbolKind = kind
        internal set

    /** The header of the declaration from its name on, as shown in lists: `greet(name: String): String`. */
    var signature: String = name
        internal set

    /** The range of the whole declaration with its body, where it has one. */
    var start: Int = nameStart
        internal set
    var end: Int = nameEnd
        internal set

    /** Declared directly in the file rather than inside a class or a function. */
    var topLevel: Boolean = false
        internal set

    /** Number of classes around the declaration. */
    var depth: Int = 0
        internal set

    internal var scope: Scope? = null
    internal var ownScope: Scope? = null
    internal var supertypes: List<String> = emptyList()
    internal val references = mutableListOf<Int>()

    /** Shown in the file structure: a type, or a function or property that is not local to a function. */
    val isStructural: Boolean
        get() = when (kind) {
            SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT, SymbolKind.ENUM_ENTRY, SymbolKind.TYPE_ALIAS -> member
            SymbolKind.FUNCTION, SymbolKind.PROPERTY -> member
            else -> false
        }

    internal val member: Boolean
        get() = scope?.kind == ScopeKind.FILE || scope?.kind == ScopeKind.CLASS

    override fun toString(): String = "${kind.title} $name"
}

/** One place where a symbol is named: its declaration or a use of it. */
class Occurrence(val start: Int, val end: Int, val declaration: Boolean, val write: Boolean)

class Completion(val label: String, val kind: String, val detail: String)

internal enum class ScopeKind { FILE, CLASS, FUNCTION, BLOCK, LAMBDA }

/** A range of tokens, given as indices of significant tokens, in which declarations are visible. */
internal class Scope(val start: Int, val end: Int, val kind: ScopeKind) {
    var parent: Scope? = null
    var owner: Symbol? = null
    val symbols = HashMap<String, MutableList<Symbol>>()

    /** The body of a companion object. */
    var companion = false

    /** The scopes of the companion objects of the class this scope belongs to. */
    val companions = ArrayList<Scope>()

    /** Declarations are visible only after their own position. */
    val ordered: Boolean get() = kind != ScopeKind.FILE && kind != ScopeKind.CLASS
}
