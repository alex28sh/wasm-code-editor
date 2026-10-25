// The libraries panel: a dialog over the page in which the user lists the Maven coordinates of the libraries the
// document may use. It is plain DOM rather than Compose, so that its text field, buttons and list are reachable
// by the keyboard and by assistive technology. The state is the application's; the panel only shows it.

const style = `
.libraries-backdrop {
    position: fixed; inset: 0; z-index: 1000; display: flex; align-items: flex-start; justify-content: center;
    padding: 48px 16px; background: rgba(0, 0, 0, 0.18); font: 13px system-ui, sans-serif; color: #080808;
}
.libraries-dialog {
    width: min(620px, 100%); max-height: calc(100% - 32px); display: flex; flex-direction: column; box-sizing: border-box;
    background: #ffffff; border: 1px solid #c9ccd6; border-radius: 8px; box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
}
.libraries-dialog h2 { margin: 0; padding: 12px 16px 2px; font-size: 14px; font-weight: 600; }
.libraries-hint { margin: 0; padding: 0 16px 10px; color: #6c707e; }
.libraries-list { list-style: none; margin: 0; padding: 0 8px; overflow-y: auto; }
.libraries-item { display: flex; align-items: flex-start; gap: 8px; padding: 6px 8px; border-top: 1px solid #ebecf0; }
.libraries-item:first-child { border-top: none; }
.libraries-text { flex: 1; min-width: 0; }
.libraries-coordinates { font-family: "JetBrains Mono", "DejaVu Sans Mono", Menlo, Consolas, monospace; overflow-wrap: anywhere; }
.libraries-status { color: #6c707e; overflow-wrap: anywhere; }
.libraries-item[data-state="failed"] .libraries-status { color: #db3b4b; }
.libraries-item[data-state="ready"] .libraries-status { color: #208a3c; }
.libraries-empty { padding: 6px 8px; color: #6c707e; }
.libraries-form { display: flex; gap: 8px; padding: 10px 16px 4px; }
.libraries-form input {
    flex: 1; min-width: 0; padding: 4px 8px; border: 1px solid #c9ccd6; border-radius: 4px;
    font: 13px "JetBrains Mono", "DejaVu Sans Mono", Menlo, Consolas, monospace; outline: none;
}
.libraries-form input:focus { border-color: #3574f0; }
.libraries-dialog button {
    padding: 4px 12px; border: 1px solid #c9ccd6; border-radius: 4px; background: #ffffff; font: inherit; cursor: pointer;
}
.libraries-dialog button:hover { background: #f5f8fe; }
.libraries-dialog button:focus-visible { outline: 2px solid #3574f0; outline-offset: 1px; }
.libraries-dialog .libraries-remove { padding: 0 6px; border: none; color: #6c707e; font-size: 16px; line-height: 20px; }
.libraries-error { min-height: 18px; margin: 0; padding: 0 16px; color: #db3b4b; }
.libraries-footer { display: flex; justify-content: flex-end; padding: 6px 16px 12px; }
`;

const statusText = {
    resolving: 'Loading…',
    ready: klibs => `Loaded: ${klibs.join(', ')}`,
};

/**
 * Creates the panel; nothing is shown until `open()`.
 *
 * `add(text)` returns an error message, or an empty string when the library was added; `remove(coordinates)`
 * removes one; `closed()` is called when the panel closes. `render(json)` shows the libraries:
 * `[{ coordinates, state: 'resolving' | 'ready' | 'failed', message, klibs }]`.
 */
