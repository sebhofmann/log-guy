// The script editor: list of scripts, CodeMirror with the draft or the active version, comparison with the active
// version or an earlier one, test against an application or pasted lines, activation and rollback.

import {
    EditorState, EditorView, HighlightStyle, MergeView, basicSetup, indentWithTab, javascript, keymap,
    syntaxHighlighting, tags,
} from '../vendor/codemirror.esm.js';
import { ApiError, getJson, sendJson } from './api.js';
import { element, firstLine, formatNumber, formatTime } from './format.js';
import { offerReindex } from './reindex.js';

const KINDS = {
    parser: 'Parser', rule: 'Meldungsregeln', lifecycle: 'Lifecycle-Filter', pseudonym: 'Pseudonymisierung',
};
const VIA = { FILESYSTEM: 'Dateisystem', GUI: 'GUI', API: 'API', MCP: 'MCP', CLI: 'Kommandozeile' };
const MAX_COLUMNS = 8;

// CodeMirror adds its styles as a <style> element to a document, which the Content-Security-Policy blocks. In a
// shadow root it uses a constructed style sheet instead, so every editor lives in one. The colors come from the CSS
// variables of the page, which reach into the shadow root.
const THEME = EditorView.theme({
    '&': { backgroundColor: 'var(--panel)', color: 'var(--text)', fontSize: '13px' },
    '.cm-scroller': { fontFamily: 'var(--mono)' },
    '.cm-content': { caretColor: 'var(--text)' },
    '.cm-cursor': { borderLeftColor: 'var(--text)' },
    '.cm-gutters': { backgroundColor: 'var(--bg)', color: 'var(--muted)', borderRightColor: 'var(--border)' },
    '.cm-activeLine, .cm-activeLineGutter': { backgroundColor: 'var(--hover)' },
    '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground':
        { backgroundColor: 'var(--selection)' },
    '.cm-collapsedLines': { background: 'var(--hover)', color: 'var(--muted)' },
});
const HIGHLIGHT = syntaxHighlighting(HighlightStyle.define([
    { tag: [tags.keyword, tags.operatorKeyword, tags.modifier], color: 'var(--code-keyword)' },
    { tag: [tags.string, tags.regexp, tags.special(tags.string)], color: 'var(--code-string)' },
    { tag: [tags.number, tags.bool, tags.null], color: 'var(--code-number)' },
    { tag: [tags.comment, tags.lineComment, tags.blockComment], color: 'var(--muted)', fontStyle: 'italic' },
    { tag: [tags.function(tags.variableName), tags.function(tags.propertyName)], color: 'var(--code-function)' },
    { tag: [tags.definition(tags.variableName), tags.propertyName], color: 'var(--code-property)' },
]));
const EDITOR_HEIGHT = EditorView.theme({ '&': { height: '55vh' } });

const root = document.getElementById('scripts');
const admin = root.dataset.admin === 'true';
const list = element('nav', 'script-list');
const main = element('section', 'script-main');
root.replaceChildren(list, main);

let scripts = [];
let apps = [];
let current = null;
let editor = null;
let merge = null;
let dirty = false;

init();

async function init() {
    try {
        const [summaries, fields] = await Promise.all([getJson('/api/v1/scripts'), getJson('/api/v1/fields')]);
        scripts = summaries;
        apps = fields.apps;
        renderList();
        const [kind, name] = (new URLSearchParams(location.search).get('script') ?? '').split('/');
        if (kind && name) {
            await open(kind, name);
        } else {
            main.replaceChildren(element('p', 'muted', 'Links ein Skript wählen oder ein neues anlegen.'));
        }
    } catch (e) {
        main.replaceChildren(element('p', 'error', e.message));
    }
    window.addEventListener('beforeunload', event => {
        if (dirty) {
            event.preventDefault();
        }
    });
}

async function reloadList() {
    scripts = await getJson('/api/v1/scripts');
    renderList();
}

