# Parser-Skripte

Wie aus Zeilen Events und Felder werden, beschreibt pro Anwendung ein JavaScript-Modul. Es läuft beim Indexieren in GraalJS, der JavaScript-Engine von GraalVM, eingebunden über die Polyglot-API. Ändert sich ein Logformat oder das Skript, bleibt das Archiv unberührt, und eine Neuindexierung wendet das neue Skript auch auf alte Daten an.

### Gemessen

Ein Tag der großen Anwendung (`mycore-app-10-22-2026-1.log.gz`): 125 MB roh, 885.184 Zeilen, 679.397 Events, 1 Thread. Gemessen mit `log-guy bench parser`, GraalJS 25.4.4.1.1, jeweils die beste Runde, bei GraalVM nach zwei Runden zum Aufwärmen.

| Parser | OpenJDK 25, nur Interpreter | GraalVM CE 25, mit JIT | QuickJS im Prototyp |
| --- | --- | --- | --- |
| nur entpacken und Zeilen trennen, ohne JavaScript | 0,07 s | 0,15 s | 0,2 s |
| JavaScript mit regulären Ausdrücken | 9,7 s, 70.000 Events/s | 1,3 s, 510.000 Events/s | 7,2 s |
| JavaScript von Hand mit `indexOf` und `charCodeAt` | 5,6 s, 122.000 Events/s | 0,88 s, 770.000 Events/s, 141 MB/s | 3,6 s |
| leeres Skript, nur die Kosten des Aufrufs | 0,72 s | 0,38 s | 0,6 s |

- Auf OpenJDK läuft GraalJS nur im Interpreter und ist langsamer als QuickJS. Mit dem JIT von GraalVM ist der handgeschriebene Parser 6-mal schneller als auf OpenJDK und 4-mal schneller als QuickJS. Der Server läuft deshalb auf GraalVM CE, siehe Technologie.
- Die erste Runde mit JIT braucht 1,65 s, ab der dritten bleibt es bei 0,88 s. Der Indexer läuft dauerhaft, die Aufwärmzeit spielt nur nach einem Neustart eine Rolle.
- OpenJDK mit JVMCI und dem Graal-Compiler als JAR wurde versucht. Dafür braucht es weitere Compiler-Module und eine JVMCI-Fassung, die zum JDK passt. Das ist zu zerbrechlich für den Betrieb.
- Ein Aufruf pro Zeile ist schneller als 2000 Zeilen auf einmal (8,5 s in QuickJS). Die Schnittstelle bleibt deshalb einfach: `isStart` pro Zeile, `parse` pro Event.
- Beide Parser liefern für alle 679.397 Events Zeit, Level und Logger, ohne Fehler.
- Hochgerechnet mit GraalVM: Eine Neuindexierung von 14 Tagen aller Anwendungen, etwa 100 bis 150 Mio. Events, braucht für das Parsen 2 bis 3 Minuten auf einem Thread. Die Grenze setzt dann Solr, siehe Solr-Verwaltung.

Der Benchmark ist ein Befehl von log-guy: `log-guy bench parser --script mycore-log4j --file <datei> [--rounds 3]`. Statt eines Namens nimmt `--script` auch den Pfad einer `.js`-Datei.

### Schnittstelle

| Export | Pflicht | Aufgabe |
| --- | --- | --- |
| `fields` | ja | Felder dieser Anwendung mit Typ |
| `parse(text)` | ja | aus dem Text eines Events die Felder machen |
| `isStart(line)` | nein | beginnt mit dieser Zeile ein neues Event? Ohne die Funktion ist jede Zeile ein Event, wie bei Apache. |

Meldungsregeln sind eigene JavaScript-Module, siehe Meldungen. Parser und Regeln lassen sich zur Laufzeit ändern, siehe Skripte: Verzeichnis, Reload, GUI und MCP.

log-guy stellt eingebaute Funktionen bereit. Reines JavaScript kann keine Zeitzonen wie `Europe/Berlin` parsen, und Log4j schreibt Ortszeit ohne Zone. Deshalb gibt es `parseTime(text, format, zone)` in Java:

- Rückgabe: Millisekunden seit 1970 als Zahl, `null`, wenn der Text nicht passt. `parse` gibt sie als `ts` zurück.
- Format mit `strftime`-Angaben: `%Y %m %d %H %M %S`, `%3f %6f %9f` für Sekundenbruchteile, `%b` für englische Monatskürzel, `%z` für einen Offset wie `+0200` und `%%`. Steht ein Offset im Text, gilt `zone` nicht.
- `%Y-%m-%dT%H:%M:%S,%3f` und `%Y-%m-%d %H:%M:%S,%3f` werden ohne `DateTimeFormatter` direkt aus den Ziffern gelesen.

`stripAnsi` und `findExceptions` in Java sind nicht nötig: Der handgeschriebene Parser erledigt beides in JavaScript, und mit JIT schafft er 141 MB/s.

### Beispiel für MyCoRe

