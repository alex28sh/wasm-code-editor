import type { Page } from '@playwright/test';
import { expect, sampleText, test } from './app';

// Edits made the way a user makes them: with keys, the mouse, the clipboard and the search panel.
// Each test checks the Kotlin state, which is what the rest of the application sees.

test.describe('editing', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
    });

    test('a function typed at the end is added to the document', async ({ app, page }) => {
        await app.focus('end');

        // The closing parenthesis CodeMirror inserts by itself is typed over.
        await page.keyboard.type('\n\nfun added() = 1');

        await expect.poll(() => app.text()).toBe(sampleText + '\n\nfun added() = 1');
        expect(await app.renderedLines()).toEqual((sampleText + '\n\nfun added() = 1').split('\n'));
        expect(await app.lineNumbers()).toEqual(['1', '2', '3', '4', '5', '6', '7', '8']);
    });

    test('a function typed at the start moves the rest down', async ({ app, page }) => {
        await app.focus('start');

        await page.keyboard.type('fun first() = 0');
        await page.keyboard.press('Enter');

        await expect.poll(() => app.text()).toBe('fun first() = 0\n' + sampleText);
    });

    test('a selected function is removed with Delete', async ({ app, page }) => {
        await app.focus('start');

        for (let line = 0; line < 3; line++) await page.keyboard.press('Shift+ArrowDown');
        await page.keyboard.press('Delete');

        await expect.poll(() => app.text()).toBe('fun main() {\n    println(greet("Wasm"))\n}');
        expect(await app.lineNumbers()).toEqual(['1', '2', '3']);
    });

    test('typing over a selection replaces it', async ({ app, page }) => {
        await app.focus('end');

        await page.keyboard.press('Control+a');
        await page.keyboard.type('val only = true');

        await expect.poll(() => app.text()).toBe('val only = true');
    });

    test('Enter splits a line and Backspace joins it again', async ({ app, page }) => {
        await app.setText('first();second()');
        await app.focus('end');
        for (let index = 0; index < 'second()'.length; index++) await page.keyboard.press('ArrowLeft');

        await page.keyboard.press('Enter');
        await expect.poll(() => app.text()).toBe('first();\nsecond()');
        expect(await app.lineNumbers()).toEqual(['1', '2']);

        await page.keyboard.press('Backspace');
        await expect.poll(() => app.text()).toBe('first();second()');
        expect(await app.lineNumbers()).toEqual(['1']);
    });

    test('brackets and quotes are closed automatically', async ({ app, page }) => {
        await app.setText('');
        await app.focus('end');

        await page.keyboard.type('call(');
        await expect.poll(() => app.text()).toBe('call()');

        await page.keyboard.type('"text');
        await expect.poll(() => app.text()).toBe('call("text")');

        // Backspace on an empty pair removes both halves.
        await app.setText('');
        await page.keyboard.type('[');
        await expect.poll(() => app.text()).toBe('[]');
        await page.keyboard.press('Backspace');
        await expect.poll(() => app.text()).toBe('');
    });

    test('Enter inside braces opens an indented block', async ({ app, page }) => {
        await app.setText('');
        await app.focus('end');

        await page.keyboard.type('fun block() {');
        await page.keyboard.press('Enter');
        await page.keyboard.type('body()');

        const lines = (await app.text()).split('\n');
        expect(lines).toHaveLength(3);
        expect(lines[0]).toBe('fun block() {');
        expect(lines[1]).toBe('    body()');
        expect(lines[2]).toBe('}');
    });

    test('undo and redo step through the edits', async ({ app, page }) => {
        await app.focus('end');
        await page.keyboard.type(' // first');
        await expect.poll(() => app.text()).toBe(sampleText + ' // first');

        await page.keyboard.press('Control+z');
        await expect.poll(() => app.text()).toBe(sampleText);

        await page.keyboard.press('Control+Shift+z');
        await expect.poll(() => app.text()).toBe(sampleText + ' // first');
    });

    test('undo has nothing to restore in a fresh document', async ({ app, page }) => {
        await app.focus('end');

        await page.keyboard.press('Control+z');
        await page.keyboard.type('!');

        await expect.poll(() => app.text()).toBe(sampleText + '!');
    });

    test('copy and paste duplicate the selection', async ({ app, page }) => {
        await app.setText('val copied = 1');
        await app.focus('end');

        await page.keyboard.press('Control+a');
        await page.keyboard.press('Control+c');
        await page.keyboard.press('Control+End');
        await page.keyboard.press('Enter');
        await page.keyboard.press('Control+v');

        await expect.poll(() => app.text()).toBe('val copied = 1\nval copied = 1');
    });

    test('cut removes the selection and paste brings it back', async ({ app, page }) => {
        await app.focus('end');

        await page.keyboard.press('Control+a');
        await page.keyboard.press('Control+x');
        await expect.poll(() => app.text()).toBe('');

        await page.keyboard.press('Control+v');
        await expect.poll(() => app.text()).toBe(sampleText);
    });

    test('pasted text keeps its lines and indentation', async ({ app }) => {
        const pasted = 'fun pasted() {\n        deeply("indented")\n}\n';
        await app.focus('start');

        await app.paste(pasted);

        await expect.poll(() => app.text()).toBe(pasted + sampleText);
    });

    test('a click places the caret on the clicked line', async ({ app, page }) => {
        // Past the end of the text of line 5, so the caret goes to its end.
        const line = (await app.content.locator('.cm-line').nth(4).boundingBox())!;
        await page.mouse.click(line.x + line.width - 5, line.y + line.height / 2);

        await page.keyboard.type(' // here');

        await expect.poll(async () => (await app.text()).split('\n')[4]).toBe('    println(greet("Wasm")) // here');
    });

    test('a double click selects a word', async ({ app, page }) => {
        await app.content.locator('span', { hasText: /^main$/ }).dblclick();

        await page.keyboard.type('start');

        await expect.poll(async () => (await app.text()).split('\n')[3]).toBe('fun start() {');
    });

    test('dragging selects across lines', async ({ app, page }) => {
        const lines = app.content.locator('.cm-line');
        const first = (await lines.nth(0).boundingBox())!;
        const fourth = (await lines.nth(3).boundingBox())!;

        // From the start of line 1 to the start of line 4.
        await page.mouse.move(first.x + 1, first.y + first.height / 2);
        await page.mouse.down();
        await page.mouse.move(fourth.x + 1, fourth.y + fourth.height / 2, { steps: 5 });
        await page.mouse.up();
        await page.keyboard.press('Delete');

        await expect.poll(() => app.text()).toBe('fun main() {\n    println(greet("Wasm"))\n}');
    });

    test('characters outside ASCII survive the round trip', async ({ app, page }) => {
        const text = 'val π = "naïve — 日本語 🚀"';
        await app.setText('');
        await app.focus('end');

        await app.paste(text);

        await expect.poll(() => app.text()).toBe(text);
        await expect(app.content.locator('.cm-line')).toHaveText([text]);
        await page.keyboard.press('Backspace');
        await page.keyboard.press('Backspace');
        // The emoji is one character for the user although it is two UTF-16 units.
        await expect.poll(() => app.text()).toBe('val π = "naïve — 日本語 ');
    });

    test('editing stays quick in a large document', async ({ app, page }) => {
        const lines = Array.from({ length: 20_000 }, (_, index) => `val line${index} = ${index}`);
        await app.setText(lines.join('\n'));
        await app.focus('end');

        const started = Date.now();
        await page.keyboard.type(' // end');
        await expect.poll(async () => (await app.text()).endsWith('val line19999 = 19999 // end')).toBe(true);

        expect(Date.now() - started).toBeLessThan(3_000);
        await expect(app.content.locator('.cm-line').last()).toHaveText('val line19999 = 19999 // end');
    });
});

