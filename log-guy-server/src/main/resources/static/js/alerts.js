// The alert page: alerts by state with acknowledge and a link to the events, and the loaded rules.

import { getJson, sendJson } from './api.js';
import { element, formatNumber, formatTime } from './format.js';

const API = '/api/v1/alerts';
const STATES = { OPEN: 'offen', ACKED: 'quittiert', RESOLVED: 'erledigt' };
const BADGES = { OPEN: 'bad', ACKED: 'warn', RESOLVED: 'ok' };
const TYPES = { HIT: 'Treffer', THRESHOLD: 'Schwelle', ABSENCE: 'Stille' };
const FILTERS = [['open', 'Offen'], ['acked', 'Quittiert'], ['resolved', 'Erledigt'], ['all', 'Alle']];
// events shown before and after an alert
const MARGIN = 5 * 60 * 1000;

const root = document.getElementById('alerts');
const rulesRoot = document.getElementById('rules');
const table = element('div');
const message = element('p', 'error');
let filter = 'open';
let reload = null;

build();
load();
loadRules();
document.addEventListener('log-guy-alerts', () => {
    // several changes in a row reload once
    clearTimeout(reload);
    reload = setTimeout(load, 300);
});

function build() {
    const toolbar = element('div', 'toolbar ranges');
    for (const [value, label] of FILTERS) {
        const button = element('button', value === filter ? 'active' : null, label);
        button.type = 'button';
        button.addEventListener('click', () => {
            filter = value;
            toolbar.querySelectorAll('button').forEach((b) => b.classList.toggle('active', b === button));
            load();
        });
        toolbar.append(button);
    }
    root.append(toolbar, message, table);
}

async function load() {
    try {
        const alerts = await getJson(API, new URLSearchParams({ state: filter }));
        message.textContent = '';
        render(alerts);
    } catch (e) {
        message.textContent = e.message;
    }
}

function headRow(labels) {
    const thead = element('thead');
    const tr = element('tr');
    for (const label of labels) {
        tr.append(element('th', null, label));
    }
    thead.append(tr);
    return thead;
}

function render(alerts) {
    table.replaceChildren();
    if (alerts.length === 0) {
        table.append(element('p', 'muted', 'Keine Meldungen.'));
        return;
    }
    const t = element('table', 'data alerts');
    t.append(headRow(['Seit', 'Zuletzt', 'Regel', 'Anwendung', 'Text', 'Anzahl', 'Zustand', '']));
    const body = element('tbody');
    for (const alert of alerts) {
        body.append(row(alert));
    }
    t.append(body);
    table.append(t);
}

function row(alert) {
    const tr = element('tr', `alert-${alert.state.toLowerCase()}`);
    tr.append(element('td', 'mono', formatTime(alert.opened)));
    tr.append(element('td', 'mono', formatTime(alert.updated)));
    tr.append(element('td', null, `${alert.rule} (${TYPES[alert.type] ?? alert.type})`));
    tr.append(element('td', null, alert.group ? `${alert.app} · ${alert.group}` : alert.app));
    const text = element('td', 'alert-text');
    text.append(element('div', null, alert.message));
    if (alert.eventLine && alert.eventLine !== alert.message) {
        text.append(element('div', 'muted mono', alert.eventLine));
    }
    tr.append(text);
    tr.append(element('td', 'number', formatNumber(alert.count)));
    const state = element('td');
    state.append(element('span', `badge ${BADGES[alert.state] ?? ''}`, STATES[alert.state] ?? alert.state));
    if (alert.ackedBy) {
        state.append(element('div', 'muted', `von ${alert.ackedBy}`));
    }
    tr.append(state);
    const actions = element('td', 'actions');
    if (alert.state === 'OPEN') {
        actions.append(ackButton(alert));
    }
    actions.append(eventsLink(alert));
    tr.append(actions);
    return tr;
}

function ackButton(alert) {
    const button = element('button', null, 'Quittieren');
    button.type = 'button';
    button.addEventListener('click', async () => {
        button.disabled = true;
        try {
            await sendJson('POST', `${API}/${encodeURIComponent(alert.id)}/ack`);
            load();
        } catch (e) {
            message.textContent = e.message;
            button.disabled = false;
        }
    });
    return button;
}

function eventsLink(alert) {
    const from = new Date(Date.parse(alert.firstTs ?? alert.opened) - MARGIN);
    const to = new Date(Date.parse(alert.lastTs ?? alert.updated) + MARGIN);
    const params = new URLSearchParams({ apps: alert.app, from: from.toISOString(), to: to.toISOString() });
    if (alert.eventId) {
        params.set('at', alert.eventId);
    }
    const link = element('a', null, 'Events ansehen');
    link.href = `/?${params}`;
    return link;
}

async function loadRules() {
    try {
        renderRules(await getJson(`${API}/rules`));
    } catch (e) {
        rulesRoot.replaceChildren(element('p', 'error', e.message));
    }
}

function renderRules(status) {
    rulesRoot.replaceChildren();
    for (const error of status.errors) {
        rulesRoot.append(element('p', 'error', error));
    }
    if (status.rules.length === 0) {
        rulesRoot.append(element('p', 'muted', 'Keine aktiven Regeln.'));
        return;
    }
    const t = element('table', 'data');
    t.append(headRow(['Regel', 'Art', 'Anwendungen', 'Bedingung', 'Cooldown', 'Ziele', 'Fehler']));
    const body = element('tbody');
    for (const rule of status.rules) {
        const tr = element('tr');
        tr.append(element('td', null, `${rule.name} ${rule.version}`));
        tr.append(element('td', null, TYPES[rule.type] ?? rule.type));
        tr.append(element('td', null, rule.apps.join(', ')));
        tr.append(element('td', null, condition(rule)));
        tr.append(element('td', null, rule.cooldown ?? 'Standard'));
        tr.append(element('td', null, rule.targets.join(', ')));
        const errors = element('td', rule.errors > 0 ? 'number error' : 'number', formatNumber(rule.errors));
        if (rule.lastError) {
            errors.title = rule.lastError;
        }
        tr.append(errors);
        body.append(tr);
    }
    t.append(body);
    rulesRoot.append(t);
}

function condition(rule) {
    if (rule.type === 'THRESHOLD') {
        return `${rule.count} in ${rule.within}${rule.per ? ` je ${rule.per}` : ''}`;
    }
    if (rule.type === 'ABSENCE') {
        return `kein Event seit ${rule.absentFor}`;
    }
    return 'jedes Event';
}
