import { expect, pluginModule, sampleText, test } from './app';

// The plugin index decides which plugins are loaded and which of them are trusted.
// These tests replace the index the application fetches, so they also cover what a broken deployment looks like.

const { hello, spinning, reaching, throwing, pair, impostor } = pluginModule;

test.describe('plugin index', () => {
    test('an empty index loads nothing', async ({ app, page }) => {
        await app.servePluginIndex([]);
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([]);
        expect(state.failures).toEqual([]);
        expect(page.workers()).toHaveLength(0);
        expect(app.requestedUrls).not.toContain(`/${hello}.wasm`);
        expect(app.consoleProblems()).toEqual([]);
    });

    test('an untrusted entry is sandboxed', async ({ app, page }) => {
        await app.servePluginIndex([{ module: hello, trusted: false }]);
        await app.open();

        expect((await app.state()).plugins).toEqual([{ id: 'hello', sandboxed: true }]);
        expect(page.workers()).toHaveLength(1);
        expect(app.requestedUrls).not.toContain(`/${hello}.mjs`);
    });

    test('an entry without a trust decision is rejected rather than trusted', async ({ app }) => {
        await app.servePluginIndex(JSON.stringify([{ module: hello }]));
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([]);
        expect(state.failures).toHaveLength(1);
        expect(state.failures[0].module).toBe('plugins.json');
        expect(state.failures[0].message).toContain("'trusted' is required");
        expect(app.requestedUrls).not.toContain(`/${hello}.mjs`);
    });

    test('the page address cannot make an untrusted plugin trusted', async ({ app, page }) => {
        await app.servePluginIndex([{ module: hello, trusted: false }]);
        await app.open('?sandbox=none&trusted=all');

        expect((await app.state()).plugins).toEqual([{ id: 'hello', sandboxed: true }]);
        expect(page.workers()).toHaveLength(1);
    });

    test('plugins load in the order of the index, each in its own tier', async ({ app, page }) => {
        await app.servePluginIndex([
            { module: pair, trusted: false },
            { module: hello, trusted: true },
            { module: hello, trusted: false },
        ]);
        await app.open();

        expect((await app.state()).plugins).toEqual([
            { id: 'pair-first', sandboxed: true },
            { id: 'pair-second', sandboxed: true },
            { id: 'hello', sandboxed: false },
            { id: 'hello', sandboxed: true },
        ]);
        // One worker per sandboxed entry.
        expect(page.workers()).toHaveLength(2);
    });

    test('a module listed twice is loaded once', async ({ app }) => {
        await app.servePluginIndex([{ module: hello, trusted: true }, { module: hello, trusted: true }]);
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([{ id: 'hello', sandboxed: false }]);
        expect(state.failures).toEqual([]);
        expect(app.console.filter(entry => entry.text === 'hello plugin activated')).toHaveLength(1);
    });

    test('a trusted module can register several plugins', async ({ app }) => {
        await app.servePluginIndex([{ module: pair, trusted: true }]);
        await app.open();

        expect((await app.state()).plugins).toEqual([
            { id: 'pair-first', sandboxed: false },
            { id: 'pair-second', sandboxed: false },
        ]);
        expect(app.console).toContainEqual({ type: 'log', text: 'pair-first activated', fromWorker: false });
        expect(app.console).toContainEqual({ type: 'log', text: 'pair-second activated', fromWorker: false });
    });
});

