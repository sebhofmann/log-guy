// Parser for logs of Solr 8 to 10 with the default Log4j pattern
//   %d{yyyy-MM-dd HH:mm:ss.SSS} %-5p (%t) [%notEmpty{c:%X{collection}}%notEmpty{ s:%X{shard}}
//   %notEmpty{ r:%X{replica}}%notEmpty{ x:%X{core}}%notEmpty{ t:%X{trace_id}}] %c{1.} %m%notEmpty{ =>%ex{short}}
// e.g.
//   2026-10-23 13:47:27.828 INFO  (qtp1281025083-34-localhost-80517) [c:logs_v1 s:shard1 r:core_node2
//   x:logs_v1_shard1_replica_n1 t:localhost-80517] o.a.s.c.S.Request webapp=/solr path=/select params={q=*:*}
//   rid=localhost-80517 hits=20897431 status=0 QTime=18
// Stack traces follow on lines starting with a tab and belong to the event.
// Solr in Docker logs in UTC. ZONE is the zone of the JVM, change it in a copy of this template.
// The request log of Jetty is in the NCSA format, apache-combined reads it.

export const fields = {
    level: { type: "string", facet: true },
    logger: { type: "string", facet: true },
    collection: { type: "string", facet: true },
    shard: { type: "string" },
    core: { type: "string" },
    path: { type: "string", facet: true },
    status: { type: "int", facet: true },
    qtime: { type: "int" },
    hits: { type: "long" },
    exc: { type: "strings", facet: true },
};

const ZONE = "UTC";
const LEVELS = new Set(["TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"]);

function isDigit(text, i) {
    const c = text.charCodeAt(i);
    return c >= 48 && c <= 57;
}

// Checks for "2026-10-23 13:47:27.828 " at the start.
function isTimestamp(text) {
    return text.length >= 24
        && isDigit(text, 0) && isDigit(text, 3)
        && text.charCodeAt(4) === 45 && text.charCodeAt(7) === 45
        && text.charCodeAt(10) === 32 && text.charCodeAt(13) === 58
        && text.charCodeAt(16) === 58 && text.charCodeAt(19) === 46
        && isDigit(text, 22) && text.charCodeAt(23) === 32;
}

export function isStart(line) {
    return isTimestamp(line);
}

function isNameChar(c) {
    return (c >= 97 && c <= 122) || (c >= 65 && c <= 90) || (c >= 48 && c <= 57) || c === 95 || c === 36; // \w $
}

// Finds fully qualified class names ending with Exception or Error, e.g. org.apache.solr.common.SolrException.
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

// Value of "name=" up to the next space, searched from the end, or undefined.
function lastValue(text, name, from) {
    const at = text.lastIndexOf(" " + name + "=");
    if (at < from) {
        return undefined;
    }
    const start = at + name.length + 2;
    const end = text.indexOf(" ", start);
    return text.substring(start, end < 0 ? text.length : end);
}

function int(value) {
    if (value === undefined) {
        return undefined;
    }
    const n = Number.parseInt(value, 10);
    return Number.isNaN(n) ? undefined : n;
}

export function parse(text) {
    if (!isTimestamp(text)) {
        return { message: text };
    }
    const ts = parseTime(text.substring(0, 23), "%Y-%m-%d %H:%M:%S.%3f", ZONE);
    let end = text.indexOf(" ", 24);
    const level = text.substring(24, end < 0 ? text.length : end);
    if (!LEVELS.has(level)) {
        return { ts, message: text };
    }
    const event = { ts, message: text, level };
    // the thread name may contain spaces and parentheses
    const open = text.indexOf(" (", end - 1);
    const close = open < 0 ? -1 : text.indexOf(") [", open);
    if (close < 0) {
        return event;
    }
    const mdcEnd = text.indexOf("] ", close + 2);
    if (mdcEnd < 0) {
        return event;
    }
    for (const part of text.substring(close + 3, mdcEnd).split(" ")) {
        if (part.startsWith("c:")) {
            event.collection = part.substring(2);
        } else if (part.startsWith("s:")) {
            event.shard = part.substring(2);
        } else if (part.startsWith("x:")) {
            event.core = part.substring(2);
        }
    }
    const loggerStart = mdcEnd + 2;
    end = text.indexOf(" ", loggerStart);
    event.logger = text.substring(loggerStart, end < 0 ? text.length : end);
    if (end < 0) {
        return event;
    }

    // requests: "webapp=/solr path=/select params={...} hits=1 status=0 QTime=3", for admin calls after "[admin] "
    const webapp = text.indexOf("webapp=", end);
    const nl = text.indexOf("\n", end);
    const first = nl < 0 ? text : text.substring(0, nl);
    if (webapp >= 0 && webapp - end < 12 && (nl < 0 || webapp < nl)) {
        const path = first.indexOf(" path=", webapp);
        if (path >= 0) {
            const pathEnd = first.indexOf(" ", path + 6);
            event.path = first.substring(path + 6, pathEnd < 0 ? first.length : pathEnd);
        }
        event.status = int(lastValue(first, "status", webapp));
        event.qtime = int(lastValue(first, "QTime", webapp));
        event.hits = int(lastValue(first, "hits", webapp));
    }
    const exc = findExceptions(text);
    if (exc.length > 0) {
        event.exc = exc;
    }
    return event;
}
