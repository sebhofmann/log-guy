// The whole state of the search page lives in the URL, so every view can be shared as a link.

export const RANGES = {
    '15m': 15 * 60 * 1000,
    '1h': 60 * 60 * 1000,
    '24h': 24 * 60 * 60 * 1000,
    '7d': 7 * 24 * 60 * 60 * 1000,
};

export const DEFAULT_RANGE = '24h';

export function readState(search = window.location.search) {
    const params = new URLSearchParams(search);
    const filters = {};
    for (const [key, value] of params) {
        if (key.startsWith('f.') && key.length > 2 && value !== '') {
            (filters[key.substring(2)] ??= []).push(value);
        }
    }
    const from = params.get('from') || '';
    const to = params.get('to') || '';
    let range = params.get('range') || '';
    if (!RANGES[range]) {
        range = from ? '' : DEFAULT_RANGE;
    }
    return {
        apps: split(params.get('apps')),
        range,
        from: range ? '' : from,
        to: range ? '' : to,
        q: params.get('q') || '',
        filters,
        cols: params.has('cols') ? split(params.get('cols')) : null,
        at: params.get('at') || '',
    };
}

export function writeState(state, replace = false) {
    const params = new URLSearchParams();
    if (state.apps.length) {
        params.set('apps', state.apps.join(','));
    }
    if (state.range) {
        params.set('range', state.range);
    } else {
        params.set('from', state.from);
        if (state.to) {
            params.set('to', state.to);
        }
    }
    if (state.q) {
        params.set('q', state.q);
    }
    for (const name of Object.keys(state.filters).sort()) {
        for (const value of state.filters[name]) {
            params.append(`f.${name}`, value);
        }
    }
    if (state.cols) {
        params.set('cols', state.cols.join(','));
    }
    if (state.at) {
        params.set('at', state.at);
    }
    const url = `${window.location.pathname}?${params}`;
    if (replace) {
        window.history.replaceState(null, '', url);
    } else {
        window.history.pushState(null, '', url);
    }
}

// Resolves a relative range against now. Called once per load, so log window and timeline see the same range.
export function timeRange(state, now = Date.now()) {
    if (state.range) {
        return { from: new Date(now - RANGES[state.range]), to: new Date(now) };
    }
    const from = new Date(state.from);
    const to = state.to ? new Date(state.to) : new Date(now);
    return { from, to };
}

export function apiParams(state, range) {
    const params = new URLSearchParams();
    if (state.apps.length) {
        params.set('apps', state.apps.join(','));
    }
    if (state.q) {
        params.set('q', state.q);
    }
    params.set('from', range.from.toISOString());
    params.set('to', range.to.toISOString());
    for (const [name, values] of Object.entries(state.filters)) {
        for (const value of values) {
            params.append(`f.${name}`, value);
        }
    }
    return params;
}

function split(value) {
    return (value || '').split(',').map(s => s.trim()).filter(Boolean);
}
