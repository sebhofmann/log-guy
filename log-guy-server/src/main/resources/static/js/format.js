// Formatting of times and numbers in the local time zone of the browser.

const time = new Intl.DateTimeFormat('de-DE', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3,
});

const number = new Intl.NumberFormat('de-DE');

export function formatTime(value) {
    return time.format(new Date(value));
}

export function formatNumber(value) {
    return number.format(value);
}

// Value for <input type="datetime-local" step="1"> in local time.
export function toLocalInput(date) {
    const pad = n => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:`
        + `${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

export function firstLine(text) {
    const end = text.indexOf('\n');
    return end < 0 ? text : text.substring(0, end);
}

export function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    if (text !== undefined && text !== null) {
        node.textContent = text;
    }
    return node;
}
