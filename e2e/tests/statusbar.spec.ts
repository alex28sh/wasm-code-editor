import { expect, pluginModule, sampleText, test } from './app';

// The status bar shows the caret as line:column, the size of the document and the plugins.
// Compose draws the status bar on a canvas, so the only way to see what it shows is to look at its pixels.
// The baselines in tests/__screenshots__ pin the exact text for a few documents; the other tests compare
// two pictures taken in the same page, which needs no baseline and holds on any machine.

const addedFunction = '\n\nfun added() = 1';

test.describe('status bar', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
    });

    test('shows the size of the initial document', async ({ app }) => {
        await app.expectStatusBar('initial.png');
    });

    test('counts a function added with the keyboard', async ({ app, page }) => {
        const before = await app.statusBar();
        await app.focus('end');

        await page.keyboard.type(addedFunction);

        await expect.poll(() => app.text()).toBe(sampleText + addedFunction);
        await app.statusBarChanged(before);
        await app.expectStatusBar('function-added.png');
    });

    test('counts a function removed with the keyboard', async ({ app, page }) => {
        const before = await app.statusBar();
        await app.focus('start');

        // The documentation comment, greet() and the empty line after it.
        for (let line = 0; line < 3; line++) await page.keyboard.press('Shift+ArrowDown');
        await page.keyboard.press('Delete');

        await expect.poll(() => app.text()).toBe('fun main() {\n    println(greet("Wasm"))\n}');
        await app.statusBarChanged(before);
        await app.expectStatusBar('function-removed.png');
    });

    test('returns to the same picture when an added function is deleted again', async ({ app, page }) => {
        const untouched = await app.statusBar();
        await app.focus('end');
        const before = await app.statusBarChanged(untouched);
        await page.keyboard.type(addedFunction);
        await app.statusBarChanged(before);

        for (let index = 0; index < addedFunction.length; index++) await page.keyboard.press('Backspace');

        await expect.poll(() => app.text()).toBe(sampleText);
        await app.statusBarMatches(before);
    });

    test('follows every keystroke', async ({ app, page }) => {
        const untouched = await app.statusBar();
        await app.focus('end');
        const initial = await app.statusBarChanged(untouched);

        await page.keyboard.type('a');
        const oneCharacter = await app.statusBarChanged(initial);
        await page.keyboard.press('Enter');
        const oneLine = await app.statusBarChanged(oneCharacter);
        expect(oneLine.equals(initial)).toBe(false);

        await page.keyboard.press('Backspace');
        await app.statusBarMatches(oneCharacter);
        await page.keyboard.press('Backspace');
        await app.statusBarMatches(initial);
    });

    test('follows undo and redo', async ({ app, page }) => {
        const untouched = await app.statusBar();
        await app.focus('end');
        const initial = await app.statusBarChanged(untouched);
        await page.keyboard.type(' // comment');
        const edited = await app.statusBarChanged(initial);

        await page.keyboard.press('Control+z');
        await app.statusBarMatches(initial);

        await page.keyboard.press('Control+Shift+z');
        await app.statusBarMatches(edited);
    });

    test('follows pasting and cutting', async ({ app, page }) => {
        const initial = await app.statusBar();
        await app.focus('end');

        await app.paste(addedFunction);
        await expect.poll(() => app.text()).toBe(sampleText + addedFunction);
        const pasted = await app.statusBarChanged(initial);

        await page.keyboard.press('Control+a');
        await page.keyboard.press('Control+x');
        await expect.poll(() => app.text()).toBe('');
        await app.statusBarChanged(pasted);
        await app.expectStatusBar('empty.png');
    });

    test('shows the same picture for a typed document and for the same document set at once', async ({ app, page }) => {
        const typedText = 'val typed = listOf(1, 2, 3)\nval more = typed.size';
        const initial = await app.statusBar();
        await app.setText('');
        const empty = await app.statusBarChanged(initial);
        await app.focus('end');
        await page.keyboard.type(typedText);
        await expect.poll(() => app.text()).toBe(typedText);
        const typed = await app.statusBarChanged(empty);

        await app.setText('');
        await app.statusBarMatches(empty);
        await app.setText(typedText);
        // The status bar also shows where the caret is.
        await page.keyboard.press('Control+End');

        await app.statusBarMatches(typed);
    });

    test('depends only on the counts, not on the content', async ({ app }) => {
        const initial = await app.statusBar();
        await app.setText('val a = 1\nval b = 2');
        const first = await app.statusBarChanged(initial);

        await app.setText('val x = 7\nval y = 9');
        await expect.poll(() => app.text()).toBe('val x = 7\nval y = 9');
        expect((await app.statusBar()).equals(first)).toBe(true);

        await app.setText('val a = 1\nval b = 22');
        await app.statusBarChanged(first);
    });

    test('counts the lines of a long document', async ({ app }) => {
        const before = await app.statusBar();

        await app.setText(Array.from({ length: 1000 }, (_, index) => `// ${index}`).join('\n'));

        await app.statusBarChanged(before);
        await app.expectStatusBar('thousand-lines.png');
    });

    test('keeps following the document after the window is resized', async ({ app, page }) => {
        await page.setViewportSize({ width: 700, height: 500 });
        await expect.poll(async () => (await app.statusBarClip()).width).toBe(700);
        await expect.poll(async () => (await app.editor.boundingBox())!.width).toBe(700);
        const resized = await app.statusBar();

        await app.setText('one line');

        await app.statusBarChanged(resized);
    });

    test('lists every plugin a module registers', async ({ app }) => {
        const before = await app.statusBar();

        await app.loadSandboxedPlugin(pluginModule.pair);

        await app.statusBarChanged(before);
        await app.expectStatusBar('three-plugins.png');
    });
});