function renderList() {
    const children = [element('h1', null, 'Skripte')];
    for (const [kind, label] of Object.entries(KINDS)) {
        const entries = scripts.filter(script => script.kind === kind);
        if (!entries.length && kind !== 'parser') {
            continue;
        }
        children.push(element('h2', null, label));
        const ul = element('ul');
        for (const script of entries) {
            const link = element('a', null, script.name);
            link.href = `?script=${kind}/${encodeURIComponent(script.name)}`;
            link.addEventListener('click', event => {
                event.preventDefault();
                open(kind, script.name);
            });
            if (current && current.kind === kind && current.name === script.name) {
                link.classList.add('active');
            }
            const li = element('li');
            li.append(link);
            if (script.draft) {
                const badge = element('span', script.draft.conflict ? 'badge bad' : 'badge',
                    script.draft.via === 'MCP' ? 'Entwurf MCP' : 'Entwurf');
                badge.title = `${script.draft.user ?? script.draft.token ?? ''}, ${formatTime(script.draft.updated)}`;
                li.append(badge);
            }
            if (script.origin === 'template') {
                li.append(element('span', 'muted', ' Vorlage'));
            }
            ul.append(li);
        }
        children.push(ul);
    }
    children.push(newScriptForm());
    if (admin) {
        const reload = button('Aus Dateisystem neu laden', reloadFromDisk);
        reload.title = 'Liest Skripte und Konfiguration neu ein, wie log-guy reload';
        children.push(reload);
    }
    list.replaceChildren(...children);
}

function newScriptForm() {
    const form = element('form', 'new-script');
    const kind = element('select');
    for (const [value, label] of Object.entries(KINDS)) {
        const option = element('option', null, label);
        option.value = value;
        kind.append(option);
    }
    kind.setAttribute('aria-label', 'Art');
    const name = element('input');
    name.placeholder = 'name';
    name.required = true;
    name.pattern = '[a-z0-9][a-z0-9_-]*';
    name.setAttribute('aria-label', 'Name');
    const submit = element('button', null, 'Neu');
    submit.type = 'submit';
    form.append(element('h2', null, 'Neues Skript'), kind, name, submit);
    form.addEventListener('submit', event => {
        event.preventDefault();
        if (!confirmDiscard()) {
            return;
        }
        current = { kind: kind.value, name: name.value, code: null, hash: null, draft: null, history: [], apps: [],
            origin: 'new' };
        render(starter(kind.value));
    });
    return form;
}

function starter(kind) {
    if (kind === 'parser') {
        return 'export const fields = {\n    level: { type: "string", facet: true },\n};\n\n'
            + '// optional: export function isStart(line) { ... }\n\n'
            + 'export function parse(text) {\n    return { ts: parseTime(text.substring(0, 23), '
            + '"%Y-%m-%dT%H:%M:%S,%3f", "Europe/Berlin"), message: text };\n}\n';
    }
    return 'export const meta = {\n    apps: [],\n};\n';
}

function confirmDiscard() {
    return !dirty || window.confirm('Ungespeicherte Änderungen verwerfen?');
}

async function open(kind, name) {
    if (!confirmDiscard()) {
        return;
    }
    try {
        current = await getJson(`/api/v1/scripts/${kind}/${encodeURIComponent(name)}`);
        history.replaceState(null, '', `?script=${kind}/${encodeURIComponent(name)}`);
        render(current.draft ? current.draft.code : current.code);
        renderList();
    } catch (e) {
        main.replaceChildren(element('p', 'error', e.message));
    }
}

function render(code) {
    dirty = false;
    destroyEditors();
    const title = element('h1', null, `${KINDS[current.kind]}: ${current.name}`);
    const info = element('div', 'script-info');
    info.append(...infoLines());
    const message = element('div', 'script-message');
    message.setAttribute('role', 'status');

    const tabs = element('div', 'tabs');
    const editBox = element('div', 'editor');
    const compareBox = element('div', 'editor');
    const historyBox = element('div', 'script-history');
    const panes = [editBox, compareBox, historyBox];
    const tabButtons = ['Bearbeiten', 'Vergleich mit aktiver Fassung', 'Verlauf'].map((label, index) => {
        const tab = button(label, () => {
            tabButtons.forEach((b, i) => b.classList.toggle('active', i === index));
            panes.forEach((p, i) => {
                p.hidden = i !== index;
            });
            if (index === 1) {
                showComparison(compareBox, current.code ?? '', editor.state.doc.toString(), 'aktiv', 'Editor');
            }
        });
        return tab;
    });
    tabButtons[0].classList.add('active');
    compareBox.hidden = true;
    historyBox.hidden = true;
    tabs.append(...tabButtons);

    const mount = shadowMount(editBox);
    editor = new EditorView({
        parent: mount,
        root: mount.getRootNode(),
        state: EditorState.create({
            doc: code ?? '',
            extensions: [basicSetup, THEME, HIGHLIGHT, EDITOR_HEIGHT, keymap.of([indentWithTab]), javascript(),
                EditorState.tabSize.of(4),
                EditorView.updateListener.of(update => {
                    if (update.docChanged) {
                        dirty = true;
                    }
                })],
        }),
    });
    renderHistory(historyBox, compareBox, tabButtons);

    const actions = element('div', 'toolbar');
    actions.append(
        button('Entwurf speichern', () => saveDraft(message)),
        button('Aktivieren', () => activate(message, false, false)),
    );
    if (current.draft) {
        actions.append(button('Entwurf verwerfen', () => discardDraft(message)));
    }
    main.replaceChildren(title, info, tabs, editBox, compareBox, historyBox, actions, message, testPanel());
}

