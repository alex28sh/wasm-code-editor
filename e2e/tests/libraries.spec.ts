import { expect, sampleText, test } from './app';

// The libraries panel: the user lists Maven coordinates, and the page downloads the wasm-js klibs of those libraries
// and of what they depend on through the `/maven/` proxy of serve.mjs. In these tests the proxy reads the
// repository the build assembles (e2eMaven), which has coroutines and atomicfu, a JVM library, a library that is
// not Kotlin Multiplatform, and the version list of coroutines.

const coroutines = 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0';
const coroutinesKlibs = ['kotlinx-coroutines-core-wasm-js-1.11.0', 'atomicfu-wasm-js-0.32.1'];
const storageKey = 'wasm-code-editor.libraries';

const program = `import kotlinx.coroutines.*

suspend fun main() = coroutineScope {
    val squares = (1..3).map { n -> async { delay(10L * (4 - n)); n * n } }
    println("Squares: " + squares.awaitAll())
    launch {
        delay(300)
        println("Launched")
    }
    println("Waiting")
}`;

test.describe('libraries panel', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
    });

    test('opens from the status bar and lists nothing at first', async ({ app }) => {
        expect((await app.state()).libraries).toEqual([]);

        await app.clickLibrariesButton();

        await expect(app.librariesPanel).toBeVisible();
        await expect(app.librariesPanel.getByRole('heading')).toHaveText('Libraries');
        await expect(app.librariesPanel.getByRole('list', { name: 'Libraries' })).toContainText('the standard library only');
        await expect(app.librariesPanel.getByLabel('Maven coordinates')).toBeFocused();
    });

    test('opens with Ctrl+Alt+Shift+S from the editor', async ({ app, page }) => {
        await app.focus();

        await page.keyboard.press('Control+Alt+Shift+S');

        await expect(app.librariesPanel).toBeVisible();
        // The shortcut types nothing.
        expect(await app.text()).toBe(sampleText);
    });

    test('loads a library with its dependencies', async ({ app }) => {
        await app.addLibrary(coroutines);

        await expect(app.librariesPanel.getByLabel('Maven coordinates')).toHaveValue('');
        expect(await app.librariesSettled()).toEqual([{ coordinates: coroutines, state: 'ready', klibs: coroutinesKlibs }]);
        await expect(app.librariesPanel.locator('.libraries-status')).toHaveText([`Loaded: ${coroutinesKlibs.join(', ')}`]);
        expect(app.pageErrors).toEqual([]);
    });

    test('takes the latest release when no version is given', async ({ app }) => {
        await app.addLibrary('org.jetbrains.kotlinx:kotlinx-coroutines-core');

        expect(await app.librariesSettled()).toEqual([{ coordinates: 'org.jetbrains.kotlinx:kotlinx-coroutines-core', state: 'ready', klibs: coroutinesKlibs }]);
    });

    test('says why a library cannot be loaded', async ({ app }) => {
        await app.addLibrary('org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0');
        await app.addLibrary('org.example:plain:1.0');
        await app.addLibrary('org.example:missing:1.0');

        expect((await app.librariesSettled()).map(library => [library.state, library.message])).toEqual([
            ['failed', 'org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0 is not built for Kotlin/Wasm in the browser (wasm-js); it is built for jvm'],
            ['failed', 'org.example:plain:1.0 has no Gradle module metadata, so it is not a Kotlin Multiplatform library'],
            ['failed', 'org.example:missing:1.0 is not in the repository'],
        ]);
        await expect(app.librariesPanel.locator('[data-state=failed]')).toHaveCount(3);
    });

    test('refuses text that is not coordinates, and a library listed twice', async ({ app }) => {
        const error = app.librariesPanel.getByRole('alert');

        await app.addLibrary('');
        await expect(error).toHaveText('Write the Maven coordinates of a library: group:artifact:version');
        await app.addLibrary('kotlinx-coroutines');
        await expect(error).toHaveText("'kotlinx-coroutines' is not group:artifact:version");
        // What was refused stays in the field, to be corrected.
        await expect(app.librariesPanel.getByLabel('Maven coordinates')).toHaveValue('kotlinx-coroutines');

        await app.addLibrary(coroutines);
        await expect(error).toHaveText('');
        await app.addLibrary('org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2');
        await expect(error).toHaveText('org.jetbrains.kotlinx:kotlinx-coroutines-core is in the list already');

        expect((await app.state()).libraries.map(library => library.coordinates)).toEqual([coroutines]);
    });

    test('removes a library', async ({ app }) => {
        await app.addLibrary(coroutines);
        await app.addLibrary('org.example:plain:1.0');
        await app.librariesSettled();

        await app.librariesPanel.getByRole('button', { name: 'Remove org.example:plain:1.0' }).click();

        expect(await app.librariesSettled()).toEqual([{ coordinates: coroutines, state: 'ready', klibs: coroutinesKlibs }]);
        await expect(app.librariesPanel.getByRole('listitem')).toHaveCount(1);
    });

    test('Escape, Close and a click beside it close the panel and give the keyboard back to the editor', async ({ app, page }) => {
        await app.focus();
        for (const close of [
            () => page.keyboard.press('Escape'),
            () => app.librariesPanel.getByRole('button', { name: 'Close' }).click(),
            () => page.mouse.click(5, 5),
        ]) {
            await page.keyboard.press('Control+Alt+Shift+S');
            await expect(app.librariesPanel).toBeVisible();

            await close();

            await expect(app.librariesPanel).toHaveCount(0);
            await expect(app.content).toBeFocused();
        }
    });

    test('what is typed in the panel does not reach the editor', async ({ app, page }) => {
        await app.clickLibrariesButton();

        await page.keyboard.type('org.example:typed:1.0');
        await page.keyboard.press('Control+z');

        expect(await app.text()).toBe(sampleText);
        await expect(app.librariesPanel.getByLabel('Maven coordinates')).toBeFocused();
    });

    test('the list is kept in the browser and loaded again with the page', async ({ app, page }) => {
        await app.addLibrary(coroutines);
        await app.librariesSettled();
        expect(JSON.parse((await page.evaluate(key => localStorage.getItem(key), storageKey))!)).toEqual([coroutines]);

        await page.reload();
        await app.editorShown();

        expect(await app.librariesSettled()).toEqual([{ coordinates: coroutines, state: 'ready', klibs: coroutinesKlibs }]);
    });

    test('a page that never lists a library downloads nothing for libraries', async ({ app }) => {
        await app.clickLibrariesButton();
        await expect(app.librariesPanel).toBeVisible();

        expect(app.requestedUrls.filter(url => url.startsWith('/maven/'))).toEqual([]);
    });
});

