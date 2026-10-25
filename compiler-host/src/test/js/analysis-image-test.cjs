// Drives the Analysis API image under Node the way the browser worker does:
// `node analysis-image-test.cjs <compilerDist> <library archive>`.
// Exits with an error when an answer is not the expected one.
const fs = require('node:fs');
const path = require('node:path');

const dist = path.resolve(process.argv[2]);
require(path.join(dist, 'kotlin-analysis-worker.js'));
const archive = fs.readFileSync(path.join(dist, 'kotlin-stdlib-wasm-js.bin')).toString('base64');

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

const members = `
class Box(val label: String) {
    fun describe(): String = "box of " + label
}

fun main() {
    val box = Box("apples")
    println(box.de)
    println("text".le)
}
`;

const coroutines = `
import kotlinx.coroutines.*

suspend fun main() = coroutineScope {
    launch { del }
    Job().can
}
`;

const after = (text, marker) => String(text.indexOf(marker) + marker.length);

function request(title, operation, first, second, expected) {
    const started = Date.now();
    const answer = globalThis.kotlinAnalysisHost.call(operation, first, second);
    console.log(`${title}: ${Date.now() - started} ms: ${answer.slice(0, 260).replace(/\\n\\t/g, '\n    ')}`);
    if (!answer.includes(expected)) throw new Error(`${title}: expected ${expected} in ${answer.slice(0, 6000).replace(/\\n\\t/g, '\n    ')}`);
}

function run() {
    request('init', 'init', archive, '', '"files"');
    request('analyze', 'analyze', hello, '', '"diagnostics":[]');
    request('analyze with errors', 'analyze', broken, '', 'Unresolved reference');
    request('complete members', 'complete', members, after(members, 'box.de'), '"label":"describe"');
    request('complete library members', 'complete', members, after(members, '"text".le'), '"label":"length"');
    request('complete in scope', 'complete', hello, after(hello, 'println(gre'), '"label":"greet"');
    request('complete from the library', 'complete', hello, after(hello, '    print'), '"label":"println"');
    const shapes = fs.readFileSync(path.join(__dirname, '../../scenario/shapes.kt'), 'utf8');
    request('analyze a larger program', 'analyze', shapes, '', '"diagnostics":[]');
    request('complete an enum entry', 'complete', shapes, after(shapes, 'Scale.MET'), '"label":"METER"');
    // What the library keeps to itself is not offered.
    const answer = globalThis.kotlinAnalysisHost.call('complete', hello, after(hello, '    print'));
    if (answer.includes('printlnImpl')) throw new Error(`An internal function is offered: ${answer.slice(0, 2000)}`);

    // A library the user added, and then removed again.
    const libraries = fs.readFileSync(path.resolve(process.argv[3])).toString('base64');
    request('analyze without the library', 'analyze', coroutines, '', "Unresolved reference 'kotlinx'");
    request('libraries', 'libraries', libraries, '', '"libraries":2');
    request('analyze with the library', 'analyze', coroutines, '', "Unresolved reference 'can'");
    request('complete a library function', 'complete', coroutines, after(coroutines, 'del'), '"label":"delay"');
    request('complete a library member', 'complete', coroutines, after(coroutines, 'Job().can'), '"label":"cancel"');
    request('no libraries', 'libraries', '', '', '"libraries":0');
    request('analyze after removing the library', 'analyze', coroutines, '', "Unresolved reference 'kotlinx'");
}

function whenReady(attempt) {
    if (globalThis.kotlinAnalysisHost) {
        run();
        process.exit(0);
    }
    if (attempt > 3000) throw new Error('The analysis host did not start');
    setTimeout(() => whenReady(attempt + 1), 10);
}

whenReady(0);
