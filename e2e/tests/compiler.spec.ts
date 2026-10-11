import { expect, test } from './app';

// The Kotlin compiler and the Analysis API run in the page as WebAssembly: Ctrl+Enter compiles the document and
// runs the result, and from then on the analysis checks the text and completes it.
// Both are built only where GraalVM is installed, so these tests are skipped when the distribution has none.

const broken = `fun main() {
    val count: Int = "three"
    println(missing(count))
}`;

const members = `class Box(val label: String) {
    fun describe(): String = "box of " + label
}

fun main() {
    val box = Box("apples")
    println(box)
    println("text")
}`;

const fixed = `fun main() {
    val count: Int = 3
    println(count * 2)
}`;

test.describe('compiler', () => {
    // Each page downloads and compiles the compiler itself, which is tens of megabytes.
    test.describe.configure({ mode: 'serial', timeout: 240_000 });

    test.beforeEach(async ({ app, request }) => {
        test.skip(!(await request.head('/kotlin-compiler-worker.js')).ok(), 'the distribution has no compiler');
        await app.open();
    });

    test('is not started until the program is run', async ({ app }) => {
        const state = await app.state();

        expect(state.compiler).toBeNull();
        expect(state.console).toBeNull();
        expect(app.requestedUrls).not.toContain('/kotlin-compiler-worker.js');
        expect(app.requestedUrls).not.toContain('/kotlin-analysis-worker.js');
    });

    test('runs the initial document', async ({ app }) => {
        const state = await app.run();

        expect(state.console).toEqual({ title: 'Finished', lines: ['Hello, Wasm!'] });
        expect(state.compiler).toBe('ready');
        expect(state.diagnostics).toEqual([]);
        expect(app.pageErrors).toEqual([]);
    });

    test('runs with Ctrl+Enter', async ({ app, page }) => {
        await app.focus();

        await page.keyboard.press('Control+Enter');

        await expect.poll(async () => (await app.state()).console, { timeout: 200_000 })
            .toEqual({ title: 'Finished', lines: ['Hello, Wasm!'] });
        expect(await app.text()).toContain('println(greet("Wasm"))\n}');
    });

    test('reports what does not compile and marks it in the text', async ({ app, page }) => {
        await app.setText(broken);

        const state = await app.run();

        expect(state.console?.title).toBe('Compilation failed');
        expect(state.console?.lines).toEqual([
            "2:20: Initializer type mismatch: expected 'Int', actual 'String'.",
            "3:13: Unresolved reference 'missing'.",
        ]);
        expect(state.diagnostics.map(diagnostic => broken.slice(diagnostic.from, diagnostic.to))).toContain('missing');
        await expect(page.locator('.cm-lintRange-error', { hasText: 'missing' })).toBeVisible();
    });

    test('checks the text again after it changes', async ({ app, page }) => {
        await app.run();

        await app.setText(broken);
        await expect.poll(async () => (await app.state()).diagnostics.length, { timeout: 60_000 }).toBe(2);
        await expect(page.locator('.cm-lintRange-error')).toHaveCount(2);

        await app.setText(fixed);
        await expect.poll(async () => (await app.state()).diagnostics.length, { timeout: 60_000 }).toBe(0);
        await expect(page.locator('.cm-lintRange-error')).toHaveCount(0);

        expect((await app.run()).console).toEqual({ title: 'Finished', lines: ['6'] });
    });

    test('shows what the program printed before it failed', async ({ app }) => {
        await app.setText('fun main() {\n    println("before")\n    error("boom")\n}');

        const state = await app.run();

        expect(state.console?.title).toBe('Failed');
        expect(state.console?.lines[0]).toBe('before');
    });

    test('completes the members of a class of the document after a dot', async ({ app, page }) => {
        await app.setText(members);
        await app.run();
        await app.caretAt('println(box)', 1, 'println(box'.length);

        await page.keyboard.type('.');

        const options = page.locator('.cm-tooltip-autocomplete li');
        await expect(options.filter({ hasText: 'describe' })).toBeVisible({ timeout: 60_000 });
        await expect(options.filter({ hasText: 'label' })).toBeVisible();
        // Members of Any come with every class; the names of the file do not belong after a dot.
        await expect(options.filter({ hasText: 'toString' })).toBeVisible();
        await expect(options.filter({ hasText: 'main' })).toHaveCount(0);
    });

    test('completes the members of a library type with their types', async ({ app, page }) => {
        await app.setText(members);
        await app.run();
        await app.caretAt('println("text")', 1, 'println("text"'.length);

        await page.keyboard.type('.le');

        const length = page.locator('.cm-tooltip-autocomplete li', { hasText: 'length' });
        await expect(length).toBeVisible({ timeout: 60_000 });
        await expect(length).toContainText('Int');

        // CodeMirror ignores keys for a moment after the list opens.
        await page.waitForTimeout(200);
        await page.keyboard.press('Enter');
        await expect.poll(() => app.text()).toContain('println("text".length)');
        const state = await app.run();
        expect(state.console?.title).toBe('Finished');
        expect(state.console?.lines[1]).toBe('4');
    });

    test('completes the functions of the standard library', async ({ app, page }) => {
        await app.run();
        await app.focus('end');

        await page.keyboard.type('\nfun other() = listO');

        await expect(page.locator('.cm-tooltip-autocomplete li', { hasText: 'listOf' }).first()).toBeVisible({ timeout: 60_000 });
    });
});