export function createLibrariesPanel(add, remove, closed) {
    if (!document.getElementById('libraries-panel-style')) {
        const element = document.head.appendChild(document.createElement('style'));
        element.id = 'libraries-panel-style';
        element.textContent = style;
    }

    let backdrop = null;
    let list = null;
    let input = null;
    let error = null;
    let libraries = [];

    function renderList() {
        if (!list) return;
        list.replaceChildren(...libraries.map(library => {
            const item = document.createElement('li');
            item.className = 'libraries-item';
            item.dataset.state = library.state;
            const text = item.appendChild(document.createElement('div'));
            text.className = 'libraries-text';
            const coordinates = text.appendChild(document.createElement('div'));
            coordinates.className = 'libraries-coordinates';
            coordinates.textContent = library.coordinates;
            const status = text.appendChild(document.createElement('div'));
            status.className = 'libraries-status';
            status.textContent = library.state === 'failed' ? library.message
                : library.state === 'ready' ? statusText.ready(library.klibs) : statusText.resolving;
            const button = item.appendChild(document.createElement('button'));
            button.className = 'libraries-remove';
            button.type = 'button';
            button.textContent = '×';
            button.title = 'Remove';
            button.setAttribute('aria-label', `Remove ${library.coordinates}`);
            button.addEventListener('click', () => {
                remove(library.coordinates);
                input?.focus();
            });
            return item;
        }));
        if (libraries.length === 0) {
            const empty = list.appendChild(document.createElement('li'));
            empty.className = 'libraries-empty';
            empty.textContent = 'No libraries: the document can use the standard library only.';
        }
    }

    function submit() {
        const message = add(input.value);
        error.textContent = message;
        if (!message) input.value = '';
        input.focus();
    }

    function close() {
        if (!backdrop) return;
        backdrop.remove();
        backdrop = list = input = error = null;
        closed();
    }

    function open() {
        if (backdrop) {
            input.focus();
            return;
        }
        backdrop = document.body.appendChild(document.createElement('div'));
        backdrop.className = 'libraries-backdrop';
        backdrop.addEventListener('mousedown', event => {
            if (event.target !== backdrop) return;
            // Otherwise the press goes on to move the focus to the page, away from the editor the panel gave it to.
            event.preventDefault();
            close();
        });
        const dialog = backdrop.appendChild(document.createElement('div'));
        dialog.className = 'libraries-dialog';
        dialog.setAttribute('role', 'dialog');
        dialog.setAttribute('aria-modal', 'true');
        dialog.setAttribute('aria-labelledby', 'libraries-title');
        dialog.addEventListener('keydown', event => {
            if (event.key === 'Escape') {
                event.preventDefault();
                close();
            }
            // The editor below must not react to what is typed here.
            event.stopPropagation();
        });
        const title = dialog.appendChild(document.createElement('h2'));
        title.id = 'libraries-title';
        title.textContent = 'Libraries';
        const hint = dialog.appendChild(document.createElement('p'));
        hint.className = 'libraries-hint';
        hint.textContent = 'Kotlin Multiplatform libraries built for wasm-js, by their Maven coordinates. ' +
            'Their dependencies are added by themselves.';
        list = dialog.appendChild(document.createElement('ul'));
        list.className = 'libraries-list';
        list.setAttribute('aria-label', 'Libraries');

        const form = dialog.appendChild(document.createElement('form'));
        form.className = 'libraries-form';
        form.addEventListener('submit', event => {
            event.preventDefault();
            submit();
        });
        input = form.appendChild(document.createElement('input'));
        input.placeholder = 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0';
        input.setAttribute('aria-label', 'Maven coordinates');
        input.spellcheck = false;
        input.autocomplete = 'off';
        const addButton = form.appendChild(document.createElement('button'));
        addButton.type = 'submit';
        addButton.textContent = 'Add';
        error = dialog.appendChild(document.createElement('p'));
        error.className = 'libraries-error';
        error.setAttribute('role', 'alert');

        const footer = dialog.appendChild(document.createElement('div'));
        footer.className = 'libraries-footer';
        const closeButton = footer.appendChild(document.createElement('button'));
        closeButton.type = 'button';
        closeButton.textContent = 'Close';
        closeButton.addEventListener('click', close);

        renderList();
        input.focus();
    }

    return {
        open,
        close,
        isOpen: () => backdrop !== null,
        render(json) {
            libraries = JSON.parse(json);
            renderList();
        },
    };
}
