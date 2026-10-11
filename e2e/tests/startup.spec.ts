import { expect, sampleText, test } from './app';

test.describe('startup', () => {
    test('loads without errors or warnings', async ({ app }) => {
        await app.open();

        expect(app.pageErrors).toEqual([]);
        expect(app.consoleProblems()).toEqual([]);
        expect(app.failedRequests).toEqual([]);
    });

    test('is served as unbundled modules linked at runtime', async ({ app }) => {
        await app.open();

        const wasmFiles = app.requestedUrls.filter(url => url.endsWith('.wasm'));
        expect(wasmFiles).toContain('/kotlin-kotlin-stdlib.wasm');
        expect(wasmFiles).toContain('/wasm-code-editor-app.wasm');
        expect(wasmFiles).toContain('/wasm-code-editor-plugin-api.wasm');
        // Every Kotlin module is a file of its own and is fetched exactly once.
        expect(wasmFiles.length).toBeGreaterThan(20);
        expect(new Set(wasmFiles).size).toBe(wasmFiles.length);
        // npm packages are resolved by the import map.
        expect(app.requestedUrls).toContain('/vendor/@codemirror/view/dist/index.js');
    });

    test('starts within the time budget', async ({ app }, testInfo) => {
        const started = Date.now();
        await app.open();
        const elapsed = Date.now() - started;

        testInfo.annotations.push({ type: 'startup', description: `${elapsed} ms until plugins are ready` });
        expect(elapsed).toBeLessThan(15_000);
    });

    test('requests nothing from other origins', async ({ app, page, baseURL }) => {
        const origins = new Set<string>();
        page.on('request', request => {
            const url = new URL(request.url());
            if (url.protocol !== 'data:') origins.add(url.origin);
        });

        await app.open('?sandbox=all');

        expect([...origins]).toEqual([new URL(baseURL!).origin]);
    });

    test('shows the sample document with the caret ready after one click', async ({ app, page }) => {
        await app.open();

        expect(await page.title()).toBe('Wasm Code Editor');
        expect(await app.text()).toBe(sampleText);
        await app.content.click();
        await expect(app.content).toBeFocused();
    });

    test('describes the editor to assistive technology', async ({ app }) => {
        await app.open();

        await expect(app.content).toHaveAttribute('role', 'textbox');
        await expect(app.content).toHaveAttribute('aria-multiline', 'true');
        await expect(app.content).toHaveAttribute('contenteditable', 'true');
        await expect(app.content).toHaveAttribute('data-language', 'kotlin');
    });

    test('starts again from the sample document after a reload', async ({ app, page }) => {
        await app.open('?sandbox=all');
        await app.setText('val edited = true');
        await expect.poll(() => app.text()).toBe('val edited = true');

        await page.reload();
        await app.editorShown();
        await app.pluginsSettled();

        const state = await app.state();
        expect(state.text).toBe(sampleText);
        expect(state.plugins).toEqual([{ id: 'hello', sandboxed: true }]);
        // The worker of the previous page is gone.
        expect(page.workers()).toHaveLength(1);
        expect(app.pageErrors).toEqual([]);
    });

    test('two pages do not share their documents', async ({ app, context }) => {
        await app.open();
        const other = await context.newPage();
        await other.goto('/');
        await expect(other.locator('.cm-editor')).toBeVisible({ timeout: 30_000 });

        await app.setText('val onlyHere = true');

        await expect(app.content.locator('.cm-line')).toHaveText(['val onlyHere = true']);
        await expect(other.locator('.cm-content .cm-line').first()).toHaveText('/** Greets [name]. */');
    });
});
