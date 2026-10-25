import { deflateRawSync } from 'node:zlib';
import { expect, test } from '@playwright/test';

// The resolver of the libraries panel (kotlin-libraries.mjs) on its own, in Node, against repositories made up
// for each test: it is plain JavaScript that only needs `fetch`.

type Resolver = typeof import('../../app/src/wasmJsMain/resources/kotlin-libraries.mjs');

/** A Gradle module file with a wasm-js variant: a klib and dependencies, or a pointer to another module. */
function wasmModule(options: { klib?: string; dependencies?: string[]; availableAt?: string; platforms?: string[] }) {
    const attributes = (platform: string) => platform === 'wasm-js'
        ? { 'org.gradle.category': 'library', 'org.gradle.usage': 'kotlin-runtime', 'org.jetbrains.kotlin.platform.type': 'wasm', 'org.jetbrains.kotlin.wasm.target': 'js' }
        : { 'org.gradle.category': 'library', 'org.gradle.usage': 'java-runtime', 'org.jetbrains.kotlin.platform.type': platform };
    return JSON.stringify({
        formatVersion: '1.1',
        variants: (options.platforms ?? ['wasm-js']).map(platform => {
            const variant: Record<string, unknown> = { name: platform, attributes: attributes(platform) };
            if (platform !== 'wasm-js') return variant;
            if (options.availableAt) {
                const [group, module, version] = options.availableAt.split(':');
                variant['available-at'] = { url: `../../${module}/${version}/${module}-${version}.module`, group, module, version };
            } else {
                variant.files = options.klib ? [{ name: `${options.klib}.klib`, url: `${options.klib}.klib` }] : [];
                variant.dependencies = (options.dependencies ?? []).map(coordinates => {
                    const [group, module, version] = coordinates.split(':');
                    return { group, module, version: { requires: version } };
                });
            }
            return variant;
        }),
    });
}

/** Files of a repository by their path, `org/example/a/1.0/a-1.0.module`. */
type Repository = Record<string, string | Uint8Array>;

const modulePath = (coordinates: string, extension = 'module') => {
    const [group, module, version] = coordinates.split(':');
    return `${group.replaceAll('.', '/')}/${module}/${version}/${module}-${version}.${extension}`;
};

/** A repository of libraries that each have a klib named after them and the given dependencies. */
function libraries(graph: Record<string, string[]>): Repository {
    const repository: Repository = {};
    for (const [coordinates, dependencies] of Object.entries(graph)) {
        const [, module, version] = coordinates.split(':');
        repository[modulePath(coordinates)] = wasmModule({ klib: `${module}-${version}`, dependencies });
    }
    return repository;
}

let resolver: Resolver;
let requested: string[];

async function serve(repository: Repository) {
    requested = [];
    globalThis.fetch = (async (input: string | URL) => {
        const path = String(input).split('/maven/')[1];
        requested.push(path);
        const content = repository[path];
        return content === undefined ? new Response('Not found', { status: 404 }) : new Response(content);
    }) as typeof fetch;
    // A new module for each test: it keeps what it has downloaded.
    resolver = await import(`../../app/src/wasmJsMain/resources/kotlin-libraries.mjs?${Math.random()}`);
}

