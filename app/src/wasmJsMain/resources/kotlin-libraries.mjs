// Libraries for the Kotlin compiler in the page, from a Maven repository.
//
// The user names a library by its Maven coordinates. Its Gradle module metadata (the `.module` file every Kotlin
// Multiplatform library publishes) tells which variant is for Kotlin/Wasm in the browser, where that variant's
// klib is and what it depends on. The klibs are zips, which the compiler in the page cannot open, so they are
// unpacked here and sent to it as one archive (see `libraries` in compiler-host/README.md).
//
// The repository is `./maven/` of the page: Maven Central does not let pages of other sites read its files,
// so the server of the page passes the requests on (e2e/serve.mjs).

const repository = new URL('./maven/', import.meta.url);

// The standard library comes with the compiler, in the compiler's version.
const provided = new Set(['org.jetbrains.kotlin:kotlin-stdlib', 'org.jetbrains.kotlin:kotlin-stdlib-wasm-js']);

const coordinatesPattern = /^([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::([A-Za-z0-9_.+-]+))?$/;

/** `group:artifact:version`, or `group:artifact` for the latest release; null when the text is neither. */
export function parseCoordinates(text) {
    const match = coordinatesPattern.exec(text.trim());
    return match ? { group: match[1], module: match[2], version: match[3] ?? null } : null;
}

const key = ({ group, module }) => `${group}:${module}`;
const id = coordinates => `${key(coordinates)}:${coordinates.version}`;
const directory = ({ group, module, version }) => `${group.replaceAll('.', '/')}/${module}/${version}/`;

async function download(path, what) {
    let response;
    try {
        response = await fetch(new URL(path, repository));
    } catch (error) {
        throw new Error(`Cannot download ${what}: ${error.message}`);
    }
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`Cannot download ${what}: HTTP ${response.status}`);
    return response;
}

/** The version a repository lists as the latest release. */
async function latestRelease(coordinates) {
    const response = await download(`${coordinates.group.replaceAll('.', '/')}/${coordinates.module}/maven-metadata.xml`, `the versions of ${key(coordinates)}`);
    if (!response) throw new Error(`${key(coordinates)} is not in the repository`);
    const text = await response.text();
    const version = /<release>([^<]+)<\/release>/.exec(text)?.[1] ?? /<latest>([^<]+)<\/latest>/.exec(text)?.[1];
    if (!version) throw new Error(`The repository lists no versions of ${key(coordinates)}`);
    return version;
}

const isWasmJs = attributes =>
    attributes['org.jetbrains.kotlin.platform.type'] === 'wasm' &&
    attributes['org.jetbrains.kotlin.wasm.target'] === 'js' &&
    attributes['org.gradle.usage'] === 'kotlin-runtime' &&
    (attributes['org.gradle.category'] ?? 'library') === 'library';

/** What a platform is called in an error message: `jvm`, `js`, `wasm-wasi`. */
const platformOf = attributes => {
    const platform = attributes['org.jetbrains.kotlin.platform.type'];
    if (!platform) return null;
    const target = attributes['org.jetbrains.kotlin.wasm.target'];
    return target ? `${platform}-${target}` : platform;
};

const versionOf = version => version?.strictly ?? version?.requires ?? version?.prefers ?? null;

// Published files never change, so a page asks for each one once.
const components = new Map();
const klibs = new Map();

/**
 * The wasm-js variant of a library: `{ id, group, module, version, name, url, dependencies }`.
 * A library that publishes its platforms as modules of their own leads to the module of this platform.
 */
function component(coordinates) {
    const cached = components.get(id(coordinates));
    if (cached) return cached;
    const loaded = (async () => {
        const base = directory(coordinates);
        const response = await download(`${base}${coordinates.module}-${coordinates.version}.module`, id(coordinates));
        if (!response) {
            const pom = await download(`${base}${coordinates.module}-${coordinates.version}.pom`, id(coordinates));
            if (!pom) throw new Error(`${id(coordinates)} is not in the repository`);
            throw new Error(`${id(coordinates)} has no Gradle module metadata, so it is not a Kotlin Multiplatform library`);
        }
        const metadata = await response.json();
        const variants = metadata.variants ?? [];
        const variant = variants.find(candidate => isWasmJs(candidate.attributes ?? {}));
        if (!variant) {
            const platforms = [...new Set(variants.map(candidate => platformOf(candidate.attributes ?? {})).filter(Boolean))];
            throw new Error(
                `${id(coordinates)} is not built for Kotlin/Wasm in the browser (wasm-js)` +
                (platforms.length > 0 ? `; it is built for ${platforms.join(', ')}` : ''),
            );
        }
        const elsewhere = variant['available-at'];
        if (elsewhere) return component({ group: elsewhere.group, module: elsewhere.module, version: elsewhere.version });
        const file = (variant.files ?? []).find(candidate => candidate.name.endsWith('.klib'));
        if (!file) throw new Error(`The wasm-js variant of ${id(coordinates)} has no klib`);
        return {
            id: id(coordinates),
            ...coordinates,
            name: file.name.replace(/\.klib$/, ''),
            url: new URL(file.url, new URL(base, repository)).href,
            dependencies: (variant.dependencies ?? [])
                .map(dependency => ({ group: dependency.group, module: dependency.module, version: versionOf(dependency.version) }))
                .filter(dependency => !provided.has(key(dependency)) && dependency.version),
        };
    })();
    components.set(id(coordinates), loaded);
    loaded.catch(() => components.delete(id(coordinates)));
    return loaded;
}

