// The number of open alerts in the header, kept current by the alert stream.

const bell = document.getElementById('bell');

if (bell && window.EventSource) {
    const source = new EventSource('/api/v1/alerts/stream');
    source.addEventListener('state', (event) => {
        const data = JSON.parse(event.data);
        bell.textContent = String(data.open);
        bell.hidden = data.open === 0;
        bell.title = `${data.open} offene Meldungen`;
        document.dispatchEvent(new CustomEvent('log-guy-alerts', { detail: data }));
    });
}
