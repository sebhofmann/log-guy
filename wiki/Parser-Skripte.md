# Parser-Skripte

Wie aus Zeilen Events und Felder werden, beschreibt pro Anwendung ein JavaScript-Modul. Es läuft beim Indexieren in GraalJS, der JavaScript-Engine von GraalVM, eingebunden über die Polyglot-API. Ändert sich ein Logformat oder das Skript, bleibt das Archiv unberührt, und eine Neuindexierung wendet das neue Skript auch auf alte Daten an.

### Gemessen

Ein Tag der großen Anwendung (`mycore-app-10-22-2026-1.log.gz`): 125 MB roh, 885.184 Zeilen, 679.397 Events, 1 Thread.

| Parser | Zeit | Events/s | MB/s roh |
| --- | --- | --- | --- |
| nur entpacken und Zeilen trennen, ohne JavaScript | 0,2 s |  | 719 |
| JavaScript mit regulären Ausdrücken | 7,2 s | 94.600 | 17 |
| JavaScript von Hand mit `indexOf` und `charCodeAt` | 3,6 s | 186.200 | 34 |
| leeres Skript, nur die Kosten des Aufrufs | 0,6 s | 1.475.000 | 210 |

- Gemessen wurde mit QuickJS aus Rust, bevor die Entscheidung für Java fiel. Für GraalJS muss der Benchmark in M1 neu laufen. In QuickJS kostete der Aufruf selbst kaum etwas, die Zeit ging in reguläre Ausdrücke und String-Operationen.
- Ein Aufruf pro Zeile ist schneller als 2000 Zeilen auf einmal (8,5 s). Die Schnittstelle bleibt deshalb einfach: `isStart` pro Zeile, `parse` pro Event.
- Beide Parser liefern für alle 679.397 Events dieselben Felder. Der handgeschriebene erkennt zusätzlich `java.lang.Exception`, die der reguläre Ausdruck übersah.
- Hochgerechnet, nicht gemessen: Eine Neuindexierung von 14 Tagen aller Anwendungen, etwa 100 bis 150 Mio. Events, dauert mit dem handgeschriebenen Parser 10 bis 15 Minuten auf einem Thread. Der Live-Betrieb braucht nur einen Bruchteil davon.

Das Benchmark-Projekt liegt in `js-parser-bench/`.

### Schnittstelle

| Export | Pflicht | Aufgabe |
| --- | --- | --- |
| `fields` | ja | Felder dieser Anwendung mit Typ |
| `parse(text)` | ja | aus dem Text eines Events die Felder machen |
| `isStart(line)` | nein | beginnt mit dieser Zeile ein neues Event? Ohne die Funktion ist jede Zeile ein Event, wie bei Apache. |

Meldungsregeln sind eigene JavaScript-Module, siehe Meldungen. Parser und Regeln lassen sich zur Laufzeit ändern, siehe Skripte: Verzeichnis, Reload, GUI und MCP.

log-guy stellt eingebaute Funktionen bereit. `parseTime(text, format, zone)` gibt es schon im Benchmark. Reines JavaScript kann keine Zeitzonen wie `Europe/Berlin` parsen, und Log4j schreibt Ortszeit ohne Zone. Geplant sind außerdem `stripAnsi` und `findExceptions` in Java, damit Skripte kurz bleiben und trotzdem schnell sind. Das ist noch nicht gemessen.

### Beispiel für MyCoRe

```js
// parsers/mycore-log4j.js
const START = /^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d,\d{3}) +(TRACE|DEBUG|INFO|WARN|ERROR|FATAL) +(?:([^\s:]+) +)?([\w.$-]+): /;
const EXC = /\b(?:[a-z_$][\w$]*\.)+(?:[A-Z][\w$]*)?(?:Exception|Error)\b/g;
const ANSI = /\x1b\[[0-9;]*m/g;

export const fields = {
    level:  { type: "string", facet: true },
    login:  { type: "string" },
    logger: { type: "string", facet: true },
    exc:    { type: "strings", facet: true },
};

export function isStart(line) {
    return START.test(line.replace(ANSI, ""));
}

export function parse(text) {
    const clean = text.replace(ANSI, "");
    const m = START.exec(clean);
    if (!m) {
        return { message: clean };
    }
    const [, ts, level, login, logger] = m;
    return {
        ts: parseTime(ts, "%Y-%m-%dT%H:%M:%S,%3f", "Europe/Berlin"),
        message: clean,
        level,
        login,
        logger,
        exc: [...new Set(clean.match(EXC) ?? [])],
    };
}
```

Ein Importer, der eine Import-ID ins Log schreibt, bekommt einfach ein Feld mehr:

```js
const IMPORT_ID = /importId=([0-9a-f-]+)/;

export const fields = {
    import_id: { type: "string", facet: true },
};

// in parse():
import_id: IMPORT_ID.exec(text)?.[1],
```

Empfehlung zur Schreibweise: Die eingebauten Parser für MyCoRe und Apache werden von Hand geschrieben, weil sie die meisten Events verarbeiten. Für eigene, kleine Anwendungen reichen reguläre Ausdrücke.

### Felder

- Die Felder stehen im Skript unter `fields`. In der Konfiguration der Anwendung steht nur noch die Aufbewahrung, z.B. `index-days: 7`.
- Typen: `string`, `strings`, `int`, `long`, `double`, `date`, `bool`, `text`.
- In Solr landen die Felder als dynamische Felder mit Typ-Endung, z.B. `level_s` oder `exc_ss`. API und GUI zeigen sie ohne Endung.
- Heißt ein Feld in zwei Anwendungen gleich, hat aber verschiedene Typen, entstehen zwei Solr-Felder wie `code_s` und `code_i`. Es gibt keinen Konflikt.
- Gibt `parse` ein Feld zurück, das nicht in `fields` steht, warnt das Parser-Labor. Beim Indexieren wird es verworfen.
- Feste Felder gibt es immer: `id`, `app`, `host`, `file`, `offset`, `ts`, `message`.

### Regeln für die Skripte

- Pro Indexer-Thread ein GraalJS-Context mit `ResourceLimits`. Ein Event, das länger als 50 ms braucht, wird abgebrochen.
- Die Contexts laufen mit `SandboxPolicy.CONSTRAINED`: kein Datei-, Netz- oder Java-Zugriff, keine Threads.
- Liefert `parse` keinen Zeitstempel, nimmt log-guy den des vorigen Events, am Dateianfang die Empfangszeit aus der `.meta.json`.
- Wirft das Skript einen Fehler oder läuft es zu lange, wird das Event nur mit `message` und dem Feld `parse_error` indexiert und auf der Status-Seite gezählt.
- Regex-Dialekt: In allen Skripten gelten JavaScript-Ausdrücke. Nur die Archivsuche nutzt RE2/J, das in linearer Zeit arbeitet. Die üblichen Muster funktionieren in beiden gleich, Lookaround und Rückverweise gibt es nur in JavaScript. Langsames Backtracking im Skript fängt das Zeitlimit ab.

[Zur Übersicht](README.md)
