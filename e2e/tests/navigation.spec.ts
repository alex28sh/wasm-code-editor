import { expect, sampleText, shapesText, test, type EditorApp } from './app';

// Navigation the way IntelliJ does it: Ctrl+B and Ctrl+click go to the declaration, on a declaration they show
// its usages, Ctrl+F12 lists the structure of the file, Ctrl+Alt+Left and Right walk the places visited.

/** Line and column, counted from one, of the [occurrence]-th appearance of [piece] in [text]. */
function positionOf(text: string, piece: string, occurrence = 1): { line: number; column: number } {
    let offset = -1;
    for (let found = 0; found < occurrence; found++) offset = text.indexOf(piece, offset + 1);
    const before = text.slice(0, offset).split('\n');
    return { line: before.length, column: before[before.length - 1].length + 1 };
}

async function caret(app: EditorApp): Promise<{ line: number; column: number }> {
    const { line, column } = (await app.state()).caret;
    return { line, column };
}

test.describe('go to declaration', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
        await app.setText(shapesText);
    });

    test('Ctrl+B on a call goes to the function', async ({ app, page }) => {
        await app.caretAt('largest', 2);

        await page.keyboard.press('Control+b');

        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'largest'));
        await expect(app.content).toBeFocused();
    });

    test('Ctrl+B works with the caret at either end of the name', async ({ app, page }) => {
        await app.caretAt('largest', 2, 0);
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'largest'));

        await app.caretAt('largest', 2, 'largest'.length);
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'largest'));
    });

    test('a local variable, a parameter, a property and a type are found', async ({ app, page }) => {
        const cases: [use: string, occurrence: number, declaration: string, declarationOccurrence: number][] = [
            ['result', 3, 'result', 1],
            ['limit', 2, 'limit', 1],
            ['radius', 3, 'radius', 1],
            ['Shape', 2, 'Shape', 1],
            ['PI', 1, 'PI', 2],
            ['T>,', 1, 'T : Shape', 1],
        ];
        for (const [use, occurrence, declaration, declarationOccurrence] of cases) {
            await app.caretAt(use, occurrence, 0);
            await page.keyboard.press('Control+b');
            await expect.poll(() => caret(app), use).toEqual(positionOf(shapesText, declaration, declarationOccurrence));
        }
    });

    test('a member named after a known type is found in that type', async ({ app, page }) => {
        await app.caretAt('METER.factor');
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'METER'));

        await app.caretAt('factor', 2);
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'factor'));
    });

    test('a name in a string template and in a documentation comment is found', async ({ app, page }) => {
        // `$name` in toString().
        await app.caretAt('name', 4);
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'name', 3));

        // `[name]` in the comment.
        await app.caretAt('name', 1);
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'name', 3));
    });

    test('an inherited member is found in the supertype', async ({ app, page }) => {
        await app.setText('open class Base {\n    val shared = 1\n}\n\nclass Derived : Base() {\n    fun use() = shared\n}');
        await app.caretAt('shared', 2);

        await page.keyboard.press('Control+b');

        await expect.poll(() => caret(app)).toEqual({ line: 2, column: 9 });
    });

    test('a name the file does not declare leaves the caret where it is', async ({ app, page }) => {
        await app.caretAt('println');
        const before = await caret(app);

        await page.keyboard.press('Control+b');

        expect(await caret(app)).toEqual(before);
        await expect(app.popup).toHaveCount(0);
        expect(await app.text()).toBe(shapesText);
    });

    test('Ctrl+click goes to the declaration without adding a caret', async ({ app, page }) => {
        await app.setText(sampleText);
        const use = app.content.locator('span', { hasText: /^greet$/ }).nth(1);

        await use.click({ modifiers: ['Control'] });

        await expect.poll(() => app.state().then(state => state.caret)).toEqual({ line: 2, column: 5, selectedCharacters: 0, carets: 1 });
    });

    test('a plain click only places the caret', async ({ app }) => {
        await app.setText(sampleText);
        const use = app.content.locator('span', { hasText: /^greet$/ }).nth(1);

        await use.click();

        expect((await app.state()).caret.line).toBe(5);
    });

    test('the declaration is scrolled into view', async ({ app, page }) => {
        const filler = Array.from({ length: 400 }, (_, index) => `val filler${index} = ${index}`).join('\n');
        await app.setText(`fun target() = 1\n${filler}\nval last = target()`);
        await app.caretAt('target', 2);
        await expect(app.content.locator('.cm-line').last()).toHaveText('val last = target()');

        await page.keyboard.press('Control+b');

        await expect(app.content.locator('.cm-line').first()).toHaveText('fun target() = 1');
        await expect(app.content.locator('.cm-line').first()).toBeInViewport();
        expect(await caret(app)).toEqual({ line: 1, column: 5 });
    });
});