test.describe('search panel', () => {
    const text = 'fun greet() = 1\nfun other() = greet()\nval greeting = greet()';

    // CodeMirror applies the query when a key is released, so it is typed rather than filled in.
    const searchFor = (page: Page, query: string) => page.locator('.cm-search input[name=search]').pressSequentially(query);

    test.beforeEach(async ({ app, page }) => {
        await app.open();
        await app.setText(text);
        await app.focus('start');
        await page.keyboard.press('Control+f');
    });

    test('opens inside the editor and highlights the matches', async ({ app, page }) => {
        const panel = page.locator('.cm-search');
        await expect(panel).toBeVisible();

        await searchFor(page, 'greet');

        await expect(page.locator('.cm-searchMatch')).toHaveCount(4);
        const panelBox = (await panel.boundingBox())!;
        const editor = (await app.editor.boundingBox())!;
        expect(panelBox.y + panelBox.height).toBeLessThanOrEqual(editor.y + editor.height);
        // The search does not edit anything.
        expect(await app.text()).toBe(text);
    });

    test('whole word search skips longer identifiers', async ({ page }) => {
        const panel = page.locator('.cm-search');
        await searchFor(page, 'greet');
        await expect(page.locator('.cm-searchMatch')).toHaveCount(4);

        await panel.locator('input[name=word]').check();

        await expect(page.locator('.cm-searchMatch')).toHaveCount(3);
    });

    test('replace all rewrites every match', async ({ app, page }) => {
        const panel = page.locator('.cm-search');
        await searchFor(page, 'greet()');
        await panel.locator('input[name=replace]').fill('salute()');

        await panel.locator('button[name=replaceAll]').click();

        await expect.poll(() => app.text()).toBe('fun salute() = 1\nfun other() = salute()\nval greeting = salute()');
    });

    test('replace all is undone in one step', async ({ app, page }) => {
        const panel = page.locator('.cm-search');
        await searchFor(page, 'greet');
        await panel.locator('input[name=replace]').fill('x');
        await panel.locator('button[name=replaceAll]').click();
        await expect.poll(() => app.text()).not.toBe(text);

        await app.content.click();
        await page.keyboard.press('Control+z');

        await expect.poll(() => app.text()).toBe(text);
    });

    test('Escape closes the panel and returns the keyboard to the document', async ({ app, page }) => {
        await expect(page.locator('.cm-search')).toBeVisible();

        await page.keyboard.press('Escape');
        await expect(page.locator('.cm-search')).toHaveCount(0);
        await page.keyboard.type('// ');

        await expect.poll(() => app.text()).toBe('// ' + text);
    });
});

