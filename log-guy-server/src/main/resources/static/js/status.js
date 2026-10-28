// The status page: system, indexer, archive, agents, applications and Solr. Reloads itself regularly.

import { getJson } from './api.js';
import { element, formatNumber, formatTime } from './format.js';

const REFRESH = 15000;

const root = document.getElementById('status');
const info = element('div', 'toolbar');
const content = element('div');
root.replaceChildren(element('h1', null, 'Status'), info, content);
load();

async function load() {
    try {
        render(await getJson('/admin/api/status'));
    } catch (e) {
        info.replaceChildren(element('span', 'error', e.message));
    }
    setTimeout(load, REFRESH);
}

function render(status) {
    info.replaceChildren(element('span', 'muted', `Stand ${formatTime(status.generated)}, `
        + `aktualisiert alle ${REFRESH / 1000} Sekunden`));
    content.replaceChildren(
        system(status.system),
        storage(status.storage),
        indexer(status.indexer),
        archive(status.archive),
        notifications(status.notifications),
        agents(status.agents, status.generated),
        apps(status.apps, status.generated),
        solr(status.solr),
    );
}

function section(title, ...children) {
    const node = element('section');
    node.append(element('h2', null, title), ...children);
    return node;
}

function table(headers, rows, numeric = []) {
    const node = element('table', 'data');
    if (headers) {
        const head = element('tr');
        headers.forEach(h => head.append(element('th', null, h)));
        const thead = element('thead');
        thead.append(head);
        node.append(thead);
    }
    const body = element('tbody');
    for (const row of rows) {
        const tr = element('tr');
        row.forEach((cell, i) => {
            const td = element('td', numeric.includes(i) ? 'number' : null);
            if (cell instanceof Node) {
                td.append(cell);
            } else {
                td.textContent = cell ?? '';
            }
            tr.append(td);
        });
        body.append(tr);
    }
    node.append(body);
    return node;
}

function pairs(entries) {
    return table(null, entries);
}

function badge(kind, text) {
    return element('span', `badge ${kind}`, text);
}

function bytes(value) {
    if (value == null || value < 0) {
        return 'unbekannt';
    }
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let size = value;
    let unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
        size /= 1024;
        unit++;
    }
    return `${size.toLocaleString('de-DE', { maximumFractionDigits: unit ? 1 : 0 })} ${units[unit]}`;
}

function duration(seconds) {
    const s = Math.max(0, Math.round(seconds));
    if (s < 60) {
        return `${s} s`;
    }
    if (s < 3600) {
        return `${Math.floor(s / 60)} min`;
    }
    if (s < 86400) {
        return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
    }
    return `${Math.floor(s / 86400)} d ${Math.floor((s % 86400) / 3600)} h`;
}

// ISO-8601 duration of Jackson, e.g. PT5H3M2.5S, in seconds.
function isoSeconds(value) {
    if (typeof value === 'number') {
        return value;
    }
    const match = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?$/.exec(value ?? '');
    return match ? (Number(match[1] ?? 0) * 3600) + (Number(match[2] ?? 0) * 60) + Number(match[3] ?? 0) : 0;
}

function ago(value, now) {
    if (!value) {
        return 'nie';
    }
    return `${formatTime(value)} (vor ${duration((new Date(now) - new Date(value)) / 1000)})`;
}

function system(s) {
    const disk = s.diskTotal > 0 ? `${bytes(s.diskFree)} frei von ${bytes(s.diskTotal)}` : 'unbekannt';
    return section('System', pairs([
        ['Version', s.version ?? 'unbekannt'],
        ['Java', s.java],
        ['Gestartet', `${formatTime(s.started)} (läuft seit ${duration(isoSeconds(s.uptime))})`],
        ['CPUs', String(s.cpus)],
        ['Last', s.load < 0 ? 'unbekannt' : s.load.toLocaleString('de-DE', { maximumFractionDigits: 2 })],
        ['Heap', `${bytes(s.heapUsed)} von ${bytes(s.heapMax)}`],
        ['Datenverzeichnis', disk],
        ['hot/', `${bytes(s.hotBytes)} in ${formatNumber(s.hotFiles)} Dateien`],
    ]));
}

// Files in hot/ and archive/ that were repaired with loss on load or cannot be written at the moment.
function storage(problems) {
    if (problems.length === 0) {
        return section('Speicher', element('p', null, 'Keine Probleme mit Dateien in hot/ und archive/.'));
    }
    const note = element('p', 'error', `${formatNumber(problems.length)} Probleme mit Dateien in hot/ und archive/. `
        + 'Beschädigte Dateien nehmen keine Daten an, bis wieder geschrieben werden kann. Beim Laden reparierte '
        + 'Dateien stehen hier bis zum nächsten Start, Einzelheiten im Server-Log.');
    const rows = problems.map(p => [`${p.store}/`, p.path, p.problem]);
    return section('Speicher', note, table(['Bereich', 'Datei', 'Problem'], rows));
}