/** Compares versions the way they are usually written: `1.10.2` is newer than `1.9.0`, and `1.0` than `1.0-RC`. */
export function compareVersions(first, second) {
    const parts = version => version.split(/[.+-]/);
    const [a, b] = [parts(first), parts(second)];
    for (let index = 0; index < Math.max(a.length, b.length); index++) {
        const [x, y] = [a[index], b[index]];
        if (x === undefined) return /^\d+$/.test(y) ? -1 : 1;
        if (y === undefined) return /^\d+$/.test(x) ? 1 : -1;
        const [m, n] = [Number(x), Number(y)];
        if (!Number.isNaN(m) && !Number.isNaN(n)) {
            if (m !== n) return m - n;
        } else if (x !== y) {
            if (!Number.isNaN(m)) return 1;
            if (!Number.isNaN(n)) return -1;
            return x < y ? -1 : 1;
        }
    }
    return 0;
}

/**
 * Resolves the libraries named by `requested`, an array of coordinates, with everything they depend on.
 *
 * As Gradle does, a library that is needed in several versions is taken in the newest of them.
 * Resolves to `{ libraries: [{ coordinates, error, klibs }], klibs: [{ id, name, url }] }`: what became of each
 * request, by the names of its klibs, and every klib to load, each after those it depends on. A request that fails
 * leaves the others alone.
 */
export async function resolveLibraries(requested) {
    const roots = [];
    const libraries = [];
    for (const text of requested) {
        const entry = { coordinates: text, error: null, klibs: [] };
        libraries.push(entry);
        try {
            const coordinates = parseCoordinates(text);
            if (!coordinates) throw new Error(`'${text}' is not group:artifact:version`);
            if (!coordinates.version) coordinates.version = await latestRelease(coordinates);
            roots.push({ entry, root: await component(coordinates) });
        } catch (error) {
            entry.error = error.message;
        }
    }

    // The newest version anything asks for, until no newly found dependency asks for a newer one.
    // A dependency is usually named by its root module (`atomicfu`), which leads to its platform module
    // (`atomicfu-wasm-js`); versions are compared between platform modules.
    const selected = new Map();
    const platformModule = new Map();
    const selectedFor = dependency => selected.get(platformModule.get(key(dependency)) ?? key(dependency));
    const select = found => {
        const current = selected.get(key(found));
        if (!current || compareVersions(found.version, current.version) > 0) selected.set(key(found), found);
    };
    roots.forEach(({ root }) => select(root));
    const failures = new Map();
    for (let changed = true; changed;) {
        changed = false;
        for (const found of [...selected.values()]) {
            for (const dependency of found.dependencies) {
                const current = selectedFor(dependency);
                if (current && compareVersions(current.version, dependency.version) >= 0) continue;
                try {
                    const resolved = await component(dependency);
                    platformModule.set(key(dependency), key(resolved));
                    const before = selected.get(key(resolved));
                    select(resolved);
                    if (selected.get(key(resolved)) !== before) changed = true;
                } catch (error) {
                    failures.set(key(dependency), error.message);
                }
            }
        }
    }

    // Each klib after its dependencies; a library is only what can be reached from what was asked for.
    const ordered = [];
    const visit = (found, trail) => {
        if (ordered.includes(found) || trail.has(found)) return;
        trail.add(found);
        for (const dependency of found.dependencies) {
            const resolved = selectedFor(dependency);
            if (resolved) visit(resolved, trail);
        }
        trail.delete(found);
        ordered.push(found);
    };
    for (const { entry, root } of roots) {
        const reached = [];
        const collect = found => {
            if (reached.includes(found)) return;
            reached.push(found);
            for (const dependency of found.dependencies) {
                const resolved = selectedFor(dependency);
                if (resolved) collect(resolved);
                else if (failures.has(key(dependency))) entry.error ??= `It needs ${id(dependency)}: ${failures.get(key(dependency))}`;
            }
        };
        collect(selected.get(key(root)));
        if (entry.error) continue;
        reached.forEach(found => visit(found, new Set()));
        entry.klibs = reached.map(found => found.name);
    }
    const used = new Set(libraries.flatMap(entry => entry.klibs));
    return {
        libraries,
        klibs: ordered.filter(found => used.has(found.name)).map(({ id, name, url }) => ({ id, name, url })),
    };
}

