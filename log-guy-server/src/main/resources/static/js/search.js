// The search page: filter bar, timeline, facets and log window. Every change goes through the URL.

import { getJson } from './api.js';
import { element, formatNumber, toLocalInput } from './format.js';
import { LogWindow } from './logwindow.js';
import { apiParams, RANGES, readState, timeRange, writeState } from './state.js';
import { Timeline } from './timeline.js';

const FIXED_HIDDEN = new Set(['parser_version']);
const MAX_DEFAULT_COLUMNS = 2;
const LINK_BEFORE = 5 * 60 * 1000;

const root = document.getElementById('search');
const form = element('form', 'filter-bar');
const appBox = element('details', 'apps');
const rangeBox = element('div', 'ranges');
const customBox = element('div', 'custom-range');
const queryInput = element('input', 'query');
const filterBox = element('div', 'active-filters');
const layout = element('div', 'search-layout');
const facetBox = element('aside', 'facets');
const mainBox = element('div', 'results');
const timelineBox = element('section', 'timeline');
const logBox = element('section', 'log');

let state;
let fields = { apps: [], pseudonymized: [], groups: {}, selected: [], fields: [] };

const timeline = new Timeline(timelineBox, {
    onRange: (from, to) => update({ range: '', from: from.toISOString(), to: to.toISOString(), at: '' }),
    onJump: to => update({ range: '', from: currentRange.from.toISOString(), to: to.toISOString(), at: '' }),
});
const logWindow = new LogWindow(logBox, {
    onFilter: (name, value) => addFilter(name, value),
    linkTo: event => linkTo(event),
});
let currentRange;

buildForm();
mainBox.append(timelineBox, logBox);
layout.append(facetBox, mainBox);
root.replaceChildren(form, filterBox, layout);
window.addEventListener('popstate', () => load());
load();

function buildForm() {
    queryInput.type = 'search';
    queryInput.name = 'q';
    queryInput.placeholder = 'Suche, z. B. level:ERROR AND logger:MCRSolr*';
    queryInput.setAttribute('aria-label', 'Suchanfrage');
    const submit = element('button', 'primary', 'Suchen');
    submit.type = 'submit';
    for (const name of Object.keys(RANGES)) {
        const button = element('button', 'range', name);
        button.type = 'button';
        button.dataset.range = name;
        button.addEventListener('click', () => update({ range: name, from: '', to: '', at: '' }));
        rangeBox.append(button);
    }
    const customButton = element('button', 'range', 'Zeitraum');
    customButton.type = 'button';
    customButton.dataset.range = '';
    customButton.addEventListener('click', () => customBox.classList.toggle('open'));
    rangeBox.append(customButton);
    const fromInput = element('input');
    fromInput.type = 'datetime-local';
    fromInput.step = '1';
    fromInput.name = 'from';
    fromInput.setAttribute('aria-label', 'Von');
    const toInput = element('input');
    toInput.type = 'datetime-local';
    toInput.step = '1';
    toInput.name = 'to';
    toInput.setAttribute('aria-label', 'Bis');
    const apply = element('button', null, 'Übernehmen');
    apply.type = 'button';
    apply.addEventListener('click', () => {
        if (!fromInput.value) {
            fromInput.focus();
            return;
        }
        update({
            range: '',
            from: new Date(fromInput.value).toISOString(),
            to: toInput.value ? new Date(toInput.value).toISOString() : '',
            at: '',
        });
    });
    const fromLabel = element('label', null, 'Von ');
    fromLabel.append(fromInput);
    const toLabel = element('label', null, 'Bis ');
    toLabel.append(toInput);
    customBox.append(fromLabel, toLabel, apply);
    form.append(appBox, queryInput, submit, rangeBox, customBox);
    form.addEventListener('submit', e => {
        e.preventDefault();
        update({ q: queryInput.value.trim(), at: '' });
    });
}

function update(changes) {
    state = { ...state, ...changes };
    writeState(state);
    load();
}

function addFilter(name, value) {
    const values = state.filters[name] ?? [];
    if (!values.includes(value)) {
        update({ filters: { ...state.filters, [name]: [...values, value] }, at: '' });
    }
}

function removeFilter(name, value) {
    const filters = { ...state.filters };
    filters[name] = filters[name].filter(v => v !== value);
    if (!filters[name].length) {
        delete filters[name];
    }
    update({ filters, at: '' });
}

function linkTo(event) {
    const ts = new Date(event.ts).getTime();
    const params = new URLSearchParams();
    params.set('apps', event.app);
    params.set('from', new Date(ts - LINK_BEFORE).toISOString());
    params.set('to', new Date(ts + 1).toISOString());
    params.set('at', event.id);
    return `${window.location.origin}${window.location.pathname}?${params}`;
}