test.describe('usages', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
        await app.setText(shapesText);
    });

    test('Ctrl+B on a declaration with one usage goes to it', async ({ app, page }) => {
        await app.caretAt('largest');

        await page.keyboard.press('Control+b');

        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'largest', 2));
        await expect(app.popup).toHaveCount(0);
    });

    test('Ctrl+B on a declaration with several usages lists them', async ({ app, page }) => {
        await app.caretAt('area');

        await page.keyboard.press('Control+b');

        await expect(app.popup.locator('.kt-popup-title')).toHaveText('Usages of area');
        await expect(app.popup.locator('.kt-popup-label')).toHaveText([
            'override fun toString() = "$name of ${area()}\\n"',
            'println(shape.area() + Scale.METER.factor)',
            'return shapes.maxByOrNull { it.area() }',
        ]);
        await expect(app.popup.locator('.kt-popup-detail')).toHaveText(['line 12', 'line 34', 'line 36']);
    });

    test('the arrow keys and Enter pick a usage', async ({ app, page }) => {
        await app.caretAt('area');
        await page.keyboard.press('Control+b');
        await expect(app.popup.locator('[aria-selected=true] .kt-popup-detail')).toHaveText('line 12');

        await page.keyboard.press('ArrowDown');
        await expect(app.popup.locator('[aria-selected=true] .kt-popup-detail')).toHaveText('line 34');
        await page.keyboard.press('Enter');

        await expect(app.popup).toHaveCount(0);
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'area', 4));
        await expect(app.content).toBeFocused();
    });

    test('a click picks a usage', async ({ app, page }) => {
        await app.caretAt('area');
        await page.keyboard.press('Control+b');

        await app.popup.locator('.kt-popup-item', { hasText: 'maxByOrNull' }).click();

        await expect(app.popup).toHaveCount(0);
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'area', 5));
    });

    test('typing filters the list', async ({ app, page }) => {
        await app.caretAt('area');
        await page.keyboard.press('Control+b');

        await page.keyboard.type('PRINT');
        await expect(app.popup.locator('.kt-popup-label')).toHaveText(['println(shape.area() + Scale.METER.factor)']);

        await page.keyboard.type('zzz');
        await expect(app.popup.locator('.kt-popup-item')).toHaveCount(0);
        await expect(app.popup.locator('.kt-popup-empty')).toHaveText('Nothing matches');
        // What is typed goes to the filter, not to the document.
        expect(await app.text()).toBe(shapesText);
    });

    test('Escape closes the list and leaves the caret', async ({ app, page }) => {
        await app.caretAt('area');
        const before = await caret(app);
        await page.keyboard.press('Control+b');
        await expect(app.popup).toBeVisible();

        await page.keyboard.press('Escape');

        await expect(app.popup).toHaveCount(0);
        await expect(app.content).toBeFocused();
        expect(await caret(app)).toEqual(before);
    });

    test('Alt+F7 on a use lists all usages with the current one selected', async ({ app, page }) => {
        await app.caretAt('area', 4);

        await page.keyboard.press('Alt+F7');

        await expect(app.popup.locator('.kt-popup-item')).toHaveCount(3);
        await expect(app.popup.locator('[aria-selected=true] .kt-popup-detail')).toHaveText('line 34');
    });

    test('a declaration nobody uses says so', async ({ app, page }) => {
        await app.caretAt('main');

        await page.keyboard.press('Alt+F7');

        await expect(app.popup.locator('.kt-popup-title')).toHaveText('Usages of main');
        await expect(app.popup.locator('.kt-popup-empty')).toHaveText('No usages in this file');
    });
});

