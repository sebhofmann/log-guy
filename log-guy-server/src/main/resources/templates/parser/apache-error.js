// Parser for the error log of Apache httpd 2.4 with the default ErrorLogFormat
//   [%{u}t] [%-m:%l] [pid %P:tid %T] %7F: %E: [client\ %a] %M% ,\ referer\ %{Referer}i
// e.g.
//   [Sat Oct 24 05:04:13.277751 2026] [proxy_http:error] [pid 8:tid 11] [client 172.17.0.1:50248] AH01114: HTTP:
//   failed to make connection to backend: 127.0.0.1, referer: https://example.org/
// and the format of Apache 2.2, [Sat Oct 24 05:04:13 2026] [error] [client 172.17.0.1] message.
// Apache writes local time without a zone. ZONE is the zone of the server, change it in a copy of this template.
// Lines without a time, like AH00558 on startup, are events of their own. Every line is an event.

export const fields = {
    level: { type: "string", facet: true },
    module: { type: "string", facet: true },
    code: { type: "string", facet: true },
    os_error: { type: "string", facet: true },
    ip: { type: "string" },
    login: { type: "string" },
    referer: { type: "string" },
};

const ZONE = "Europe/Berlin";
const CODE = /\bAH\d{5}\b/;
const OS_ERROR = /^\((\d+)\)([^:]+): /;
// AH01617 "user mueller: authentication failure for ..." and AH01618 "user schulze not found: ..."
const USER = /\buser ([^\s:]+)(?:: authentication failure| not found)/;

// Reads "[...] " at position at. Returns [content, index after the space] or null.
function bracket(line, at) {
    if (line.charCodeAt(at) !== 91) { // [
        return null;
    }
    const end = line.indexOf("] ", at);
    return end < 0 ? null : [line.substring(at + 1, end), end + 2];
}

// "Sat Oct 24 05:04:13.277751 2026" or without microseconds
function time(text) {
    const date = text.substring(4);
    return parseTime(date, date.indexOf(".") > 0 ? "%b %d %H:%M:%S.%6f %Y" : "%b %d %H:%M:%S %Y", ZONE);
}

export function parse(text) {
    const line = text.endsWith("\r") ? text.substring(0, text.length - 1) : text;
    const t = bracket(line, 0);
    if (t === null) {
        return { message: line };
    }
    const event = { message: line, ts: time(t[0]) };
    let at = t[1];

    const m = bracket(line, at);
    if (m === null) {
        return event;
    }
    const colon = m[0].lastIndexOf(":");
    if (colon >= 0) {
        event.module = m[0].substring(0, colon) || undefined;
        event.level = m[0].substring(colon + 1);
    } else {
        event.level = m[0];
    }
    at = m[1];

    let withPort = false;
    const pid = bracket(line, at);
    if (pid !== null && pid[0].startsWith("pid ")) {
        at = pid[1];
        withPort = true;
    }
    let rest = line.substring(at);
    const os = OS_ERROR.exec(rest);
    if (os !== null) {
        event.os_error = os[2];
        rest = rest.substring(os[0].length);
    }
    if (rest.startsWith("[client ")) {
        const end = rest.indexOf("] ");
        if (end > 0) {
            let client = rest.substring(8, end);
            // Apache 2.4 writes "address:port", also for IPv6 without brackets
            const port = withPort ? client.lastIndexOf(":") : -1;
            if (port > 0) {
                client = client.substring(0, port);
            }
            event.ip = client;
            rest = rest.substring(end + 2);
        }
    }
    const code = CODE.exec(rest);
    if (code !== null) {
        event.code = code[0];
    }
    const user = USER.exec(rest);
    if (user !== null) {
        event.login = user[1];
    }
    const ref = rest.lastIndexOf(", referer: ");
    if (ref >= 0) {
        event.referer = rest.substring(ref + 11);
    }
    return event;
}
