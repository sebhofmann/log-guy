// Calls of the log-guy API with the session of the page. Errors carry the German message of the server.

const csrfHeader = document.querySelector('meta[name="csrf-header"]')?.content;
const csrfToken = document.querySelector('meta[name="csrf-token"]')?.content;

export class ApiError extends Error {
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

async function handle(response) {
    if (response.status === 401) {
        window.location.href = '/login';
        throw new ApiError(401, 'Nicht angemeldet.');
    }
    let body = null;
    try {
        body = await response.json();
    } catch (e) {
        body = null;
    }
    if (!response.ok) {
        throw new ApiError(response.status, body?.error ?? `Fehler ${response.status}`);
    }
    return body;
}

export async function getJson(path, params, signal) {
    const query = params ? params.toString() : '';
    const response = await fetch(query ? `${path}?${query}` : path, {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
        signal,
    });
    return handle(response);
}

export async function sendJson(method, path, data) {
    const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
    if (csrfHeader && csrfToken) {
        headers[csrfHeader] = csrfToken;
    }
    const response = await fetch(path, {
        method,
        headers,
        credentials: 'same-origin',
        body: data === undefined ? undefined : JSON.stringify(data),
    });
    return handle(response);
}