test.describe('file structure', () => {
    test.beforeEach(async ({ app, page }) => {
        await app.open();
        await app.setText(shapesText);
        await app.caretAt('var result');
        await page.keyboard.press('Control+F12');
    });

    test('lists the declarations of the file with their kinds', async ({ app }) => {
        await expect(app.popup.locator('.kt-popup-title')).toHaveText('File structure');
        await expect(app.popup.locator('.kt-popup-label')).toHaveText([
            'Shape(val name: String)',
            'name: String',
            'area(): Double',
            'toString()',
            'Circle(private val radius: Double) : Shape("circle")',
            'radius: Double',
            'area(): Double',
            'PI',
            'Scale(val factor: Int)',
            'factor: Int',
            'METER',
            'KILOMETER',
            'largest(shapes: List<T>, limit: Int = 0xFF): T?',
            'main()',
        ]);
        await expect(app.popup.locator('.kt-popup-detail').first()).toHaveText('class');
        await expect(app.popup.locator('.kt-popup-kind').nth(2)).toHaveText('f');
        await expect(app.popup.locator('.kt-popup-kind').nth(10)).toHaveText('E');
    });

    test('indents members under their class', async ({ app }) => {
        const indent = (index: number) => app.popup.locator('.kt-popup-item').nth(index).evaluate(element => parseInt(getComputedStyle(element).paddingLeft));

        expect(await indent(1)).toBeGreaterThan(await indent(0));
        // PI is inside the companion object of Circle.
        expect(await indent(7)).toBeGreaterThan(await indent(6));
        expect(await indent(12)).toBe(await indent(0));
    });

    test('starts at the declaration the caret is in', async ({ app }) => {
        await expect(app.popup.locator('[aria-selected=true] .kt-popup-label')).toHaveText('area(): Double');
        await expect(app.popup.locator('.kt-popup-item').nth(6)).toHaveAttribute('aria-selected', 'true');
    });

    test('Enter goes to the selected declaration', async ({ app, page }) => {
        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('Enter');

        await expect(app.popup).toHaveCount(0);
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'PI', 2));
    });

    test('typing narrows the list to matching names', async ({ app, page }) => {
        await page.keyboard.type('ar');

        await expect(app.popup.locator('.kt-popup-label')).toHaveText(['area(): Double', 'area(): Double', 'largest(shapes: List<T>, limit: Int = 0xFF): T?']);
        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('ArrowDown');
        await page.keyboard.press('Enter');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'largest'));
    });

    test('closes when the focus leaves it', async ({ app }) => {
        // Next to the popup, which covers the middle of the editor.
        await app.content.click({ position: { x: 5, y: 5 } });

        await expect(app.popup).toHaveCount(0);
    });

    test('stays inside the editor', async ({ app }) => {
        const popup = (await app.popup.boundingBox())!;
        const editor = (await app.editor.boundingBox())!;

        expect(popup.x).toBeGreaterThanOrEqual(editor.x);
        expect(popup.x + popup.width).toBeLessThanOrEqual(editor.x + editor.width);
        expect(popup.y + popup.height).toBeLessThanOrEqual(editor.y + editor.height);
    });
});

