import { expect, test as base, type Locator, type Page } from '@playwright/test';

export interface AppState {
    pluginsReady: boolean;
    text: string;
    caret: { line: number; column: number; selectedCharacters: number; carets: number };
    plugins: { id: string; sandboxed: boolean }[];
    failures: { module: string; message: string }[];
    /** What the compiler is doing; `null` until the first run starts it. */
    compiler: string | null;
    console: { title: string; lines: string[] } | null;
    diagnostics: { severity: string; message: string; from: number; to: number }[];
}

export interface ConsoleEntry {
    type: string;
    text: string;
    fromWorker: boolean;
}

export interface PluginIndexEntry {
    module: string;
    trusted: boolean;
}

/** Base names of the plugin modules in the end-to-end distribution. */
export const pluginModule = {
    hello: 'wasm-code-editor-plugins-hello',
    spinning: 'wasm-code-editor-test-plugins-spinning',
    reaching: 'wasm-code-editor-test-plugins-reaching',
    throwing: 'wasm-code-editor-test-plugins-throwing',
    pair: 'wasm-code-editor-test-plugins-pair',
    impostor: 'wasm-code-editor-test-plugins-impostor',
};

/** A document with most kinds of declarations, for the tests of highlighting and navigation. */
export const shapesText = `package sample

import kotlin.math.max

/**
 * A shape with a [name].
 * @param name how it is called
 */
abstract class Shape(val name: String) {
    abstract fun area(): Double

    override fun toString() = "$name of \${area()}\\n"
}

data class Circle(private val radius: Double) : Shape("circle") {
    override fun area(): Double {
        var result = radius * radius
        result *= PI
        return result
    }

    companion object {
        const val PI = 3.14159
    }
}

enum class Scale(val factor: Int) { METER(1), KILOMETER(1_000) }

@Suppress("unused")
fun <T : Shape> largest(shapes: List<T>, limit: Int = 0xFF): T? {
    // The biggest one wins.
    loop@ for ((index, shape) in shapes.withIndex()) {
        if (index > limit) break@loop
        println(shape.area() + Scale.METER.factor)
    }
    return shapes.maxByOrNull { it.area() }
}

fun main() {
    val circle = Circle(radius = 2.0)
    println(largest(listOf(circle)))
}`;

/** The document the application starts with. */
export const sampleText = [
    '/** Greets [name]. */',
    'fun greet(name: String): String = "Hello, $name!"',
    '',
    'fun main() {',
    '    println(greet("Wasm"))',
    '}',
].join('\n');

const appModule = '/wasm-code-editor-app.mjs';

/** Drives the application: DOM for the CodeMirror part, the automation exports for what Compose draws on canvas. */
export class EditorApp {
    readonly console: ConsoleEntry[] = [];
    readonly pageErrors: string[] = [];
    readonly failedRequests: string[] = [];
    readonly requestedUrls: string[] = [];

    readonly editor: Locator;
    readonly content: Locator;
    readonly scroller: Locator;
    readonly gutters: Locator;
    readonly canvas: Locator;
    /** The list of the file structure or of usages. */
    readonly popup: Locator;

    constructor(readonly page: Page) {
        this.editor = page.locator('.cm-editor');
        this.content = page.locator('.cm-content');
        this.scroller = page.locator('.cm-scroller');
        this.gutters = page.locator('.cm-gutters');
        this.canvas = page.locator('canvas');
        this.popup = page.locator('.kt-popup');

        page.on('console', message => {
            this.console.push({ type: message.type(), text: message.text(), fromWorker: message.worker() !== null });
        });
        page.on('pageerror', error => this.pageErrors.push(String(error)));
        page.on('request', request => this.requestedUrls.push(new URL(request.url()).pathname));
        page.on('requestfailed', request => this.failedRequests.push(`${request.url()} ${request.failure()?.errorText}`));
        page.on('response', response => {
            if (response.status() >= 400) this.failedRequests.push(`${response.url()} ${response.status()}`);
        });
    }