test.describe('IntelliJ keymap', () => {
    const text = 'fun first() {\n    one()\n    two()\n}';

    test.beforeEach(async ({ app }) => {
        await app.open();
        await app.setText(text);
    });

    test('Ctrl+D duplicates the line and moves the caret into the copy', async ({ app, page }) => {
        await app.caretAt('one');

        await page.keyboard.press('Control+d');

        await expect.poll(() => app.text()).toBe('fun first() {\n    one()\n    one()\n    two()\n}');
        expect((await app.state()).caret.line).toBe(3);
    });

    test('Ctrl+D duplicates a selection right after itself', async ({ app, page }) => {
        await app.select(text.indexOf('one()'), text.indexOf('one()') + 'one()'.length);

        await page.keyboard.press('Control+d');

        await expect.poll(() => app.text()).toBe('fun first() {\n    one()one()\n    two()\n}');
        expect((await app.state()).caret.selectedCharacters).toBe(5);
    });

    test('Ctrl+Y deletes the line', async ({ app, page }) => {
        await app.caretAt('one');

        await page.keyboard.press('Control+y');

        await expect.poll(() => app.text()).toBe('fun first() {\n    two()\n}');
    });

    test('Alt+Shift+Down and Up move the line', async ({ app, page }) => {
        await app.caretAt('one');

        await page.keyboard.press('Alt+Shift+ArrowDown');
        await expect.poll(() => app.text()).toBe('fun first() {\n    two()\n    one()\n}');

        await page.keyboard.press('Alt+Shift+ArrowUp');
        await expect.poll(() => app.text()).toBe(text);
    });

    test('Ctrl+/ comments the line out and back in', async ({ app, page }) => {
        await app.caretAt('one');

        await page.keyboard.press('Control+/');
        await expect.poll(() => app.text()).toBe('fun first() {\n    // one()\n    two()\n}');
        await expect.poll(() => app.tokenClasses('// one()')).toEqual(['kt-comment']);

        await page.keyboard.press('Control+/');
        await expect.poll(() => app.text()).toBe(text);
    });

    test('Ctrl+Shift+/ wraps the selection in a block comment', async ({ app, page }) => {
        await app.select(text.indexOf('one()'), text.indexOf('one()') + 'one()'.length);

        // Shift turns the slash into a question mark on the keyboard layout Playwright types with.
        await page.keyboard.press('Control+Shift+?');

        await expect.poll(() => app.text()).toBe('fun first() {\n    /* one() */\n    two()\n}');
    });

    test('Ctrl+Shift+J joins the next line to this one', async ({ app, page }) => {
        await app.caretAt('one');

        await page.keyboard.press('Control+Shift+j');

        await expect.poll(() => app.text()).toBe('fun first() {\n    one() two()\n}');
    });

    test('Shift+Enter starts a new line below from the middle of a line', async ({ app, page }) => {
        await app.caretAt('one');

        await page.keyboard.press('Shift+Enter');
        await page.keyboard.type('between()');

        await expect.poll(() => app.text()).toBe('fun first() {\n    one()\n    between()\n    two()\n}');
    });

    test('Ctrl+Alt+Enter starts a new line above', async ({ app, page }) => {
        await app.caretAt('two');

        await page.keyboard.press('Control+Alt+Enter');
        await page.keyboard.type('between()');

        await expect.poll(() => app.text()).toBe('fun first() {\n    one()\n    between()\n    two()\n}');
    });

    test('Tab indents by four spaces and Shift+Tab takes them back', async ({ app, page }) => {
        await app.caretAt('one');

        await page.keyboard.press('Tab');
        await expect.poll(() => app.text()).toBe('fun first() {\n        one()\n    two()\n}');
        await expect(app.content).toBeFocused();

        await page.keyboard.press('Shift+Tab');
        await page.keyboard.press('Shift+Tab');
        await expect.poll(() => app.text()).toBe('fun first() {\none()\n    two()\n}');
    });

    test('Tab indents every selected line', async ({ app, page }) => {
        await app.select(text.indexOf('one'), text.indexOf('two') + 1);

        await page.keyboard.press('Tab');

        await expect.poll(() => app.text()).toBe('fun first() {\n        one()\n        two()\n}');
    });

    test('Alt+J adds a caret at the next occurrence', async ({ app, page }) => {
        await app.setText('val a = item\nval b = item\nval c = item');
        await app.caretAt('item');

        await page.keyboard.press('Alt+j');
        await page.keyboard.press('Alt+j');
        await expect.poll(async () => (await app.state()).caret.carets).toBe(2);
        await page.keyboard.type('thing');

        await expect.poll(() => app.text()).toBe('val a = thing\nval b = thing\nval c = item');
    });

    test('a new block is indented by four spaces like the rest of the code', async ({ app, page }) => {
        await app.focus('end');

        await page.keyboard.type('\nfun second() {');
        await page.keyboard.press('Enter');
        await page.keyboard.type('three()');

        await expect.poll(() => app.text()).toBe(text + '\nfun second() {\n    three()\n}');
    });
});

