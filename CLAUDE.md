# Working in this repository

What the application does is in [`README.md`](README.md); how the compiler is built into WebAssembly is in
[`compiler-host/README.md`](compiler-host/README.md); how the browser tests work is in [`e2e/README.md`](e2e/README.md).

## Log the problems of the tools

Whenever a compiler, a runtime, a library, a build tool or a test tool behaves wrongly or surprisingly, add an
entry to [`docs/problem-log.md`](docs/problem-log.md) **in the same change** as the workaround. This covers Kotlin
(compiler, Analysis API, standard library, Gradle plugin), GraalVM, the IntelliJ platform, Compose and Skiko,
CodeMirror, Playwright, Gradle and the browser.

It applies when:

- something had to be patched, stubbed, replaced or configured away;
- an error message was misleading, or the same code behaved differently on the JVM and in the image, or in
  headless and in a desktop browser;
- a test had to wait, retry or be written around a tool;
- a problem was seen but neither reproduced nor fixed: log it as open, with what was tried.

The format and the rules are at the end of the log. In short:

- the date and the versions;
- the exact message with the top frames of the stack trace;
- the code that triggers the problem, quoted with its path from the sources of that version or from `javap`,
  or a reproducer of a few lines. Never quote from memory; when the code could not be found, say so;
- how sure the cause is: verified, and how, or a guess;
- what the project does about it and where.

Do not delete an entry when it is resolved; mark it.

Bugs of this project's own code do not go to the log: fix them and add a test.

Say in the final message of the task which entries were added or changed.

## Checks

- `./gradlew :compiler-host:imageTest` after a change in `compiler-host` (needs `graalvm.home` in `local.properties`).
- `./gradlew e2eTest` after a change in the application. When port 8787 is taken or another run is going, see
  `PW-1` and `PW-2` in the log.
