import { expect, sampleText, test } from './app';

test.describe('editor', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
    });

    test('shows the initial document', async ({ app }) => {
        const lines = await app.renderedLines();

        expect(lines).toEqual(sampleText.split('\n'));
        expect(await app.text()).toBe(sampleText);
    });

    test('typing reaches the Kotlin state', async ({ app, page }) => {
        await app.content.click();
        await page.keyboard.press('Control+End');
        await page.keyboard.type('\n// typed in the browser');

        await expect.poll(async () => (await app.state()).text).toMatch(/\}\n\/\/ typed in the browser$/);
        expect(await app.renderedLines()).toHaveLength(7);
    });

    test('undo restores the document', async ({ app, page }) => {
        const original = (await app.state()).text;
        await app.content.click();
        await page.keyboard.press('Control+End');
        await page.keyboard.type(' // comment');
        await expect.poll(async () => (await app.state()).text).not.toBe(original);

        await page.keyboard.press('Control+z');

        await expect.poll(async () => (await app.state()).text).toBe(original);
    });

    test('a document set from Kotlin is rendered', async ({ app }) => {
        await app.setText('val answer = 42\nval question = "?"');

        await expect(app.content.locator('.cm-line')).toHaveText(['val answer = 42', 'val question = "?"']);
        expect((await app.state()).text).toBe('val answer = 42\nval question = "?"');
    });

    test('the status bar drawn by Compose follows the document', async ({ app, page }) => {
        const clip = await app.statusBarClip();
        const before = await page.screenshot({ clip });

        await app.setText('one line');

        await expect.poll(async () => (await page.screenshot({ clip })).equals(before)).toBe(false);
    });

    test('numbers every line', async ({ app }) => {
        expect(await app.lineNumbers()).toEqual(['1', '2', '3', '4', '5', '6']);

        await app.setText('one\ntwo\nthree');

        await expect.poll(() => app.lineNumbers()).toEqual(['1', '2', '3']);
    });

    test('renders only the visible part of a long document', async ({ app }) => {
        const lines = Array.from({ length: 5000 }, (_, index) => `val line${index} = ${index}`);

        await app.setText(lines.join('\n'));

        await expect(app.content.locator('.cm-line').first()).toHaveText('val line0 = 0');
        const rendered = await app.renderedLines();
        expect(rendered.length).toBeLessThan(500);
        expect(rendered).toEqual(lines.slice(0, rendered.length));
        // The state still holds all of it.
        expect((await app.text()).split('\n')).toHaveLength(5000);
    });

    test('an empty document keeps one empty line', async ({ app }) => {
        await app.setText('');

        await expect(app.content.locator('.cm-line')).toHaveText(['']);
        expect(await app.text()).toBe('');
        expect(await app.lineNumbers()).toEqual(['1']);
    });

    test('line endings are normalized', async ({ app }) => {
        await app.setText('windows\r\nold mac\runix\n');

        await expect.poll(() => app.text()).toBe('windows\nold mac\nunix\n');
        expect(await app.lineNumbers()).toEqual(['1', '2', '3', '4']);
    });

    test('setting the same document again changes nothing', async ({ app }) => {
        await app.setText(sampleText);

        expect(await app.text()).toBe(sampleText);
        expect(await app.renderedLines()).toEqual(sampleText.split('\n'));
    });

    test('a document set from Kotlin replaces a typed one', async ({ app, page }) => {
        await app.focus('end');
        await page.keyboard.type(' // typed');
        await expect.poll(() => app.text()).toBe(sampleText + ' // typed');

        await app.setText('val replaced = true');

        await expect(app.content.locator('.cm-line')).toHaveText(['val replaced = true']);
        await page.keyboard.type('!');
        await expect.poll(() => app.text()).toContain('!');
        expect((await app.text()).replace('!', '')).toBe('val replaced = true');
    });
});