test.describe('resolving libraries', () => {
    test('a library and what it depends on, each after its dependencies', async () => {
        await serve(libraries({
            'org.example:app:1.0': ['org.example:http:2.0', 'org.example:json:1.0'],
            'org.example:http:2.0': ['org.example:io:1.0'],
            'org.example:json:1.0': ['org.example:io:1.0'],
            'org.example:io:1.0': [],
        }));

        const resolved = await resolver.resolveLibraries(['org.example:app:1.0']);

        expect(resolved.libraries).toEqual([{ coordinates: 'org.example:app:1.0', error: null, klibs: ['app-1.0', 'http-2.0', 'io-1.0', 'json-1.0'] }]);
        expect(resolved.klibs.map(klib => klib.name)).toEqual(['io-1.0', 'http-2.0', 'json-1.0', 'app-1.0']);
        expect(resolved.klibs[0]).toEqual({ id: 'org.example:io:1.0', name: 'io-1.0', url: expect.stringMatching(/\/maven\/org\/example\/io\/1\.0\/io-1\.0\.klib$/) });
    });

    test('the standard library is left out: the compiler brings its own', async () => {
        await serve(libraries({ 'org.example:app:1.0': ['org.jetbrains.kotlin:kotlin-stdlib:2.2.20'] }));

        const resolved = await resolver.resolveLibraries(['org.example:app:1.0']);

        expect(resolved.klibs.map(klib => klib.name)).toEqual(['app-1.0']);
        expect(requested.some(path => path.includes('kotlin-stdlib'))).toBe(false);
    });

    test('a library needed in two versions is taken in the newer one', async () => {
        await serve(libraries({
            'org.example:app:1.0': ['org.example:io:1.9.0', 'org.example:http:1.0'],
            'org.example:http:1.0': ['org.example:io:1.10.0'],
            'org.example:io:1.9.0': ['org.example:old-only:1.0'],
            'org.example:io:1.10.0': [],
            'org.example:old-only:1.0': [],
        }));

        const resolved = await resolver.resolveLibraries(['org.example:app:1.0']);

        expect(resolved.klibs.map(klib => klib.name)).toEqual(['io-1.10.0', 'http-1.0', 'app-1.0']);
        // What only the older version needed is not loaded.
        expect(resolved.libraries[0].klibs).not.toContain('old-only-1.0');
    });

    test('two libraries listed by the user share their dependencies', async () => {
        await serve(libraries({
            'org.example:a:1.0': ['org.example:io:1.0'],
            'org.example:b:1.0': ['org.example:io:2.0'],
            'org.example:io:1.0': [],
            'org.example:io:2.0': [],
        }));

        const resolved = await resolver.resolveLibraries(['org.example:a:1.0', 'org.example:b:1.0']);

        expect(resolved.klibs.map(klib => klib.name)).toEqual(['io-2.0', 'a-1.0', 'b-1.0']);
        expect(resolved.libraries.map(library => library.klibs)).toEqual([['a-1.0', 'io-2.0'], ['b-1.0', 'io-2.0']]);
    });

    test('a library published as a root module and platform modules is followed to its wasm-js module', async () => {
        await serve({
            ...libraries({ 'org.example:kit-wasm-js:3.0': ['org.example:base:1.0'], 'org.example:base-wasm-js:1.0': [] }),
            [modulePath('org.example:kit:3.0')]: wasmModule({ availableAt: 'org.example:kit-wasm-js:3.0', platforms: ['jvm', 'wasm-js'] }),
            [modulePath('org.example:base:1.0')]: wasmModule({ availableAt: 'org.example:base-wasm-js:1.0' }),
        });

        const resolved = await resolver.resolveLibraries(['org.example:kit:3.0']);

        expect(resolved.libraries[0]).toEqual({ coordinates: 'org.example:kit:3.0', error: null, klibs: ['kit-wasm-js-3.0', 'base-wasm-js-1.0'] });
        expect(resolved.klibs.map(klib => klib.id)).toEqual(['org.example:base-wasm-js:1.0', 'org.example:kit-wasm-js:3.0']);
    });

    test('without a version, the latest release is taken', async () => {
        await serve({
            ...libraries({ 'org.example:app:2.1': [] }),
            'org/example/app/maven-metadata.xml': '<metadata><versioning><latest>2.2-SNAPSHOT</latest><release>2.1</release></versioning></metadata>',
        });

        const resolved = await resolver.resolveLibraries(['org.example:app']);

        expect(resolved.libraries).toEqual([{ coordinates: 'org.example:app', error: null, klibs: ['app-2.1'] }]);
    });

    test('a failing library is reported and the others still load', async () => {
        await serve({
            ...libraries({ 'org.example:good:1.0': [] }),
            [modulePath('org.example:jvm-only:1.0')]: wasmModule({ platforms: ['jvm', 'androidJvm'] }),
            [modulePath('org.example:plain:1.0', 'pom')]: '<project/>',
        });

        const resolved = await resolver.resolveLibraries([
            'org.example:missing:1.0', 'org.example:jvm-only:1.0', 'org.example:plain:1.0', 'not coordinates', 'org.example:good:1.0',
        ]);

        expect(resolved.libraries.map(library => library.error)).toEqual([
            'org.example:missing:1.0 is not in the repository',
            'org.example:jvm-only:1.0 is not built for Kotlin/Wasm in the browser (wasm-js); it is built for jvm, androidJvm',
            'org.example:plain:1.0 has no Gradle module metadata, so it is not a Kotlin Multiplatform library',
            "'not coordinates' is not group:artifact:version",
            null,
        ]);
        expect(resolved.klibs.map(klib => klib.name)).toEqual(['good-1.0']);
    });

    test('a library whose dependency cannot be found fails, naming the dependency', async () => {
        await serve(libraries({ 'org.example:app:1.0': ['org.example:gone:1.0'] }));

        const resolved = await resolver.resolveLibraries(['org.example:app:1.0']);

        expect(resolved.libraries[0].error).toBe('It needs org.example:gone:1.0: org.example:gone:1.0 is not in the repository');
        expect(resolved.klibs).toEqual([]);
    });

    test('a cycle of dependencies does not hang', async () => {
        await serve(libraries({ 'org.example:a:1.0': ['org.example:b:1.0'], 'org.example:b:1.0': ['org.example:a:1.0'] }));

        const resolved = await resolver.resolveLibraries(['org.example:a:1.0']);

        expect(resolved.klibs.map(klib => klib.name).sort()).toEqual(['a-1.0', 'b-1.0']);
    });

    test('each file is downloaded once', async () => {
        await serve(libraries({ 'org.example:a:1.0': ['org.example:io:1.0'], 'org.example:b:1.0': ['org.example:io:1.0'], 'org.example:io:1.0': [] }));

        await resolver.resolveLibraries(['org.example:a:1.0', 'org.example:b:1.0']);
        await resolver.resolveLibraries(['org.example:a:1.0', 'org.example:b:1.0']);

        expect(requested.filter(path => path.includes('/io/'))).toHaveLength(1);
    });
});

