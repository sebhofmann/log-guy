// The timeline above the log window: events per interval as stacked bars, split by the level.
// Dragging selects a new range, a click on a bar shows the events up to the end of that bar.

import uPlot from '../vendor/uPlot.esm.js';
import { getJson } from './api.js';
import { formatNumber } from './format.js';

const COLORS = {
    FATAL: '#7a0010',
    ERROR: '#d62839',
    WARN: '#f0a202',
    INFO: '#3a86c8',
    DEBUG: '#8d99ae',
    TRACE: '#c3cad6',
};
const OTHER_COLOR = '#b0a8b9';
const ORDER = ['FATAL', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'];

export class Timeline {
    constructor(root, callbacks) {
        this.root = root;
        this.callbacks = callbacks;
        this.info = document.createElement('div');
        this.info.className = 'timeline-info';
        this.chartBox = document.createElement('div');
        this.chartBox.className = 'timeline-chart';
        root.replaceChildren(this.info, this.chartBox);
        this.generation = 0;
        new ResizeObserver(() => {
            if (this.plot) {
                this.plot.setSize(this.size());
            }
        }).observe(this.chartBox);
    }

    size() {
        return { width: Math.max(200, this.chartBox.clientWidth), height: 140 };
    }

    async load(params, split) {
        const generation = ++this.generation;
        const query = new URLSearchParams(params);
        if (split) {
            query.set('split', split);
        }
        this.info.textContent = 'Zeitleiste wird geladen …';
        this.info.classList.remove('error');
        try {
            const histogram = await getJson('/api/v1/histogram', query);
            if (generation === this.generation) {
                this.draw(histogram);
            }
        } catch (e) {
            if (generation === this.generation) {
                this.info.textContent = e.message;
                this.info.classList.add('error');
            }
        }
    }

    draw(histogram) {
        const sum = histogram.total.reduce((a, b) => a + b, 0);
        this.info.textContent = `${formatNumber(sum)} Ereignisse, ein Balken je ${interval(histogram.interval)}`;
        const names = Object.keys(histogram.series ?? {}).sort((a, b) => rank(a) - rank(b));
        const layers = names.length ? names.map(name => histogram.series[name]) : [histogram.total];
        const labels = names.length ? names : ['Ereignisse'];
        // uPlot has no stacking, so each series holds the sum of itself and all series below it.
        const stacked = [];
        let below = new Array(histogram.times.length).fill(0);
        for (let i = layers.length - 1; i >= 0; i--) {
            below = below.map((value, j) => value + layers[i][j]);
            stacked[i] = below;
        }
        this.times = histogram.times;
        this.interval = histogram.interval;
        this.layers = layers;
        const bars = uPlot.paths.bars({ size: [0.9, Infinity], align: 1 });
        const series = [{
            label: 'Zeit',
            value: (u, v) => (v == null ? '' : new Date(v * 1000).toLocaleString('de-DE')),
        }];
        labels.forEach((label, i) => {
            const color = names.length ? (COLORS[label.toUpperCase()] ?? OTHER_COLOR) : COLORS.INFO;
            series.push({
                label,
                fill: color,
                stroke: color,
                width: 0,
                paths: bars,
                points: { show: false },
                value: (u, v, s, idx) => (idx == null ? '' : formatNumber(this.layers[i][idx])),
            });
        });
        const options = {
            ...this.size(),
            series,
            scales: { x: { time: true }, y: { range: (u, min, max) => [0, Math.max(1, max)] } },
            axes: [
                { values: (u, ticks, space, incr) => ticks.map(t => tick(t, incr)) },
                { size: 50, values: (u, ticks) => ticks.map(t => (Number.isInteger(t) ? formatNumber(t) : '')) },
            ],
            legend: { show: names.length > 0, live: true },
            cursor: { drag: { x: true, y: false, setScale: false }, points: { show: false } },
            hooks: { setSelect: [u => this.selected(u)] },
        };
        const data = [this.times.map(t => t), ...stacked];
        if (this.plot) {
            this.plot.destroy();
        }
        this.plot = new uPlot(options, data, this.chartBox);
        this.plot.over.addEventListener('click', () => this.clicked());
    }

    selected(u) {
        if (u.select.width < 3) {
            return;
        }
        this.dragged = true;
        const from = u.posToVal(u.select.left, 'x');
        const to = u.posToVal(u.select.left + u.select.width, 'x');
        u.setSelect({ left: 0, width: 0, top: 0, height: 0 }, false);
        this.callbacks.onRange(new Date(from * 1000), new Date(to * 1000));
    }

    clicked() {
        if (this.dragged) {
            this.dragged = false;
            return;
        }
        const index = this.plot.cursor.idx;
        if (index == null) {
            return;
        }
        this.callbacks.onJump(new Date((this.times[index] + this.interval) * 1000));
    }
}

const DAY = new Intl.DateTimeFormat('de-DE', { day: '2-digit', month: '2-digit' });
const CLOCK = new Intl.DateTimeFormat('de-DE', { hour: '2-digit', minute: '2-digit' });
const SECONDS = new Intl.DateTimeFormat('de-DE', { hour: '2-digit', minute: '2-digit', second: '2-digit' });

// Axis label in German: the day for day ticks and at midnight, otherwise the time.
function tick(seconds, increment) {
    const date = new Date(seconds * 1000);
    if (increment >= 86400) {
        return DAY.format(date);
    }
    const time = (increment < 60 ? SECONDS : CLOCK).format(date);
    if (date.getHours() === 0 && date.getMinutes() === 0 && date.getSeconds() === 0) {
        return `${DAY.format(date)}\n${time}`;
    }
    return time;
}

function rank(name) {
    const index = ORDER.indexOf(name.toUpperCase());
    return index < 0 ? ORDER.length : index;
}

function interval(seconds) {
    if (seconds % 86400 === 0) {
        return seconds === 86400 ? 'Tag' : `${seconds / 86400} Tage`;
    }
    if (seconds % 3600 === 0) {
        return seconds === 3600 ? 'Stunde' : `${seconds / 3600} Stunden`;
    }
    if (seconds % 60 === 0) {
        return seconds === 60 ? 'Minute' : `${seconds / 60} Minuten`;
    }
    return seconds === 1 ? 'Sekunde' : `${seconds} Sekunden`;
}
