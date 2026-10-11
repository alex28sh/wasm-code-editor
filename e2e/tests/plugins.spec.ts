import { expect, pluginModule, test } from './app';

const { hello, spinning, reaching, throwing, pair, impostor } = pluginModule;

test.describe('trusted plugins', () => {
    test('run in the host instance group', async ({ app, page }) => {
        await app.open();

        expect((await app.state()).plugins).toEqual([{ id: 'hello', sandboxed: false }]);
        expect(app.console).toContainEqual({ type: 'log', text: 'hello plugin activated', fromWorker: false });
        expect(page.workers()).toHaveLength(0);
        // The plugin reuses the modules the host has already loaded.
        expect(app.requestedUrls).toContain(`/${hello}.wasm`);
        expect(app.requestedUrls.filter(url => url === '/kotlin-kotlin-stdlib.wasm')).toHaveLength(1);
    });
});

test.describe('sandboxed plugins', () => {
    test('the same binary runs in a worker when sandboxing is requested', async ({ app, page }) => {
        await app.open('?sandbox=all');

        expect((await app.state()).plugins).toEqual([{ id: 'hello', sandboxed: true }]);
        expect(app.console).toContainEqual({ type: 'log', text: 'hello plugin activated', fromWorker: true });
        expect(page.workers()).toHaveLength(1);
        expect(app.pageErrors).toEqual([]);
    });

    test('a worker has an instance group of its own without the UI modules', async ({ app }) => {
        await app.open('?sandbox=all');

        const requests = (file: string) => app.requestedUrls.filter(url => url === file).length;
        expect(requests('/kotlin-kotlin-stdlib.wasm')).toBe(2);
        expect(requests('/wasm-code-editor-plugin-api.wasm')).toBe(2);
        expect(requests('/skiko.wasm')).toBe(1);
        expect(requests('/compose-multiplatform-core-compose-ui-ui.wasm')).toBe(1);
        expect(requests('/kotlinx-browser.wasm')).toBe(1);
        // The plugin's own JavaScript glue is never executed in the sandbox.
        expect(requests(`/${hello}.mjs`)).toBe(0);
    });

    test('a plugin that never returns is terminated while the editor stays responsive', async ({ app, page }) => {
        await app.open();
        const started = Date.now();
        const loading = app.loadSandboxedPlugin(spinning);

        await app.content.click();
        await page.keyboard.press('Control+End');
        await page.keyboard.type(' // still responsive');
        await expect.poll(async () => (await app.state()).text, { timeout: 3_000 }).toContain('// still responsive');
        expect(Date.now() - started).toBeLessThan(8_000);

        const result = await loading;
        expect(result.loaded).toEqual([]);
        expect(result.error).toMatch(/did not respond within 10s and was terminated/);
        await expect.poll(() => page.workers().length).toBe(0);
        expect((await app.state()).failures.map(failure => failure.module)).toEqual([spinning]);
    });

    test('a plugin bringing its own JavaScript is rejected', async ({ app, page }) => {
        await app.open();

        const result = await app.loadSandboxedPlugin(reaching);

        expect(result.loaded).toEqual([]);
        expect(result.error).toBe(`Plugin ./${reaching}.wasm imports 'js_code', which is not available to sandboxed plugins`);
        await expect.poll(() => page.workers().length).toBe(0);
    });

    test('a missing plugin fails without leaving a worker behind', async ({ app, page }) => {
        await app.open();

        const result = await app.loadSandboxedPlugin('no-such-plugin');

        expect(result.loaded).toEqual([]);
        expect(result.error).toMatch(/^Cannot load plugin \.\/no-such-plugin\.wasm: /);
        await expect.poll(() => page.workers().length).toBe(0);
    });

    test('failing plugins do not affect loaded ones', async ({ app }) => {
        await app.open();
        await app.loadSandboxedPlugin(reaching);

        const loaded = await app.loadSandboxedPlugin(hello);

        expect(loaded).toEqual({ loaded: ['hello'], error: null });
        expect((await app.state()).plugins).toEqual([
            { id: 'hello', sandboxed: false },
            { id: 'hello', sandboxed: true },
        ]);
        await app.setText('val stillWorks = true');
        await expect(app.content.locator('.cm-line')).toHaveText(['val stillWorks = true']);
    });

    test('a plugin failing to activate is reported and its worker is terminated', async ({ app, page }) => {
        await app.open();

        const result = await app.loadSandboxedPlugin(throwing);

        expect(result).toEqual({ loaded: [], error: 'activation failed on purpose' });
        await expect.poll(() => page.workers().length).toBe(0);
        expect((await app.state()).failures).toEqual([{ module: throwing, message: 'activation failed on purpose' }]);
        expect(app.pageErrors).toEqual([]);
    });

    test('a module registering several plugins shares one worker', async ({ app, page }) => {
        await app.open();

        const result = await app.loadSandboxedPlugin(pair);

        expect(result).toEqual({ loaded: ['pair-first', 'pair-second'], error: null });
        expect(page.workers()).toHaveLength(1);
        expect(app.console).toContainEqual({ type: 'log', text: 'pair-first activated', fromWorker: true });
        expect(app.console).toContainEqual({ type: 'log', text: 'pair-second activated', fromWorker: true });
    });

    test('a worker has a registry of its own, so an id taken in the host is free there', async ({ app }) => {
        await app.open();

        const result = await app.loadSandboxedPlugin(impostor);

        expect(result).toEqual({ loaded: ['hello'], error: null });
        expect(app.console).toContainEqual({ type: 'log', text: 'impostor activated', fromWorker: true });
        expect((await app.state()).plugins).toEqual([
            { id: 'hello', sandboxed: false },
            { id: 'hello', sandboxed: true },
        ]);
    });

    test('every sandboxed load gets a worker of its own', async ({ app, page }) => {
        await app.open();

        const results = await Promise.all([app.loadSandboxedPlugin(hello), app.loadSandboxedPlugin(hello), app.loadSandboxedPlugin(pair)]);

        expect(results.map(result => result.error)).toEqual([null, null, null]);
        expect(page.workers()).toHaveLength(3);
        // No worker sees what another one registered: both copies of hello register the same id.
        expect(results[0].loaded).toEqual(['hello']);
        expect(results[1].loaded).toEqual(['hello']);
    });

    test('a sandboxed plugin cannot reach the page', async ({ app, page }) => {
        await app.open('?sandbox=all');

        const [worker] = page.workers();
        const scope = await worker.evaluate(() => ({
            document: typeof (globalThis as any).document,
            window: typeof (globalThis as any).window,
            localStorage: typeof (globalThis as any).localStorage,
        }));

        expect(scope).toEqual({ document: 'undefined', window: 'undefined', localStorage: 'undefined' });
    });

    test('a binary that is not WebAssembly is rejected', async ({ app, page }) => {
        await app.open();
        await page.route(`**/${pair}.wasm`, route => route.fulfill({ body: 'not wasm', contentType: 'application/wasm' }));

        const result = await app.loadSandboxedPlugin(pair);

        expect(result.loaded).toEqual([]);
        expect(result.error).toMatch(new RegExp(`^Cannot load plugin \\./${pair}\\.wasm: `));
        await expect.poll(() => page.workers().length).toBe(0);
    });

    test('a binary served with the wrong content type is rejected', async ({ app, page }) => {
        await app.open();
        await page.route(`**/${pair}.wasm`, async route => {
            const response = await route.fetch();
            await route.fulfill({ response, headers: { ...response.headers(), 'content-type': 'text/plain' } });
        });

        const result = await app.loadSandboxedPlugin(pair);

        expect(result.loaded).toEqual([]);
        expect(result.error).toContain("Expected 'application/wasm'");
        await expect.poll(() => page.workers().length).toBe(0);
    });

    test('nothing is linked when the list of shared modules is unavailable', async ({ app, page }) => {
        await app.open();
        await page.route('**/shared-modules.json', route => route.fulfill({ status: 404, body: 'Not found' }));

        const result = await app.loadSandboxedPlugin(hello);

        expect(result.loaded).toEqual([]);
        expect(result.error).toMatch(new RegExp(`^Cannot load plugin \\./${hello}\\.wasm: `));
        await expect.poll(() => page.workers().length).toBe(0);
    });

    test('a plugin is linked only against the modules the list allows', async ({ app, page }) => {
        await app.open();
        // Without the API module in the list, even a well-behaved plugin has nothing to register with.
        await page.route('**/shared-modules.json', route => route.fulfill({ json: { '<kotlin>': './kotlin-kotlin-stdlib.mjs' } }));

        const result = await app.loadSandboxedPlugin(hello);

        expect(result.loaded).toEqual([]);
        expect(result.error).toBe(
            `Plugin ./${hello}.wasm imports '<wasm-code-editor:plugin-api>', which is not available to sandboxed plugins`,
        );
    });

    test('a worker script that fails to load is reported at once', async ({ app, page }) => {
        await app.open();
        await page.route('**/wasm-code-editor-plugin-worker.mjs', route => route.fulfill({ status: 404, body: 'Not found' }));
        const started = Date.now();

        const result = await app.loadSandboxedPlugin(hello);

        expect(result).toEqual({ loaded: [], error: 'Plugin worker failed' });
        // Not after the ten seconds given to a plugin that does not respond.
        expect(Date.now() - started).toBeLessThan(5_000);
        expect((await app.state()).plugins).toEqual([{ id: 'hello', sandboxed: false }]);
    });
});
