// Standard pseudonymization for everything that leaves the MCP endpoint.
// Replaces logins, user parameters, IP and mail addresses with pseudonyms like $Nutzer1, $IP3, $Mail2.
// Users come first, so a login that is a mail address stays $Nutzer everywhere. ORCID and GND identifiers of
// authors are public and stay.
// A file scripts/pseudonym/standard.js replaces this template.

export const meta = {
    apps: ["*"],
    fields: ["login", "ip"], // on these fields the MCP endpoint only accepts pseudonyms
};

// Technical accounts that identify nobody.
const KEEP = new Set(["guest", "SYSTEM", "administrator", "MCRJANITOR", "MCRJANITOR@local", "-", ""]);

// MyCoRe pattern: time, level, login, logger, e.g. "2026-10-01T00:00:00,152 INFO  mueller MCRServlet: ..."
const USER_AFTER_LEVEL = /^(\S+ +(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL) +)([^\s:]+)( +[\w.$-]+: )/;
// Access log pattern: host, ident, user, time, e.g. "203.0.113.7 - mueller [01/Oct/2026:00:00:05 +0200] ..."
const USER_IN_ACCESS_LOG = /^(\S+ \S+ )([^\s\[]+)( \[\d{2}\/\w{3}\/\d{4}:)/;
const USER_PARAM = /\b((?:user|userid|login|username|uid)=)([^\s&;,"']+)/gi;
// MyCoRe messages naming a user, e.g. "MCRSessionFilter: user detected: mueller".
const USER_PHRASE = /\b((?:user detected|requested user): |user:)([^\s,;"']+)/g;

function user(name) {
    return KEEP.has(name) ? name : ps("Nutzer", name);
}

function text(value) {
    if (typeof value !== "string") {
        return value;
    }
    let out = value
        .replace(USER_AFTER_LEVEL, (m, before, name, after) => before + user(name) + after)
        .replace(USER_IN_ACCESS_LOG, (m, before, name, after) => before + user(name) + after)
        .replace(USER_PARAM, (m, key, name) => key + user(name))
        .replace(USER_PHRASE, (m, key, name) => key + user(name));
    out = replaceEmails(out, mail => ps("Mail", mail));
    return replaceIps(out, ip => ps("IP", ip));
}

export function apply(event) {
    if (event.login) {
        event.login = user(event.login);
    }
    event.message = text(event.message);
    for (const name of Object.keys(event)) {
        if (name === "message" || name === "login" || name === "app" || name === "host" || name === "ts") {
            continue;
        }
        const value = event[name];
        event[name] = Array.isArray(value) ? value.map(text) : text(value);
    }
    return event;
}
