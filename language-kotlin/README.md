# language-kotlin

Analysis of one Kotlin source text for an editor: what to highlight, what is declared where, and what refers to it.
It is plain Kotlin without dependencies, compiled to Wasm with the rest of the application.

It is **not a compiler**. There are no types, no standard library and no other files: a name is resolved by where it
is declared in the same text. Everything below follows from that.

```kotlin
val file = KotlinFile(text)

file.highlights(from, to) { start, end, highlight -> /* paint */ }
val symbol = file.symbolAt(offset)              // declaration or use under the caret
val places = symbol?.let(file::occurrences)     // its declaration and all uses
val outline = file.structure                    // types, functions and properties
```

`KotlinFile` is immutable: a changed text gets a new instance, which analyzes the whole text again.
It accepts any text, complete or not, and never throws.

## API

| Member | Result |
|---|---|
| `highlights(from, to, sink)` | Every highlighted piece overlapping the range, in source order. A piece reaching into the range is reported whole. |
| `symbolAt(offset)` | The symbol named at the offset, by its declaration or by a use. The offset may be at either end of the name. |
| `isDeclarationAt(offset)` | Whether the name at the offset is the declaration itself. |
| `occurrences(symbol)` | The declaration and all uses in source order, each flagged as `declaration` and as `write`. |
| `symbols` | Everything the text declares, in source order. |
| `structure` | The outline: types, enum entries, type aliases, and functions and properties that are not local. |
| `enclosing(offset)` | The innermost outline entry whose declaration contains the offset. |
| `foldRange(lineStart, lineEnd)` | The range to fold for a line, or null. |
| `completions(offset, prefix, limit)` | What can be written at the offset in place of the prefix. |

A `Symbol` has a `name`, a `kind`, the range of its name (`nameStart`, `nameEnd`), the range of the whole declaration
(`start`, `end`), a `signature` for lists (`greet(name: String): String`), `mutable` for `var`, `topLevel`,
and `depth`, the number of classes around it.

## Tokens

The lexer recognizes:

- **Keywords**: all hard keywords.
- **Numbers**: decimal, hexadecimal and binary, underscores, fractions, exponents, and the suffixes `L`, `u`, `U`, `f`, `F`.
  `1..10` and `1.toString()` are not taken for fractions.
- **Strings**: escapes including `\uXXXX`, templates `$name` and `${expression}` nested to any depth, and raw strings,
  which have no escapes and end at the last three quotes of a run. A string left open ends with its line.
- **Character literals**, with escapes.
- **Comments**: line comments and nested block comments.
- **KDoc**: tags like `@param`, links like `[name]` and `[Pair.second]`, and the name after `@param`, `@property`,
  `@throws`, `@exception`, `@see` and `@sample`. `[text](url)` is left as plain comment.
- **Annotations**: with use-site targets (`@file:JvmName`) and qualified names (`@kotlin.Deprecated`).
- **Labels**: `loop@` and `@loop` after `return`, `break`, `continue`, `this` and `super`.
- **Identifiers in backticks**.

## Declarations

| Kind | Found in |
|---|---|
| Class, interface, object | `class`, `interface`, `object`, including nested ones and companion objects |
| Enum entry | the entries at the start of an `enum class` body |
| Type alias | `typealias` |
| Type parameter | `<T>` of functions and classes |
| Function | `fun`, including extension, generic and local functions |
| Property | `val` and `var` in a file or a class, including constructor properties and extension properties |
| Parameter | functions, constructors, lambdas (`{ key, value -> }`), `catch`, setters |
| Local variable | `val` and `var` in a body, destructuring, `for` loops, `when (val x = ...)` |
| Import | the last name of an `import`, or its alias after `as` |

A `val` or `var` is a property in a file or class and a local variable anywhere else.

## Resolution

A plain name is looked up from the innermost scope outwards:

- **Blocks, lambdas and function bodies** show only what is declared in front of the use, so a local variable
  shadows a parameter from its own declaration on.
- **Classes and the file** show their declarations in any order.
- **Supertypes** declared in the same text contribute their members, through any number of levels.
- **Companion objects** contribute their members to their class.
- **Constructor parameters** are visible in initializers but not in member functions.
- **A call** prefers a function or class, anything else a property, when both have the name.