test.describe('completion', () => {
    const text = 'fun calculate(amount: Int) = amount * 2\nval total = calculate(1)\n';
    const options = (page: Page) => page.locator('.cm-tooltip-autocomplete .cm-completionLabel');

    /** Waits for the list to show [labels] and to accept keys: CodeMirror ignores them for a moment after it opens. */
    const offered = async (page: Page, labels: string[]) => {
        await expect(options(page)).toHaveText(labels);
        await page.waitForTimeout(200);
    };

    test.beforeEach(async ({ app }) => {
        await app.open();
        await app.setText(text);
        await app.focus('end');
    });

    test('offers the names of the file that start with what is typed', async ({ app, page }) => {
        await page.keyboard.type('val next = ca');

        await expect(options(page)).toHaveText(['calculate', 'catch']);
        await expect(page.locator('.cm-tooltip-autocomplete .cm-completionDetail').first()).toHaveText('calculate(amount: Int)');
        expect(await app.text()).toBe(text + 'val next = ca');
    });

    test('Enter inserts the selected name', async ({ app, page }) => {
        await page.keyboard.type('val next = ca');
        await offered(page, ['calculate', 'catch']);

        await page.keyboard.press('Enter');

        await expect.poll(() => app.text()).toBe(text + 'val next = calculate');
        await expect(page.locator('.cm-tooltip-autocomplete')).toHaveCount(0);
    });

    test('Tab inserts the selected name too', async ({ app, page }) => {
        await page.keyboard.type('val next = to');
        await offered(page, ['total']);

        await page.keyboard.press('Tab');

        await expect.poll(() => app.text()).toBe(text + 'val next = total');
    });

    test('the arrow keys choose another name', async ({ app, page }) => {
        await page.keyboard.type('val next = ca');
        await offered(page, ['calculate', 'catch']);

        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('Enter');

        await expect.poll(() => app.text()).toBe(text + 'val next = catch');
    });

    test('Escape closes the list and Enter then breaks the line', async ({ app, page }) => {
        await page.keyboard.type('val next = ca');
        await offered(page, ['calculate', 'catch']);

        await page.keyboard.press('Escape');
        await expect(page.locator('.cm-tooltip-autocomplete')).toHaveCount(0);
        await page.keyboard.press('Enter');

        await expect.poll(() => app.text()).toBe(text + 'val next = ca\n');
    });

    test('offers parameters only inside their function', async ({ app, page }) => {
        await page.keyboard.type('val next = am');
        // `amount` is a parameter of calculate().
        await page.waitForTimeout(300);
        await expect(page.locator('.cm-tooltip-autocomplete')).toHaveCount(0);

        await app.caretAt('amount * 2', 1, 0);
        await page.keyboard.type('am');
        await expect(options(page)).toHaveText(['amount']);
    });

    test('offers nothing for the name of a new declaration, so Enter breaks the line', async ({ app, page }) => {
        await page.keyboard.type('val ca');
        await page.waitForTimeout(300);
        await expect(page.locator('.cm-tooltip-autocomplete')).toHaveCount(0);

        await page.keyboard.press('Enter');

        await expect.poll(() => app.text()).toBe(text + 'val ca\n');
    });

    test('offers nothing inside comments and strings', async ({ app, page }) => {
        await page.keyboard.type('// ca');
        await page.waitForTimeout(300);
        await expect(page.locator('.cm-tooltip-autocomplete')).toHaveCount(0);

        await page.keyboard.press('Enter');
        await page.keyboard.insertText('val text = "ca');
        await page.keyboard.type('l');
        await page.waitForTimeout(300);
        await expect(page.locator('.cm-tooltip-autocomplete')).toHaveCount(0);
    });

    test('a finished keyword is not offered again', async ({ app, page }) => {
        await page.keyboard.type('val');
        await page.waitForTimeout(300);
        await expect(page.locator('.cm-tooltip-autocomplete')).toHaveCount(0);

        await page.keyboard.press('Enter');

        await expect.poll(() => app.text()).toBe(text + 'val\n');
    });

    test('Ctrl+Space lists everything visible at the caret', async ({ app, page }) => {
        await page.keyboard.press('Control+Space');

        await expect(options(page)).toHaveText(['calculate', 'total']);
    });
});