function infoLines() {
    const lines = [];
    const origin = { file: 'Datei', template: 'eingebaute Vorlage', draft: 'nur Entwurf', new: 'neu' }[current.origin];
    lines.push(element('div', null, `Herkunft: ${origin ?? current.origin}`
        + (current.apps?.length ? `, Anwendungen: ${current.apps.join(', ')}` : '')));
    const draft = current.draft?.info;
    if (draft) {
        const who = [draft.user, draft.token].filter(Boolean).join(' mit Token ');
        lines.push(element('div', null, `Entwurf von ${who || 'unbekannt'} über ${VIA[draft.via] ?? draft.via}, `
            + `gespeichert ${formatTime(draft.updated)}`));
        if (draft.test) {
            lines.push(element('div', draft.test.errors ? 'warn' : null, `Letzter Test dieses Entwurfs: `
                + `${formatTime(draft.test.at)}, ${draft.test.source}, ${formatNumber(draft.test.events)} Events, `
                + `${formatNumber(draft.test.errors)} Fehler`));
        } else {
            lines.push(element('div', 'warn', 'Dieser Entwurf ist noch nicht getestet.'));
        }
        if (draft.conflict) {
            lines.push(element('div', 'error', 'Die aktive Fassung hat sich geändert, seit der Entwurf begonnen '
                + 'wurde. Vor dem Aktivieren im Vergleich prüfen.'));
        }
        if (draft.error) {
            lines.push(element('div', 'error', `Der Entwurf lässt sich nicht laden: ${draft.error}`));
        }
    }
    return lines;
}

function destroyEditors() {
    editor?.destroy();
    merge?.destroy();
    editor = null;
    merge = null;
}

function shadowMount(box) {
    const host = element('div');
    const mount = element('div');
    host.attachShadow({ mode: 'open' }).append(mount);
    box.replaceChildren(host);
    return mount;
}

function showComparison(box, left, right, leftLabel, rightLabel) {
    merge?.destroy();
    const legend = element('div', 'compare-legend muted', `links ${leftLabel}, rechts ${rightLabel}`);
    const mount = shadowMount(box);
    box.prepend(legend);
    const extensions = [basicSetup, THEME, HIGHLIGHT, javascript(), EditorState.readOnly.of(true),
        EditorView.editable.of(false)];
    merge = new MergeView({
        a: { doc: left, extensions },
        b: { doc: right, extensions },
        parent: mount,
        root: mount.getRootNode(),
        collapseUnchanged: { margin: 3, minSize: 6 },
    });
    merge.dom.style.maxHeight = '55vh';
    merge.dom.style.overflow = 'auto';
}

function renderHistory(box, compareBox, tabButtons) {
    if (!current.history?.length) {
        box.replaceChildren(element('p', 'muted', 'Noch keine früheren Fassungen.'));
        return;
    }
    const table = element('table', 'data');
    const head = element('tr');
    for (const label of ['Aktiviert', 'Von', 'Weg', 'Hash', '']) {
        head.append(element('th', null, label));
    }
    table.append(head);
    for (const version of current.history) {
        const row = element('tr');
        const actions = element('td');
        const isActive = version.hash === current.hash;
        actions.append(button('Vergleichen', async () => {
            const { code } = await getJson(`/api/v1/scripts/${current.kind}/${encodeURIComponent(current.name)}`
                + '/history', new URLSearchParams({ at: version.at }));
            tabButtons[1].click();
            showComparison(compareBox, code, current.code ?? '', `Fassung vom ${formatTime(version.at)}`, 'aktiv');
        }));
        if (!isActive) {
            actions.append(button('Zurückrollen', () => rollback(version)));
        }
        row.append(
            element('td', null, formatTime(version.at)),
            element('td', null, [version.user, version.token].filter(Boolean).join(' / ')),
            element('td', null, VIA[version.via] ?? version.via),
            element('td', 'mono', version.hash.substring(0, 12) + (isActive ? ' (aktiv)' : '')),
            actions,
        );
        table.append(row);
    }
    box.replaceChildren(table);
}

