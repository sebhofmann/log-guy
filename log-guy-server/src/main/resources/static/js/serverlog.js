// The server log page: the newest entries of the own log from the ring buffer, followed live, and the log levels
// of the loggers through the actuator endpoint.

import { ApiError, getJson, sendJson } from './api.js';
import { element, formatTime } from './format.js';

const POLL = 2000;
const LIMIT = 1000;
const LEVELS = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL', 'OFF'];

const root = document.getElementById('serverlog');
const levelSelect = element('select');
const loggerInput = element('input');
const pauseButton = element('button', null, 'Anhalten');
const clearButton = element('button', null, 'Leeren');
const statusText = element('span', 'muted');
const output = element('pre', 'server-log');
const loggerBox = element('section');

let after = -1;
let paused = false;
let timer = null;
let lines = [];

build();
poll();
loadLoggers();

function build() {
    for (const level of ['', ...LEVELS.slice(0, -1)]) {
        const option = element('option', null, level || 'Alle Stufen');
        option.value = level;
        levelSelect.append(option);
    }
    levelSelect.setAttribute('aria-label', 'Mindeststufe');
    loggerInput.type = 'search';
    loggerInput.placeholder = 'Logger enthält …';
    loggerInput.setAttribute('aria-label', 'Logger');
    pauseButton.type = 'button';
    clearButton.type = 'button';
    levelSelect.addEventListener('change', restart);
    loggerInput.addEventListener('change', restart);
    pauseButton.addEventListener('click', () => {
        paused = !paused;
        pauseButton.textContent = paused ? 'Fortsetzen' : 'Anhalten';
        if (!paused) {
            poll();
        }
    });
    clearButton.addEventListener('click', () => {
        lines = [];
        output.replaceChildren();
    });
    const toolbar = element('div', 'toolbar');
    toolbar.append(levelSelect, loggerInput, pauseButton, clearButton, statusText);
    root.replaceChildren(element('h1', null, 'Server-Log'), toolbar, output, loggerBox);
}

function restart() {
    after = -1;
    lines = [];
    output.replaceChildren();
    poll();
}

async function poll() {
    clearTimeout(timer);
    if (paused) {
        return;
    }
    const params = new URLSearchParams({ after, limit: LIMIT });
    if (levelSelect.value) {
        params.set('level', levelSelect.value);
    }
    if (loggerInput.value.trim()) {
        params.set('logger', loggerInput.value.trim());
    }
    try {
        const page = await getJson('/admin/api/log', params);
        append(page.entries);
        const last = page.entries.length ? page.entries[page.entries.length - 1].seq : -1;
        after = Math.max(after, last, page.next - 1);
        statusText.textContent = `${lines.length} Einträge, aktualisiert ${formatTime(new Date())}`;
        statusText.classList.remove('error');
    } catch (e) {
        statusText.textContent = e.message;
        statusText.classList.add('error');
    }
    timer = setTimeout(poll, POLL);
}

function append(entries) {
    if (!entries.length) {
        return;
    }
    const stick = output.scrollHeight - output.scrollTop - output.clientHeight < 20;
    for (const entry of entries) {
        const span = element('span', `level-${entry.level}`, `${formatTime(entry.ts)} ${entry.level.padEnd(5)} `
            + `[${entry.thread}] ${entry.logger}: ${entry.message}\n${entry.thrown ? `${entry.thrown}\n` : ''}`);
        lines.push(span);
        output.append(span);
    }
    while (lines.length > LIMIT) {
        lines.shift().remove();
    }
    if (stick) {
        output.scrollTop = output.scrollHeight;
    }
}

async function loadLoggers() {
    let data;
    try {
        data = await getJson('/actuator/loggers');
    } catch (e) {
        loggerBox.replaceChildren(element('h2', null, 'Log-Level'), element('p', 'error', e.message));
        return;
    }
    const filter = element('input');
    filter.type = 'search';
    filter.placeholder = 'Logger suchen, z. B. de.gbv.logguy';
    filter.setAttribute('aria-label', 'Logger suchen');
    const table = element('table', 'data');
    const body = element('tbody');
    table.append(body);
    const names = Object.keys(data.loggers);
    const render = () => {
        const part = filter.value.trim().toLowerCase();
        const rows = names.filter(n => !part || n.toLowerCase().includes(part)).slice(0, 200)
            .map(n => loggerRow(n, data.loggers[n]));
        body.replaceChildren(...rows);
    };
    filter.addEventListener('input', render);
    const toolbar = element('div', 'toolbar');
    toolbar.append(filter, element('span', 'muted', 'Änderungen gelten bis zum Neustart, höchstens 200 Logger '
        + 'werden angezeigt.'));
    loggerBox.replaceChildren(element('h2', null, 'Log-Level'), toolbar, table);
    render();
}

function loggerRow(name, logger) {
    const row = element('tr');
    const select = element('select');
    const inherit = element('option', null, `erben (${logger.effectiveLevel})`);
    inherit.value = '';
    select.append(inherit);
    for (const level of LEVELS) {
        const option = element('option', null, level);
        option.value = level;
        select.append(option);
    }
    select.value = logger.configuredLevel ?? '';
    select.setAttribute('aria-label', `Stufe von ${name}`);
    const result = element('span', 'muted');
    select.addEventListener('change', async () => {
        try {
            await sendJson('POST', `/actuator/loggers/${encodeURIComponent(name)}`,
                { configuredLevel: select.value || null });
            logger.configuredLevel = select.value || null;
            result.textContent = 'gespeichert';
            result.classList.remove('error');
        } catch (e) {
            result.textContent = e instanceof ApiError ? e.message : String(e);
            result.classList.add('error');
        }
    });
    const nameCell = element('td', null, name);
    const selectCell = element('td');
    selectCell.append(select, document.createTextNode(' '), result);
    row.append(nameCell, selectCell);
    return row;
}
