// Static file server for the unbundled distribution: `node serve.mjs <directory> [port]`.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';

const root = resolve(process.argv[2] ?? '../build/e2eDist');
const port = Number(process.argv[3] ?? 8787);

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

createServer(async (request, response) => {
    const path = decodeURIComponent(new URL(request.url, 'http://localhost').pathname);
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
}).listen(port, () => console.log(`Serving ${root} at http://localhost:${port}`));
