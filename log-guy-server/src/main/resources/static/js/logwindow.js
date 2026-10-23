// The log window: a virtual list that renders only the visible rows and loads further pages by cursor.
// Collapsed rows have a fixed height, expanded rows are measured after rendering.

import { getJson } from './api.js';
import { element, firstLine, formatNumber, formatTime } from './format.js';

const ROW_HEIGHT = 22;
const PAGE_SIZE = 200;
const OVERSCAN = 15;
const PRELOAD = 60;

export class LogWindow {
    constructor(root, callbacks) {
        this.callbacks = callbacks;
        this.status = element('div', 'log-status');
        this.scroller = element('div', 'log-scroll');
        this.scroller.tabIndex = 0;
        this.spacer = element('div', 'log-spacer');
        this.rows = element('div', 'log-rows');
        this.spacer.append(this.rows);
        this.scroller.append(this.spacer);
        root.replaceChildren(this.status, this.scroller);
        this.scroller.addEventListener('scroll', () => this.schedule());
        new ResizeObserver(() => this.schedule()).observe(this.scroller);
        this.events = [];
        this.columns = [];
        this.expanded = new Map();
        this.extra = new Map();
        this.contexts = new Map();
        this.nodes = new Map();
        this.done = true;
        this.total = null;
        this.generation = 0;
    }

    // Starts a new search. columns: declared fields shown as columns, showApp: whether to show the app column.
    reset(params, columns, showApp) {
        this.generation++;
        this.params = params;
        this.columns = columns;
        this.showApp = showApp;
        this.events = [];
        this.cursor = null;
        this.done = false;
        this.loading = false;
        this.total = null;
        this.error = null;
        this.expanded = new Map();
        this.extra = new Map();
        this.contexts = new Map();
        this.nodes = new Map();
        this.scroller.scrollTop = 0;
        const template = ['24ch'];
        if (showApp) {
            template.push('minmax(6ch, 16ch)');
        }
        for (let i = 0; i < columns.length; i++) {
            template.push('minmax(5ch, 14ch)');
        }
        template.push('minmax(0, 1fr)');
        this.rows.style.setProperty('--log-columns', template.join(' '));
        this.render();
        return this.loadMore();
    }

    async loadMore() {
        if (this.loading || this.done || this.error) {
            return;
        }
        this.loading = true;
        const generation = this.generation;
        const params = new URLSearchParams(this.params);
        params.set('rows', PAGE_SIZE);
        if (this.cursor) {
            params.set('cursor', this.cursor);
        }
        try {
            const page = await getJson('/api/v1/search', params);
            if (generation !== this.generation) {
                return;
            }
            this.events.push(...page.events);
            this.cursor = page.cursor;
            this.done = page.done;
            this.total = page.total;
        } catch (e) {
            if (generation === this.generation) {
                this.error = e.message;
            }
        } finally {
            if (generation === this.generation) {
                this.loading = false;
                this.render();
            }
        }
    }

    schedule() {
        if (!this.frame) {
            this.frame = requestAnimationFrame(() => {
                this.frame = null;
                this.render();
            });
        }
    }

    offsetOf(index) {
        let offset = index * ROW_HEIGHT;
        for (const [i, extra] of this.extra) {
            if (i < index) {
                offset += extra;
            }
        }
        return offset;
    }

