// The archive search page: starts a job on the server, shows its progress and loads the hits while it runs.

import { ApiError, getJson, sendJson } from './api.js';
import { element, formatNumber, formatTime, toLocalInput } from './format.js';

const API = '/api/v1/archive-search';
const POLL = 1000;
const STATES = {
    RUNNING: 'läuft',
    DONE: 'fertig',
    CANCELLED: 'abgebrochen',
    LIMIT: 'Trefferlimit erreicht',
    TIMEOUT: 'Zeit überschritten',
};

const root = document.getElementById('archive');
const queryInput = element('input');
const regexBox = checkbox('Regulärer Ausdruck');
const caseBox = checkbox('Groß/Klein ignorieren');
const appSelect = element('select');
const fromInput = element('input');
const toInput = element('input');
const contextInput = element('input');
const startButton = element('button', 'primary', 'Suchen');
const cancelButton = element('button', null, 'Abbrechen');
const progress = element('progress');
const statusText = element('span', 'muted');
const message = element('p', 'error');
const output = element('div', 'archive-hits');
const jobList = element('section');

let job = null;
let offset = 0;
let timer = null;

build();
load();

function checkbox(label) {
    const input = element('input');
    input.type = 'checkbox';
    const wrapper = element('label', 'check');
    wrapper.append(input, ` ${label}`);
    return { input, wrapper };
}

function labeled(text, input) {
    const label = element('label', 'field');
    label.append(element('span', 'muted', text), input);
    return label;
}

function build() {
    queryInput.type = 'search';
    queryInput.required = true;
    queryInput.placeholder = 'Text oder regulärer Ausdruck';
    queryInput.className = 'archive-query';
    appSelect.multiple = true;
    appSelect.size = 4;
    for (const input of [fromInput, toInput]) {
        input.type = 'datetime-local';
        input.step = 1;
    }
    const now = new Date();
    toInput.value = toLocalInput(now);
    fromInput.value = toLocalInput(new Date(now.getTime() - 24 * 3600 * 1000));
    contextInput.type = 'number';
    contextInput.min = 0;
    contextInput.max = 20;
    contextInput.value = 0;
    startButton.type = 'submit';
    cancelButton.type = 'button';
    cancelButton.hidden = true;
    progress.max = 1;
    progress.value = 0;
    progress.hidden = true;

    const form = element('form', 'archive-form');
    const line = element('div', 'toolbar');
    line.append(labeled('Ausdruck', queryInput), regexBox.wrapper, caseBox.wrapper);
    const range = element('div', 'toolbar');
    range.append(labeled('Anwendungen', appSelect), labeled('Von', fromInput), labeled('Bis', toInput),
        labeled('Kontextzeilen', contextInput));
    const actions = element('div', 'toolbar');
    actions.append(startButton, cancelButton, progress, statusText);
    form.append(line, range, actions);
    form.addEventListener('submit', event => {
        event.preventDefault();
        start();
    });
    cancelButton.addEventListener('click', cancel);
    message.hidden = true;
    root.replaceChildren(element('h1', null, 'Archivsuche'),
        element('p', 'muted', 'Durchsucht die aufbewahrten Logdateien wie zgrep. Reguläre Ausdrücke in RE2-Syntax, '
            + 'ohne Rückverweise und Lookaround.'),
        form, message, output, jobList);
}

async function load() {
    try {
        const data = await getJson(API);
        appSelect.replaceChildren();
        for (const app of data.apps) {
            const option = element('option', null, app);
            option.value = app;
            appSelect.append(option);
        }
        showJobs(data.jobs);
    } catch (e) {
        showError(e);
    }
}

