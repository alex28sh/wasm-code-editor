import { expect, sampleText, shapesText, test } from './app';

// What the editor paints comes from the Kotlin analysis running in Wasm; the classes on the rendered pieces
// tell what it takes each of them for, and the theme gives every class its IntelliJ color.

// Tall enough to render the whole sample: CodeMirror draws only the lines in view.
test.use({ viewport: { width: 1280, height: 1200 } });

test.describe('highlighting', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
    });

    test('paints the initial document', async ({ app }) => {
        expect(await app.tokenClasses('fun')).toEqual(['kt-keyword']);
        expect(await app.tokenClasses('greet')).toEqual(['kt-function-declaration']);
        expect(await app.tokenClasses('name')).toEqual(['kt-doc-link']);
        expect(await app.tokenClasses('name', 2)).toEqual(['kt-parameter']);
        expect(await app.tokenClasses('"Hello, ')).toEqual(['kt-string']);
        expect(await app.tokenClasses('$')).toEqual(['kt-template']);
        expect(await app.tokenClasses('name', 3)).toEqual(['kt-parameter']);
        expect(await app.tokenClasses('greet', 2)).toEqual(['kt-top-level-call']);
        expect(await app.tokenClasses('println')).toEqual(['kt-function-call']);
    });

    test('tells declarations and uses apart by their kind', async ({ app }) => {
        await app.setText(shapesText);

        expect(await app.tokenClasses('package')).toEqual(['kt-keyword']);
        expect(await app.tokenClasses('import')).toEqual(['kt-keyword']);
        expect(await app.tokenClasses('@param')).toEqual(['kt-doc-tag']);
        expect(await app.tokenClasses('abstract')).toEqual(['kt-keyword']);
        expect(await app.tokenClasses('Shape')).toEqual(['kt-class']);
        expect(await app.tokenClasses('name', 3)).toEqual(['kt-property']);
        expect(await app.tokenClasses('area')).toEqual(['kt-function-declaration']);
        expect(await app.tokenClasses('\\n')).toEqual(['kt-escape']);
        expect(await app.tokenClasses('data')).toEqual(['kt-keyword']);
        expect(await app.tokenClasses('result')).toEqual(['kt-local', 'kt-mutable']);
        expect(await app.tokenClasses('radius', 2)).toEqual(['kt-property']);
        expect(await app.tokenClasses('companion')).toEqual(['kt-keyword']);
        expect(await app.tokenClasses('const')).toEqual(['kt-keyword']);
        expect(await app.tokenClasses('3.14159')).toEqual(['kt-number']);
        expect(await app.tokenClasses('METER')).toEqual(['kt-instance']);
        expect(await app.tokenClasses('1_000')).toEqual(['kt-number']);
        expect(await app.tokenClasses('@Suppress')).toEqual(['kt-annotation']);
        expect(await app.tokenClasses('T')).toEqual(['kt-type-parameter']);
        expect(await app.tokenClasses('0xFF')).toEqual(['kt-number']);
        expect(await app.tokenClasses('// The biggest one wins.')).toEqual(['kt-comment']);
        expect(await app.tokenClasses('loop@')).toEqual(['kt-label']);
        expect(await app.tokenClasses('@loop')).toEqual(['kt-label']);
        expect(await app.tokenClasses('it')).toEqual(['kt-it']);
        expect(await app.tokenClasses('radius', 4)).toEqual(['kt-named-argument']);
        expect(await app.tokenClasses('largest', 2)).toEqual(['kt-top-level-call']);
    });

    test('gives the kinds the colors of IntelliJ Light', async ({ app }) => {
        await app.setText(shapesText);
        const style = (text: string, occurrence = 1) => app.content.locator('span', { hasText: new RegExp(`^${text}$`) }).nth(occurrence - 1)
            .evaluate(element => {
                const computed = getComputedStyle(element);
                return `${computed.color} ${computed.fontStyle} ${computed.textDecorationLine}`;
            });

        expect(await style('abstract')).toBe('rgb(0, 51, 179) normal none');
        expect(await style('"circle"')).toBe('rgb(6, 125, 23) normal none');
        expect(await style('3.14159')).toBe('rgb(23, 80, 235) normal none');
        expect(await style('// The biggest one wins.')).toBe('rgb(140, 140, 140) italic none');
        expect(await style('area')).toBe('rgb(0, 98, 122) normal none');
        expect(await style('radius', 2)).toBe('rgb(135, 16, 148) normal none');
        expect(await style('result')).toBe('rgb(8, 8, 8) normal underline');
        expect(await style('METER')).toBe('rgb(135, 16, 148) italic none');
        expect(await style('@Suppress')).toBe('rgb(158, 136, 13) normal none');
        expect(await style('T')).toBe('rgb(0, 126, 138) normal none');
        expect(await style('largest', 2)).toBe('rgb(8, 8, 8) italic none');
    });

    test('follows the text as it is typed', async ({ app, page }) => {
        await app.setText('');
        await app.focus('end');

        await page.keyboard.type('val typed = "text" // note');

        expect(await app.tokenClasses('val')).toEqual(['kt-keyword']);
        expect(await app.tokenClasses('typed')).toEqual(['kt-property']);
        expect(await app.tokenClasses('"text"')).toEqual(['kt-string']);
        expect(await app.tokenClasses('// note')).toEqual(['kt-comment']);
    });

    test('repaints a use when its declaration changes', async ({ app, page }) => {
        await app.setText('val count = 1\nfun use() = count');
        await expect.poll(() => app.tokenClasses('count', 2)).toEqual(['kt-property']);

        // `val` becomes `var`.
        await app.select(2);
        await page.keyboard.press('Delete');
        await page.keyboard.type('r');

        await expect.poll(() => app.tokenClasses('count', 2)).toEqual(['kt-mutable', 'kt-property']);
    });

    test('paints a string left open only up to the end of its line', async ({ app, page }) => {
        await app.setText('\nval next = 1');
        await app.focus('start');

        await page.keyboard.type('val open = ');
        await page.keyboard.insertText('"unfinished');

        await expect.poll(() => app.tokenClasses('"unfinished')).toEqual(['kt-string']);
        expect(await app.tokenClasses('val', 2)).toEqual(['kt-keyword']);
        expect(await app.tokenClasses('next')).toEqual(['kt-property']);
    });

    test('paints what scrolls into view in a long document', async ({ app }) => {
        const lines = Array.from({ length: 3000 }, (_, index) => `val line${index} = ${index}`);
        await app.setText(lines.join('\n'));

        await app.scroller.evaluate(element => element.scrollTo(0, element.scrollHeight));

        await expect(app.content.locator('.cm-line').last()).toHaveText('val line2999 = 2999');
        await expect.poll(() => app.tokenClasses('line2999')).toEqual(['kt-property']);
        expect(await app.tokenClasses('2999')).toEqual(['kt-number']);
    });

    test('marks the declaration and the uses of the name under the caret', async ({ app, page }) => {
        expect(await app.markedUsages()).toEqual([]);

        await app.caretAt('name', 2);
        await expect.poll(() => app.markedUsages()).toEqual(['name', 'name', 'name']);

        await app.caretAt('greet', 2);
        await expect.poll(() => app.markedUsages()).toEqual(['greet', 'greet']);

        // Nothing is known about a name the file does not declare.
        await app.caretAt('println');
        await expect.poll(() => app.markedUsages()).toEqual([]);
        expect(await app.text()).toBe(sampleText);
        await page.keyboard.press('Control+End');
        await expect.poll(() => app.markedUsages()).toEqual([]);
    });

    test('marks writes differently from reads', async ({ app }) => {
        await app.setText(shapesText);

        await app.caretAt('result');

        await expect.poll(() => app.markedUsages()).toEqual(['result', 'result', 'result']);
        await expect(app.content.locator('.kt-usage-write')).toHaveText(['result', 'result']);
    });
});