function path(suffix) {
    return `/api/v1/scripts/${current.kind}/${encodeURIComponent(current.name)}${suffix}`;
}

async function saveDraft(message) {
    try {
        current = await sendJson('PUT', path('/draft'), { code: editor.state.doc.toString(), base: current.hash });
        await afterChange(message, 'Entwurf gespeichert.');
    } catch (e) {
        show(message, e);
    }
}

async function discardDraft(message) {
    if (!window.confirm('Entwurf verwerfen?')) {
        return;
    }
    try {
        await sendJson('DELETE', path('/draft'));
        dirty = false;
        await reloadList();
        if (current.code === null) {
            current = null;
            main.replaceChildren(element('p', 'muted', 'Entwurf verworfen.'));
        } else {
            await open(current.kind, current.name);
        }
    } catch (e) {
        show(message, e);
    }
}

async function activate(message, force, confirm) {
    if (dirty) {
        show(message, 'Erst den Entwurf speichern und testen, dann aktivieren.');
        return;
    }
    if (!current.draft) {
        show(message, 'Es gibt keinen Entwurf zum Aktivieren.');
        return;
    }
    try {
        const params = new URLSearchParams({ force, confirm });
        current = await sendJson('POST', `${path('/activate')}?${params}`);
        await afterChange(message, 'Aktiviert. Neue Events nutzen ab jetzt diese Fassung.');
    } catch (e) {
        if (e instanceof ApiError && e.status === 409 && window.confirm(`${e.message}\n\nTrotzdem aktivieren?`)) {
            const conflict = e.body?.reason === 'conflict';
            await activate(message, force || conflict, confirm || !conflict);
            return;
        }
        show(message, e);
    }
}

async function rollback(version) {
    if (!window.confirm(`Fassung vom ${formatTime(version.at)} wieder aktivieren?`)) {
        return;
    }
    try {
        current = await sendJson('POST', `${path('/rollback')}?${new URLSearchParams({ at: version.at })}`);
        await afterChange(main.querySelector('.script-message'), 'Frühere Fassung aktiviert.');
    } catch (e) {
        show(main.querySelector('.script-message'), e);
    }
}

async function afterChange(message, text) {
    render(current.draft ? current.draft.code : current.code);
    await reloadList();
    const box = main.querySelector('.script-message');
    show(box, text, true);
    if (current.kind === 'parser') {
        const offer = element('div', 'script-reindex');
        box.after(offer);
        await offerReindex(offer, current.apps);
    } else if (current.kind === 'pseudonym' && admin) {
        const offer = element('div', 'script-reindex');
        box.after(offer);
        offerPseudonymize(offer);
    }
}

// The archive keeps the form it was written in, a new or changed script only reaches it when it is written again
function offerPseudonymize(box) {
    const text = element('p', 'muted', 'Das Archiv bleibt, wie es geschrieben wurde. Es lässt sich mit den aktiven '
        + 'Skripten neu pseudonymisieren, vorhandene Pseudonyme bleiben dabei stehen.');
    const result = element('span', 'muted');
    const start = button('Archiv neu pseudonymisieren', async () => {
        start.disabled = true;
        result.textContent = 'läuft …';
        try {
            result.textContent = describePseudonymize(await sendJson('POST', '/admin/archive/pseudonymize'));
        } catch (e) {
            result.textContent = e instanceof ApiError && e.body?.pseudonymized
                ? describePseudonymize(e.body) : e.message;
            start.disabled = false;
        }
    });
    const actions = element('div', 'toolbar');
    actions.append(start, result);
    box.replaceChildren(text, actions);
}

function describePseudonymize(report) {
    if (report.pausedBy?.length) {
        return `Nicht gestartet: ${report.pausedBy.join(', ')}`;
    }
    const parts = [`${formatNumber(report.pseudonymized.length)} Dateien neu geschrieben`,
        `${formatNumber(report.unchanged)} schon aktuell`];
    if (report.newKeys) {
        parts.push(`${formatNumber(report.newKeys)} ohne Original mit neuem Schlüssel`);
    }
    if (report.placeholders) {
        parts.push(`${formatNumber(report.placeholders)} Platzhalter`);
    }
    if (report.failed.length) {
        parts.push(`fehlgeschlagen: ${report.failed.join(', ')}`);
    }
    return parts.join(', ');
}