function showJobs(jobs) {
    if (!jobs.length) {
        jobList.replaceChildren();
        return;
    }
    const table = element('table', 'data');
    const head = element('tr');
    for (const title of ['Gestartet', 'Ausdruck', 'Anwendungen', 'Zustand', 'Treffer', '']) {
        head.append(element('th', null, title));
    }
    table.append(head);
    for (const entry of jobs) {
        const row = element('tr');
        const open = element('button', null, 'Anzeigen');
        open.type = 'button';
        open.addEventListener('click', () => show(entry));
        const action = element('td');
        action.append(open);
        row.append(element('td', null, formatTime(entry.started)), element('td', 'mono', entry.query),
            element('td', null, entry.apps.join(', ')), element('td', null, STATES[entry.state] ?? entry.state),
            element('td', 'number', formatNumber(entry.hits)), action);
        table.append(row);
    }
    jobList.replaceChildren(element('h2', null, 'Letzte Suchen'), table);
}

async function start() {
    message.hidden = true;
    const request = {
        apps: [...appSelect.selectedOptions].map(option => option.value),
        hosts: [],
        query: queryInput.value,
        regex: regexBox.input.checked,
        ignoreCase: caseBox.input.checked,
        from: fromInput.value ? new Date(fromInput.value).toISOString() : null,
        to: toInput.value ? new Date(toInput.value).toISOString() : null,
        context: Number(contextInput.value) || 0,
    };
    try {
        show(await sendJson('POST', API, request));
    } catch (e) {
        showError(e);
    }
}

function show(status) {
    clearTimeout(timer);
    job = status;
    offset = 0;
    output.replaceChildren();
    update(status);
    poll();
}

async function cancel() {
    if (!job) {
        return;
    }
    try {
        update(await sendJson('DELETE', `${API}/${job.id}`));
    } catch (e) {
        showError(e);
    }
}

async function poll() {
    clearTimeout(timer);
    const current = job;
    try {
        const status = await getJson(`${API}/${current.id}`);
        await loadHits(current);
        if (job !== current) {
            return;
        }
        update(status);
        if (status.state === 'RUNNING') {
            timer = setTimeout(poll, POLL);
        } else {
            await loadHits(current);
            load();
        }
    } catch (e) {
        showError(e);
    }
}

async function loadHits(current) {
    const params = new URLSearchParams({ offset, follow: false });
    const response = await fetch(`${API}/${current.id}/results?${params}`, {
        headers: { Accept: 'application/x-ndjson' },
        credentials: 'same-origin',
    });
    if (!response.ok) {
        throw new ApiError(response.status, `Fehler ${response.status}`);
    }
    const text = await response.text();
    if (job !== current) {
        return;
    }
    for (const line of text.split('\n')) {
        if (line) {
            output.append(hitElement(JSON.parse(line)));
            offset++;
        }
    }
}

function hitElement(hit) {
    const box = element('article', 'archive-hit');
    box.append(element('div', 'muted', `${hit.app} · ${hit.host} · ${hit.file} @ ${formatNumber(hit.offset)}`));
    const pre = element('pre');
    for (const line of hit.before) {
        pre.append(element('span', 'muted', `${line}\n`));
    }
    pre.append(element('strong', null, `${hit.line}\n`));
    for (const line of hit.after) {
        pre.append(element('span', 'muted', `${line}\n`));
    }
    box.append(pre);
    return box;
}

function update(status) {
    const running = status.state === 'RUNNING';
    cancelButton.hidden = !running;
    progress.hidden = false;
    progress.max = Math.max(1, status.bytes);
    progress.value = running ? status.bytesDone : progress.max;
    let text = `${STATES[status.state] ?? status.state}: ${formatNumber(status.hits)} Treffer, `
        + `${formatNumber(status.filesDone)} von ${formatNumber(status.files)} Dateien, `
        + `${formatNumber(Math.round(status.bytesDone / 1048576))} von `
        + `${formatNumber(Math.round(status.bytes / 1048576))} MB`;
    if (status.pseudonymized) {
        text += ', pseudonymisiert';
    }
    statusText.textContent = text;
    const notes = [];
    if (status.message) {
        notes.push(status.message);
    }
    if (status.failed.length) {
        notes.push(`Nicht lesbar: ${status.failed.join(', ')}`);
    }
    message.textContent = notes.join('. ');
    message.hidden = notes.length === 0;
}

function showError(e) {
    message.textContent = e.message;
    message.hidden = false;
}
