import { expect, sampleText, test } from './app';

// The document is kept in the browser's local storage, so a page opened later starts with it.

const storageKey = 'wasm-code-editor.document';

test.describe('kept document', () => {
    test('a fresh browser starts with the sample and stores nothing', async ({ app, page }) => {
        await app.open();

        expect(await app.text()).toBe(sampleText);
        expect(await page.evaluate(key => localStorage.getItem(key), storageKey)).toBeNull();
    });

    test('is there again after a reload', async ({ app, page }) => {
        await app.open();
        await app.focus();
        await page.keyboard.type('\n\nfun added() = 1');
        await expect.poll(() => page.evaluate(key => localStorage.getItem(key), storageKey))
            .toBe(sampleText + '\n\nfun added() = 1');

        await page.reload();
        await app.editorShown();

        expect(await app.text()).toBe(sampleText + '\n\nfun added() = 1');
        expect(await app.renderedLines()).toEqual((sampleText + '\n\nfun added() = 1').split('\n'));
        expect(app.pageErrors).toEqual([]);
    });

    test('keeps what was typed just before the page was left', async ({ app, page }) => {
        await app.open();
        await app.focus();

        await page.keyboard.type(' // last words');
        await page.reload();
        await app.editorShown();

        expect(await app.text()).toBe(sampleText + ' // last words');
    });

    test('is what a page opened later starts with', async ({ app, context }) => {
        await app.open();
        await app.setText('val kept = true');
        await expect.poll(() => app.page.evaluate(key => localStorage.getItem(key), storageKey)).toBe('val kept = true');

        const later = await context.newPage();
        await later.goto('/');

        await expect(later.locator('.cm-content .cm-line')).toHaveText(['val kept = true']);
    });

    test('a page that was not edited does not put its old text over a newer one when it is closed', async ({ app, context }) => {
        await app.open();
        const other = await context.newPage();
        await other.goto('/');
        await expect(other.locator('.cm-editor')).toBeVisible({ timeout: 30_000 });
        await app.setText('val newer = true');
        await expect.poll(() => app.page.evaluate(key => localStorage.getItem(key), storageKey)).toBe('val newer = true');

        await other.close({ runBeforeUnload: true });
        await app.page.waitForTimeout(300);

        expect(await app.page.evaluate(key => localStorage.getItem(key), storageKey)).toBe('val newer = true');
    });

    test('stays empty when everything was deleted', async ({ app, page }) => {
        await app.open();
        await app.setText('');
        await expect.poll(() => page.evaluate(key => localStorage.getItem(key), storageKey)).toBe('');

        await page.reload();
        await app.editorShown();

        expect(await app.text()).toBe('');
    });

    test('keeps characters outside ASCII and both kinds of line ends', async ({ app, page }) => {
        const text = 'val greeting = "Grüße, 世界 🌍"\r\nval next = 1';
        await app.open();
        await app.setText(text);
        const shown = await app.text();
        await expect.poll(() => page.evaluate(key => localStorage.getItem(key), storageKey)).toBe(shown);

        await page.reload();
        await app.editorShown();

        expect(await app.text()).toBe(shown);
    });

    test('the editor works when the browser gives no storage', async ({ app, page }) => {
        await page.addInitScript(() => {
            Object.defineProperty(globalThis, 'localStorage', { get() { throw new DOMException('denied', 'SecurityError'); } });
        });
        await app.open();
        await app.focus();

        await page.keyboard.type(' // typed');
        await page.waitForTimeout(600);

        expect(await app.text()).toBe(sampleText + ' // typed');
        expect(app.pageErrors).toEqual([]);
    });

    test('the editor works when the storage is full', async ({ app, page }) => {
        await page.addInitScript(() => {
            Storage.prototype.setItem = () => { throw new DOMException('full', 'QuotaExceededError'); };
        });
        await app.open();
        await app.focus();

        await page.keyboard.type(' // typed');
        await page.waitForTimeout(600);

        expect(await app.text()).toBe(sampleText + ' // typed');
        expect(app.pageErrors).toEqual([]);
    });
});