async function reloadFromDisk() {
    try {
        const report = await sendJson('POST', '/admin/reload');
        const changed = report.scripts.length || report.apps.length
            ? `Geändert: ${report.scripts.concat(report.apps).join(', ')}.` : 'Keine Änderungen.';
        const restart = report.restartNeeded.length
            ? ` Erst nach einem Neustart wirksam: ${report.restartNeeded.join(', ')}.` : '';
        window.alert(changed + restart);
        await reloadList();
    } catch (e) {
        window.alert(e.message);
    }
}

function show(box, value, ok) {
    const text = value instanceof Error ? value.message : value;
    box.replaceChildren(element('span', ok ? 'ok' : 'error', text));
}

function button(label, action) {
    const node = element('button', null, label);
    node.type = 'button';
    node.addEventListener('click', action);
    return node;
}

function testPanel() {
    if (current.kind === 'rule') {
        return ruleTestPanel();
    }
    const panel = element('section', 'script-test');
    const source = element('select');
    for (const [value, label] of [['app', 'Anwendung'], ['text', 'Eingefügte Zeilen'], ['file', 'Datei']]) {
        const option = element('option', null, label);
        option.value = value;
        source.append(option);
    }
    source.setAttribute('aria-label', 'Testquelle');
    const app = element('select');
    for (const name of apps) {
        const option = element('option', null, name);
        option.value = name;
        app.append(option);
    }
    const preferred = current.apps?.find(name => apps.includes(name));
    if (preferred) {
        app.value = preferred;
    }
    app.setAttribute('aria-label', 'Anwendung');
    const lines = element('input');
    lines.type = 'number';
    lines.min = '1';
    lines.value = '1000';
    lines.setAttribute('aria-label', 'Zeilen');
    const file = element('input');
    file.placeholder = 'Fingerabdruck der Datei';
    file.setAttribute('aria-label', 'Datei');
    const text = element('textarea');
    text.rows = 6;
    text.placeholder = 'Logzeilen hier einfügen';
    text.setAttribute('aria-label', 'Logzeilen');
    const result = element('div', 'test-result');
    const run = button('Testen', () => test(source.value, app.value, lines.value, file.value, text.value, result));
    const sync = () => {
        app.hidden = source.value !== 'app';
        lines.hidden = source.value !== 'app';
        file.hidden = source.value !== 'file';
        text.hidden = source.value !== 'text';
    };
    source.addEventListener('change', sync);
    sync();
    const bar = element('div', 'toolbar');
    bar.append(source, app, lines, file, run);
    panel.append(element('h2', null, 'Test mit dem Inhalt des Editors'), bar, text, result);
    return panel;
}

function ruleTestPanel() {
    const panel = element('section', 'script-test');
    const app = element('select');
    const all = element('option', null, 'Alle Anwendungen der Regel');
    all.value = '';
    app.append(all);
    for (const name of apps) {
        const option = element('option', null, name);
        option.value = name;
        app.append(option);
    }
    app.setAttribute('aria-label', 'Anwendung');
    const result = element('div', 'test-result');
    const run = button('Gegen die letzten 24 Stunden testen', () => test('rule', app.value, null, null, null, result));
    const bar = element('div', 'toolbar');
    bar.append(app, run);
    panel.append(element('h2', null, 'Test mit dem Inhalt des Editors'),
        element('p', 'muted', 'Spielt die indexierten Events mit ihren eigenen Zeiten durch die Regel, '
            + 'mit Schwelle, Stille und Cooldown. Es wird nichts gemeldet.'), bar, result);
    return panel;
}

async function test(source, app, lines, file, text, result) {
    const body = { code: editor.state.doc.toString() };
    if (source === 'rule') {
        body.app = app || null;
    } else if (source === 'app') {
        body.app = app;
        body.lines = Number(lines);
    } else if (source === 'file') {
        body.file = file.trim();
    } else {
        body.text = text;
    }
    result.replaceChildren(element('p', 'muted', 'Test läuft …'));
    try {
        const outcome = await sendJson('POST', path('/test'), body);
        result.replaceChildren(...resultNodes(outcome));
        if (current.draft && current.draft.code === body.code) {
            current = await getJson(path(''));
            main.querySelector('.script-info').replaceChildren(...infoLines());
        }
    } catch (e) {
        result.replaceChildren(element('p', 'error', e.message));
    }
}

