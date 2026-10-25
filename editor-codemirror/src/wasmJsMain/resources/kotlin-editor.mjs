// The CodeMirror side of the Kotlin editor: an IntelliJ-like look, keymap and navigation.
// Everything that needs to understand Kotlin is asked from the `host` object, which the Kotlin module implements.

import { acceptCompletion, autocompletion } from '@codemirror/autocomplete';
import {
    copyLineDown, cursorLineEnd, cursorLineStart, deleteLine, indentLess, indentMore, insertNewlineAndIndent,
    moveLineDown, moveLineUp, toggleBlockComment,
} from '@codemirror/commands';
import { HighlightStyle, StreamLanguage, foldService, indentUnit, syntaxHighlighting } from '@codemirror/language';
import { kotlin } from '@codemirror/legacy-modes/mode/clike';
import { setDiagnostics } from '@codemirror/lint';
import { selectNextOccurrence } from '@codemirror/search';
import { EditorSelection, EditorState, Prec, RangeSetBuilder } from '@codemirror/state';
import { Decoration, EditorView, ViewPlugin, keymap } from '@codemirror/view';
import { basicSetup } from 'codemirror';

// IntelliJ Light.
const theme = EditorView.theme({
    '&': { height: '100%', color: '#080808', backgroundColor: '#ffffff', fontSize: '13px' },
    '.cm-scroller': {
        overflow: 'auto',
        fontFamily: '"JetBrains Mono", "Fira Code", "DejaVu Sans Mono", Menlo, Consolas, monospace',
        lineHeight: '1.6',
    },
    '.cm-content': { caretColor: '#000000' },
    '.cm-cursor': { borderLeftColor: '#000000', borderLeftWidth: '2px' },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': { backgroundColor: '#a6d2ff' },
    '.cm-activeLine': { backgroundColor: '#f5f8fe' },
    '.cm-gutters': { backgroundColor: '#ffffff', color: '#aeb3c2', borderRight: '1px solid #ebecf0' },
    '.cm-activeLineGutter': { backgroundColor: '#f5f8fe', color: '#767a8a' },
    '.cm-lineNumbers .cm-gutterElement': { padding: '0 8px 0 12px' },
    '.cm-foldGutter .cm-gutterElement': { color: '#aeb3c2', cursor: 'pointer' },
    '.cm-foldPlaceholder': { backgroundColor: '#ebecf0', border: 'none', color: '#6c707e', padding: '0 4px', margin: '0 2px' },
    '&.cm-focused .cm-matchingBracket, .cm-matchingBracket': { backgroundColor: '#93d9d9', outline: 'none' },
    '.cm-selectionMatch': { backgroundColor: '#e4e4ff' },
    '.cm-searchMatch': { backgroundColor: '#fcd47e' },

    '.kt-keyword': { color: '#0033b3' },
    '.kt-string': { color: '#067d17' },
    '.kt-escape, .kt-template': { color: '#0037a6' },
    '.kt-number': { color: '#1750eb' },
    '.kt-comment, .kt-doc, .kt-doc-tag, .kt-doc-link': { color: '#8c8c8c', fontStyle: 'italic' },
    '.kt-doc-tag': { textDecoration: 'underline' },
    '.kt-doc-link': { color: '#4585be' },
    '.kt-annotation': { color: '#9e880d' },
    '.kt-label, .kt-named-argument': { color: '#4a86e8' },
    '.kt-function-declaration': { color: '#00627a' },
    '.kt-top-level-call': { fontStyle: 'italic' },
    '.kt-property': { color: '#871094' },
    '.kt-instance': { color: '#871094', fontStyle: 'italic' },
    '.kt-type-parameter': { color: '#007e8a' },
    '.kt-mutable': { textDecoration: 'underline' },
    '.kt-it': { fontWeight: 'bold' },

    '.kt-usage': { backgroundColor: '#edebfc' },
    '.kt-usage-write': { backgroundColor: '#fce8f4' },

    '.kt-popup': {
        position: 'absolute', top: '48px', left: '50%', transform: 'translateX(-50%)', zIndex: '300',
        width: 'min(560px, calc(100% - 32px))', maxHeight: '60%', display: 'flex', flexDirection: 'column',
        backgroundColor: '#ffffff', border: '1px solid #c9ccd6', borderRadius: '8px',
        boxShadow: '0 8px 24px rgba(0, 0, 0, 0.18)', fontFamily: 'system-ui, sans-serif', fontSize: '13px', color: '#080808',
    },
    '.kt-popup-title': { padding: '8px 12px 4px', fontWeight: '600' },
    '.kt-popup-filter': {
        margin: '4px 8px 6px', padding: '4px 8px', border: '1px solid #c9ccd6', borderRadius: '4px', font: 'inherit', outline: 'none',
    },
    '.kt-popup-filter:focus': { borderColor: '#3574f0' },
    '.kt-popup-list': { listStyle: 'none', margin: '0', padding: '0 0 6px', overflowY: 'auto' },
    '.kt-popup-item': { display: 'flex', alignItems: 'baseline', gap: '8px', padding: '3px 12px', cursor: 'pointer', whiteSpace: 'nowrap' },
    '.kt-popup-item[aria-selected=true]': { backgroundColor: '#d4e2ff' },
    '.kt-popup-kind': {
        flex: 'none', width: '16px', height: '16px', lineHeight: '16px', borderRadius: '50%', textAlign: 'center',
        fontSize: '10px', fontWeight: '600', color: '#ffffff', backgroundColor: '#6c707e', alignSelf: 'center',
    },
    '.kt-popup-kind-class, .kt-popup-kind-alias': { backgroundColor: '#3574f0' },
    '.kt-popup-kind-interface': { backgroundColor: '#208a3c' },
    '.kt-popup-kind-object, .kt-popup-kind-entry': { backgroundColor: '#c77d0a' },
    '.kt-popup-kind-function': { backgroundColor: '#d64f8a' },
    '.kt-popup-kind-property': { backgroundColor: '#871094' },
    '.kt-popup-label': { fontFamily: '"JetBrains Mono", "DejaVu Sans Mono", Menlo, Consolas, monospace', overflow: 'hidden', textOverflow: 'ellipsis' },
    '.kt-popup-detail': { color: '#6c707e', marginLeft: 'auto', paddingLeft: '16px' },
    '.kt-popup-empty': { padding: '4px 12px 8px', color: '#6c707e' },
});