test.describe('failing index entries', () => {
    test('a missing trusted plugin is reported and the next one still loads', async ({ app }) => {
        await app.servePluginIndex([{ module: 'no-such-plugin', trusted: true }, { module: hello, trusted: true }]);
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([{ id: 'hello', sandboxed: false }]);
        expect(state.failures).toHaveLength(1);
        expect(state.failures[0].module).toBe('no-such-plugin');
        expect(state.failures[0].message).toContain('/no-such-plugin.mjs');
        expect(app.pageErrors).toEqual([]);
    });

    test('a missing untrusted plugin is reported and leaves no worker', async ({ app, page }) => {
        await app.servePluginIndex([{ module: 'no-such-plugin', trusted: false }, { module: hello, trusted: true }]);
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([{ id: 'hello', sandboxed: false }]);
        expect(state.failures.map(failure => failure.module)).toEqual(['no-such-plugin']);
        expect(state.failures[0].message).toMatch(/^Cannot load plugin \.\/no-such-plugin\.wasm: /);
        await expect.poll(() => page.workers().length).toBe(0);
    });

    test('a trusted plugin failing to activate is reported', async ({ app }) => {
        await app.servePluginIndex([{ module: throwing, trusted: true }, { module: hello, trusted: true }]);
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([{ id: 'hello', sandboxed: false }]);
        expect(state.failures).toEqual([{ module: throwing, message: 'activation failed on purpose' }]);
        expect(app.pageErrors).toEqual([]);
    });

    test('a trusted plugin claiming a registered id is refused', async ({ app }) => {
        await app.servePluginIndex([{ module: hello, trusted: true }, { module: impostor, trusted: true }]);
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([{ id: 'hello', sandboxed: false }]);
        expect(state.failures.map(failure => failure.module)).toEqual([impostor]);
        expect(state.failures[0].message).toContain("Plugin 'hello' is already registered");
        expect(app.console.map(entry => entry.text)).not.toContain('impostor activated');
    });

    test('several failures are all reported, in order', async ({ app, page }) => {
        await app.servePluginIndex([
            { module: reaching, trusted: false },
            { module: hello, trusted: true },
            { module: throwing, trusted: false },
        ]);
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([{ id: 'hello', sandboxed: false }]);
        expect(state.failures.map(failure => failure.module)).toEqual([reaching, throwing]);
        await expect.poll(() => page.workers().length).toBe(0);
    });

    test('a plugin that hangs holds back only the plugins after it, not the editor', async ({ app, page }) => {
        await app.servePluginIndex([{ module: spinning, trusted: false }, { module: hello, trusted: true }]);
        await app.openEditor();

        await app.focus('end');
        await page.keyboard.type(' // usable');
        await expect.poll(() => app.text(), { timeout: 3_000 }).toBe(sampleText + ' // usable');
        expect((await app.state()).pluginsReady).toBe(false);

        await app.pluginsSettled();
        const state = await app.state();
        expect(state.plugins).toEqual([{ id: 'hello', sandboxed: false }]);
        expect(state.failures.map(failure => failure.module)).toEqual([spinning]);
        await expect.poll(() => page.workers().length).toBe(0);
    });
});

test.describe('broken index', () => {
    test('a missing index is reported and the editor still works', async ({ app }) => {
        await app.servePluginIndex('Not found', 404);
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([]);
        expect(state.failures).toHaveLength(1);
        expect(state.failures[0].module).toBe('plugins.json');
        expect(state.failures[0].message).toContain('Cannot fetch ./plugins.json');
        expect(app.pageErrors).toEqual([]);
        await app.setText('val stillWorks = true');
        await expect(app.content.locator('.cm-line')).toHaveText(['val stillWorks = true']);
    });

    test('an index that is not JSON is reported', async ({ app }) => {
        await app.servePluginIndex('<html>Sign in</html>');
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([]);
        expect(state.failures.map(failure => failure.module)).toEqual(['plugins.json']);
        expect(app.pageErrors).toEqual([]);
    });

    test('an index that cannot be reached is reported', async ({ app, page }) => {
        await page.route('**/plugins.json', route => route.abort('connectionrefused'));
        await app.open();

        const state = await app.state();
        expect(state.plugins).toEqual([]);
        expect(state.failures.map(failure => failure.module)).toEqual(['plugins.json']);
        expect(app.pageErrors).toEqual([]);
    });

    test('the editor is usable while the index is still on its way', async ({ app, page }) => {
        let release!: () => void;
        const released = new Promise<void>(resolve => release = resolve);
        await page.route('**/plugins.json', async route => {
            await released;
            await route.continue();
        });
        await app.openEditor();

        await app.focus('end');
        await page.keyboard.type(' // early');
        await expect.poll(() => app.text()).toBe(sampleText + ' // early');
        const waiting = await app.state();
        expect(waiting.pluginsReady).toBe(false);
        expect(waiting.plugins).toEqual([]);

        release();
        await app.pluginsSettled();
        const state = await app.state();
        expect(state.plugins).toEqual([{ id: 'hello', sandboxed: false }]);
        // Loading the plugins does not touch the document.
        expect(state.text).toBe(sampleText + ' // early');
    });
});