test.describe('versions', () => {
    test('are compared by their numbers, with a qualifier before the release', async () => {
        await serve({});
        const sorted = ['1.10.0', '1.9.0', '1.10.0-RC', '2.0', '1.9.10', '1.10.0-beta2'].sort(resolver.compareVersions);

        expect(sorted).toEqual(['1.9.0', '1.9.10', '1.10.0-RC', '1.10.0-beta2', '1.10.0', '2.0']);
    });

    test('coordinates are group:artifact with an optional version', async () => {
        await serve({});

        expect(resolver.parseCoordinates(' org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0 ')).toEqual({ group: 'org.jetbrains.kotlinx', module: 'kotlinx-coroutines-core', version: '1.11.0' });
        expect(resolver.parseCoordinates('org.example:app')).toEqual({ group: 'org.example', module: 'app', version: null });
        expect(resolver.parseCoordinates('app')).toBeNull();
        expect(resolver.parseCoordinates('org.example:app:1.0:extra')).toBeNull();
        expect(resolver.parseCoordinates('../../etc:passwd:1')).toBeNull();
    });
});

/** A zip with the given files, the first stored and the others deflated, as klibs mix both. */
function zip(files: Record<string, string>): Uint8Array {
    const local: Buffer[] = [];
    const central: Buffer[] = [];
    let offset = 0;
    Object.entries(files).forEach(([name, text], index) => {
        const stored = index === 0;
        const raw = Buffer.from(text);
        const data = stored ? raw : deflateRawSync(raw);
        const nameBytes = Buffer.from(name);
        const header = Buffer.alloc(30);
        header.writeUInt32LE(0x04034b50, 0);
        header.writeUInt16LE(stored ? 0 : 8, 8);
        header.writeUInt32LE(data.length, 18);
        header.writeUInt32LE(raw.length, 22);
        header.writeUInt16LE(nameBytes.length, 26);
        local.push(header, nameBytes, data);
        const entry = Buffer.alloc(46);
        entry.writeUInt32LE(0x02014b50, 0);
        entry.writeUInt16LE(stored ? 0 : 8, 10);
        entry.writeUInt32LE(data.length, 20);
        entry.writeUInt32LE(raw.length, 24);
        entry.writeUInt16LE(nameBytes.length, 28);
        entry.writeUInt32LE(offset, 42);
        central.push(entry, nameBytes);
        offset += 30 + nameBytes.length + data.length;
    });
    const directory = Buffer.concat(central);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50, 0);
    end.writeUInt16LE(Object.keys(files).length, 8);
    end.writeUInt16LE(Object.keys(files).length, 10);
    end.writeUInt32LE(directory.length, 12);
    end.writeUInt32LE(offset, 16);
    return new Uint8Array(Buffer.concat([...local, directory, end]));
}

