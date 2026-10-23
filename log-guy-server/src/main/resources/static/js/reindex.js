// Indexing again: loading older days from the archive into the index with an expiry, and indexing the files of
// hot/ again after a parser changed. Used by the archive page and the script editor.

import { getJson, sendJson } from './api.js';
import { element, formatNumber, formatTime, toLocalInput } from './format.js';

const API = '/api/v1/reindex';
const POLL = 1000;
const STATES = {
    RUNNING: 'läuft',
    DONE: 'fertig',
    CANCELLED: 'abgebrochen',
    FAILED: 'fehlgeschlagen',
};

function labeled(text, input) {
    const label = element('label', 'field');
    label.append(element('span', 'muted', text), input);
    return label;
}

function describe(status) {
    const parts = [STATES[status.state] ?? status.state];
    if (status.hotFiles) {
        parts.push(`${formatNumber(status.hotFiles)} Dateien aus hot/ an den Indexer übergeben`);
    }
    if (status.archiveFrom) {
        parts.push(`${formatNumber(status.events)} Events aus ${formatNumber(status.archiveFilesDone)} von `
            + `${formatNumber(status.archiveFiles)} Archivdateien geladen`);
        if (status.expires) {
            parts.push(`im Index bis ${formatTime(status.expires)}`);
        }
    }
    return parts.join(', ');
}

function notes(status) {
    const result = [];
    if (status.message) {
        result.push(status.message);
    }
    if (status.failed?.length) {
        result.push(`Nicht lesbar: ${status.failed.join(', ')}`);
    }
    return result.join('. ');
}

/**
 * Follows a job until it finishes.
 *
 * @param {object} status the status returned by the start
 * @param {(status: object) => void} onUpdate called with every new status
 * @returns {Promise<object>} the last status
 */
async function follow(status, onUpdate) {
    let current = status;
    onUpdate(current);
    while (current.state === 'RUNNING') {
        await new Promise(resolve => setTimeout(resolve, POLL));
        current = await getJson(`${API}/${current.id}`);
        onUpdate(current);
    }
    return current;
}

/**
 * Offers to index the index window of the given applications again after a parser was activated.
 *
 * @param {HTMLElement} box where the offer is shown
 * @param {string[]} apps applications that use the parser
 */
export async function offerReindex(box, apps) {
    box.replaceChildren();
    if (!apps?.length) {
        return;
    }
    let overview;
    try {
        overview = await getJson(API);
    } catch {
        return;
    }
    const allowed = overview.apps.filter(entry => entry.hot && apps.includes(entry.app));
    if (!allowed.length) {
        return;
    }
    const text = element('p', 'muted', 'Bereits indexierte Events bleiben, wie sie sind. Das Index-Fenster lässt '
        + 'sich mit der neuen Fassung neu indexieren, die Suche zeigt währenddessen teils alte, teils neue Felder.');
    const actions = element('div', 'toolbar');
    const result = element('span', 'muted');
    for (const entry of allowed) {
        const start = element('button', null, `${entry.app} neu indexieren`);
        start.type = 'button';
        start.addEventListener('click', async () => {
            start.disabled = true;
            try {
                const status = await sendJson('POST', API, { app: entry.app, from: entry.windowStart, source: 'HOT' });
                result.textContent = `${entry.app}: ${describe(status)}`;
            } catch (e) {
                result.textContent = e.message;
                start.disabled = false;
            }
        });
        actions.append(start);
    }
    actions.append(result);
    box.append(text, actions);
}

/**
 * Builds the section of the archive page that loads a time range into the index.
 *
 * @param {HTMLElement} root the section
 */