const kindLetters = {
    class: 'C', interface: 'I', object: 'O', entry: 'E', alias: 'T', function: 'f', property: 'v',
};

/**
 * Creates the editor for the Kotlin text `doc`.
 *
 * `host` answers questions about the text. Positions are offsets into it; lists of ranges come as flat arrays
 * of `from, to, flags` triples.
 */
export function createKotlinEditor(doc, host) {
    // The text the host has analyzed. Documents are immutable, so comparing them by identity tells whether it is current.
    let analyzedDoc = null;
    let analyzedText = '';
    const analyze = state => {
        if (state.doc !== analyzedDoc) {
            analyzedDoc = state.doc;
            analyzedText = state.doc.toString();
            host.analyze(analyzedText);
        }
        return analyzedText;
    };

    // Semantic highlighting of the visible part.
    const marks = host.highlightClasses.map(name => Decoration.mark({ class: name }));
    const highlight = view => {
        analyze(view.state);
        const builder = new RangeSetBuilder();
        let last = -1;
        for (const { from, to } of view.visibleRanges) {
            const pieces = host.highlights(from, to);
            for (let index = 0; index < pieces.length; index += 3) {
                // A piece reaching over two visible ranges is reported for both.
                if (pieces[index] <= last) continue;
                last = pieces[index];
                builder.add(pieces[index], pieces[index + 1], marks[pieces[index + 2]]);
            }
        }
        return builder.finish();
    };
    const highlighting = ViewPlugin.fromClass(class {
        constructor(view) {
            this.decorations = highlight(view);
        }

        update(update) {
            if (update.docChanged || update.viewportChanged) this.decorations = highlight(update.view);
        }
    }, { decorations: plugin => plugin.decorations });

    // The declaration and the uses of the name under the caret.
    const usageMark = Decoration.mark({ class: 'kt-usage' });
    const writeMark = Decoration.mark({ class: 'kt-usage kt-usage-write' });
    const usagesOf = state => {
        const builder = new RangeSetBuilder();
        // Several carets are an in-place rename, which would only flicker.
        if (state.selection.ranges.length === 1) {
            analyze(state);
            const ranges = host.occurrences(state.selection.main.head);
            for (let index = 0; index < ranges.length; index += 3) {
                builder.add(ranges[index], ranges[index + 1], ranges[index + 2] & 2 ? writeMark : usageMark);
            }
        }
        return builder.finish();
    };
    const usages = ViewPlugin.fromClass(class {
        constructor(view) {
            this.decorations = usagesOf(view.state);
        }

        update(update) {
            if (update.docChanged || update.selectionSet) this.decorations = usagesOf(update.state);
        }
    }, { decorations: plugin => plugin.decorations });

    // Places the caret was at before it jumped, for navigating back and forward.
    let back = [];
    let forward = [];
    const jump = (view, position) => {
        view.dispatch({
            selection: { anchor: position },
            effects: EditorView.scrollIntoView(position, { y: 'center' }),
            userEvent: 'select.navigate',
        });
        view.focus();
    };
    const navigate = (view, position) => {
        back.push(view.state.selection.main.head);
        forward = [];
        jump(view, position);
    };
    const navigateBack = view => {
        if (back.length === 0) return true;
        forward.push(view.state.selection.main.head);
        jump(view, back.pop());
        return true;
    };
    const navigateForward = view => {
        if (forward.length === 0) return true;
        back.push(view.state.selection.main.head);
        jump(view, forward.pop());
        return true;
    };

    // A list to pick from, with a filter: the file structure and the usages of a symbol.
    let closePopup = () => {};
    const openPopup = (view, title, items, selected, emptyText) => {
        closePopup();
        const popup = document.createElement('div');
        popup.className = 'kt-popup';
        popup.setAttribute('role', 'dialog');
        popup.setAttribute('aria-label', title);
        const heading = popup.appendChild(document.createElement('div'));
        heading.className = 'kt-popup-title';
        heading.textContent = title;
        const filter = popup.appendChild(document.createElement('input'));
        filter.className = 'kt-popup-filter';
        filter.placeholder = 'Type to filter';
        filter.setAttribute('aria-label', 'Filter');
        const list = popup.appendChild(document.createElement('ul'));
        list.className = 'kt-popup-list';
        list.setAttribute('role', 'listbox');

        let shown = [];
        let current = 0;
        const select = index => {
            current = Math.max(0, Math.min(index, shown.length - 1));
            [...list.children].forEach((element, position) => {
                element.setAttribute('aria-selected', String(position === current));
                if (position === current) element.scrollIntoView({ block: 'nearest' });
            });
        };
        let closed = false;
        const close = () => {
            // Removing the popup takes the focus from the filter, which closes it once more.
            if (closed) return;
            closed = true;
            closePopup = () => {};
            popup.remove();
        };
        const pick = item => {
            close();
            if (item) navigate(view, item.from); else view.focus();
        };
        const render = preferred => {
            const query = filter.value.toLowerCase();
            shown = items.filter(item => item.search.toLowerCase().includes(query));
            list.replaceChildren(...shown.map(item => {
                const element = document.createElement('li');
                element.className = 'kt-popup-item';
                element.setAttribute('role', 'option');
                element.style.paddingLeft = `${12 + 16 * (item.depth ?? 0)}px`;
                if (item.kind) {
                    const kind = element.appendChild(document.createElement('span'));
                    kind.className = `kt-popup-kind kt-popup-kind-${item.kind}`;
                    kind.textContent = kindLetters[item.kind] ?? '';
                    kind.title = item.kind;
                }
                const label = element.appendChild(document.createElement('span'));
                label.className = 'kt-popup-label';
                label.textContent = item.label;
                if (item.detail) {
                    const detail = element.appendChild(document.createElement('span'));
                    detail.className = 'kt-popup-detail';
                    detail.textContent = item.detail;
                }
                // The list must not take the focus from the filter.
                element.addEventListener('mousedown', event => {
                    event.preventDefault();
                    pick(item);
                });
                return element;
            }));
            if (shown.length === 0) {
                const empty = list.appendChild(document.createElement('li'));
                empty.className = 'kt-popup-empty';
                empty.textContent = items.length === 0 ? emptyText : 'Nothing matches';
            }
            select(Math.max(0, shown.indexOf(preferred)));
        };
        filter.addEventListener('input', () => render(shown[current]));
        filter.addEventListener('keydown', event => {
            if (event.key === 'ArrowDown') select(current + 1);
            else if (event.key === 'ArrowUp') select(current - 1);
            else if (event.key === 'PageDown') select(current + 10);
            else if (event.key === 'PageUp') select(current - 10);
            else if (event.key === 'Enter') pick(shown[current]);
            else if (event.key === 'Escape') pick(null);
            else return;
            event.preventDefault();
            event.stopPropagation();
        });
        filter.addEventListener('blur', () => close());

        view.dom.appendChild(popup);
        closePopup = close;
        render(items[selected]);
        filter.focus();
        return true;
    };

    // Line and column are counted from one, like in the status bar.
    const goToLine = view => {
        closePopup();
        const { state } = view;
        const head = state.selection.main.head;
        const current = state.doc.lineAt(head);
        const popup = document.createElement('div');
        popup.className = 'kt-popup';
        popup.setAttribute('role', 'dialog');
        popup.setAttribute('aria-label', 'Go to line');
        const heading = popup.appendChild(document.createElement('div'));
        heading.className = 'kt-popup-title';
        heading.textContent = 'Go to Line:Column';
        const input = popup.appendChild(document.createElement('input'));
        input.className = 'kt-popup-filter';
        input.setAttribute('aria-label', 'Line and column');
        input.value = `${current.number}:${head - current.from + 1}`;

        let closed = false;
        const close = () => {
            if (closed) return;
            closed = true;
            closePopup = () => {};
            popup.remove();
        };
        input.addEventListener('keydown', event => {
            if (event.key === 'Enter') {
                const match = /^\s*(\d+)\s*(?::\s*(\d+))?\s*$/.exec(input.value);
                close();
                if (match) {
                    const line = view.state.doc.line(Math.max(1, Math.min(view.state.doc.lines, Number(match[1]))));
                    const column = Math.max(1, Math.min(line.length + 1, Number(match[2] ?? 1)));
                    navigate(view, line.from + column - 1);
                } else {
                    view.focus();
                }
            } else if (event.key === 'Escape') {
                close();
                view.focus();
            } else {
                return;
            }
            event.preventDefault();
            event.stopPropagation();
        });
        input.addEventListener('blur', () => close());
        view.dom.appendChild(popup);
        closePopup = close;
        input.focus();
        input.select();
        return true;
    };

    const lineItem = (state, from) => {
        const line = state.doc.lineAt(from);
        return { from, label: line.text.trim(), detail: `line ${line.number}`, search: line.text };
    };

    const showUsages = (view, position = view.state.selection.main.head) => {
        analyze(view.state);
        const ranges = host.occurrences(position);
        if (ranges.length === 0) return true;
        const name = view.state.sliceDoc(ranges[0], ranges[1]);
        const items = [];
        for (let index = 0; index < ranges.length; index += 3) {
            if (!(ranges[index + 2] & 1)) items.push(lineItem(view.state, ranges[index]));
        }
        // A single usage is where the user wants to go.
        if (items.length === 1 && host.declaration(position) === -2) {
            navigate(view, items[0].from);
            return true;
        }
        const current = items.findIndex(item => item.from <= position && position <= item.from + name.length);
        return openPopup(view, `Usages of ${name}`, items, Math.max(0, current), 'No usages in this file');
    };

    const goToDeclaration = (view, position = view.state.selection.main.head) => {
        analyze(view.state);
        const target = host.declaration(position);
        // On the declaration itself, IntelliJ shows where it is used.
        if (target === -2) return showUsages(view, position);
        if (target >= 0) navigate(view, target);
        return true;
    };

    const showStructure = view => {
        analyze(view.state);
        const items = host.structure().map(item => ({ ...item, search: item.label }));
        return openPopup(view, 'File structure', items, host.enclosing(view.state.selection.main.head), 'No declarations');
    };

    // Renaming in place: a caret on every occurrence, so typing changes them all. Escape or Enter goes back to one caret.
    const rename = view => {
        analyze(view.state);
        const head = view.state.selection.main.head;
        const ranges = host.occurrences(head);
        if (ranges.length === 0) return true;
        const selections = [];
        let main = 0;
        for (let index = 0; index < ranges.length; index += 3) {
            if (ranges[index] <= head && head <= ranges[index + 1]) main = selections.length;
            selections.push(EditorSelection.range(ranges[index], ranges[index + 1]));
        }
        view.dispatch({ selection: EditorSelection.create(selections, main), userEvent: 'select.rename' });
        return true;
    };

    const finishRename = view => {
        const { main, ranges } = view.state.selection;
        if (ranges.length === 1) return false;
        view.dispatch({ selection: EditorSelection.cursor(main.head), userEvent: 'select' });
        return true;
    };

    const duplicate = view => {
        const { main } = view.state.selection;
        if (main.empty) return copyLineDown(view);
        const text = view.state.sliceDoc(main.from, main.to);
        view.dispatch({
            changes: { from: main.to, insert: text },
            selection: EditorSelection.range(main.to, main.to + text.length),
            userEvent: 'input.copyline',
        });
        return true;
    };

    const joinLines = view => {
        const { state } = view;
        const line = state.doc.lineAt(state.selection.main.head);
        if (line.number === state.doc.lines) return true;
        const next = state.doc.line(line.number + 1);
        const end = line.from + line.text.trimEnd().length;
        const start = next.from + (next.text.length - next.text.trimStart().length);
        const separator = line.text.trim() === '' || next.text.trim() === '' ? '' : ' ';
        view.dispatch({ changes: { from: end, to: start, insert: separator }, selection: { anchor: end }, userEvent: 'delete.join' });
        return true;
    };

    const startNewLine = view => {
        cursorLineEnd(view);
        return insertNewlineAndIndent(view);
    };

    const startNewLineBefore = view => {
        const line = view.state.doc.lineAt(view.state.selection.main.head);
        const indentation = line.text.slice(0, line.text.length - line.text.trimStart().length);
        view.dispatch({ changes: { from: line.from, insert: indentation + '\n' }, selection: { anchor: line.from + indentation.length }, userEvent: 'input' });
        return true;
    };

    const completions = context => {
        // While a rename types at several carets, a list of names would only get in the way of Escape and Enter.
        if (context.state.selection.ranges.length > 1) return null;
        const word = context.matchBefore(/[\p{L}_][\p{L}\p{N}_]*/u);
        // The members of what is before a dot are worth offering before the first letter of a name.
        const afterDot = !word && context.state.sliceDoc(context.pos - 1, context.pos) === '.';
        if (!word && !context.explicit && !afterDot) return null;
        analyze(context.state);
        const ownCompletions = () => {
            if (afterDot && !context.explicit) return null;
            const options = host.completions(context.pos, word ? word.text : '');
            // A popup offering only what is already written would just swallow the next Enter.
            if (options.length === 0 || options.length === 1 && word && options[0].label === word.text) return null;
            return { from: word ? word.from : context.pos, options };
        };
        // The host completes by the meaning of the program once it can, and the names in the file serve until then.
        const semantic = host.semanticCompletions(context.pos);
        if (!semantic) return ownCompletions();
        return semantic.then(
            result => result && result.options.length > 0 ? result : ownCompletions(),
            ownCompletions,
        );
    };

    const reportSelection = state => {
        const { main, ranges } = state.selection;
        const line = state.doc.lineAt(main.head);
        host.selectionChanged(line.number, main.head - line.from + 1, main.to - main.from, ranges.length);
    };

    const view = new EditorView({
        state: EditorState.create({
            doc,
            extensions: [
                Prec.highest(keymap.of([
                    { key: 'Mod-Enter', run: () => (host.run(), true) },
                    { key: 'Mod-Alt-Shift-s', run: () => (host.libraries(), true) },
                    { key: 'Mod-b', run: goToDeclaration },
                    { key: 'Alt-F7', run: showUsages },
                    { key: 'Mod-F12', run: showStructure },
                    { key: 'Shift-F6', run: rename },
                    { key: 'Escape', run: finishRename },
                    { key: 'Enter', run: finishRename },
                    { key: 'Mod-g', run: goToLine },
                    { key: 'Mod-Alt-ArrowLeft', run: navigateBack },
                    { key: 'Mod-Alt-ArrowRight', run: navigateForward },
                    { key: 'Mod-d', run: duplicate },
                    { key: 'Mod-y', run: deleteLine },
                    { key: 'Alt-j', run: selectNextOccurrence },
                    { key: 'Alt-Shift-ArrowUp', run: moveLineUp },
                    { key: 'Alt-Shift-ArrowDown', run: moveLineDown },
                    { key: 'Mod-Shift-/', run: toggleBlockComment },
                    { key: 'Mod-Shift-j', run: joinLines },
                    { key: 'Shift-Enter', run: startNewLine },
                    { key: 'Mod-Alt-Enter', run: startNewLineBefore },
                    { key: 'Tab', run: acceptCompletion },
                    { key: 'Tab', run: indentMore, shift: indentLess },
                ])),
                basicSetup,
                // The stream mode is kept for indentation and comment syntax; the colors come from the host.
                StreamLanguage.define(kotlin),
                syntaxHighlighting(HighlightStyle.define([])),
                indentUnit.of('    '),
                autocompletion({ override: [completions] }),
                foldService.of((state, lineStart, lineEnd) => {
                    analyze(state);
                    const range = host.foldRange(lineStart, lineEnd);
                    return range ? { from: range[0], to: range[1] } : null;
                }),
                highlighting,
                usages,
                theme,
                // Ctrl+click goes to the declaration; another caret is added with Alt+Shift+click.
                EditorView.clickAddsSelectionRange.of(event => event.altKey && event.shiftKey),
                EditorView.domEventHandlers({
                    mousedown(event, view) {
                        if (event.button !== 0 || !(event.ctrlKey || event.metaKey) || event.altKey) return false;
                        const position = view.posAtCoords({ x: event.clientX, y: event.clientY });
                        if (position === null) return false;
                        event.preventDefault();
                        goToDeclaration(view, position);
                        return true;
                    },
                }),
                EditorView.updateListener.of(update => {
                    if (update.docChanged) {
                        back = back.map(position => update.changes.mapPos(position));
                        forward = forward.map(position => update.changes.mapPos(position));
                        host.changed(analyze(update.state));
                    }
                    if (update.docChanged || update.selectionSet) reportSelection(update.state);
                }),
            ],
        }),
    });
    reportSelection(view.state);
    return view;
}

/** Marks the problems the compiler found: each is `{ from, to, severity, message }` with offsets into the document. */
export function showDiagnostics(view, diagnostics) {
    const length = view.state.doc.length;
    view.dispatch(setDiagnostics(view.state, diagnostics.map(diagnostic => ({
        from: Math.min(diagnostic.from, length),
        // A problem without an extent still needs a character to be drawn under.
        to: Math.min(Math.max(diagnostic.to, diagnostic.from + 1), length),
        severity: diagnostic.severity,
        message: diagnostic.message,
    }))));
}
