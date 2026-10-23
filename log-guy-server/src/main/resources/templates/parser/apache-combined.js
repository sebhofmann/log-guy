// Parser for access logs in the Apache Common and Combined Log Format, as written by Apache httpd, nginx and the
// Tomcat AccessLogValve:
//   %h %l %u %t "%r" %>s %b "%{Referer}i" "%{User-Agent}i"
// e.g.
//   203.0.113.7 - - [01/Oct/2026:00:00:00 +0200] "GET /mir/ HTTP/1.1" 200 5120 "-" "Mozilla/5.0 ..."
// Referer and user agent are optional (Common Log Format), anything after them is ignored. Every line is an event.
// Written by hand with indexOf, because access logs have the most lines of all.

export const fields = {
    ip: { type: "string" },
    login: { type: "string" },
    method: { type: "string", facet: true },
    path: { type: "string" },
    status: { type: "int", facet: true },
    bytes: { type: "long" },
    referer: { type: "string" },
    agent: { type: "string" },
    bot: { type: "bool", facet: true },
};

const BOT = /bot\b|crawl|spider|slurp|archiver|curl\/|wget\/|python-requests|httpclient|java\//i;

// Reads a quoted field starting at the opening quote, with \" and \\ escaped as Apache writes them.
// Returns [value, index after the closing quote] or null.
function quoted(text, at) {
    if (text.charCodeAt(at) !== 34) { // "
        return null;
    }
    let end = at + 1;
    let escaped = false;
    while (end < text.length) {
        const c = text.charCodeAt(end);
        if (c === 92) { // backslash
            escaped = true;
            end += 2;
            continue;
        }
        if (c === 34) {
            break;
        }
        end++;
    }
    if (end >= text.length) {
        return null;
    }
    let value = text.substring(at + 1, end);
    if (escaped) {
        value = value.replace(/\\(.)/g, "$1");
    }
    return [value, end + 1];
}

function dash(value) {
    return value === "-" || value === "" ? undefined : value;
}

export function parse(text) {
    const line = text.endsWith("\r") ? text.substring(0, text.length - 1) : text;
    const s1 = line.indexOf(" ");
    const s2 = s1 < 0 ? -1 : line.indexOf(" ", s1 + 1);
    const open = s2 < 0 ? -1 : line.indexOf(" [", s2);
    const close = open < 0 ? -1 : line.indexOf("] ", open);
    if (close < 0) {
        return { message: line };
    }
    const event = {
        message: line,
        ts: parseTime(line.substring(open + 2, close), "%d/%b/%Y:%H:%M:%S %z", "UTC"),
        ip: line.substring(0, s1),
        login: dash(line.substring(s2 + 1, open)),
    };

    const request = quoted(line, close + 2);
    if (request === null) {
        return event;
    }
    const [target, afterRequest] = request;
    const m = target.indexOf(" ");
    if (m > 0) {
        event.method = target.substring(0, m);
        const p = target.indexOf(" ", m + 1);
        const uri = p < 0 ? target.substring(m + 1) : target.substring(m + 1, p);
        const q = uri.indexOf("?");
        event.path = q < 0 ? uri : uri.substring(0, q);
    }

    const s3 = line.indexOf(" ", afterRequest + 1);
    const status = Number.parseInt(line.substring(afterRequest + 1, s3 < 0 ? line.length : s3), 10);
    if (!Number.isNaN(status)) {
        event.status = status;
    }
    if (s3 < 0) {
        return event;
    }
    const s4 = line.indexOf(" ", s3 + 1);
    const bytes = line.substring(s3 + 1, s4 < 0 ? line.length : s4);
    if (bytes !== "-") {
        const n = Number.parseInt(bytes, 10);
        if (!Number.isNaN(n)) {
            event.bytes = n;
        }
    }
    if (s4 < 0) {
        return event;
    }

    const referer = quoted(line, s4 + 1);
    if (referer === null) {
        return event;
    }
    event.referer = dash(referer[0]);
    const agent = quoted(line, referer[1] + 1);
    if (agent !== null) {
        event.agent = dash(agent[0]);
        event.bot = event.agent !== undefined && BOT.test(event.agent);
    }
    return event;
}