async function archiveSection(root) {
    let overview;
    try {
        overview = await getJson(API);
    } catch {
        return;
    }
    const apps = overview.apps.filter(entry => entry.archive);
    if (!apps.length) {
        return;
    }
    const appSelect = element('select');
    for (const entry of apps) {
        const option = element('option', null, entry.app);
        option.value = entry.app;
        appSelect.append(option);
    }
    const fromInput = element('input');
    const toInput = element('input');
    for (const input of [fromInput, toInput]) {
        input.type = 'datetime-local';
        input.step = 1;
    }
    const windowInfo = element('span', 'muted');
    const startButton = element('button', 'primary', 'Laden');
    startButton.type = 'submit';
    const cancelButton = element('button', null, 'Abbrechen');
    cancelButton.type = 'button';
    cancelButton.hidden = true;
    const progress = element('progress');
    progress.hidden = true;
    const statusText = element('span', 'muted');
    const message = element('p', 'error');
    message.hidden = true;
    const jobList = element('div');
    let job = null;

    function selectApp() {
        const entry = apps.find(candidate => candidate.app === appSelect.value);
        const start = new Date(entry.windowStart);
        windowInfo.textContent = `Im Index ab ${formatTime(entry.windowStart)}`;
        toInput.value = toLocalInput(start);
        fromInput.value = toLocalInput(new Date(start.getTime() - 24 * 3600 * 1000));
    }

    function update(status) {
        job = status;
        const running = status.state === 'RUNNING';
        cancelButton.hidden = !running;
        startButton.disabled = running;
        progress.hidden = false;
        progress.max = Math.max(1, status.bytes);
        progress.value = running ? status.bytesDone : progress.max;
        statusText.textContent = describe(status);
        message.textContent = notes(status);
        message.hidden = !message.textContent;
    }

    function showJobs(jobs) {
        const loads = jobs.filter(entry => entry.archiveFrom);
        if (!loads.length) {
            jobList.replaceChildren();
            return;
        }
        const table = element('table', 'data');
        const head = element('tr');
        for (const title of ['Gestartet', 'Anwendung', 'Zeitraum', 'Zustand', 'Events', 'Im Index bis']) {
            head.append(element('th', null, title));
        }
        table.append(head);
        for (const entry of loads) {
            const row = element('tr');
            row.append(element('td', null, formatTime(entry.started)), element('td', null, entry.app),
                element('td', null, `${formatTime(entry.archiveFrom)} bis ${formatTime(entry.archiveTo)}`),
                element('td', null, STATES[entry.state] ?? entry.state),
                element('td', 'number', formatNumber(entry.events)),
                element('td', null, entry.expires ? formatTime(entry.expires) : ''));
            table.append(row);
        }
        jobList.replaceChildren(element('h3', null, 'Letzte Ladevorgänge'), table);
    }

    async function refreshJobs() {
        try {
            showJobs((await getJson(API)).jobs);
        } catch {
            // the table is optional
        }
    }

    const form = element('form', 'archive-form');
    const range = element('div', 'toolbar');
    range.append(labeled('Anwendung', appSelect), labeled('Von', fromInput), labeled('Bis', toInput), windowInfo);
    const actions = element('div', 'toolbar');
    actions.append(startButton, cancelButton, progress, statusText);
    form.append(range, actions);
    appSelect.addEventListener('change', selectApp);
    form.addEventListener('submit', async event => {
        event.preventDefault();
        message.hidden = true;
        try {
            const status = await sendJson('POST', API, {
                app: appSelect.value,
                from: fromInput.value ? new Date(fromInput.value).toISOString() : null,
                to: toInput.value ? new Date(toInput.value).toISOString() : null,
                source: 'ARCHIVE',
            });
            await follow(status, update);
            refreshJobs();
        } catch (e) {
            message.textContent = e.message;
            message.hidden = false;
            startButton.disabled = false;
        }
    });
    cancelButton.addEventListener('click', async () => {
        if (job) {
            try {
                update(await sendJson('DELETE', `${API}/${job.id}`));
            } catch (e) {
                message.textContent = e.message;
                message.hidden = false;
            }
        }
    });
    root.replaceChildren(element('h2', null, 'Zeitraum in den Index laden'),
        element('p', 'muted', `Lädt Events vor dem Index-Fenster aus dem Archiv in den Index, damit Suche, Verlauf `
            + `und Facetten sie zeigen. Nach ${formatNumber(overview.expiresAfterHours)} Stunden werden sie bei `
            + 'der nächsten stündlichen Löschung wieder entfernt.'),
        form, message, jobList);
    selectApp();
    showJobs(overview.jobs);
}

const section = document.getElementById('reindex');
if (section) {
    archiveSection(section);
}