function indexer(i) {
    if (!i.enabled) {
        return section('Indexer', element('p', null, 'Der Indexer ist ausgeschaltet.'));
    }
    return section('Indexer', element('p', 'muted', 'Zahlen seit dem Start des Servers.'), pairs([
        ['Indexierte Ereignisse', formatNumber(i.events)],
        ['Indexierte Daten', bytes(i.bytes)],
        ['Parserfehler', formatNumber(i.parseErrors)],
        ['Rückstand', `${bytes(i.backlogBytes)} in ${formatNumber(i.backlogFiles)} Dateien`],
    ]));
}

function archive(a) {
    if (!a.enabled) {
        return section('Archiv', element('p', null, 'Der Archiv-Schreiber ist ausgeschaltet.'));
    }
    const children = [];
    if (a.pausedBy.length > 0) {
        children.push(element('p', 'error', 'Angehalten, weil Lifecycle-Filter nicht laden: '
            + a.pausedBy.join('; ')));
    }
    children.push(element('p', 'muted', 'Zahlen seit dem Start des Servers, Größe und Dateien aus archive/.'),
        pairs([
            ['Archivierte Zeilen', formatNumber(a.lines)],
            ['Von Filtern weggelassen', formatNumber(a.dropped)],
            ['Filterfehler (Platzhalter)', formatNumber(a.filterErrors)],
            ['Rückstand', bytes(a.backlogBytes)],
            ['Archiv', `${bytes(a.bytes)} in ${formatNumber(a.files)} Dateien`],
        ]));
    return section('Archiv', ...children);
}

function notifications(n) {
    const children = [];
    if (n.failed > 0) {
        children.push(element('p', 'error', `${formatNumber(n.failed)} Benachrichtigungen nach allen Versuchen `
            + 'nicht zugestellt, Einzelheiten im Server-Log.'));
    }
    children.push(element('p', 'muted', 'Mails und Webhooks seit dem Start des Servers.'), pairs([
        ['Zugestellt', formatNumber(n.sent)],
        ['Aufgegeben', formatNumber(n.failed)],
        ['Wartend oder in Wiederholung', formatNumber(n.pending)],
    ]));
    return section('Benachrichtigungen', ...children);
}

function agents(list, now) {
    const note = element('p', 'muted', 'Der Rückstand eines Agenten ist dem Server nicht bekannt. „Zuletzt gesehen“ '
        + 'zählt seit dem Start des Servers, „zuletzt empfangen“ kommt aus hot/.');
    const rows = list.map(a => [
        a.token,
        a.host,
        a.apps.join(', '),
        a.lastSeen ? ago(a.lastSeen, now) : badge('warn', 'seit dem Start nicht gesehen'),
        ago(a.lastReceived, now),
        formatNumber(a.openFiles),
    ]);
    return section('Agenten', note,
        table(['Token', 'Host', 'Anwendungen', 'Zuletzt gesehen', 'Zuletzt empfangen', 'Offene Dateien'], rows, [5]));
}

function apps(list, now) {
    const rows = list.map(a => [
        a.configured ? a.name : (() => {
            const span = element('span', null, `${a.name} `);
            span.append(badge('warn', 'nicht konfiguriert'));
            return span;
        })(),
        a.parser ?? '',
        String(a.indexDays),
        ago(a.lastEvent, now),
        formatNumber(a.lastHour),
        formatNumber(a.indexed),
        a.parseErrorsIndexed > 0 ? badge('warn', formatNumber(a.parseErrorsIndexed)) : '0',
        formatNumber(a.parseErrors),
        a.backlogBytes > 0 ? `${bytes(a.backlogBytes)} in ${formatNumber(a.backlogFiles)} Dateien` : '',
    ]);
    return section('Anwendungen', table(['Anwendung', 'Parser', 'Tage im Index', 'Letztes Ereignis',
        'Letzte Stunde', 'Im Index', 'Parserfehler im Index', 'Parserfehler seit Start', 'Rückstand'], rows,
    [2, 4, 5, 6, 7]));
}

function solr(s) {
    if (!s.reachable) {
        const p = element('p');
        p.append(badge('bad', 'nicht erreichbar'), document.createTextNode(` ${s.error ?? ''}`));
        return section('Solr', p);
    }
    const head = element('p');
    head.append(badge('ok', 'erreichbar'), document.createTextNode(` ${formatNumber(s.documents)} Dokumente`));
    const rows = s.replicas.map(r => [
        r.collection,
        r.shard,
        r.name + (r.leader ? ' (Leader)' : ''),
        r.node,
        badge(r.state === 'active' ? 'ok' : 'bad', r.state),
        r.documents < 0 ? '' : formatNumber(r.documents),
        r.deletedDocuments < 0 ? '' : formatNumber(r.deletedDocuments),
        r.error ?? bytes(r.sizeBytes),
    ]);
    return section('Solr', head, table(['Collection', 'Shard', 'Replikat', 'Knoten', 'Zustand', 'Dokumente',
        'Gelöscht', 'Größe'], rows, [5, 6, 7]));
}