/** Reads the archive back the way the compiler hosts do (DataInputStream). */
function readArchive(bytes: Uint8Array): Record<string, string> {
    const buffer = Buffer.from(bytes);
    const files: Record<string, string> = {};
    let position = 4;
    for (let index = 0; index < buffer.readInt32BE(0); index++) {
        const length = buffer.readUInt16BE(position);
        const name = buffer.subarray(position + 2, position + 2 + length).toString('utf8');
        const size = buffer.readInt32BE(position + 2 + length);
        files[name] = buffer.subarray(position + 6 + length, position + 6 + length + size).toString('utf8');
        position += 6 + length + size;
    }
    return files;
}

test.describe('packing klibs', () => {
    test('unpacks stored and deflated entries and skips directories', async () => {
        await serve({});
        const files = await resolver.unzip(zip({ 'default/manifest': 'abi=1', 'default/ir/bodies.knb': 'x'.repeat(5000), 'default/linkdata/': '' }));

        expect(files.map(file => [file.name, Buffer.from(file.bytes).toString('utf8').length])).toEqual([
            ['default/manifest', 5],
            ['default/ir/bodies.knb', 5000],
        ]);
    });

    test('puts every klib under a directory of its own, in order', async () => {
        await serve({
            [modulePath('org.example:io:1.0')]: wasmModule({ klib: 'io-1.0' }),
            [modulePath('org.example:app:1.0')]: wasmModule({ klib: 'app-1.0', dependencies: ['org.example:io:1.0'] }),
            'org/example/io/1.0/io-1.0.klib': zip({ 'default/manifest': 'io', 'default/ir/ü.knm': 'non-ASCII name' }),
            'org/example/app/1.0/app-1.0.klib': zip({ 'default/manifest': 'app' }),
        });
        const { klibs } = await resolver.resolveLibraries(['org.example:app:1.0']);

        const archive = readArchive(await resolver.packLibraries(klibs));

        expect(archive).toEqual({
            '0-io-1.0/default/manifest': 'io',
            '0-io-1.0/default/ir/ü.knm': 'non-ASCII name',
            '1-app-1.0/default/manifest': 'app',
        });
    });

    test('a klib that cannot be downloaded fails the libraries that need it', async () => {
        await serve(libraries({ 'org.example:app:1.0': [] }));

        const answer = JSON.parse(await resolver.prepareLibraries(JSON.stringify(['org.example:app:1.0'])));

        expect(answer).toEqual({ libraries: [{ coordinates: 'org.example:app:1.0', error: 'The klib of org.example:app:1.0 is not in the repository', klibs: ['app-1.0'] }], archive: '' });
    });

    test('nothing listed is nothing to load', async () => {
        await serve({});

        expect(JSON.parse(await resolver.prepareLibraries('[]'))).toEqual({ libraries: [], archive: '' });
        expect(requested).toEqual([]);
    });
});
