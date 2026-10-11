# Browser tests

Playwright tests that run the unbundled distribution in the installed Chrome.

```bash
npm install                 # once, in this directory
../gradlew -p .. e2eTest    # build build/e2eDist and run everything
npx playwright test plugins # run one suite against the existing build/e2eDist
npm run update-snapshots    # accept intended visual changes
npm run report              # open the HTML report of the last run
```

The tests use a server on port 8787 and reuse one that already answers there, whatever it serves. When that port
is taken, for example by `serve.mjs build/devDist`, which has no test plugins, choose another: `E2E_PORT=8799`.
Two runs at once delete each other's traces in `test-results`; give one of them `--output=<another directory>`.

| Suite | What it checks |
|---|---|
| `startup.spec.ts` | no console errors or failed requests, unbundled module loading, same-origin requests only, reload, startup time budget |
| `editor.spec.ts` | document rendering, line numbers, long and empty documents, line endings, documents set from Kotlin |
| `editing.spec.ts` | edits made with the keyboard, the mouse and the clipboard, undo and redo, the search panel, the IntelliJ keymap, completion |
| `highlighting.spec.ts` | semantic highlighting and its IntelliJ Light colors, usages of the name under the caret, folding |
| `navigation.spec.ts` | go to declaration, usages, file structure, go to line, navigation history, rename |
| `statusbar.spec.ts` | the status bar Compose draws follows the caret, every kind of edit, the plugin list and plugin failures |
| `layout.spec.ts` | editor, canvas and gutter geometry at several window sizes and pixel densities, scrolling, resizing |
| `plugins.spec.ts` | both plugin tiers, worker isolation, termination of a hung plugin, rejection of disallowed imports and broken binaries |
| `plugin-index.spec.ts` | trust decisions of the plugin index, failing entries, a missing, broken or slow index |
| `compiler.spec.ts` | compiling and running the document with Ctrl+Enter, problems marked in the text, completion by the Analysis API; skipped when the distribution has no compiler (see `compiler-host/README.md`) |
| `distribution.spec.ts` | contents of the distribution (import map, plugin index, shared modules) and the static server, without a browser |
| `visual.spec.ts` | screenshots of the whole page against the baselines in `tests/__screenshots__` |

The Kotlin analysis behind highlighting and navigation (`language-kotlin/`) has unit tests of its own, which run on Node.js
without a browser: `../gradlew -p .. :language-kotlin:wasmJsNodeTest`.

What the editor takes a piece of text for is visible in the DOM: the analysis puts a class like `kt-property` on it,
which `app.tokenClasses(...)` reads. The caret position comes from the automation state.

Compose draws on a canvas, so its part of the UI is not in the DOM. Tests observe it in two ways:
through the automation functions the application module exports (`app/src/wasmJsMain/kotlin/editor/app/Automation.kt`,
wrapped by `tests/app.ts`) and through screenshots.

The status bar is checked in both ways. A baseline pins its exact text for a few documents. Most tests instead compare
two pictures taken in the same page (after an edit it differs, after undoing the edit it is identical again),
which needs no baseline and holds on any machine.

Tests that need a different plugin index replace `plugins.json` with `app.servePluginIndex(...)` before opening the page.

The screenshot baselines depend on the machine's font rendering; regenerate them when moving to another machine.
`test-plugins/` contains the plugins only the tests load; they exist only in `build/e2eDist`:

| Plugin | Behaviour |
|---|---|
| `spinning` | never returns from `main` |
| `reaching` | calls into JavaScript of its own |
| `throwing` | fails in `activate()` |
| `pair` | registers two plugins from one module |
| `impostor` | registers under the id of the `hello` plugin |