    /** Opens the application and waits until the editor is shown and the indexed plugins are settled. */
    async open(query = ''): Promise<void> {
        await this.openEditor(query);
        await this.pluginsSettled();
    }

    /** Opens the application and waits only for the editor, while the plugins may still be loading. */
    async openEditor(query = ''): Promise<void> {
        await this.page.goto('/' + query);
        await this.editorShown();
    }

    async editorShown(): Promise<void> {
        try {
            await expect(this.editor).toBeVisible({ timeout: 30_000 });
        } catch (error) {
            throw new Error(this.diagnostics(), { cause: error });
        }
    }

    async pluginsSettled(): Promise<void> {
        await expect.poll(async () => (await this.state()).pluginsReady, { timeout: 30_000 }).toBe(true);
    }

    /** Replaces the plugin index the application is going to fetch; call before opening it. */
    async servePluginIndex(index: PluginIndexEntry[] | string, status = 200): Promise<void> {
        const body = typeof index === 'string' ? index : JSON.stringify(index);
        await this.page.route('**/plugins.json', route => route.fulfill({ status, body, contentType: 'application/json' }));
    }

    async state(): Promise<AppState> {
        const json = await this.page.evaluate(async module => (await import(module)).automationState(), appModule);
        return JSON.parse(json);
    }

    async setText(text: string): Promise<void> {
        await this.page.evaluate(
            async ([module, text]) => (await import(module)).automationSetText(text),
            [appModule, text],
        );
    }

    /** Puts the caret at the offset [anchor] of the document, or selects up to [head], and focuses the editor. */
    async select(anchor: number, head = anchor): Promise<void> {
        await this.page.evaluate(
            async ([module, anchor, head]) => (await import(module as string)).automationSelect(anchor, head),
            [appModule, anchor, head] as const,
        );
        await this.content.focus();
    }

    async loadSandboxedPlugin(module: string): Promise<{ loaded: string[]; error: string | null }> {
        const json = await this.page.evaluate(
            async ([appModule, module]) => (await import(appModule)).automationLoadSandboxedPlugin(module),
            [appModule, module],
        );
        return JSON.parse(json);
    }

    /** Compiles and runs the document, as Ctrl+Enter does, and waits until the program has finished. */
    async run(): Promise<AppState> {
        await this.page.evaluate(async module => (await import(module)).automationRun(), appModule);
        return this.state();
    }

    async text(): Promise<string> {
        return (await this.state()).text;
    }

    /** Lines of the document as CodeMirror renders them; a long document is rendered only around the visible part. */
    async renderedLines(): Promise<string[]> {
        return this.page.locator('.cm-content .cm-line').allTextContents();
    }

    /** Numbers in the gutter, without the hidden element CodeMirror keeps there to reserve the width. */
    async lineNumbers(): Promise<string[]> {
        return this.page.locator('.cm-lineNumbers .cm-gutterElement:visible').allTextContents();
    }

    /** Color of the first highlighted token whose text is exactly [text]. */
    async tokenColor(text: string): Promise<string> {
        const exactly = new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
        return this.content.locator('span', { hasText: exactly }).first().evaluate(element => getComputedStyle(element).color);
    }

