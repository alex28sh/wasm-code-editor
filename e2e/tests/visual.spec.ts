import { expect, shapesText, test } from './app';

// Baselines live in tests/__screenshots__ and depend on the machine's font rendering:
// regenerate them with `npm run update-snapshots` after an intended visual change.

test.describe('appearance', () => {
    test('desktop', async ({ app, page }) => {
        await app.open();

        await expect(page).toHaveScreenshot('desktop.png');
    });

    test.describe('phone', () => {
        test.use({ viewport: { width: 390, height: 844 } });

        test('phone', async ({ app, page }) => {
            await app.open();

            await expect(page).toHaveScreenshot('phone.png');
        });
    });

    test('sandboxed plugin in the status bar', async ({ app, page }) => {
        await app.open('?sandbox=all');

        await expect(page).toHaveScreenshot('sandboxed.png');
    });

    test('plugin failure in the status bar', async ({ app, page }) => {
        await app.open();
        await app.loadSandboxedPlugin('wasm-code-editor-test-plugins-reaching');

        await expect(page).toHaveScreenshot('plugin-failure.png');
    });

    test('long document scrolled to the end', async ({ app, page }) => {
        await app.open();
        await app.setText(Array.from({ length: 400 }, (_, index) => `val line${index} = ${index}`).join('\n'));
        await app.scroller.evaluate(element => element.scrollTo(0, element.scrollHeight));
        await expect(app.content.locator('.cm-line').last()).toHaveText('val line399 = 399');

        await expect(page).toHaveScreenshot('long-document.png');
    });

    test('a document with every kind of declaration, with the usages of a variable marked', async ({ app, page }) => {
        await app.open();
        await app.setText(shapesText);
        await app.caretAt('result');
        await expect(app.content.locator('.kt-usage')).toHaveCount(3);

        await expect(page).toHaveScreenshot('kotlin.png');
    });

    test('file structure', async ({ app, page }) => {
        await app.open();
        await app.setText(shapesText);
        await app.caretAt('var result');
        await page.keyboard.press('Control+F12');
        await expect(app.popup).toBeVisible();

        await expect(page).toHaveScreenshot('structure.png');
    });

    test('a folded class and completion', async ({ app, page }) => {
        await app.open();
        await app.setText(shapesText);
        await page.locator('.cm-foldGutter .cm-gutterElement span[title="Fold line"]').nth(1).click();
        await app.focus('end');
        await page.keyboard.type('\nval other = la');
        await expect(page.locator('.cm-tooltip-autocomplete')).toBeVisible();

        await expect(page).toHaveScreenshot('folded-completion.png');
    });
});
