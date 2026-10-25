# Wasm Code Editor

A Kotlin code editor that runs entirely in the browser. The application is written in Kotlin and compiled to
WebAssembly (Kotlin/Wasm); it edits one Kotlin document, understands it, and can compile and run it without a server.

This file describes what the application can do. How its parts are built is in:
- [`compiler-host/README.md`](compiler-host/README.md): the Kotlin compiler and the Analysis API as WebAssembly;
- [`e2e/README.md`](e2e/README.md): the browser tests;
- [`docs/problem-log.md`](docs/problem-log.md): bugs and surprising behaviour of the compilers and libraries the
  project is built on, with what is done about each.

## Running it

```bash
./gradlew devDist                      # the application as unbundled ES modules, in build/devDist
node e2e/serve.mjs build/devDist 8787  # then open http://localhost:8787
./gradlew e2eTest                      # the browser tests (Node.js and Chrome)
```

`serve.mjs` also passes the page's requests under `/maven/` on to Maven Central and Google's repository, which is
how the [libraries panel](#libraries) downloads libraries. Served by anything else, the editor works without
libraries. `MAVEN_REPOSITORIES` (a comma-separated list of repository URLs and local directories) changes where
they come from.

The compiler and the Analysis API are part of the distribution only where Oracle GraalVM is configured
(`graalvm.home` in `local.properties`). Everything under [Compiling and running](#compiling-and-running) and
[Analysis by the compiler](#analysis-by-the-compiler) needs them; the rest works without.

## The window

- The editor fills the window; a status bar is under it. The page itself never scrolls: a long document and a
  long line scroll inside the editor, and the line numbers stay at the left edge.
- The document is kept in the browser (`localStorage`), so a reload or a page opened later starts with what was
  there last; the first time it is a small sample program. It is saved 300 ms after the last change, and when the
  page is left with a change not saved yet. Two pages open at the same time do not follow each other: each edits its own copy, and the one
  changed last is what is kept. Where the browser gives no storage the editor works as before, without keeping
  anything. There is no opening or saving of files.
- The status bar shows:
  - the caret as `line:column`, and what is selected: `(3 chars)`, or `(2 carets)` during a rename;
  - the number of lines and characters of the document;
  - the loaded plugins, with `(sandboxed)` after those that run in a worker;
  - the state of the compiler, once it has been started: `loading` or `ready`;
  - a line for every plugin that failed to load, with the reason.
- The **Run** button is at the right end of the status bar. It is drawn on the canvas like the rest of the bar,
  so the keyboard and assistive technology do not reach it; Ctrl+Enter does the same.
- The look follows IntelliJ Light. The editor is a CodeMirror 6 view; the status bar and the output panel are
  drawn by Compose Multiplatform on a canvas.

## Editing

Typing, selection with the keyboard and the mouse, the clipboard, undo and redo work as in any editor. Brackets
and quotes are closed automatically, Enter inside braces opens an indented block, and the indentation is four spaces.

The keymap is IntelliJ's:

| Keys | Action |
|---|---|
| Ctrl+D | duplicate the line, or the selection right after itself |
| Ctrl+Y | delete the line |
| Alt+Shift+Up / Down | move the line |
| Ctrl+/ | comment the line out and back in |
| Ctrl+Shift+/ | wrap the selection in a block comment |
| Ctrl+Shift+J | join the next line to this one |
| Shift+Enter | start a new line below, from anywhere in the line |
| Ctrl+Alt+Enter | start a new line above |
| Tab / Shift+Tab | indent and unindent the line or every selected line |
| Alt+J | add a caret at the next occurrence of the selection |
| Alt+Shift+click | add a caret |
| Ctrl+F | search and replace, with whole-word search and replace all |
| Ctrl+Space | completion |
| Ctrl+Enter | compile and run the document |

## Understanding the document

The editor analyzes the document itself, in the page, on every change (`language-kotlin`). This analysis works by
the names declared in the file; it does not know the standard library or types of expressions.

- **Semantic highlighting.** Keywords, strings with their escapes and templates, numbers, comments, documentation
  comments with their tags and links, annotations and labels are painted, and names are painted by what they are:
  classes, type parameters, function declarations and calls (calls of top-level functions in italics), properties,
  parameters, local variables, objects and enum entries, named arguments, the implicit `it`. What is declared with
  `var` is underlined. Only the visible part of a long document is painted.
- **Usages under the caret.** The declaration and every use of the name at the caret are marked; writes are marked
  in a different color from reads.
- **Go to declaration** (Ctrl+B, Ctrl+click). Works for local variables, parameters, properties, functions, types,
  members found through a known type or a supertype, and names inside string templates and documentation comments.
  On a declaration it goes to its only usage, or lists the usages.
- **Usages** (Alt+F7). A list of the lines that use the name, with a filter; the arrow keys, Enter or a click pick one.
- **File structure** (Ctrl+F12). The declarations of the file with their kinds and signatures, members indented
  under their class, starting at the declaration the caret is in; typing filters the list.
- **Go to line** (Ctrl+G). Takes `line` or `line:column`.
- **Navigation history** (Ctrl+Alt+Left / Right). Returns to where a jump started and repeats it; the remembered
  places move with the text edited in front of them.
- **Rename** (Shift+F6). Puts a caret on every occurrence of the symbol, so typing renames them all; Enter or Escape
  finishes. Only the symbol under the caret is renamed, not others of the same name, and a named argument follows
  its parameter. The rename is undone in one step.
- **Folding.** Classes, functions and other blocks, documentation comments and the imports fold from the gutter.
- **Completion.** The names of the file that are visible at the caret and the keywords, with signatures of
  functions. Parameters are offered only inside their function; nothing is offered inside comments and strings or
  for the name of a new declaration.

## Compiling and running

- **Ctrl+Enter** in the editor, or the **Run** button at the right end of the status bar, compiles the document
  for the Wasm target and runs the result. An output panel appears between
  the editor and the status bar with the state (`Compiling`, `Running`, `Finished`, `Failed`, `Compilation failed`)
  and what the program printed.
- The three kinds of lines in the panel look different, so that the output of the program is not mistaken for
  anything else: the state is bold, gray while the run is going, green when it finished and red when it failed;
  what the program printed is black; the errors of the compiler and the reason a program stopped are red.
- When the document does not compile, the panel lists the errors as `line:column: message` and they are marked in
  the text.
- The program runs in a web worker of its own, so it cannot block the editor. It is stopped after 10 seconds.
  When it fails, what it printed before is kept.
- A run lasts until `main` has returned and nothing is scheduled any more, so a coroutine waiting in `delay()`,
  a `suspend fun main` or a timer still prints into the panel.
- The document is one file compiled against the Kotlin standard library for Wasm and the libraries of the
  [libraries panel](#libraries); running it calls its `main` function.
- The compiler is the real Kotlin compiler (K2 with the Wasm back end), itself running as WebAssembly in a worker.
  It is downloaded on the first run and not before: 75 MB for the compiler, 75 MB for the Analysis API and 12 MB for
  the standard library. In the tests the first run of the sample takes about five seconds from a local server, and
  later compilations about a second.

## Libraries

A program may use Kotlin Multiplatform libraries that are built for Kotlin/Wasm in the browser (`wasm-js`), such as
`kotlinx-coroutines-core`.

- The **Libraries** button in the status bar, or **Ctrl+Alt+Shift+S**, opens the libraries panel. A library is
  added by its Maven coordinates, `group:artifact:version`, or `group:artifact` for the latest release.
- The page reads the library's Gradle module metadata, finds its `wasm-js` variant and downloads its klib, with
  what it depends on. When two libraries need one in different versions, the newer is taken. The standard library
  is the compiler's own and is never downloaded.
- Each library shows what it loaded (`Loaded: kotlinx-coroutines-core-wasm-js-1.11.0, atomicfu-wasm-js-0.32.1`) or
  why it could not: not in the repository, not a Kotlin Multiplatform library, or not built for `wasm-js` (with the
  platforms it is built for). The button says how many libraries there are, in red when one failed.
- The compiler and the analysis get the libraries as soon as they are loaded: names from them are no longer marked
  as unresolved, and completion offers them. A run waits for libraries that are still loading, and stops with
  `Libraries failed` and the reasons when one could not be loaded.
- The list is kept in the browser next to the document and loaded again with the page. Nothing is downloaded for
  libraries until one is listed.
- Escape, Close or a click beside the panel closes it and gives the keyboard back to the editor.

What does not work:
- libraries that need a compiler plugin (`kotlinx-serialization`'s `@Serializable`, Compose), because the compiler
  in the page runs without plugins;
- libraries that import npm packages or JavaScript files of their own (`@JsModule`), because the program runs from
  the compiled files alone;
- repositories other than those of the server, and anything that is not a published release (no local builds, no
  `.klib` files from disk).

## Syntax errors from the compiler's parser

The Kotlin compiler's own lexer and parser run in the page as Kotlin/Wasm (`kotlin-parser`). They are the
compiler's multiplatform parser (`compiler/multiplatform-parsing` of the Kotlin repository), taken over unchanged,
built on IntelliJ's multiplatform syntax library.

- 100 ms after each change the document is parsed and its syntax errors are marked, with the compiler's own words
  (`Expecting ')'`, `Expecting a top level declaration`). An error at a single position, such as a missing
  parenthesis, is a small mark where the token belongs; the message shows on hover.
- This works from the start, without the compiler: nothing of the 75 MB workers is downloaded for it. The parser
  is three Wasm modules of the application, about 1.3 MB together (the parser 0.5 MB, the syntax library 0.7 MB).
- Once the compiler is started, the Analysis API checks the text instead and reports syntax errors with everything
  else.
- A file of 900 lines is parsed in well under a second.

## Analysis by the compiler

Once the first run has started the compiler, the Analysis API takes over two things from the editor's own analysis:

- **Errors and warnings.** The document is analyzed 300 ms after each change, and what is found is underlined in
  the text, with the message shown on hover.
- **Completion by meaning.**
  - After a dot: the members of the type of the expression before it, for types of the document and of the
    standard library alike, or the nested and static declarations of a class, such as enum entries.
  - Elsewhere: everything visible at the caret, the standard library included (`listOf`, `println`).
  - Each item shows its parameters and types.
  - The list opens right after a dot is typed. When the analysis offers nothing, the editor's own completion is used.

Highlighting, navigation, usages, rename and the file structure still come from the editor's own analysis.

## Plugins

The editor loads plugins at run time. A plugin is a Kotlin/Wasm module of its own that links against the running
application instead of bundling a copy of the standard library (`kotlin.wasm.compilationMode=multimodule-open-world`).

- A plugin implements `EditorPlugin` (`id`, `activate()`, `deactivate()`) from `plugin-api` and registers itself in
  `PluginRegistry` from its `main`. One module may register several plugins.
- `plugins.json` lists the plugins to load at start and whether each is trusted. A plugin that fails to load is
  reported in the status bar and does not affect the others.
- There are two tiers:
  - **Trusted** plugins are imported into the application itself and share its registry.
  - **Sandboxed** plugins each get a web worker. Only the plugin's `.wasm` is loaded, none of its JavaScript, and
    it is linked only against the modules the host allows (`shared-modules.json`: the standard library and the
    plugin API). It cannot reach the page. A worker has a registry of its own, and one that does not answer within
    10 seconds is terminated.
- Every load is written to the browser console with the prefix `[plugins]`: what the index lists, then for each
  module the plugins it registered and the way it was loaded (`in the application, trusted by the index`;
  `in a sandbox worker, not trusted by the index`; `in a sandbox worker, trusted by the index but ?sandbox=all is
  set`; `in a sandbox worker, loaded on request`). A failed load is a warning with the same words and the reason.
- `?sandbox=all` in the page address moves the trusted plugins into sandboxes too. The address can only tighten
  the trust decision.
- A plugin can so far only be activated and deactivated: the API gives it nothing of the editor to work with.
  `plugins/hello` is the one first-party plugin; `test-plugins/` holds misbehaving ones for the tests.

## Delivery

- The application is served as unbundled ES modules, one `.wasm` per Gradle module, linked in the browser. npm
  packages are resolved by an import map the build generates into `index.html`.
- Nothing is requested from other origins. Libraries come through the server of the page (`/maven/`).
- The editor is described to assistive technology by CodeMirror; what Compose draws on the canvas is not.

## Automation

The application module exports functions the browser tests use to observe and drive what is drawn on the canvas
(`app/src/wasmJsMain/kotlin/editor/app/Automation.kt`): `automationState()` (text, caret, plugins, failures,
compiler state, output panel, diagnostics, where the Run and Libraries buttons are, the libraries and what became of
them), `automationSetText`, `automationSelect`, `automationRun` and `automationLoadSandboxedPlugin`.

## Modules

| Module | What it is |
|---|---|
| `app` | the application: session state, the Compose shell, the plugin loader, the compiler client, the libraries panel and its resolver |
| `editor-codemirror` | the CodeMirror editor and its Kotlin wrapper |
| `language-kotlin` | the editor's own analysis of Kotlin text: lexer, symbols, highlighting, completion (multiplatform, with unit tests) |
| `kotlin-parser` | the Kotlin compiler's multiplatform lexer and parser (`upstream/`, unchanged from the Kotlin repository) and the editor's API over it: syntax errors and the syntax tree |
| `compiler-host` | builds the Kotlin compiler and the Analysis API into WebAssembly workers (JVM build, needs GraalVM) |
| `plugin-api` | what plugins and the host share |
| `worker-rt`, `plugin-worker` | the sandbox: the worker's entry and the host's side of it |
| `plugins`, `test-plugins` | first-party plugins and the ones only tests load |
| `e2e` | Playwright tests and the static server |

## Not there yet

- Files: opening, saving, several documents, projects. A way back to the sample other than clearing the site data.
- Keeping anything but the text between sessions: the caret, the scroll position, the undo history, the output.
- Input to a running program.
- Libraries that need compiler plugins or JavaScript of their own, and libraries from files rather than a repository.
- Navigation, rename and highlighting by the Analysis API; they use the editor's name-based analysis.
- Extension functions and keywords in the completion by meaning, and adding imports.
- Anything a plugin could do with the editor.
- Themes other than the light one.