// Every load is written to the browser console: what a module registered and the way it was loaded.
test.describe('plugin log', () => {
    const logged = (app: { console: { type: string; text: string }[] }, type: string) =>
        app.console.filter(entry => entry.type === type && entry.text.startsWith('[plugins]')).map(entry => entry.text);

    test('says what the index lists and that a trusted plugin runs in the application', async ({ app }) => {
        await app.open();

        expect(logged(app, 'info')).toEqual([
            `[plugins] the index ./plugins.json lists 1: ${hello}`,
            `[plugins] loaded hello from ${hello} in the application, trusted by the index`,
        ]);
        expect(logged(app, 'warning')).toEqual([]);
    });

    test('says that the page address moved a trusted plugin into a sandbox', async ({ app }) => {
        await app.open('?sandbox=all');

        expect(logged(app, 'info')).toContain(
            `[plugins] loaded hello from ${hello} in a sandbox worker, trusted by the index but ?sandbox=all is set`,
        );
    });

    test('says that the index does not trust a plugin, and names every plugin of a module', async ({ app }) => {
        await app.servePluginIndex([{ module: pair, trusted: false }, { module: hello, trusted: true }]);
        await app.open();

        expect(logged(app, 'info')).toEqual([
            `[plugins] the index ./plugins.json lists 2: ${pair}, ${hello}`,
            `[plugins] loaded pair-first, pair-second from ${pair} in a sandbox worker, not trusted by the index`,
            `[plugins] loaded hello from ${hello} in the application, trusted by the index`,
        ]);
    });

    test('says that a plugin was loaded on request', async ({ app }) => {
        await app.open();

        await app.loadSandboxedPlugin(pair);

        expect(logged(app, 'info')).toContain(
            `[plugins] loaded pair-first, pair-second from ${pair} in a sandbox worker, loaded on request`,
        );
    });

    test('an empty index is logged as listing nothing', async ({ app }) => {
        await app.servePluginIndex([]);
        await app.open();

        expect(logged(app, 'info')).toEqual(['[plugins] the index ./plugins.json lists 0: nothing']);
    });

    test('a failed load is a warning with the way it was tried', async ({ app }) => {
        await app.servePluginIndex([{ module: 'no-such-plugin', trusted: false }, { module: hello, trusted: true }]);
        await app.open();

        const warnings = logged(app, 'warning');
        expect(warnings).toHaveLength(1);
        expect(warnings[0]).toMatch(/^\[plugins\] failed to load no-such-plugin in a sandbox worker, not trusted by the index: Cannot load plugin /);
        expect(logged(app, 'info')).toContain(`[plugins] loaded hello from ${hello} in the application, trusted by the index`);
    });

    test('an index that cannot be read is a warning', async ({ app }) => {
        await app.servePluginIndex('not found', 404);
        await app.open();

        const warnings = logged(app, 'warning');
        expect(warnings).toHaveLength(1);
        // Kotlin wraps the message of a JavaScript error in words of its own (KT-7 in docs/problem-log.md).
        expect(warnings[0]).toMatch(/^\[plugins\] cannot read the index \.\/plugins\.json: .*Cannot fetch \.\/plugins\.json/);
    });
});