test.describe('libraries panel on a phone', () => {
    test.use({ viewport: { width: 390, height: 844 } });

    test('fits the window', async ({ app }) => {
        await app.open();
        await app.addLibrary('org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0');
        await app.librariesSettled();

        const dialog = (await app.librariesPanel.boundingBox())!;
        expect(dialog.x).toBeGreaterThanOrEqual(0);
        expect(dialog.x + dialog.width).toBeLessThanOrEqual(390);
        expect(await app.page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBe(0);
    });
});

test.describe('compiling with libraries', () => {
    // Each page downloads and compiles the compiler itself, which is tens of megabytes.
    test.describe.configure({ mode: 'serial', timeout: 240_000 });

    test.beforeEach(async ({ app, request }) => {
        test.skip(!(await request.head('/kotlin-compiler-worker.js')).ok(), 'the distribution has no compiler');
        await app.open();
        await app.setText(program);
    });

    test('a program that uses a library listed in the panel runs, coroutines and all', async ({ app }) => {
        await app.addLibrary(coroutines);

        const state = await app.run();

        // "Launched" comes 300 ms after main has returned: the run lasts until nothing is scheduled any more.
        expect(state.console).toEqual({ title: 'Finished', lines: ['Squares: [1, 4, 9]', 'Waiting', 'Launched'], errors: [] });
        expect(state.diagnostics).toEqual([]);
        expect(app.pageErrors).toEqual([]);
    });

    test('without the library the imports are unresolved, and adding it resolves them', async ({ app }) => {
        const first = await app.run();
        expect(first.console?.title).toBe('Compilation failed');
        expect(first.console?.errors[0]).toBe("1:8: Unresolved reference 'kotlinx'.");

        await app.addLibrary(coroutines);
        await app.librariesSettled();

        // The analysis is told about the library, and the marks go away without another run.
        await expect.poll(async () => (await app.state()).diagnostics, { timeout: 60_000 }).toEqual([]);
        expect((await app.run()).console?.title).toBe('Finished');
    });

    test('removing the library makes its names unresolved again', async ({ app }) => {
        await app.addLibrary(coroutines);
        expect((await app.run()).console?.title).toBe('Finished');

        await app.librariesPanel.getByRole('button', { name: `Remove ${coroutines}` }).click();
        await app.librariesSettled();

        const state = await app.run();
        expect(state.console?.title).toBe('Compilation failed');
        expect(state.console?.errors[0]).toBe("1:8: Unresolved reference 'kotlinx'.");
    });

    test('a library that failed stops the run with its reason', async ({ app }) => {
        await app.addLibrary('org.example:plain:1.0');
        await app.librariesSettled();

        const state = await app.run();

        expect(state.console).toEqual({
            title: 'Libraries failed',
            lines: [],
            errors: ['org.example:plain:1.0: org.example:plain:1.0 has no Gradle module metadata, so it is not a Kotlin Multiplatform library'],
        });
    });

    test('completion offers what the library declares', async ({ app, page }) => {
        await app.addLibrary(coroutines);
        await app.librariesSettled();
        await page.keyboard.press('Escape');
        await app.run();
        await app.caretAt('println("Waiting")', 1, 0);

        await page.keyboard.type('withTimeo');

        await expect(page.locator('.cm-tooltip-autocomplete li', { hasText: 'withTimeout' }).first()).toBeVisible({ timeout: 60_000 });
    });

    test('a program that schedules nothing finishes as soon as main returns', async ({ app }) => {
        await app.setText('fun main() {\n    println("at once")\n}');
        const started = Date.now();

        const state = await app.run();

        expect(state.console).toEqual({ title: 'Finished', lines: ['at once'], errors: [] });
        expect((await app.run()).console?.title).toBe('Finished');
        expect(Date.now() - started).toBeLessThan(60_000);
    });
});
