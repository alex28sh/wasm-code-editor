// Static file server for the unbundled distribution: `node serve.mjs <directory> [port]`.
//
// It also passes `/maven/<path>` on to Maven repositories, because the libraries panel of the editor downloads
// libraries from them and Maven Central does not allow pages of other sites to read its files (it sends no CORS
// headers: MVN-1 in docs/problem-log.md). `MAVEN_REPOSITORIES` is a comma-separated list of repository URLs and local directories, tried in
// order; by default Maven Central, then Google's repository.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';

const root = resolve(process.argv[2] ?? '../build/e2eDist');
const port = Number(process.argv[3] ?? 8787);
const repositories = (process.env.MAVEN_REPOSITORIES ?? 'https://repo1.maven.org/maven2,https://dl.google.com/dl/android/maven2')
    .split(',').map(entry => entry.trim()).filter(Boolean)
    .map(entry => /^https?:\/\//.test(entry) ? entry.replace(/\/$/, '') : resolve(entry));

const contentTypes = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'text/javascript',
    '.mjs': 'text/javascript',
    '.json': 'application/json',
    '.wasm': 'application/wasm',
    '.map': 'application/json',
    '.css': 'text/css',
    '.ttf': 'font/ttf',
};

// Files of a repository do not change once published, so each is downloaded once per server.
const downloaded = new Map();

/** The file at `path` of the first repository that has it, or null. */
async function fromRepositories(path) {
    if (downloaded.has(path)) return downloaded.get(path);
    for (const repository of repositories) {
        try {
            if (repository.startsWith('http')) {
                const answer = await fetch(`${repository}/${path}`);
                if (answer.ok) {
                    const content = Buffer.from(await answer.arrayBuffer());
                    // Version lists change when a version is published.
                    if (!path.endsWith('maven-metadata.xml')) downloaded.set(path, content);
                    return content;
                }
            } else {
                return await readFile(join(repository, path));
            }
        } catch {
            // Not in this repository, or it cannot be reached: try the next one.
        }
    }
    return null;
}

async function maven(path, response) {
    // Only the names a repository layout has: no `..`, no query, nothing to escape with.
    if (!/^[\w.+-]+(\/[\w.+-]+)*$/.test(path) || path.split('/').some(segment => segment === '..' || segment === '.')) {
        response.writeHead(400).end('Bad path');
        return;
    }
    const content = await fromRepositories(path);
    if (content === null) {
        response.writeHead(404).end('Not found');
        return;
    }
    response.writeHead(200, {
        'Content-Type': contentTypes[extname(path)] ?? 'application/octet-stream',
        'Cache-Control': 'no-store',
    });
    response.end(content);
}

createServer(async (request, response) => {
    let path;
    try {
        path = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
    } catch {
        response.writeHead(400).end('Bad path');
        return;
    }
    if (path.startsWith('/maven/')) {
        await maven(path.slice('/maven/'.length), response);
        return;
    }
    const file = join(root, normalize(path === '/' ? '/index.html' : path));
    if (!file.startsWith(root)) {
        response.writeHead(403).end();
        return;
    }
    try {
        const content = await readFile(file);
        response.writeHead(200, {
            'Content-Type': contentTypes[extname(file)] ?? 'application/octet-stream',
            'Cache-Control': 'no-store',
        });
        response.end(content);
    } catch {
        response.writeHead(404).end('Not found');
    }
}).listen(port, () => console.log(`Serving ${root} at http://localhost:${port}, Maven repositories: ${repositories.join(', ')}`));