test.describe('file structure on a phone', () => {
    test.use({ viewport: { width: 390, height: 844 } });

    test('fits the narrow window', async ({ app, page }) => {
        await app.open();
        await app.setText(shapesText);
        await app.content.focus();

        await page.keyboard.press('Control+F12');

        const popup = (await app.popup.boundingBox())!;
        expect(popup.x).toBeGreaterThanOrEqual(0);
        expect(popup.x + popup.width).toBeLessThanOrEqual(390);
        await expect(app.popup.locator('.kt-popup-item')).toHaveCount(14);
    });
});

test.describe('navigation history', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
        await app.setText(shapesText);
    });

    test('Ctrl+Alt+Left returns to where the jump started and Ctrl+Alt+Right repeats it', async ({ app, page }) => {
        await app.caretAt('largest', 2);
        const start = await caret(app);
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'largest'));

        await page.keyboard.press('Control+Alt+ArrowLeft');
        await expect.poll(() => caret(app)).toEqual(start);

        await page.keyboard.press('Control+Alt+ArrowRight');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'largest'));
    });

    test('several jumps are undone one by one', async ({ app, page }) => {
        await app.caretAt('circle', 3);
        const start = await caret(app);
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'circle', 2));
        await app.caretAt('Circle', 2);
        const second = await caret(app);
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'Circle'));

        await page.keyboard.press('Control+Alt+ArrowLeft');
        await expect.poll(() => caret(app)).toEqual(second);
        await page.keyboard.press('Control+Alt+ArrowLeft');
        await expect.poll(() => caret(app)).toEqual(start);
        // Nothing is left to go back to.
        await page.keyboard.press('Control+Alt+ArrowLeft');
        expect(await caret(app)).toEqual(start);
    });

    test('the places move with the text that is edited in front of them', async ({ app, page }) => {
        await app.caretAt('largest', 2);
        const start = await caret(app);
        await page.keyboard.press('Control+b');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'largest'));

        // Two new lines at the top of the file.
        await page.keyboard.press('Control+Home');
        await page.keyboard.type('// one\n// two\n');
        await page.keyboard.press('Control+Alt+ArrowLeft');

        await expect.poll(() => caret(app)).toEqual({ line: start.line + 2, column: start.column });
    });

    test('a jump from the file structure can be undone too', async ({ app, page }) => {
        await app.caretAt('var result');
        const start = await caret(app);
        await page.keyboard.press('Control+F12');
        await page.keyboard.type('main');
        await page.keyboard.press('Enter');
        await expect.poll(() => caret(app)).toEqual(positionOf(shapesText, 'main'));

        await page.keyboard.press('Control+Alt+ArrowLeft');

        await expect.poll(() => caret(app)).toEqual(start);
    });
});

test.describe('go to line', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
        await app.setText(shapesText);
        await app.caretAt('var result', 1, 0);
    });

    test('Ctrl+G offers the current position and goes to the one that is typed', async ({ app, page }) => {
        await page.keyboard.press('Control+g');
        await expect(app.popup.locator('.kt-popup-title')).toHaveText('Go to Line:Column');
        await expect(app.popup.locator('input')).toHaveValue('17:9');

        await page.keyboard.type('30:17');
        await page.keyboard.press('Enter');

        await expect(app.popup).toHaveCount(0);
        await expect.poll(() => caret(app)).toEqual({ line: 30, column: 17 });
        await expect(app.content).toBeFocused();
        expect(positionOf(shapesText, 'largest')).toEqual({ line: 30, column: 17 });
    });

    test('a line alone goes to its start, and numbers out of range to the nearest place', async ({ app, page }) => {
        await page.keyboard.press('Control+g');
        await page.keyboard.type('9');
        await page.keyboard.press('Enter');
        await expect.poll(() => caret(app)).toEqual({ line: 9, column: 1 });

        await page.keyboard.press('Control+g');
        await page.keyboard.type('999:999');
        await page.keyboard.press('Enter');
        await expect.poll(() => caret(app)).toEqual({ line: 42, column: 2 });
    });

    test('Escape and text that is not a position change nothing', async ({ app, page }) => {
        await page.keyboard.press('Control+g');
        await page.keyboard.press('Escape');
        await expect(app.popup).toHaveCount(0);
        expect(await caret(app)).toEqual({ line: 17, column: 9 });

        await page.keyboard.press('Control+g');
        await page.keyboard.type('somewhere');
        await page.keyboard.press('Enter');
        await expect(app.popup).toHaveCount(0);
        expect(await caret(app)).toEqual({ line: 17, column: 9 });
        expect(await app.text()).toBe(shapesText);
    });

    test('the jump can be undone with Ctrl+Alt+Left', async ({ app, page }) => {
        await page.keyboard.press('Control+g');
        await page.keyboard.type('1');
        await page.keyboard.press('Enter');
        await expect.poll(() => caret(app)).toEqual({ line: 1, column: 1 });

        await page.keyboard.press('Control+Alt+ArrowLeft');

        await expect.poll(() => caret(app)).toEqual({ line: 17, column: 9 });
    });
});