test.describe('folding', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
        await app.setText(shapesText);
    });

    const foldLine = async (app: { page: import('@playwright/test').Page }, line: number) => {
        const lineNumber = app.page.locator('.cm-lineNumbers .cm-gutterElement', { hasText: new RegExp(`^${line}$`) });
        const box = (await lineNumber.boundingBox())!;
        const marker = app.page.locator('.cm-foldGutter .cm-gutterElement span[title="Fold line"]');
        for (const candidate of await marker.all()) {
            const candidateBox = (await candidate.boundingBox())!;
            if (Math.abs(candidateBox.y + candidateBox.height / 2 - (box.y + box.height / 2)) < box.height / 2) return candidate.click();
        }
        throw new Error(`Line ${line} cannot be folded`);
    };

    test('a class folds into its header', async ({ app }) => {
        await foldLine(app, 9);

        await expect(app.content.locator('.cm-line', { hasText: 'abstract class Shape' })).toHaveText('abstract class Shape(val name: String) {…}');
        await expect(app.content.locator('.cm-line', { hasText: 'abstract fun area' })).toHaveCount(0);
        // Folding hides text but does not change it.
        expect(await app.text()).toBe(shapesText);
    });

    test('a documentation comment and the imports fold', async ({ app }) => {
        await foldLine(app, 5);

        await expect(app.content.locator('.cm-line', { hasText: '/**' })).toHaveText('/**…*/');
    });

    test('clicking the placeholder unfolds', async ({ app, page }) => {
        await foldLine(app, 16);
        await expect(app.content.locator('.cm-line', { hasText: 'var result' })).toHaveCount(0);

        await page.locator('.cm-foldPlaceholder').click();

        await expect(app.content.locator('.cm-line', { hasText: 'var result' })).toHaveCount(1);
    });

    test('lines without a block cannot be folded', async ({ app, page }) => {
        const markers = await page.locator('.cm-foldGutter .cm-gutterElement span[title="Fold line"]').count();

        // The doc comment, Shape, Circle, area, the companion object, largest, the loop and main.
        expect(markers).toBe(8);
    });
});