function resultNodes(outcome) {
    if ('hitSamples' in outcome) {
        return ruleResult(outcome);
    }
    if ('filtersLines' in outcome) {
        return lifecycleResult(outcome);
    }
    return 'sample' in outcome && 'declared' in outcome ? parserResult(outcome) : pseudonymResult(outcome);
}

function lifecycleResult(outcome) {
    const nodes = [];
    if (outcome.filtersLines) {
        nodes.push(element('p', outcome.errors ? 'warn' : 'ok', `${outcome.source}: ${formatNumber(outcome.lines)} `
            + `Zeilen, ${formatNumber(outcome.changed)} geändert, ${formatNumber(outcome.dropped)} gelöscht, `
            + `${formatNumber(outcome.errors)} Fehler`));
        if (outcome.errors) {
            nodes.push(element('p', 'muted', 'Bei einem Fehler steht im Archiv statt der Zeile der Platzhalter.'));
        }
        const changes = [...outcome.failed, ...outcome.samples];
        if (changes.length) {
            const table = element('table', 'data test-events');
            table.append(row('th', ['Zeile', 'Vorher', 'Nachher']));
            for (const change of changes) {
                const after = change.error ? `Fehler: ${change.error}` : change.after ?? 'gelöscht';
                const tr = row('td', [change.line, change.before, after]);
                if (change.error || change.after === null) {
                    tr.classList.add('failed');
                }
                table.append(tr);
            }
            nodes.push(element('h3', null, 'Beispiele vorher und nachher'), table);
        }
    }
    if (outcome.filtersFields) {
        nodes.push(element('p', outcome.eventErrors ? 'warn' : 'ok', `fields(event): ${formatNumber(outcome.events)} `
            + `Events, ${formatNumber(outcome.eventsChanged)} geändert, ${formatNumber(outcome.eventErrors)} Fehler`));
        if (outcome.eventSamples.length) {
            const table = element('table', 'data test-events');
            table.append(row('th', ['Event', 'Vorher', 'Nachher']));
            for (const change of outcome.eventSamples) {
                const tr = row('td', [change.event, JSON.stringify(change.before),
                    change.error ? `Fehler: ${change.error}` : JSON.stringify(change.after)]);
                if (change.error) {
                    tr.classList.add('failed');
                }
                table.append(tr);
            }
            nodes.push(element('h3', null, 'Geänderte Events'), table);
        }
    }
    return nodes;
}

const KIND_LABELS = { OPENED: 'geöffnet', SUMMARY: 'Zusammenfassung', RESOLVED: 'erledigt' };
const STATE_LABELS = { OPEN: 'offen', ACKED: 'quittiert', RESOLVED: 'erledigt' };

function ruleResult(outcome) {
    const sent = Object.entries(outcome.notifications)
        .map(([kind, count]) => `${formatNumber(count)} ${KIND_LABELS[kind] ?? kind}`).join(', ');
    const nodes = [element('p', outcome.errors ? 'warn' : 'ok', `${outcome.source}: `
        + `${formatNumber(outcome.events)} Events${outcome.truncated ? ' (gekürzt)' : ''}, `
        + `${formatNumber(outcome.hits)} Treffer, ${formatNumber(outcome.alerts)} `
        + `${outcome.alerts === 1 ? 'Meldung' : 'Meldungen'}, `
        + `Benachrichtigungen: ${sent || 'keine'}, ${formatNumber(outcome.errors)} Fehler`)];
    for (const error of outcome.errorSamples) {
        nodes.push(element('p', 'error', error));
    }
    if (outcome.alertSamples.length) {
        const table = element('table', 'data test-events');
        table.append(row('th', ['Seit', 'Anwendung', 'Text', 'Anzahl', 'Zustand']));
        for (const alert of outcome.alertSamples) {
            table.append(row('td', [formatTime(alert.opened), alert.group ? `${alert.app} · ${alert.group}`
                : alert.app, alert.message, formatNumber(alert.count),
                STATE_LABELS[alert.state] ?? alert.state]));
        }
        nodes.push(element('h3', null, 'Meldungen'), table);
    }
    if (outcome.sentSamples.length) {
        const table = element('table', 'data test-events');
        table.append(row('th', ['Zeit', 'Art', 'Anwendung', 'Text']));
        for (const entry of outcome.sentSamples) {
            table.append(row('td', [formatTime(entry.at), KIND_LABELS[entry.kind] ?? entry.kind, entry.app,
                entry.text]));
        }
        nodes.push(element('h3', null, 'Benachrichtigungen'), table);
    }
    if (outcome.hitSamples.length) {
        const table = element('table', 'data test-events');
        table.append(row('th', ['Zeit', 'Anwendung', 'Zeile']));
        for (const hit of outcome.hitSamples) {
            table.append(row('td', [formatTime(hit.ts), hit.app, hit.line]));
        }
        nodes.push(element('h3', null, 'Treffer'), table);
    }
    return nodes;
}

