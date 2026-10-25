import { defineConfig } from '@playwright/test';

const port = Number(process.env.E2E_PORT ?? 8787);
const defaultChromeArgs = '--use-angle=gl --enable-gpu --ignore-gpu-blocklist --enable-unsafe-swiftshader';

export default defineConfig({
    testDir: './tests',
    timeout: 60_000,
    fullyParallel: true,
    // Every page compiles some thirty Wasm modules and renders through WebGL, so more pages than this slow each other down.
    workers: 6,
    reporter: [
        ['list'],
        ['html', { open: 'never' }],
        ['json', { outputFile: 'playwright-report/results.json' }],
    ],
    use: {
        baseURL: `http://localhost:${port}`,
        // The installed Chrome. Compose draws through WebGL: the GPU is used when there is one, SwiftShader otherwise.
        // SwiftShader miscomposites CodeMirror's sticky gutter over the status bar when a line overflows,
        // so baselines made with it differ from what users see.
        // E2E_CHROME_ARGS replaces the flags.
        channel: 'chrome',
        launchOptions: { args: (process.env.E2E_CHROME_ARGS ?? defaultChromeArgs).split(' ') },
        viewport: { width: 1280, height: 800 },
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure',
    },
    expect: {
        // Rendering is deterministic on one machine; a changed word in the status bar is only a few hundred pixels.
        toHaveScreenshot: { maxDiffPixels: 20 },
    },
    snapshotPathTemplate: '{testDir}/__screenshots__/{testFileName}/{arg}{ext}',
    webServer: {
        command: `node serve.mjs ../build/e2eDist ${port}`,
        // Libraries come from a repository the build assembles, not from the network (see e2eMaven).
        env: { MAVEN_REPOSITORIES: '../build/e2eMaven' },
        url: `http://localhost:${port}/plugins.json`,
        reuseExistingServer: !process.env.CI,
    },
});
