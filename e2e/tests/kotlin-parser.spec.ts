import { expect, sampleText, test } from './app';

// The Kotlin compiler's own multiplatform parser (kotlin-parser) runs in the page as Kotlin/Wasm. Until the compiler
// workers are started it checks the document after each change and marks its syntax errors, with the compiler's words.

const unclosed = 'fun main() {\n    println("unclosed"\n}\n';

test.describe('syntax errors from the compiler\'s parser', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
    });

    test('a valid document has none', async ({ app }) => {
        await app.page.waitForTimeout(300);

        expect((await app.state()).diagnostics).toEqual([]);
        await expect(app.page.locator('.cm-lintRange-error')).toHaveCount(0);
    });

    test('are marked without starting the compiler', async ({ app, page }) => {
        await app.setText(unclosed);

        await expect.poll(async () => (await app.state()).diagnostics).toEqual([
            { severity: 'ERROR', message: "Expecting ')'", from: unclosed.indexOf('"unclosed"') + '"unclosed"'.length, to: unclosed.indexOf('"unclosed"') + '"unclosed"'.length },
        ]);
        // A zero-width error is a point mark where the missing token belongs.
        await expect(page.locator('.cm-lintPoint-error')).toHaveCount(1);
        const state = await app.state();
        expect(state.compiler).toBeNull();
        expect(app.requestedUrls).not.toContain('/kotlin-compiler-worker.js');
        expect(app.requestedUrls).not.toContain('/kotlin-analysis-worker.js');
    });

    test('follow typing and go away when the error is fixed', async ({ app, page }) => {
        await app.setText(sampleText);
        await app.caretAt('println(greet("Wasm"))', 1, 'println(greet("Wasm")'.length);

        await page.keyboard.press('Delete');
        await expect.poll(async () => (await app.state()).diagnostics.map(d => d.message)).toEqual(["Expecting ')'"]);

        await page.keyboard.type(')');
        await expect.poll(async () => (await app.state()).diagnostics).toEqual([]);
    });

    test('several errors are all marked, in order', async ({ app }) => {
        await app.setText('class {\n}\nfun f( = 1\nval x = \n');

        await expect.poll(async () => (await app.state()).diagnostics.length).toBeGreaterThanOrEqual(3);
        const diagnostics = (await app.state()).diagnostics;
        expect(diagnostics.map(d => d.from)).toEqual([...diagnostics.map(d => d.from)].sort((a, b) => a - b));
        expect(diagnostics.every(d => d.severity === 'ERROR')).toBe(true);
    });

    test('a long document is checked quickly', async ({ app }) => {
        const program = 'class Box<T>(val item: T) {\n    fun <R> map(f: (T) -> R): Box<R> = Box(f(item))\n}\n'.repeat(300) + 'fun broken( {\n';
        const started = Date.now();

        await app.setText(program);

        await expect.poll(async () => (await app.state()).diagnostics.length, { timeout: 5_000 }).toBeGreaterThan(0);
        expect(Date.now() - started).toBeLessThan(3_000);
    });
});

test.describe('the compiler\'s parser in the page', () => {
    test('builds the syntax tree with the compiler\'s node types', async ({ app }) => {
        await app.open();
        await app.setText('fun greet(name: String) = "Hello, $name!"');

        const tree = await app.syntaxTree();

        expect(tree.split('\n')[0]).toBe('kotlin.FILE: fun greet(name: String) = "Hello, $name!"');
        expect(tree).toContain('  FUNCTION: fun greet(name: String) = "Hello, $name!"');
        expect(tree).toContain('VALUE_PARAMETER_LIST: (name: String)');
        expect(tree).toContain('SHORT_STRING_TEMPLATE_ENTRY: $name');
    });

    test('is a Kotlin/Wasm module of its own, loaded with the application', async ({ app }) => {
        await app.open();

        expect(app.requestedUrls).toContain('/wasm-code-editor-kotlin-parser.wasm');
        expect(app.requestedUrls.some(url => /syntax-api/.test(url))).toBe(true);
    });
});