function parserResult(outcome) {
    const nodes = [];
    nodes.push(element('p', outcome.errors ? 'warn' : 'ok', `${outcome.source}: ${formatNumber(outcome.lines)} `
        + `Zeilen, ${formatNumber(outcome.events)} Events, ${formatNumber(outcome.errors)} Fehler, `
        + `${formatNumber(outcome.eventsPerSecond)} Events/s`));
    if (outcome.undeclared.length) {
        nodes.push(element('p', 'warn', `Nicht deklarierte Felder: ${outcome.undeclared.join(', ')}`));
    }
    const counts = Object.entries(outcome.errorCounts);
    if (counts.length) {
        const table = element('table', 'data');
        table.append(row('th', ['Fehler', 'Anzahl']));
        for (const [error, count] of counts) {
            table.append(row('td', [error, formatNumber(count)]));
        }
        nodes.push(element('h3', null, 'Fehlerarten'), table);
    }
    if (outcome.failed.length) {
        nodes.push(element('h3', null, 'Zeilen mit Fehlern'), eventTable(outcome.failed, outcome.declared));
    }
    nodes.push(element('h3', null, outcome.truncated ? `Die ersten ${outcome.sample.length} Events` : 'Events'),
        eventTable(outcome.sample, outcome.declared));
    return nodes;
}

function eventTable(events, declared) {
    const columns = declared.slice(0, MAX_COLUMNS);
    const table = element('table', 'data test-events');
    table.append(row('th', ['Zeile', 'Zeit', ...columns, 'Message']));
    for (const event of events) {
        const tr = row('td', [event.line, event.ts ? formatTime(event.ts) : 'ohne Zeit',
            ...columns.map(name => format(event.fields?.[name])), firstLine(event.message ?? '')]);
        if (event.error || !event.ts) {
            tr.classList.add('failed');
            tr.title = event.error ?? 'Kein Zeitstempel, die Zeile gehört zu keinem Event';
        }
        table.append(tr);
    }
    return table;
}

function format(value) {
    if (value === undefined || value === null) {
        return '';
    }
    return Array.isArray(value) ? value.join(', ') : String(value);
}

function pseudonymResult(outcome) {
    const nodes = [element('p', outcome.heldBack ? 'warn' : 'ok', `${formatNumber(outcome.events)} Events, `
        + `${formatNumber(outcome.heldBack)} zurückgehalten`)];
    const table = element('table', 'data');
    table.append(row('th', ['Kategorie', 'Pseudonyme']));
    for (const [category, count] of Object.entries(outcome.pseudonyms ?? {})) {
        table.append(row('td', [category, formatNumber(count)]));
    }
    nodes.push(table);
    if (outcome.suspicious?.length) {
        const suspicious = element('table', 'data');
        suspicious.append(row('th', ['Event', 'Feld', 'Kategorie', 'Wert']));
        for (const entry of outcome.suspicious) {
            suspicious.append(row('td', [entry.event, entry.field, entry.category, entry.value]));
        }
        nodes.push(element('h3', 'warn', 'Verdächtige Werte nach dem Skript'), suspicious);
    }
    if (outcome.sample?.length) {
        const pre = element('pre', 'server-log');
        pre.textContent = outcome.sample.map(event => JSON.stringify(event)).join('\n');
        nodes.push(element('h3', null, 'Beispiele nach dem Skript'), pre);
    }
    return nodes;
}

function row(cell, values) {
    const tr = element('tr');
    for (const value of values) {
        tr.append(element(cell, null, value));
    }
    return tr;
}
