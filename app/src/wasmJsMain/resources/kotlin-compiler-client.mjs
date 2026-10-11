// The page's side of the Kotlin compiler and of the Analysis API: each runs in a worker of its own (compiler-host
// builds them), and what is compiled runs in a third one, so that none of them can block or break the editor.

/**
 * Starts the worker `kotlin-<name>-worker.js`: `compiler` or `analysis`.
 * `request(operation, text, argument)` resolves to the JSON the worker's host answers with.
 */
export function createKotlinService(name) {
    const worker = new Worker(new URL(`./kotlin-${name}-worker.js`, import.meta.url));
    const pending = new Map();
    let nextId = 1;
    let failure = null;

    function failAll(message) {
        failure = JSON.stringify({ error: message });
        for (const resolve of pending.values()) resolve(failure);
        pending.clear();
    }

    worker.onmessage = ({ data }) => {
        const resolve = pending.get(data.id);
        pending.delete(data.id);
        if (resolve) resolve(data.answer);
    };
    worker.onerror = event => failAll(event.message || `The Kotlin ${name} worker failed to load`);

    return {
        request(operation, text, argument) {
            if (failure) return Promise.resolve(failure);
            return new Promise(resolve => {
                const id = nextId++;
                pending.set(id, resolve);
                worker.postMessage({ id, operation, text, argument });
            });
        },
    };
}

function decode(base64) {
    return Uint8Array.from(atob(base64), char => char.charCodeAt(0));
}

/**
 * Runs what `compile` answered with. Every line the program prints goes to `onOutput`;
 * the promise resolves to an empty string, or to the reason the program did not finish.
 */
export function runKotlinProgram(compiled, onOutput, timeoutMilliseconds) {
    const files = new Map(JSON.parse(compiled).files.map(file => [file.name, decode(file.content)]));
    const urls = [];
    const url = (content, type) => {
        const created = URL.createObjectURL(new Blob([content], { type }));
        urls.push(created);
        return created;
    };
    const script = (name, replacements) => {
        let text = new TextDecoder().decode(files.get(name));
        for (const [from, to] of replacements) {
            if (!text.includes(from)) throw new Error(`Unexpected shape of ${name}: no ${from}`);
            text = text.replaceAll(from, to);
        }
        return url(text, 'text/javascript');
    };

    // The generated modules import each other by relative paths, which object URLs do not have.
    let runner;
    try {
        const wasm = url(files.get('main.wasm'), 'application/wasm');
        const builtins = script('main.js-builtins.mjs', []);
        const imports = script('main.import-object.mjs', [["'./main.js-builtins.mjs'", `'${builtins}'`]]);
        const main = script('main.mjs', [
            ["'./main.import-object.mjs'", `'${imports}'`],
            ["new URL('./main.wasm',import.meta.url).href", `'${wasm}'`],
        ]);
        runner = url(
            `for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
                 console[level] = (...values) => postMessage({ output: values.map(String).join(' ') });
             }
             try {
                 await import(${JSON.stringify(main)});
                 postMessage({ done: true, error: '' });
             } catch (error) {
                 postMessage({ done: true, error: String((error && error.stack) || error) });
             }`,
            'text/javascript',
        );
    } catch (error) {
        urls.forEach(URL.revokeObjectURL);
        return Promise.resolve(String(error));
    }

    return new Promise(resolve => {
        const worker = new Worker(runner, { type: 'module' });
        const finish = error => {
            clearTimeout(timer);
            worker.terminate();
            urls.forEach(URL.revokeObjectURL);
            resolve(error);
        };
        const timer = setTimeout(() => finish(`Stopped after ${timeoutMilliseconds / 1000} seconds`), timeoutMilliseconds);
        worker.onmessage = ({ data }) => {
            if (data.done) finish(data.error);
            else onOutput(data.output);
        };
        worker.onerror = event => finish(event.message || 'The program failed to start');
    });
}