    /**
     * The semantic classes on the [occurrence]-th piece of rendered text that is exactly [text]: `['kt-keyword']`.
     *
     * They come from the Kotlin analysis, so they tell what the editor takes the piece for.
     */
    async tokenClasses(text: string, occurrence = 1): Promise<string[]> {
        const exactly = new RegExp(`^${text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
        // The innermost piece: a mark for a usage wraps the one for the kind.
        const token = this.content.locator('span:not(:has(span))', { hasText: exactly }).nth(occurrence - 1);
        return token.evaluate(element => {
            const classes: string[] = [];
            for (let node: Element | null = element; node && !node.classList.contains('cm-line'); node = node.parentElement) {
                classes.push(...[...node.classList].filter(name => name.startsWith('kt-')));
            }
            return classes.sort();
        });
    }

    /** Puts the caret inside the [occurrence]-th appearance of [text] in the document, [offset] characters from its start. */
    async caretAt(text: string, occurrence = 1, offset = 1): Promise<void> {
        const document = await this.text();
        let position = -1;
        for (let found = 0; found < occurrence; found++) {
            position = document.indexOf(text, position + 1);
            if (position < 0) throw new Error(`'${text}' occurs fewer than ${occurrence} times`);
        }
        await this.select(position + offset);
    }

    /** The texts the editor marks as the declaration and the uses of the name under the caret. */
    async markedUsages(): Promise<string[]> {
        return this.content.locator('.kt-usage').allTextContents();
    }

    /** Gives the editor the keyboard focus with the caret at the start or at the end of the document. */
    async focus(caret: 'start' | 'end' = 'end'): Promise<void> {
        await this.content.click();
        await this.page.keyboard.press(caret === 'end' ? 'Control+End' : 'Control+Home');
    }

    /** Pastes [text] at the caret the way the browser does it for Ctrl+V, without touching the system clipboard. */
    async paste(text: string): Promise<void> {
        await this.content.evaluate((element, text) => {
            const clipboardData = new DataTransfer();
            clipboardData.setData('text/plain', text);
            element.dispatchEvent(new ClipboardEvent('paste', { clipboardData, bubbles: true, cancelable: true }));
        }, text);
    }

    /** What the page reported so far, for the message of a failed startup. */
    diagnostics(): string {
        return [
            'The editor did not appear.',
            `Page errors: ${JSON.stringify(this.pageErrors)}`,
            `Failed requests: ${JSON.stringify(this.failedRequests)}`,
            `Console: ${JSON.stringify(this.console.slice(-20))}`,
            `Requests: ${this.requestedUrls.length}, last: ${this.requestedUrls.slice(-3).join(', ')}`,
        ].join('\n');
    }

    consoleProblems(): ConsoleEntry[] {
        return this.console.filter(entry => entry.type === 'error' || entry.type === 'warning');
    }

    /** The strip below the editor that Compose draws: status line and plugin failures. */
    async statusBarClip(): Promise<{ x: number; y: number; width: number; height: number }> {
        const editor = (await this.editor.boundingBox())!;
        const viewport = this.page.viewportSize()!;
        return { x: 0, y: editor.y + editor.height, width: viewport.width, height: viewport.height - editor.y - editor.height };
    }

    /** The pixels of the status bar; Compose redraws it a frame after the state changes, so poll for an expected picture. */
    async statusBar(): Promise<Buffer> {
        return this.page.screenshot({ clip: await this.statusBarClip() });
    }

    /**
     * Compares the status bar with a baseline exactly: one changed digit is only a handful of pixels.
     *
     * When the baseline is being written, the picture on screen is taken as it is,
     * so wait for the redraw first, for example with [statusBarChanged].
     */
    async expectStatusBar(name: string): Promise<void> {
        await expect(this.page).toHaveScreenshot(name, { clip: await this.statusBarClip(), maxDiffPixels: 0 });
    }

    /** Waits until the status bar looks different from [before] and returns the new picture. */
    async statusBarChanged(before: Buffer): Promise<Buffer> {
        let current = before;
        await expect.poll(async () => (current = await this.statusBar()).equals(before)).toBe(false);
        return current;
    }

    /** Waits until the status bar looks exactly like [expected]. */
    async statusBarMatches(expected: Buffer): Promise<void> {
        await expect.poll(async () => (await this.statusBar()).equals(expected)).toBe(true);
    }
}

export const test = base.extend<{ app: EditorApp }>({
    app: async ({ page }, use) => {
        await use(new EditorApp(page));
    },
});

export { expect };