async function load() {
    state = readState();
    currentRange = timeRange(state);
    queryInput.value = state.q;
    try {
        const query = new URLSearchParams();
        if (state.apps.length) {
            query.set('apps', state.apps.join(','));
        }
        fields = await getJson('/api/v1/fields', query);
    } catch (e) {
        logBox.replaceChildren(element('div', 'log-status error', e.message));
        return;
    }
    renderApps();
    renderRanges();
    renderFilters();
    const params = apiParams(state, currentRange);
    const names = fields.fields.map(f => f.name);
    const columns = state.cols ?? defaultColumns();
    const showApp = fields.selected.length > 1;
    const split = names.includes('level') ? 'level' : null;
    timeline.load(params, split);
    loadFacets(params);
    await logWindow.reset(params, columns.filter(c => names.includes(c)), showApp);
    if (state.at) {
        if (logWindow.events.some(e => e.id === state.at)) {
            logWindow.toggle(state.at);
        }
    }
}

function defaultColumns() {
    const names = fields.fields.map(f => f.name);
    if (names.includes('level') && names.includes('logger')) {
        return ['level', 'logger'];
    }
    return fields.fields.filter(f => f.facet && !['app', 'host', 'parse_error', 'parser_version'].includes(f.name))
        .slice(0, MAX_DEFAULT_COLUMNS)
        .map(f => f.name);
}

function renderApps() {
    const selected = new Set(state.apps);
    const pseudonymized = new Set(fields.pseudonymized);
    const summary = element('summary', null,
        selected.size ? `Anwendungen: ${[...selected].join(', ')}` : 'Alle Anwendungen');
    if (fields.selected.some(app => pseudonymized.has(app))) {
        const badge = element('span', 'badge warn', 'pseudonymisiert');
        badge.title = 'Mindestens eine Anwendung ist nur pseudonymisiert freigegeben. Deshalb zeigt die ganze Suche '
            + 'Pseudonyme wie $Nutzer1 statt der echten Werte. Suche und Filter nehmen diese Pseudonyme an.';
        summary.append(' ', badge);
    }
    const list = element('div', 'app-list');
    for (const [group, members] of Object.entries(fields.groups)) {
        const label = element('label', 'app-group');
        label.title = members.join(', ');
        const box = element('input');
        box.type = 'checkbox';
        box.value = `@${group}`;
        box.checked = selected.has(box.value);
        label.append(box, document.createTextNode(` @${group} `), element('span', 'muted', `(${members.length})`));
        list.append(label);
    }
    for (const app of fields.apps) {
        const label = element('label');
        const box = element('input');
        box.type = 'checkbox';
        box.value = app;
        box.checked = selected.has(app);
        label.append(box, document.createTextNode(` ${app}`));
        if (pseudonymized.has(app)) {
            label.append(' ', element('span', 'badge warn', 'pseudonymisiert'));
        }
        list.append(label);
    }
    const apply = element('button', null, 'Übernehmen');
    apply.type = 'button';
    apply.addEventListener('click', () => {
        const apps = [...list.querySelectorAll('input:checked')].map(b => b.value);
        appBox.open = false;
        update({ apps, at: '' });
    });
    list.append(apply);
    appBox.replaceChildren(summary, list);
}

function renderRanges() {
    for (const button of rangeBox.querySelectorAll('button')) {
        button.classList.toggle('active', button.dataset.range === state.range);
    }
    const [fromInput, toInput] = customBox.querySelectorAll('input');
    fromInput.value = toLocalInput(currentRange.from);
    toInput.value = state.range || !state.to ? '' : toLocalInput(currentRange.to);
    customBox.classList.toggle('open', !state.range);
}

function renderFilters() {
    const chips = [];
    for (const [name, values] of Object.entries(state.filters)) {
        for (const value of values) {
            const chip = element('button', 'chip removable');
            chip.type = 'button';
            chip.title = 'Filter entfernen';
            chip.append(element('span', 'chip-name', name), element('span', 'chip-value', value));
            chip.addEventListener('click', () => removeFilter(name, value));
            chips.push(chip);
        }
    }
    filterBox.replaceChildren(...chips);
    filterBox.hidden = chips.length === 0;
}

async function loadFacets(params) {
    const names = fields.fields.filter(f => f.facet && !FIXED_HIDDEN.has(f.name)).map(f => f.name);
    if (!names.length) {
        facetBox.replaceChildren();
        return;
    }
    const query = new URLSearchParams(params);
    query.set('fields', names.join(','));
    const generation = currentRange;
    try {
        const facets = await getJson('/api/v1/facets', query);
        if (generation !== currentRange) {
            return;
        }
        const groups = [];
        for (const name of names) {
            const values = facets[name] ?? [];
            if (!values.length) {
                continue;
            }
            const group = element('section', 'facet');
            group.append(element('h2', null, name));
            const list = element('ul');
            const active = new Set(state.filters[name] ?? []);
            for (const facet of values) {
                const item = element('li');
                const button = element('button', active.has(facet.value) ? 'active' : null);
                button.type = 'button';
                button.append(element('span', 'facet-value', facet.value),
                    element('span', 'facet-count', formatNumber(facet.count)));
                button.addEventListener('click', () => active.has(facet.value)
                    ? removeFilter(name, facet.value) : addFilter(name, facet.value));
                item.append(button);
                list.append(item);
            }
            group.append(list);
            groups.push(group);
        }
        facetBox.replaceChildren(...groups);
    } catch (e) {
        facetBox.replaceChildren(element('div', 'error', e.message));
    }
}
