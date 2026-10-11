
// Appended by the build to the script GraalVM generates, which makes that script the worker itself: the generated
// part looks for its .wasm file next to the script it runs in.
// The image publishes its host as a global: the compiler or the analysis, whichever this script was built from.
// Messages are `{ id, operation, text, argument }` and are answered with `{ id, answer }`, the answer being the host's JSON.
if (typeof WorkerGlobalScope !== 'undefined' && self instanceof WorkerGlobalScope) {
    const startTimeout = 180_000;
    const host = () => globalThis.kotlinCompilerHost || globalThis.kotlinAnalysisHost;

    async function start() {
        const started = Date.now();
        while (!host()) {
            if (Date.now() - started > startTimeout) throw new Error('The Kotlin worker did not start');
            await new Promise(resolve => setTimeout(resolve, 20));
        }
        const response = await fetch(new URL('./kotlin-stdlib-wasm-js.bin', self.location.href));
        if (!response.ok) throw new Error(`Cannot load the standard library: ${response.status}`);
        const bytes = new Uint8Array(await response.arrayBuffer());
        let binary = '';
        for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
        const answer = JSON.parse(host().call('init', btoa(binary), ''));
        if (answer.error) throw new Error(answer.error);
    }

    const ready = start();
    // Reported once here; every request repeats it in its answer.
    ready.catch(error => console.error(error));

    self.onmessage = async ({ data }) => {
        let answer;
        try {
            await ready;
            answer = host().call(data.operation, data.text, data.argument || '');
        } catch (error) {
            answer = JSON.stringify({ error: String((error && error.stack) || error) });
        }
        self.postMessage({ id: data.id, answer });
    };
}