test.describe('rename', () => {
    test.beforeEach(async ({ app }) => {
        await app.open();
    });

    test('Shift+F6 puts a caret on every occurrence, so typing renames them all', async ({ app, page }) => {
        await app.caretAt('name', 2);

        await page.keyboard.press('Shift+F6');
        await expect.poll(async () => (await app.state()).caret.carets).toBe(3);
        await page.keyboard.type('who');

        await expect.poll(() => app.text()).toBe(sampleText.replaceAll('name', 'who'));
    });

    test('the renamed symbol is still one symbol', async ({ app, page }) => {
        await app.caretAt('greet', 2);
        await page.keyboard.press('Shift+F6');
        await page.keyboard.type('welcome');
        await page.keyboard.press('Escape');

        await expect.poll(async () => (await app.state()).caret.carets).toBe(1);
        await expect.poll(() => app.markedUsages()).toEqual(['welcome', 'welcome']);
        expect(await app.tokenClasses('welcome')).toEqual(['kt-function-declaration', 'kt-usage']);
        expect(await app.tokenClasses('welcome', 2)).toEqual(['kt-top-level-call', 'kt-usage']);
    });

    test('only the symbol under the caret is renamed, not others of the same name', async ({ app, page }) => {
        await app.setText('fun first(value: Int) = value\nfun second(value: Int) = value + 1');
        await app.caretAt('value', 4);

        await page.keyboard.press('Shift+F6');
        await page.keyboard.type('number');

        await expect.poll(() => app.text()).toBe('fun first(value: Int) = value\nfun second(number: Int) = number + 1');
    });

    test('Enter finishes the rename without breaking the line', async ({ app, page }) => {
        await app.caretAt('greet', 2);
        await page.keyboard.press('Shift+F6');
        await page.keyboard.type('welcome');

        await page.keyboard.press('Enter');

        await expect.poll(async () => (await app.state()).caret.carets).toBe(1);
        expect(await app.text()).toBe(sampleText.replaceAll('greet', 'welcome'));
    });

    test('a rename is undone in one step', async ({ app, page }) => {
        await app.caretAt('name', 2);
        await page.keyboard.press('Shift+F6');
        await page.keyboard.type('who');
        await expect.poll(() => app.text()).not.toBe(sampleText);

        await page.keyboard.press('Control+z');

        await expect.poll(() => app.text()).toBe(sampleText);
    });

    test('a named argument follows its parameter', async ({ app, page }) => {
        await app.setText(shapesText);
        await app.caretAt('radius');

        await page.keyboard.press('Shift+F6');
        await page.keyboard.type('r');

        await expect.poll(() => app.text()).toBe(shapesText.replaceAll('radius', 'r'));
    });
});
