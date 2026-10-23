// Parser for MyCoRe/MIR logs written with the Log4j pattern
//   %d{ISO8601} %-5p %X{loginId} %c{1}: %m%n%ex
// with ANSI colors, e.g.
//   ESC[32m2026-10-01T00:00:00,152 INFO  guest MCRSessionHookFilter: locale/translate ...
// Written by hand with indexOf and charCodeAt, because MyCoRe logs are the bulk of all events.

export const fields = {
    level: { type: "string", facet: true },
    login: { type: "string" },
    logger: { type: "string", facet: true },
    exc: { type: "strings", facet: true },
};

const ESC = "\x1b";
const LEVELS = new Set(["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"]);

// Removes ANSI color sequences like ESC[1;31m and ESC[m.
function stripAnsi(text) {
    let at = text.indexOf(ESC);
    if (at < 0) {
        return text;
    }
    let out = "";
    let from = 0;
    while (at >= 0) {
        out += text.substring(from, at);
        let end = at + 1;
        if (text.charCodeAt(end) === 91) { // [
            end++;
            while (end < text.length) {
                const c = text.charCodeAt(end);
                if ((c >= 48 && c <= 57) || c === 59) { // digits and ;
                    end++;
                } else {
                    break;
                }
            }
            if (end < text.length) {
                end++; // final byte, usually m
            }
        }
        from = end;
        at = text.indexOf(ESC, from);
    }
    return out + text.substring(from);
}

// Offset of the first character after leading ANSI sequences.
function skipAnsi(line) {
    let i = 0;
    while (line.charCodeAt(i) === 27 && line.charCodeAt(i + 1) === 91) {
        i += 2;
        while (i < line.length && line.charCodeAt(i) !== 109) { // m
            i++;
        }
        i++;
    }
    return i;
}

function isDigit(text, i) {
    const c = text.charCodeAt(i);
    return c >= 48 && c <= 57;
}

// Checks for "2026-10-01T00:00:00,152 " at position i.
function isTimestamp(text, i) {
    return text.length >= i + 24
        && isDigit(text, i) && isDigit(text, i + 3)
        && text.charCodeAt(i + 4) === 45 && text.charCodeAt(i + 7) === 45
        && text.charCodeAt(i + 10) === 84 && text.charCodeAt(i + 13) === 58
        && text.charCodeAt(i + 16) === 58 && text.charCodeAt(i + 19) === 44
        && isDigit(text, i + 22) && text.charCodeAt(i + 23) === 32;
}

export function isStart(line) {
    return isTimestamp(line, skipAnsi(line));
}

function isNameChar(c) {
    return (c >= 97 && c <= 122) || (c >= 65 && c <= 90) || (c >= 48 && c <= 57) || c === 95 || c === 36; // \w $
}

// Finds fully qualified class names ending with Exception or Error, e.g. java.io.IOException.
function findExceptions(text) {
    const found = new Set();
    for (const suffix of ["Exception", "Error"]) {
        let at = text.indexOf(suffix);
        while (at >= 0) {
            const end = at + suffix.length;
            if (end >= text.length || !isNameChar(text.charCodeAt(end))) {
                let start = at;
                let dots = 0;
                while (start > 0) {
                    const c = text.charCodeAt(start - 1);
                    if (isNameChar(c)) {
                        start--;
                    } else if (c === 46) { // .
                        start--;
                        dots++;
                    } else {
                        break;
                    }
                }
                const first = text.charCodeAt(start);
                if (dots > 0 && first >= 97 && first <= 122) {
                    found.add(text.substring(start, end));
                }
            }
            at = text.indexOf(suffix, end);
        }
    }
    return [...found];
}

export function parse(text) {
    const clean = stripAnsi(text);
    if (!isTimestamp(clean, 0)) {
        return { message: clean };
    }
    const ts = parseTime(clean.substring(0, 23), "%Y-%m-%dT%H:%M:%S,%3f", "Europe/Berlin");
    let i = 24;
    let end = clean.indexOf(" ", i);
    const level = clean.substring(i, end);
    if (!LEVELS.has(level)) {
        return { ts, message: clean };
    }
    i = end;
    while (clean.charCodeAt(i) === 32) {
        i++;
    }
    // either "login logger: " or "logger: " when the login is empty
    let login;
    end = clean.indexOf(" ", i);
    if (end < 0) {
        return { ts, message: clean, level };
    }
    if (clean.charCodeAt(end - 1) !== 58) { // :
        login = clean.substring(i, end);
        i = end + 1;
        end = clean.indexOf(" ", i);
    }
    const logger = end > i && clean.charCodeAt(end - 1) === 58 ? clean.substring(i, end - 1) : undefined;
    const exc = findExceptions(clean);
    return {
        ts,
        message: clean,
        level,
        login,
        logger,
        exc: exc.length > 0 ? exc : undefined,
    };
}