```js
// Variante mit regulären Ausdrücken, zum Vergleich.
// Die mitgelieferte Vorlage mycore-log4j.js ist von Hand mit indexOf und charCodeAt geschrieben.
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

### Vorlage `apache-combined`

`apache-combined.js` liest Access-Logs im Common und Combined Log Format von Apache httpd, nginx und dem Tomcat-`AccessLogValve`: `%h %l %u %t "%r" %>s %b "%{Referer}i" "%{User-Agent}i"`. Referer und User-Agent dürfen fehlen, Angaben dahinter wie `%D` werden ignoriert. Jede Zeile ist ein Event, `isStart` gibt es nicht. Die Zeit kommt mit Zeitzone aus der Zeile.

| Feld | Typ | Facette | Inhalt |
| --- | --- | --- | --- |
| `ip` | string | | `%h`, pseudonymisiert |
| `login` | string | | `%u`, ohne `-`, pseudonymisiert |
| `method` | string | ja | z.B. `GET` |
| `path` | string | | Pfad ohne Query-String |
| `status` | int | ja | HTTP-Status |
| `bytes` | long | | ohne `-` |
| `referer` | string | | ohne `-` |
| `agent` | string | | User-Agent |
| `bot` | bool | ja | User-Agent sieht nach Crawler oder Skript aus (`bot`, `crawl`, `spider`, `curl/`, `python-requests` usw.) |

`ip` und `login` heißen wie die Felder des MyCoRe-Parsers, damit die Standard-Pseudonymisierung sie ohne eigenes Skript schützt. Den Nutzer in der Zeile selbst ersetzt sie über das Muster `USER_IN_ACCESS_LOG`. Der Query-String fehlt in `path`, weil er Suchbegriffe enthalten kann und das Feld sonst kaum zu facettieren wäre. Er steht weiter in `message`.

Geprüft mit einer echten Zeile aus dem Tomcat-Access-Log einer lokalen MIR-Instanz und 2.000 synthetischen Combined-Zeilen über `POST /api/v1/scripts/parser/apache-combined/test`: 0 Fehler, rund 6.000 Events pro Sekunde in dieser kleinen Probe. Echte Apache-Logs der Produktion liegen nicht vor, die Messung mit einem vollen Tag steht noch aus.

### Felder

- Die Felder stehen im Skript unter `fields`. In der Konfiguration der Anwendung stehen nur die Aufbewahrung, z.B. `index-days: 7`, und welches Parser-Skript sie nutzt.
- Welcher Parser: `parser: mycore-log4j` in der Konfiguration der Anwendung, ohne Angabe der Name der Anwendung. log-guy sucht `scripts/parser/<parser>.js` und nimmt sonst die Vorlage gleichen Namens aus dem JAR. So teilen sich alle MIR-Anwendungen einen Parser, ohne dass er 35-mal kopiert wird.
- Typen: `string`, `strings`, `int`, `long`, `double`, `date`, `bool`, `text`.
- In Solr landen die Felder als dynamische Felder mit Typ-Endung, z.B. `level_s` oder `exc_ss`. API und GUI zeigen sie ohne Endung.
- Heißt ein Feld in zwei Anwendungen gleich, hat aber verschiedene Typen, entstehen zwei Solr-Felder wie `code_s` und `code_i`. Es gibt keinen Konflikt.
- Gibt `parse` ein Feld zurück, das nicht in `fields` steht, warnt das Parser-Labor. Beim Indexieren wird es verworfen.
- Feste Felder gibt es immer: `id`, `app`, `host`, `file`, `offset`, `ts`, `message`, dazu `parser_version` mit einem kurzen Hash des Skripts und bei Fehlern `parse_error`. Diese Namen sind für Skripte gesperrt.
- Feldnamen bestehen aus Kleinbuchstaben, Ziffern und `_`.

### Regeln für die Skripte

- Pro Indexer-Thread ein GraalJS-Context, alle aus einer gemeinsamen Engine. Ein Event, das länger als 50 ms braucht (`event-timeout`), wird abgebrochen: Ein Wächter-Thread ruft `Context.interrupt` auf, der Context bleibt danach benutzbar.
- Die Contexts laufen mit `SandboxPolicy.CONSTRAINED`: kein Datei-, Netz- oder Java-Zugriff, keine Threads, Ausgaben werden verworfen.
- Ein Speicherlimit pro Context (`sandbox.MaxHeapMemory`) gibt es nur in Oracle GraalVM, nicht in der Community-Fassung. Läuft ein Skript über, meldet GraalJS das als `isResourceExhausted`, der Heap ist dann aber für alle knapp. Schutz bieten das Zeitlimit und die Grenze für die Eventgröße.
- Liefert `parse` keinen Zeitstempel, nimmt log-guy den des vorigen Events, am Dateianfang die Empfangszeit aus der `.meta.json`.
- Wirft das Skript einen Fehler oder läuft es zu lange, wird das Event nur mit `message` und dem Feld `parse_error` indexiert und auf der Status-Seite gezählt. Ein Fehler in `isStart` zählt als Beginn eines Events, damit ein kaputtes Skript nicht die ganze Datei zu einem Event macht.
- Regex-Dialekt: In allen Skripten gelten JavaScript-Ausdrücke. Nur die Archivsuche nutzt RE2/J, das in linearer Zeit arbeitet. Die üblichen Muster funktionieren in beiden gleich, Lookaround und Rückverweise gibt es nur in JavaScript. Langsames Backtracking im Skript fängt das Zeitlimit ab.

[Zur Übersicht](README.md)
