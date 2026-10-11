import { expect, pluginModule, test } from './app';

const viewports = [
    { name: 'desktop', width: 1280, height: 800 },
    { name: 'small window', width: 800, height: 500 },
    { name: 'phone', width: 390, height: 844 },
];

const longDocument = Array.from({ length: 400 }, (_, index) => `val line${index} = ${index}`).join('\n');

for (const viewport of viewports) {
    test.describe(`layout on ${viewport.name}`, () => {
        test.use({ viewport: { width: viewport.width, height: viewport.height } });

        test.beforeEach(async ({ app }) => {
            await app.open();
        });

        test('the editor fills the window above the status bar', async ({ app }) => {
            const editor = (await app.editor.boundingBox())!;
            const statusBar = await app.statusBarClip();

            expect(editor.x).toBe(0);
            expect(editor.y).toBe(0);
            expect(editor.width).toBe(viewport.width);
            expect(statusBar.height).toBeGreaterThan(15);
            expect(statusBar.height).toBeLessThan(60);
        });

        test('the Compose canvas covers the window', async ({ app }) => {
            const canvas = (await app.canvas.boundingBox())!;

            expect(canvas).toEqual({ x: 0, y: 0, width: viewport.width, height: viewport.height });
        });

        test('the page itself never scrolls', async ({ app, page }) => {
            await app.setText(longDocument);

            const overflow = await page.evaluate(() => ({
                horizontal: document.documentElement.scrollWidth - window.innerWidth,
                vertical: document.documentElement.scrollHeight - window.innerHeight,
            }));
            expect(overflow).toEqual({ horizontal: 0, vertical: 0 });
        });

        test('a long document scrolls inside the editor and stays above the status bar', async ({ app }) => {
            await app.setText(longDocument);
            const scrollable = await app.scroller.evaluate(element => element.scrollHeight > element.clientHeight);
            expect(scrollable).toBe(true);

            await app.scroller.evaluate(element => element.scrollTo(0, element.scrollHeight));

            const lastLine = app.content.locator('.cm-line').last();
            await expect(lastLine).toHaveText('val line399 = 399');
            const line = (await lastLine.boundingBox())!;
            const editor = (await app.editor.boundingBox())!;
            expect(line.y + line.height).toBeLessThanOrEqual(editor.y + editor.height);
        });

        test('line numbers stay at the left edge', async ({ app }) => {
            const gutters = (await app.gutters.boundingBox())!;

            expect(gutters.x).toBe(0);
            expect(gutters.width).toBeGreaterThan(10);
            expect(gutters.width).toBeLessThan(viewport.width / 4);
        });
    });
}

test('the editor follows window resizing', async ({ app, page }) => {
    await app.open();

    await page.setViewportSize({ width: 640, height: 480 });

    await expect.poll(async () => (await app.editor.boundingBox())!.width).toBe(640);
    const statusBar = await app.statusBarClip();
    expect(statusBar.height).toBeGreaterThan(15);
    expect(statusBar.height).toBeLessThan(60);
    expect((await app.canvas.boundingBox())!).toEqual({ x: 0, y: 0, width: 640, height: 480 });
});

test('the window can grow again after it was shrunk', async ({ app, page }) => {
    await app.open();
    await page.setViewportSize({ width: 500, height: 400 });
    await expect.poll(async () => (await app.editor.boundingBox())!.width).toBe(500);

    await page.setViewportSize({ width: 1600, height: 1000 });

    await expect.poll(async () => (await app.editor.boundingBox())!.width).toBe(1600);
    await expect.poll(async () => (await app.canvas.boundingBox())!).toEqual({ x: 0, y: 0, width: 1600, height: 1000 });
    const statusBar = await app.statusBarClip();
    expect(statusBar.height).toBeGreaterThan(15);
    expect(statusBar.height).toBeLessThan(60);
});

test('a long line scrolls inside the editor and leaves the line numbers in place', async ({ app, page }) => {
    await app.open();
    await app.setText('val long = "' + 'x'.repeat(3000) + '"\nval short = 1');
    const overflow = await app.scroller.evaluate(element => element.scrollWidth - element.clientWidth);
    expect(overflow).toBeGreaterThan(1000);

    await app.scroller.evaluate(element => element.scrollTo(element.scrollWidth, 0));

    expect((await app.gutters.boundingBox())!.x).toBe(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBe(0);
    // The start of the short line is scrolled out of view behind the gutter.
    const short = (await app.content.locator('.cm-line').nth(1).boundingBox())!;
    expect(short.x).toBeLessThan(0);
});

test('the mouse wheel scrolls the document, not the page', async ({ app, page }) => {
    await app.open();
    await app.setText(longDocument);
    await app.content.hover();

    await page.mouse.wheel(0, 2000);

    await expect.poll(() => app.scroller.evaluate(element => element.scrollTop)).toBeGreaterThan(1000);
    expect(await page.evaluate(() => window.scrollY)).toBe(0);
});

test('a plugin failure takes its room from the editor, not from the window', async ({ app, page }) => {
    await app.open();
    const before = (await app.editor.boundingBox())!;

    await app.loadSandboxedPlugin(pluginModule.reaching);

    await expect.poll(async () => (await app.editor.boundingBox())!.height).toBeLessThan(before.height);
    const editor = (await app.editor.boundingBox())!;
    expect(editor.y).toBe(0);
    expect(before.height - editor.height).toBeLessThan(40);
    expect(await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight)).toBe(0);
});

test.describe('on a high-density display', () => {
    test.use({ deviceScaleFactor: 2 });

    test('the canvas is drawn at the resolution of the device', async ({ app }) => {
        await app.open();

        const canvas = await app.canvas.evaluate((element: HTMLCanvasElement) => ({ width: element.width, height: element.height }));

        expect(canvas).toEqual({ width: 2560, height: 1600 });
        expect((await app.canvas.boundingBox())!).toEqual({ x: 0, y: 0, width: 1280, height: 800 });
        const statusBar = await app.statusBarClip();
        expect(statusBar.height).toBeGreaterThan(15);
        expect(statusBar.height).toBeLessThan(60);
    });
});

test.describe('in a very small window', () => {
    test.use({ viewport: { width: 320, height: 240 } });

    test('the editor and the status bar both stay visible', async ({ app }) => {
        await app.open();

        const editor = (await app.editor.boundingBox())!;
        const statusBar = await app.statusBarClip();

        expect(editor.height).toBeGreaterThan(100);
        expect(statusBar.height).toBeGreaterThan(15);
        expect(editor.height + statusBar.height).toBe(240);
        await expect(app.content.locator('.cm-line').first()).toBeVisible();
    });
});