    indexAt(y) {
        let low = 0;
        let high = this.events.length;
        while (low < high) {
            const mid = (low + high) >>> 1;
            if (this.offsetOf(mid + 1) <= y) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    renderStatus() {
        let text;
        if (this.error) {
            text = this.error;
        } else if (this.total === null) {
            text = 'Suche läuft …';
        } else if (this.total === 0) {
            text = 'Keine Treffer.';
        } else {
            text = `${formatNumber(this.total)} Treffer, ${formatNumber(this.events.length)} geladen`;
        }
        this.status.textContent = text;
        this.status.classList.toggle('error', Boolean(this.error));
    }

    render() {
        this.renderStatus();
        const count = this.events.length;
        const top = this.scroller.scrollTop;
        const height = this.scroller.clientHeight;
        const first = Math.max(0, this.indexAt(top) - OVERSCAN);
        const last = Math.min(count, this.indexAt(top + height) + OVERSCAN);
        const rows = [];
        const visible = new Map();
        for (let i = first; i < last; i++) {
            const id = this.events[i].id;
            const node = this.nodes.get(id) ?? this.row(i);
            visible.set(id, node);
            rows.push(node);
        }
        this.nodes = visible;
        if (!this.done && !this.error && count > 0) {
            rows.push(element('div', 'log-row log-more', this.loading ? 'Lade weitere …' : ''));
        }
        this.rows.replaceChildren(...rows);
        this.measure(first, last);
        this.place(first, last, rows);
        if (!this.done && last >= count - PRELOAD) {
            this.loadMore();
        }
    }

    // Stores the real height of expanded rows, the virtual layout depends on it.
    measure(first, last) {
        let changed = false;
        for (let i = first; i < last; i++) {
            const node = this.rows.children[i - first];
            const id = this.events[i].id;
            if (this.expanded.has(id)) {
                const extra = Math.max(0, node.offsetHeight - ROW_HEIGHT);
                if (this.extra.get(i) !== extra) {
                    this.extra.set(i, extra);
                    changed = true;
                }
            } else if (this.extra.has(i)) {
                this.extra.delete(i);
                changed = true;
            }
        }
        return changed;
    }

    place(first, last, rows) {
        for (let i = first; i < last; i++) {
            rows[i - first].style.top = `${this.offsetOf(i)}px`;
        }
        const end = this.offsetOf(this.events.length);
        if (rows.length > last - first) {
            rows[rows.length - 1].style.top = `${end}px`;
        }
        this.spacer.style.height = `${end + (this.done ? 0 : ROW_HEIGHT)}px`;
    }

    row(index) {
        const event = this.events[index];
        const level = event.fields.level ? String(event.fields.level).toUpperCase() : '';
        const row = element('div', 'log-row');
        if (level) {
            row.classList.add(`level-${level}`);
        }
        const line = element('div', 'log-line');
        line.append(element('span', 'log-ts', formatTime(event.ts)));
        if (this.showApp) {
            line.append(element('span', 'log-app', event.app));
        }
        for (const column of this.columns) {
            const value = event.fields[column];
            line.append(element('span', 'log-field', value === undefined ? '' : format(value)));
        }
        line.append(element('span', 'log-message', firstLine(event.message)));
        line.addEventListener('click', () => this.toggle(event.id));
        row.append(line);
        if (this.expanded.has(event.id)) {
            row.classList.add('expanded');
            row.append(this.detail(event));
        }
        return row;
    }

    toggle(id) {
        if (this.expanded.has(id)) {
            this.expanded.delete(id);
        } else {
            this.expanded.set(id, true);
        }
        this.nodes.delete(id);
        this.render();
    }

    detail(event) {
        const detail = element('div', 'log-detail');
        const fields = element('div', 'log-fields');
        const chips = [['app', event.app], ['host', event.host], ...Object.entries(event.fields)];
        if (event.parseError) {
            chips.push(['parse_error', event.parseError]);
        }
        for (const [name, value] of chips) {
            const values = Array.isArray(value) ? value : [value];
            for (const single of values) {
                const chip = element('button', 'chip');
                chip.type = 'button';
                chip.title = 'Als Filter übernehmen';
                chip.append(element('span', 'chip-name', name), element('span', 'chip-value', format(single)));
                chip.addEventListener('click', () => this.callbacks.onFilter(name, String(single)));
                fields.append(chip);
            }
        }
        const actions = element('div', 'log-actions');
        const contextButton = element('button', null, 'Kontext');
        contextButton.type = 'button';
        contextButton.addEventListener('click', () => this.loadContext(event));
        const linkButton = element('button', null, 'Link kopieren');
        linkButton.type = 'button';
        linkButton.addEventListener('click', async () => {
            await navigator.clipboard.writeText(this.callbacks.linkTo(event));
            linkButton.textContent = 'Link kopiert';
        });
        actions.append(contextButton, linkButton);
        const context = this.contexts.get(event.id);
        if (context) {
            detail.append(fields, actions, this.contextView(context));
        } else {
            detail.append(fields, actions, element('pre', 'log-text', event.message));
        }
        return detail;
    }

    async loadContext(event) {
        const generation = this.generation;
        try {
            const context = await getJson(`/api/v1/events/${encodeURIComponent(event.id)}/context`);
            this.contexts.set(event.id, context);
        } catch (e) {
            this.contexts.set(event.id, { error: e.message });
        }
        if (generation === this.generation) {
            this.nodes.delete(event.id);
            this.render();
            const line = this.nodes.get(event.id)?.querySelector('.context-event');
            if (line) {
                const pre = line.parentElement;
                pre.scrollTop = line.offsetTop - pre.offsetTop - pre.clientHeight / 3;
            }
        }
    }

    contextView(context) {
        const pre = element('pre', 'log-text log-context');
        if (context.error) {
            pre.textContent = context.error;
            pre.classList.add('error');
            return pre;
        }
        if (context.start) {
            pre.append(element('span', 'context-edge', '(Anfang der Datei)\n'));
        }
        for (const line of context.before) {
            pre.append(element('span', 'context-other', `${line}\n`));
        }
        context.lines.forEach((line, i) => {
            pre.append(element('span', i === 0 ? 'context-event' : 'context-other', `${line}\n`));
        });
        if (context.end) {
            pre.append(element('span', 'context-edge', '(aktuelles Ende der Datei)'));
        }
        return pre;
    }
}

function format(value) {
    return Array.isArray(value) ? value.join(', ') : String(value);
}