A name after a dot is resolved in these cases:

- **`this.name` and `super.name`**: in the enclosing class and its supertypes; `super` skips the class itself.
- **`Type.name`, `Object.name`, `Entry.name`**: in that type, its supertypes and its companion object.
- **Any other qualifier**: only when exactly one member of the text has the name, or all that have it override
  one declaration; that one is then taken. `list.size` stays unresolved unless the text declares a single `size`.

Also resolved:

- **Named arguments** refer to the parameter of the called function or constructor.
- **Names in KDoc** refer to the declaration after the comment, to what it declares, or to anything in scope.
- **Names in string templates** are resolved like any other code.

## Highlighting

`Highlight` names what a piece is; `cssClass` is what the editor puts on it.

| Highlight | For |
|---|---|
| `KEYWORD` | hard keywords, and soft keywords and modifiers where they act as such |
| `STRING`, `STRING_ESCAPE`, `TEMPLATE` | string and character literals, escapes, and `$`, `${`, `}` of templates |
| `NUMBER` | numeric literals |
| `COMMENT`, `DOC_COMMENT`, `DOC_TAG`, `DOC_LINK` | comments and the parts of KDoc |
| `ANNOTATION`, `LABEL` | annotations and labels |
| `CLASS`, `TYPE_PARAMETER` | declarations and uses of types declared in the text |
| `FUNCTION_DECLARATION` | the name in a `fun` |
| `FUNCTION_CALL`, `TOP_LEVEL_FUNCTION_CALL` | calls; the second for functions declared at the top level of the text |
| `PROPERTY`, `MUTABLE_PROPERTY` | properties, by `val` and `var` |
| `PARAMETER` | parameters |
| `LOCAL_VARIABLE`, `MUTABLE_LOCAL_VARIABLE` | local variables, by `val` and `var` |
| `INSTANCE` | objects and enum entries |
| `NAMED_ARGUMENT` | the name in `name = value` of a call |
| `IMPLICIT_PARAMETER` | `it` that is not declared |

Modifiers (`data`, `private`, `override`, `inline`, `vararg`, `reified`, ...) are keywords only in front of a
declaration. Soft keywords (`import`, `by`, `where`, `constructor`, `init`, `get`, `set`, `field`, `catch`, `finally`,
`out`) are keywords only in their position; elsewhere all of these are ordinary names.

A use of a name the text does not declare gets `FUNCTION_CALL` when it is called and nothing otherwise.

## Folding

- **Blocks**: from an opening brace to its closing one on a later line.
- **Comments**: a block or documentation comment spanning several lines.
- **Imports**: the lines after the first of a run of imports.

## Completion

- **In code**: the declarations visible at the offset, closest first, then keywords, filtered by the prefix
  without regard to case.
- **After a dot**: the members and the top-level functions and properties of the text, without keywords.
- **Nothing** inside comments, strings, character literals and numbers, or for the name of a new declaration
  after `val`, `var`, `fun`, `class`, `interface`, `object`, `typealias` and `package`.

The result is cut at `limit` entries, 200 by default.

## Limits

- **No types.** Overloads are one name, receivers are unknown, and a member after a dot is found only in the cases
  listed under Resolution.
- **One text.** The standard library and other files do not exist: their names are unresolved, and an import
  is itself the declaration of its name.
- **No errors.** Invalid code is not reported; it is analyzed as far as it makes sense.
- **No increments.** Every change analyzes the whole text. The browser tests type into a text of 20,000 lines
  within their time budget, but nothing larger has been measured.
- **Heuristic statement ends.** An expression body ends at a line break unless an operator on either side
  carries it on, which decides how far the parameters of the function reach.
- **Rename relies on resolution.** A member found only because its name is unique in the text can be the wrong one
  when the receiver is of a type from elsewhere.

## Tests

```bash
./gradlew :language-kotlin:wasmJsNodeTest
```

The tests in `src/commonTest` run on Node.js: `HighlightTest`, `ResolveTest`, `OutlineTest` for structure, folding
and completion, and `RobustnessTest`, which analyzes every prefix and many damaged variants of a sample.
