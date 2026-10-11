import { expect, pluginModule, test } from './app';

// What the build puts into the distribution and how it is served, checked without a browser.

test.describe('distribution', () => {
    test('index.html carries the generated import map', async ({ request }) => {
        const response = await request.get('/');
        const html = await response.text();

        expect(response.headers()['content-type']).toBe('text/html; charset=utf-8');
        expect(html).toBe(await (await request.get('/index.html')).text());
        expect(html).not.toContain('<!-- importmap -->');
        expect(html.match(/<script type="importmap">/g)).toHaveLength(1);
        // The import map has to come before the first module script to take effect.
        expect(html.indexOf('<script type="importmap">')).toBeLessThan(html.indexOf('<script type="module"'));
    });

    test('every import map entry points to a served JavaScript file', async ({ request }) => {
        const html = await (await request.get('/')).text();
        const imports: Record<string, string> = JSON.parse(html.match(/<script type="importmap">([\s\S]*?)<\/script>/)![1]).imports;

        expect(Object.keys(imports)).toContain('@codemirror/view');
        expect(Object.keys(imports)).toContain('codemirror');
        for (const [specifier, target] of Object.entries(imports)) {
            expect(target, specifier).toMatch(/^\.\/vendor\//);
            const response = await request.get(target.slice(1));
            expect(response.status(), `${specifier} -> ${target}`).toBe(200);
            expect(response.headers()['content-type'], target).toBe('text/javascript');
        }
    });

    test('every indexed plugin has its module and its binary', async ({ request }) => {
        const index: { module: string; trusted: boolean }[] = await (await request.get('/plugins.json')).json();

        expect(index).toEqual([{ module: pluginModule.hello, trusted: true }]);
        for (const { module } of index) {
            expect((await request.get(`/${module}.mjs`)).headers()['content-type']).toBe('text/javascript');
            // Streaming compilation refuses any other content type.
            expect((await request.get(`/${module}.wasm`)).headers()['content-type']).toBe('application/wasm');
        }
    });

    test('the test plugins are present but not indexed', async ({ request }) => {
        const index: { module: string }[] = await (await request.get('/plugins.json')).json();
        const indexed = index.map(entry => entry.module);

        for (const module of Object.values(pluginModule).filter(module => module !== pluginModule.hello)) {
            expect(indexed).not.toContain(module);
            expect((await request.get(`/${module}.wasm`)).status(), module).toBe(200);
        }
    });

    test('sandboxed plugins may link only against the standard library and the plugin API', async ({ request }) => {
        const shared = await (await request.get('/shared-modules.json')).json();

        expect(shared).toEqual({
            '<kotlin>': './kotlin-kotlin-stdlib.mjs',
            '<wasm-code-editor:plugin-api>': './wasm-code-editor-plugin-api.mjs',
        });
        for (const file of Object.values<string>(shared)) {
            expect((await request.get(file.slice(1))).status(), file).toBe(200);
        }
    });

    test('the worker entry point is a module of its own', async ({ request }) => {
        expect((await request.get('/wasm-code-editor-plugin-worker.mjs')).status()).toBe(200);
        expect((await request.get('/wasm-code-editor-plugin-worker.wasm')).headers()['content-type']).toBe('application/wasm');
    });
});

test.describe('static server', () => {
    test('never lets the browser cache a build', async ({ request }) => {
        for (const path of ['/', '/plugins.json', '/wasm-code-editor-app.wasm']) {
            expect((await request.get(path)).headers()['cache-control'], path).toBe('no-store');
        }
    });

    test('answers 404 for a file that does not exist', async ({ request }) => {
        const response = await request.get('/no-such-file.wasm');

        expect(response.status()).toBe(404);
    });

    test('serves nothing from outside the distribution', async ({ request }) => {
        // Both files exist: one next to the distribution directory, one in the project root.
        for (const path of ['/..%2FdevDist%2Findex.html', '/..%2F..%2Fsettings.gradle.kts']) {
            const response = await request.get(path);

            expect([403, 404], path).toContain(response.status());
        }
    });
});