/** The files of a zip: `[{ name, bytes }]`. Klibs are plain zips: no ZIP64, entries stored or deflated. */
export async function unzip(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    let end = bytes.length - 22;
    while (end >= 0 && view.getUint32(end, true) !== 0x06054b50) end--;
    if (end < 0) throw new Error('Not a zip file');
    const count = view.getUint16(end + 10, true);
    let entry = view.getUint32(end + 16, true);
    const names = new TextDecoder();
    const files = [];
    for (let index = 0; index < count; index++) {
        if (view.getUint32(entry, true) !== 0x02014b50) throw new Error('Broken zip file');
        const method = view.getUint16(entry + 10, true);
        const compressedSize = view.getUint32(entry + 20, true);
        const nameLength = view.getUint16(entry + 28, true);
        const extraLength = view.getUint16(entry + 30, true);
        const commentLength = view.getUint16(entry + 32, true);
        const local = view.getUint32(entry + 42, true);
        const name = names.decode(bytes.subarray(entry + 46, entry + 46 + nameLength));
        entry += 46 + nameLength + extraLength + commentLength;
        if (name.endsWith('/')) continue;
        const start = local + 30 + view.getUint16(local + 26, true) + view.getUint16(local + 28, true);
        const data = bytes.subarray(start, start + compressedSize);
        if (method === 0) {
            files.push({ name, bytes: data });
        } else if (method === 8) {
            const inflated = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
            files.push({ name, bytes: new Uint8Array(await new Response(inflated).arrayBuffer()) });
        } else {
            throw new Error(`Unsupported compression in ${name}`);
        }
    }
    return files;
}

/** The bytes of `text` the way Java's `DataOutputStream.writeUTF` writes them, length first. */
function modifiedUtf8(text) {
    const bytes = [];
    for (let index = 0; index < text.length; index++) {
        const code = text.charCodeAt(index);
        if (code >= 0x01 && code <= 0x7f) bytes.push(code);
        else if (code <= 0x7ff) bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
        else bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    }
    if (bytes.length > 0xffff) throw new Error(`Name too long: ${text.slice(0, 80)}`);
    return [bytes.length >> 8, bytes.length & 0xff, ...bytes];
}

/**
 * The archive the compiler hosts read: the number of files, then the name, the size and the bytes of each,
 * big-endian as Java's `DataInputStream` reads them. Each klib is a directory of its own, in the given order.
 */
export async function packLibraries(found) {
    const entries = [];
    for (const [index, klib] of found.entries()) {
        let bytes = klibs.get(klib.url);
        if (!bytes) {
            const response = await download(new URL(klib.url).href, klib.id);
            if (!response) throw new Error(`The klib of ${klib.id} is not in the repository`);
            bytes = new Uint8Array(await response.arrayBuffer());
            klibs.set(klib.url, bytes);
        }
        for (const file of await unzip(bytes)) entries.push({ name: modifiedUtf8(`${index}-${klib.name}/${file.name}`), bytes: file.bytes });
    }
    const size = 4 + entries.reduce((total, entry) => total + entry.name.length + 4 + entry.bytes.length, 0);
    const archive = new Uint8Array(size);
    const view = new DataView(archive.buffer);
    view.setInt32(0, entries.length);
    let position = 4;
    for (const entry of entries) {
        archive.set(entry.name, position);
        position += entry.name.length;
        view.setInt32(position, entry.bytes.length);
        archive.set(entry.bytes, position + 4);
        position += 4 + entry.bytes.length;
    }
    return archive;
}

function base64(bytes) {
    let binary = '';
    for (let index = 0; index < bytes.length; index += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(index, index + 0x8000));
    return btoa(binary);
}

/**
 * Resolves and downloads the libraries in `requestedJson`, a JSON array of coordinates.
 * Resolves to JSON: `{ libraries: [{ coordinates, error, klibs }], archive }`, the archive in Base64 and empty
 * when there is nothing to load. Libraries that fail are left out of it.
 */
export async function prepareLibraries(requestedJson) {
    const { libraries, klibs: found } = await resolveLibraries(JSON.parse(requestedJson));
    let archive = '';
    if (found.length > 0) {
        try {
            archive = base64(await packLibraries(found));
        } catch (error) {
            for (const entry of libraries) if (!entry.error) entry.error = error.message;
        }
    }
    return JSON.stringify({ libraries, archive });
}
