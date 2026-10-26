// The log browser: lists the files of hot/ and the archive, shows a file page by page, searches in it, downloads,
// indexes and deletes it. The state of the viewer is in the URL: ?file=<fingerprint>&source=archive|hot&at=<offset>.

import { getJson, sendJson } from './api.js';
import { element, formatNumber, formatTime } from './format.js';
import { describe, follow, notes } from './reindex.js';

const API = '/api/v1/files';
const END = Number.MAX_SAFE_INTEGER;
const ESCAPE = /\u001b\[([0-9;]*)m/g;

const root = document.getElementById('files');
const params = new URLSearchParams(window.location.search);

if (params.get('file')) {
    showFile(params.get('file'), params.get('source') === 'hot' ? 'HOT' : 'ARCHIVE', params.get('at'));
} else {
    showList();
}

function labeled(text, input) {
    const label = element('label', 'field');
    label.append(element('span', 'muted', text), input);
    return label;
}

function checkbox(label, checked) {
    const input = element('input');
    input.type = 'checkbox';
    input.checked = checked;
    const wrapper = element('label', 'check');
    wrapper.append(input, ` ${label}`);
    return { input, wrapper };
}

function button(text, className, onClick) {
    const node = element('button', className, text);
    node.type = 'button';
    node.addEventListener('click', onClick);
    return node;
}

function formatBytes(bytes) {
    if (bytes >= 1048576) {
        return `${formatNumber(Math.round(bytes / 104857.6) / 10)} MB`;
    }
    if (bytes >= 1024) {
        return `${formatNumber(Math.round(bytes / 102.4) / 10)} KB`;
    }
    return `${formatNumber(bytes)} B`;
}

function fileUrl(fingerprint, source, at) {
    const query = new URLSearchParams({ file: fingerprint });
    if (source === 'HOT') {
        query.set('source', 'hot');
    }
    if (at !== null && at !== undefined) {
        query.set('at', at);
    }
    return `/archive?${query}`;
}

function lastPath(paths) {
    return paths?.length ? paths[paths.length - 1] : '';
}

function timeRange(file) {
    const from = file.archive?.from ?? file.hot?.firstReceived;
    const to = file.archive?.to ?? file.hot?.lastReceived;
    if (!from) {
        return '';
    }
    return to ? `${formatTime(from)} bis ${formatTime(to)}` : formatTime(from);
}

function state(file) {
    const parts = [];
    const closed = file.archive?.closed || file.hot?.closed;
    parts.push(closed ? 'abgeschlossen' : 'offen');
    if (file.hot) {
        parts.push(file.indexed ? 'indexiert' : 'nicht vollständig indexiert');
    } else {
        parts.push('nur im Archiv');
    }
    if (!file.archive) {
        parts.push('nicht archiviert');
    }
    if (file.archive?.filterErrors) {
        parts.push(`${formatNumber(file.archive.filterErrors)} Platzhalter`);
    }
    return parts.join(', ');
}

// ---------------------------------------------------------------------------------------------------------------
// The list

function showList() {
    const appSelect = element('select');
    const hostSelect = element('select');
    const fromInput = element('input');
    const toInput = element('input');
    fromInput.type = 'date';
    toInput.type = 'date';
    const filterButton = element('button', 'primary', 'Anzeigen');
    filterButton.type = 'submit';
    const message = element('p', 'error');
    message.hidden = true;
    const summary = element('p', 'muted');
    const table = element('div', 'file-list');
    const blockedSection = element('section');

    const form = element('form', 'archive-form');
    const line = element('div', 'toolbar');
    line.append(labeled('Anwendung', appSelect), labeled('Host', hostSelect), labeled('Erstellt ab', fromInput),
        labeled('Erstellt bis', toInput), filterButton);
    form.append(line);
    const filters = { app: appSelect, host: hostSelect, from: fromInput, to: toInput };
    fromInput.value = params.get('from') ?? '';
    toInput.value = params.get('to') ?? '';
    form.addEventListener('submit', event => {
        event.preventDefault();
        const query = new URLSearchParams();
        for (const [key, input] of Object.entries(filters)) {
            if (input.value) {
                query.set(key, input.value);
            }
        }
        history.replaceState(null, '', query.toString() ? `/archive?${query}` : '/archive');
        load(query);
    });

    root.replaceChildren(element('h1', null, 'Dateien'),
        element('p', 'muted', 'Alle Logdateien, die log-guy aufbewahrt: das Original in hot/ und die gefilterte '
            + 'Fassung im Archiv. Eine Datei lässt sich ansehen, durchsuchen, herunterladen, neu indexieren und '
            + 'löschen.'),
        form, message, summary, table, blockedSection);

    function fillSelect(select, values, selected) {
        select.replaceChildren(new Option('alle', ''));
        for (const value of values) {
            select.append(new Option(value, value, false, value === selected));
        }
    }

    async function load(query) {
        message.hidden = true;
        try {
            const data = await getJson(API, query);
            fillSelect(appSelect, data.apps, query.get('app'));
            fillSelect(hostSelect, data.hosts, query.get('host'));
            summary.textContent = data.truncated
                ? `Die neuesten ${formatNumber(data.files.length)} Dateien, weitere über die Filter.`
                : `${formatNumber(data.files.length)} Dateien`;
            table.replaceChildren(fileTable(data.files));
            if (data.mayDelete) {
                loadBlocked(blockedSection);
            }
        } catch (e) {
            message.textContent = e.message;
            message.hidden = false;
        }
    }

    load(new URLSearchParams([...params].filter(([key]) => key in filters)));
}

function fileTable(files) {
    const table = element('table', 'data');
    const head = element('tr');
    for (const title of ['Erstellt', 'Host', 'Anwendung', 'Datei auf dem Host', 'Original', 'Archiv', 'Zeitraum',
        'Zustand']) {
        head.append(element('th', null, title));
    }
    table.append(head);
    for (const file of files) {
        const row = element('tr');
        const name = element('td', 'mono');
        const link = element('a', null, lastPath(file.paths) || file.fingerprint);
        link.href = fileUrl(file.fingerprint, file.archive ? 'ARCHIVE' : 'HOT');
        link.title = file.paths.join('\n');
        name.append(link);
        row.append(element('td', null, file.created), element('td', null, file.host), element('td', null, file.app),
            name, element('td', 'number', file.hot ? formatBytes(file.hot.length) : ''),
            element('td', 'number', file.archive ? formatBytes(file.archive.compressedSize) : ''),
            element('td', null, timeRange(file)), element('td', null, state(file)));
        table.append(row);
    }
    return table;
}

async function loadBlocked(section) {
    let entries;
    try {
        entries = await getJson(`${API}/blocked`);
    } catch {
        return;
    }
    if (!entries.length) {
        section.replaceChildren();
        return;
    }
    const table = element('table', 'data');
    const head = element('tr');
    for (const title of ['Gelöscht', 'Von', 'Host', 'Anwendung', 'Datei', 'Größe', '']) {
        head.append(element('th', null, title));
    }
    table.append(head);
    for (const entry of entries) {
        const row = element('tr');
        const action = element('td');
        action.append(button('Sperre aufheben', null, async () => {
            if (!window.confirm('Die Sperre aufheben? Schickt der Agent die Datei noch, kommt sie neu an.')) {
                return;
            }
            try {
                await sendJson('DELETE', `${API}/blocked/${encodeURIComponent(entry.fingerprint)}`);
                loadBlocked(section);
            } catch (e) {
                window.alert(e.message);
            }
        }));
        row.append(element('td', null, formatTime(entry.deletedAt)), element('td', null, entry.user),
            element('td', null, entry.host), element('td', null, entry.app),
            element('td', 'mono', entry.relativePath), element('td', 'number', formatBytes(entry.length)), action);
        table.append(row);
    }
    section.replaceChildren(element('h2', null, 'Gesperrte Dateien'),
        element('p', 'muted', 'Gelöschte Dateien, die ein Agent nicht erneut einliefern kann. Der Server antwortet '
            + 'ihm, die Datei sei abgeschlossen.'),
        table);
}

// ---------------------------------------------------------------------------------------------------------------
// The viewer

async function showFile(fingerprint, requestedSource, at) {
    const message = element('p', 'error');
    message.hidden = true;
    root.replaceChildren(message);
    let detail;
    try {
        detail = await getJson(`${API}/${encodeURIComponent(fingerprint)}`);
    } catch (e) {
        const back = element('a', null, 'Zur Liste');
        back.href = '/archive';
        root.replaceChildren(element('h1', null, 'Datei'), back, message);
        message.textContent = e.message;
        message.hidden = false;
        return;
    }
    const file = detail.file;
    const may = detail.may;
    let source = requestedSource;
    if (source === 'HOT' && (!file.hot || !may.hot)) {
        source = 'ARCHIVE';
    }
    if (source === 'ARCHIVE' && !file.archive && file.hot && may.hot) {
        source = 'HOT';
    }
    const readable = source === 'HOT' ? !!file.hot : !!file.archive;

    const back = element('a', null, 'Zur Liste');
    back.href = '/archive';
    const title = element('h1', 'mono', lastPath(file.paths) || fingerprint);
    root.replaceChildren(back, title, info(file), actions(file, may, source, message), message);
    if (!readable) {
        message.textContent = file.hot
            ? 'Die Datei ist noch nicht archiviert, das Original in hot/ sehen nur Administratoren.'
            : 'Die Datei ist nicht mehr vorhanden.';
        message.hidden = false;
        return;
    }
    const viewer = new Viewer(fingerprint, source, may.pseudonymized);
    root.append(viewer.searchBox, viewer.box);
    viewer.open(at === null ? null : Number(at));
}

function info(file) {
    const list = element('dl', 'file-info');
    const add = (term, value, className) => {
        if (value === null || value === undefined || value === '') {
            return;
        }
        list.append(element('dt', null, term), element('dd', className, value));
    };
    add('Host', file.host);
    add('Anwendung', file.app);
    add('Erstellt', file.created);
    add('Pfade auf dem Host', file.paths.join(', '), 'mono');
    add('Zeitraum', timeRange(file));
    add('Zustand', state(file));
    if (file.hot) {
        add('Original in hot/', `${formatBytes(file.hot.length)}, ${file.hot.path}`, 'mono');
    }
    if (file.archive) {
        add('Archiv', `${formatBytes(file.archive.length)} gefiltert, ${formatBytes(file.archive.compressedSize)} `
            + `komprimiert, ${formatNumber(file.archive.lines)} Zeilen, ${file.archive.path}`, 'mono');
        add('Filter', file.archive.filters.join(', '), 'mono');
    }
    add('Fingerabdruck', file.fingerprint, 'mono');
    return list;
}

function actions(file, may, source, message) {
    const bar = element('div', 'toolbar');
    if (file.archive && file.hot && may.hot) {
        const archiveLink = element('a', source === 'ARCHIVE' ? 'button active' : 'button', 'Archiv (gefiltert)');
        archiveLink.href = fileUrl(file.fingerprint, 'ARCHIVE');
        const hotLink = element('a', source === 'HOT' ? 'button active' : 'button', 'Original in hot/');
        hotLink.href = fileUrl(file.fingerprint, 'HOT');
        bar.append(archiveLink, hotLink);
    }
    if (may.download && (source === 'HOT' ? file.hot : file.archive)) {
        const download = element('a', 'button', 'Herunterladen');
        download.href = `${API}/${encodeURIComponent(file.fingerprint)}/download?source=${source}`;
        bar.append(download);
    }
    const status = element('span', 'muted');
    if (may.reindexHot || may.reindexArchive) {
        const reindex = button('Neu indexieren', null, async () => {
            if (!window.confirm('Die Datei neu indexieren? Der Teil im Index-Fenster wird aus hot/ neu indexiert, '
                + 'ältere Teile werden befristet aus dem Archiv geladen.')) {
                return;
            }
            reindex.disabled = true;
            message.hidden = true;
            try {
                const job = await sendJson('POST', `${API}/${encodeURIComponent(file.fingerprint)}/reindex`);
                await follow(job, current => {
                    status.textContent = describe(current);
                    message.textContent = notes(current);
                    message.hidden = !message.textContent;
                });
            } catch (e) {
                message.textContent = e.message;
                message.hidden = false;
            }
            reindex.disabled = false;
        });
        bar.append(reindex);
    }
    if (may.delete) {
        const block = checkbox('Erneutes Einliefern sperren', true);
        const remove = button('Löschen', 'danger', async () => {
            const text = block.input.checked
                ? 'Die Datei aus hot/, dem Archiv, dem Backup und dem Index löschen und für die Agenten sperren?'
                : 'Die Datei aus hot/, dem Archiv, dem Backup und dem Index löschen? Ohne Sperre liefert der Agent '
                    + 'sie neu ein, falls er sie noch hat.';
            if (!window.confirm(text)) {
                return;
            }
            remove.disabled = true;
            try {
                const result = await sendJson('DELETE',
                    `${API}/${encodeURIComponent(file.fingerprint)}?block=${block.input.checked}`);
                if (result.indexError) {
                    window.alert(`Gelöscht, aber die Events im Index nicht: ${result.indexError}`);
                }
                window.location.href = '/archive';
            } catch (e) {
                message.textContent = e.message;
                message.hidden = false;
                remove.disabled = false;
            }
        });
        bar.append(remove, block.wrapper);
    }
    bar.append(status);
    return bar;
}

class Viewer {

    constructor(fingerprint, source, pseudonymized) {
        this.path = `${API}/${encodeURIComponent(fingerprint)}`;
        this.fingerprint = fingerprint;
        this.source = source;
        this.first = null;
        this.next = null;
        this.highlight = null;
        this.searchFrom = 0;

        this.lines = element('div', 'file-lines');
        this.message = element('p', 'error');
        this.message.hidden = true;
        this.status = element('span', 'muted');
        this.earlier = button('Davor laden', null, () => this.loadBefore());
        this.later = button('Weiter laden', null, () => this.loadAfter());
        const top = element('div', 'toolbar');
        top.append(button('Anfang', null, () => this.open(0)), button('Ende', null, () => this.openEnd()),
            this.earlier, this.status);
        const bottom = element('div', 'toolbar');
        bottom.append(this.later, button('Ende', null, () => this.openEnd()));
        this.box = element('section', 'file-view');
        this.box.append(top, this.message, this.lines, bottom);

        this.query = element('input', 'archive-query');
        this.query.type = 'search';
        this.query.placeholder = pseudonymized ? 'Text oder regulärer Ausdruck, pseudonymisiert'
            : 'Text oder regulärer Ausdruck';
        this.regex = checkbox('Regulärer Ausdruck', false);
        this.ignoreCase = checkbox('Groß/Klein ignorieren', true);
        this.searchButton = element('button', 'primary', 'In der Datei suchen');
        this.searchButton.type = 'submit';
        this.moreButton = button('Weitersuchen', null, () => this.search(false));
        this.moreButton.hidden = true;
        this.searchStatus = element('span', 'muted');
        this.hits = element('ol', 'file-hits');
        const form = element('form', 'archive-form');
        const line = element('div', 'toolbar');
        line.append(this.query, this.regex.wrapper, this.ignoreCase.wrapper, this.searchButton, this.moreButton,
            this.searchStatus);
        form.append(line);
        form.addEventListener('submit', event => {
            event.preventDefault();
            this.search(true);
        });
        this.searchBox = element('section', 'file-search');
        this.searchBox.append(form, this.hits);
    }

    url(at) {
        history.replaceState(null, '', fileUrl(this.fingerprint, this.source, at));
    }

    async fetchPage(query) {
        this.message.hidden = true;
        try {
            return await getJson(`${this.path}/content`, new URLSearchParams({ source: this.source, ...query }));
        } catch (e) {
            this.message.textContent = e.message;
            this.message.hidden = false;
            return null;
        }
    }

    async open(at) {
        const page = await this.fetchPage(at === null ? {} : { at });
        if (!page) {
            return;
        }
        this.lines.replaceChildren();
        this.append(page);
        this.first = page.lines.length ? page.lines[0].offset : 0;
        this.hasBefore = page.prev !== null;
        this.update(page);
        this.url(at);
        // the first line of the page contains the offset
        if (at !== null && this.lines.firstElementChild) {
            this.lines.firstElementChild.classList.add('target');
            this.lines.firstElementChild.scrollIntoView({ block: 'center' });
        }
    }

    async openEnd() {
        const page = await this.fetchPage({ before: END });
        if (!page) {
            return;
        }
        this.lines.replaceChildren();
        for (const line of page.lines) {
            this.lines.append(this.lineElement(line));
        }
        this.first = page.lines.length ? page.lines[0].offset : 0;
        this.hasBefore = page.prev !== null;
        this.next = null;
        this.update(page);
        this.url(this.first);
        this.lines.lastElementChild?.scrollIntoView({ block: 'end' });
    }

    async loadBefore() {
        const page = await this.fetchPage({ before: this.first });
        if (!page) {
            return;
        }
        const anchor = this.lines.firstElementChild;
        const nodes = page.lines.map(line => this.lineElement(line));
        this.lines.prepend(...nodes);
        anchor?.scrollIntoView({ block: 'start' });
        this.first = page.lines.length ? page.lines[0].offset : this.first;
        this.hasBefore = page.prev !== null;
        this.update(page);
    }

    async loadAfter() {
        const page = await this.fetchPage(this.next === null ? { before: END } : { at: this.next });
        if (!page) {
            return;
        }
        if (this.next === null) {
            // the end was shown, a live file may have grown: show its end again
            this.openEnd();
            return;
        }
        this.append(page);
        this.update(page);
    }

    append(page) {
        for (const line of page.lines) {
            this.lines.append(this.lineElement(line));
        }
        this.next = page.next;
    }

    update(page) {
        this.earlier.disabled = !this.hasBefore;
        this.later.textContent = this.next === null ? 'Neu laden' : 'Weiter laden';
        this.status.textContent = `${formatNumber(this.lines.childElementCount)} Zeilen, Datei `
            + `${formatBytes(page.length)}${this.next === null ? ', Ende erreicht' : ''}`;
    }

    lineElement(line) {
        const row = element('div', 'file-line');
        const offset = element('a', 'file-offset', formatNumber(line.offset));
        offset.href = fileUrl(this.fingerprint, this.source, line.offset);
        offset.addEventListener('click', event => {
            event.preventDefault();
            this.url(line.offset);
            this.lines.querySelectorAll('.target').forEach(node => node.classList.remove('target'));
            row.classList.add('target');
        });
        const text = element('span', 'file-text');
        renderAnsi(text, line.text ?? '', this.highlight);
        row.append(offset, text);
        return row;
    }

    async search(fresh) {
        if (!this.query.value) {
            return;
        }
        if (fresh) {
            this.searchFrom = 0;
            this.hits.replaceChildren();
            this.highlight = highlighter(this.query.value, this.regex.input.checked, this.ignoreCase.input.checked);
        }
        this.searchButton.disabled = true;
        this.moreButton.hidden = true;
        this.searchStatus.textContent = 'sucht …';
        try {
            const result = await getJson(`${this.path}/search`, new URLSearchParams({
                source: this.source,
                q: this.query.value,
                regex: this.regex.input.checked,
                ignoreCase: this.ignoreCase.input.checked,
                from: this.searchFrom,
            }));
            for (const hit of result.hits) {
                const item = element('li');
                const link = element('a', 'mono');
                link.href = fileUrl(this.fingerprint, this.source, hit.offset);
                renderAnsi(link, hit.text ?? '', this.highlight);
                link.addEventListener('click', event => {
                    event.preventDefault();
                    this.open(hit.offset);
                });
                item.append(element('span', 'file-offset', formatNumber(hit.offset)), link);
                this.hits.append(item);
            }
            this.searchFrom = result.scannedTo;
            const count = this.hits.childElementCount;
            this.searchStatus.textContent = result.done
                ? `${formatNumber(count)} Treffer, ganze Datei durchsucht`
                : `${formatNumber(count)} Treffer bis ${formatBytes(result.scannedTo)}`;
            this.moreButton.hidden = result.done;
            if (fresh && result.hits.length) {
                this.open(result.hits[0].offset);
            }
        } catch (e) {
            this.searchStatus.textContent = e.message;
        }
        this.searchButton.disabled = false;
    }
}

// A function that splits a text into parts with and without a match, null if the query is not a valid expression
// in JavaScript. The server decides with RE2, the marks are only a help.
function highlighter(query, regex, ignoreCase) {
    let pattern;
    try {
        pattern = new RegExp(regex ? query : query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), ignoreCase ? 'gi' : 'g');
    } catch {
        return null;
    }
    return text => {
        const parts = [];
        let last = 0;
        pattern.lastIndex = 0;
        let match;
        while ((match = pattern.exec(text)) !== null) {
            if (match[0] === '') {
                pattern.lastIndex++;
                continue;
            }
            parts.push([text.substring(last, match.index), false], [match[0], true]);
            last = match.index + match[0].length;
        }
        parts.push([text.substring(last), false]);
        return parts;
    };
}

