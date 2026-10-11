// Drives the WebAssembly image under Node the way the browser worker does: `node image-test.cjs <compilerDist>`.
// Exits with an error when the compiler does not answer as expected or its output does not run.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const dist = path.resolve(process.argv[2]);
require(path.join(dist, 'kotlin-compiler-worker.js'));

const hello = `
fun greet(name: String): String = "Hello, $name!"

fun main() {
    println(greet("Wasm"))
}
`;

const broken = `
fun main() {
    val count: Int = "three"
    println(missing(count))
}
`;

function request(title, operation, first, expected) {
    const started = Date.now();
    const answer = globalThis.kotlinCompilerHost.call(operation, first, '');
    console.log(`${title}: ${Date.now() - started} ms: ${answer.slice(0, 200)}`);
    if (!answer.includes(expected)) throw new Error(`${title}: expected ${expected} in ${answer.slice(0, 3000)}`);
    return JSON.parse(answer);
}

function run() {
    request('init', 'init', fs.readFileSync(path.join(dist, 'kotlin-stdlib-wasm-js.bin')).toString('base64'), '"files"');
    request('compile with errors', 'compile', broken, "Unresolved reference 'missing'");
    const compiled = request('compile', 'compile', hello, '"ok":true');
    request('compile again', 'compile', hello.replace('Wasm', 'again'), '"ok":true');
    // A program with most kinds of declarations and expressions, which the image was not necessarily built to handle.
    request('compile a larger program', 'compile', fs.readFileSync(path.join(__dirname, '../../scenario/shapes.kt'), 'utf8'), '"ok":true');

    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'compiler-host-'));
    for (const file of compiled.files) fs.writeFileSync(path.join(directory, file.name), Buffer.from(file.content, 'base64'));
    const output = execFileSync(process.execPath, [path.join(directory, 'main.mjs')], { encoding: 'utf8' });
    console.log(`program output: ${output.trim()}`);
    if (output.trim() !== 'Hello, Wasm!') throw new Error(`Unexpected program output: ${output}`);
}

function whenReady(attempt) {
    if (globalThis.kotlinCompilerHost) {
        run();
        process.exit(0);
    }
    if (attempt > 3000) throw new Error('The compiler host did not start');
    setTimeout(() => whenReady(attempt + 1), 10);
}

whenReady(0);