test.describe('plugin failures in the status bar', () => {
    // Wide enough for the longer failure message to fit in one line next to the buttons.
    test.use({ viewport: { width: 1600, height: 800 } });

    test.beforeEach(async ({ app }) => {
        await app.open();
    });

    test('adds a line for each plugin failure and gives it room by shrinking the editor', async ({ app }) => {
        const height = async () => (await app.statusBarClip()).height;
        const withoutFailures = await height();
        const editorBefore = (await app.editor.boundingBox())!.height;

        await app.loadSandboxedPlugin(pluginModule.reaching);
        await expect.poll(height).toBeGreaterThan(withoutFailures);
        const withOneFailure = await height();

        const before = await app.statusBar();
        await app.loadSandboxedPlugin(pluginModule.throwing);
        await expect.poll(height).toBeGreaterThan(withOneFailure);
        const withTwoFailures = await height();

        // Each failure takes one more line of the same height.
        expect(withTwoFailures - withOneFailure).toBe(withOneFailure - withoutFailures);
        expect((await app.editor.boundingBox())!.height).toBe(editorBefore - (withTwoFailures - withoutFailures));
        await app.statusBarChanged(before);
        await app.expectStatusBar('two-failures.png');
    });
});

test.describe('caret position in the status bar', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
    });

    test('follows the caret', async ({ app, page }) => {
        await app.focus('start');
        const atStart = await app.statusBar();

        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('End');

        await expect.poll(async () => (await app.state()).caret).toEqual({ line: 2, column: 50, selectedCharacters: 0, carets: 1 });
        await app.statusBarChanged(atStart);
        await app.expectStatusBar('caret-moved.png');

        await page.keyboard.press('Control+Home');
        await app.statusBarMatches(atStart);
    });

    test('shows how much is selected', async ({ app, page }) => {
        await app.focus('start');
        const atStart = await app.statusBar();

        for (let index = 0; index < 5; index++) await page.keyboard.press('Shift+ArrowRight');

        await expect.poll(async () => (await app.state()).caret).toEqual({ line: 1, column: 6, selectedCharacters: 5, carets: 1 });
        await app.statusBarChanged(atStart);
        await app.expectStatusBar('selection.png');
    });

    test('counts the carets of a rename', async ({ app, page }) => {
        await app.select(sampleText.indexOf('greet'));
        const single = await app.statusBar();

        await page.keyboard.press('Shift+F6');

        await expect.poll(async () => (await app.state()).caret.carets).toBe(2);
        await app.statusBarChanged(single);
        await app.expectStatusBar('carets.png');
    });
});

test.describe('libraries in the status bar', () => {
    test('the button counts the libraries, in red when one failed', async ({ app, page }) => {
        await app.open();
        await app.addLibrary('org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0');
        await app.librariesSettled();
        await page.keyboard.press('Escape');
        await app.expectStatusBar('one-library.png');

        const one = await app.statusBar();
        await app.addLibrary('org.example:plain:1.0');
        await app.librariesSettled();
        await page.keyboard.press('Escape');
        await app.statusBarChanged(one);
        await app.expectStatusBar('failed-library.png');
    });
});

test.describe('status bar without plugins', () => {
    test('says that there are none', async ({ app }) => {
        await app.servePluginIndex([]);
        await app.open();

        await app.expectStatusBar('no-plugins.png');
    });
});

test.describe('status bar on a phone', () => {
    test.use({ viewport: { width: 390, height: 844 } });

    test('wraps a long failure message instead of cutting it off', async ({ app }) => {
        await app.open();
        const oneLine = (await app.statusBarClip()).height;

        await app.loadSandboxedPlugin(pluginModule.reaching);

        // The message does not fit into one line of this width.
        await expect.poll(async () => (await app.statusBarClip()).height).toBeGreaterThan(oneLine * 3);
        const editor = (await app.editor.boundingBox())!;
        expect(editor.height).toBeGreaterThan(844 / 2);
        await app.expectStatusBar('phone-failure.png');
    });
});

test.describe('status bar canvas', () => {
    // By default WebGL lets the browser discard the picture once it is shown, and Compose draws no new frame
    // until something of its own changes. A canvas that the browser presents again in between, as it does while
    // the editor above it changes, was then blank. Reading the canvas back gets what such a presentation gets.
    test('stays in the canvas between the frames Compose draws', async ({ app, page }) => {
        await app.open();
        await page.waitForTimeout(500);

        const inked = await page.evaluate(() => {
            const canvases: HTMLCanvasElement[] = [];
            const collect = (root: Document | ShadowRoot) => root.querySelectorAll('*').forEach(element => {
                if (element instanceof HTMLCanvasElement) canvases.push(element);
                if (element.shadowRoot) collect(element.shadowRoot);
            });
            collect(document);
            const source = canvases[0];
            const copy = document.createElement('canvas');
            copy.width = source.width;
            copy.height = source.height;
            const context = copy.getContext('2d')!;
            context.drawImage(source, 0, 0);
            // The status bar is the bottom strip; its text is the only thing with opaque dark pixels there.
            const strip = context.getImageData(0, copy.height - 40, copy.width, 40).data;
            let count = 0;
            for (let i = 0; i < strip.length; i += 4) if (strip[i + 3] > 128 && strip[i] < 128) count++;
            return count;
        });

        expect(inked).toBeGreaterThan(100);
    });
});