// Renders terminal colors as CSS classes: 1 bold, 30 to 37 and 90 to 97 foreground, 0 or 39 reset.
function renderAnsi(target, text, highlight) {
    let classes = [];
    let last = 0;
    const emit = part => {
        if (!part) {
            return;
        }
        const pieces = highlight ? highlight(part) : [[part, false]];
        for (const [piece, marked] of pieces) {
            if (!piece) {
                continue;
            }
            const node = marked ? element('mark', classes.join(' ') || null, piece)
                : classes.length ? element('span', classes.join(' '), piece) : document.createTextNode(piece);
            target.append(node);
        }
    };
    ESCAPE.lastIndex = 0;
    let match;
    while ((match = ESCAPE.exec(text)) !== null) {
        emit(text.substring(last, match.index));
        last = match.index + match[0].length;
        for (const code of (match[1] || '0').split(';').map(Number)) {
            if (code === 0) {
                classes = [];
            } else if (code === 1) {
                classes.push('ansi-bold');
            } else if (code === 39) {
                classes = classes.filter(name => !name.startsWith('ansi-fg'));
            } else if (code >= 30 && code <= 37 || code >= 90 && code <= 97) {
                classes = classes.filter(name => !name.startsWith('ansi-fg'));
                classes.push(`ansi-fg-${code}`);
            }
        }
    }
    emit(text.substring(last));
}
