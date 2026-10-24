# Milestones und offene Fragen

Sechs Milestones, jeder endet mit etwas Benutzbarem. Der MCP-Server kommt früh, in M3, weil Skripte per LLM entstehen sollen. Mail und Webhooks kamen erst mit dem Produktivbetrieb in M6, bis dahin zeigte log-guy Meldungen nur in GUI und API.

| Milestone | Ergebnis | fertig, wenn |
| --- | --- | --- |
| M1 Fundament | Logs kommen an, liegen in `hot/` und sind in Solr | `prod-logs/` per Backfill und die Live-Datei von digibib sind ohne Duplikate indexiert |
| M2 Suche und GUI | Logs durchsuchen im Browser | eine Woche digibib lässt sich in der GUI durchsuchen, filtern und über die Zeitleiste eingrenzen |
| M3 Skripte und MCP | Skripte zur Laufzeit, auch per LLM | ein LLM schreibt per MCP den Apache-Parser für `digibib-apache`, testet ihn, ein Mensch gibt ihn frei |
| M4 Archiv | gesichertes Archiv mit gekürzten IP-Adressen, Archivsuche | `archive/` ist vollständig gefiltert, `hot/` hält nur 7 Tage, eine Regex-Suche über einen Monat liefert Treffer |
| M5 Meldungen | Regeln in JavaScript, Meldungen in GUI und API | die Regel „Connection Pool voll“ meldet die Hikari-Warnungen von digibib in der GUI |
| M6 Produktivbetrieb | alle Anwendungen angebunden, Mail, Betrieb | alle 30 bis 40 Anwendungen liefern Logs, Meldungen kommen per Mail, Backups laufen |

### M1 Fundament

- [x] Maven-Projekt mit `log-guy-server`, `log-guy-agent` und `log-guy-common`, Docker-Images für Server und Agent
- [x] Konfiguration aus `/etc/log-guy/application.yml` mit `@ConfigurationProperties`, `log-guy check`
- [x] Ingest-Tokens pro Host
- [x] Agent: Live-Datei mitlesen, Rotation über Inode, Prüfung jede Sekunde als Rückfall, Offset merken
- [x] Agent: rotierte `.gz` beobachten und Lücken nachliefern, `backfill`
- [x] Stream-API mit Fingerabdruck, Duplikate verwerfen, `fsync` vor der Antwort
- [x] `hot/` schreiben: zstd-Frames, `.idx`, `.meta.json`
- [x] GraalJS-Laufzeit mit Sandbox, Speicher- und Zeitlimit, `parseTime`. Benchmark nach GraalJS portieren und auf GraalVM-JDK und OpenJDK messen
- [x] Parser `mycore-log4j.js`, von Hand geschrieben
- [x] Solr: Configset hochladen, `logs_v1` und Alias `logs` anlegen, `log-guy solr init`
- [x] Indexer mit Fortschritt pro Datei, vorläufige Stacktraces, Event-ID `<fingerprint>:<offset>`
- [x] Messen: Durchsatz der Indexierung mit allen Teilen zusammen

M1 ist erfüllt mit dem Backfill von `prod-logs/` und der Live-Datei einer lokalen MIR-Instanz statt der von digibib. Abweichungen: GraalJS hat in der Community Edition kein Speicherlimit, siehe Parser-Skripte. Die Messwerte stehen in Solr-Verwaltung, Speicherung und Parser-Skripte.

### M2 Suche und GUI

- [x] Nutzer mit Argon2-Passwörtern, Anmeldung, Session-Cookie
- [x] Rechte `read` und `admin` pro Anwendung, erzwungener Filter auf `app`
- [x] Such-API: `search`, `histogram`, `facets`, `fields`, `context`
- [x] JTE-Seiten, Logfenster mit virtuellem Scrollen und `cursorMark`
- [x] Zeitleiste mit uPlot, Bereich markieren grenzt die Suche ein
- [x] Filter aus den Feldern der Anwendungen, Zustand in der URL
- [x] Status-Seite wie bei Solr: System, Agents, Anwendungen, Solr. Eigenes Log mit Log4j 2 als Datei und Ringpuffer, Seite Server-Log, Log-Level zur Laufzeit über Actuator
- [x] Aufbewahrung: nächtliches Löschen nach 7 Tagen, `expungeDeletes`

M2 ist erfüllt mit dem Backfill von digibib in der E2E-Umgebung: Suche, Facetten, Zeitleiste, Kontext und Links laufen über 219 Millionen Events, die Status-Seite braucht dafür rund 3 Sekunden. Im E2E ist die Aufbewahrung mit `cron: "-"` aus, damit der Backfill für Messungen erhalten bleibt.

### M3 Skripte und MCP

- [x] Skriptverzeichnis mit `.history/` und `.drafts/`, `log-guy reload` über den Admin-Endpunkt
- [x] Skript-API: Entwurf, Test, Aktivieren, Zurückrollen, Konflikterkennung
- [x] Testquellen: Anwendung, Logdatei, eingefügte Zeilen
- [x] Skript-Editor in der GUI mit CodeMirror, Freigabe von Entwürfen
- [x] Pseudonymisierung als JavaScript, `ps()`, Rückübersetzung in Anfragen, Ausgabe zurückhalten bei Fehlern
- [x] MCP-Server mit Spring AI: Such-, Skript-, Status- und Log-Tools, MCP-Tokens, `activate: true`
- [x] Recht `scripts`, Audit-Log für alle Änderungen
- [x] Erster Parser per LLM: `apache-combined.js`
- [x] `read_pseudonymized` in GUI und API, gleiche Pseudonymisierung wie beim MCP

Die Pseudonymisierung gehört in diesen Milestone, weil alles, was den MCP-Endpunkt verlässt, schon pseudonymisiert sein muss.

`apache-combined.js` hat Claude als LLM geschrieben und über die Script-API gegen eingefügte Zeilen getestet, der Parser wird als Vorlage ausgeliefert. M3 ist damit erfüllt.

### M4 Archiv

- [x] Archiv-Schreiber von `hot/` nach `archive/` mit Lifecycle-Filtern
- [x] Filter `ip-kuerzen.js`, eingebaute Funktionen `maskIp`, `replaceIps`, `findIps`, `findEmails`
- [x] Platzhalter bei Filterfehlern, `log-guy archive refilter`
- [x] `hot/` nach 7 Tagen leeren
- [x] Archivsuche als Job mit Regex, Fortschritt und Abbruch, in GUI und MCP
- [x] Ältere Zeiträume nachindexieren mit Ablaufdatum
- [x] Neuindexierung im Index-Fenster nach einer Skriptänderung, `log-guy reindex`, Angebot im Skript-Editor nach dem Aktivieren

### M5 Meldungen

- [x] Regel-Module mit `meta`, `check`, `message`
- [x] Zähler für Schwellen und Zeitgeber für Stille in Java, `cooldown`
- [x] Zustände offen, quittiert, erledigt in `state/alerts.ndjson`
- [x] GUI: Glocke und Meldungsseite, Regeln gegen die letzten 24 Stunden testen
- [x] API mit Stream, MCP-Tool `list_alerts`

M5 ist erfüllt mit einer Regel „Connection Pool voll“ auf nachgestellten Hikari-Warnungen in einer Live-Datei von digibib, weil die Logs aus `prod-logs/` älter als die 1 Stunde sind, ab der Regeln greifen. Gegen die echten Logs lief die Regel im Regeltest. Benachrichtigungen schreibt log-guy bis M6 nur ins Server-Log.

### M6 Produktivbetrieb

- [x] Mail per SMTP und Webhooks
- [ ] Agent-Container für alle Anwendungen ausrollen, Rechte auf die Log-Verzeichnisse
- [ ] Parser für die übrigen Logformate, per LLM oder von Hand
- [x] Solr absichern: eigener Nutzer, `timeAllowed`, `cpuAllowed`, Circuit Breakers
- [ ] Backups von `archive/`, `state/`, `audit/` und `/etc/log-guy/`

Später: LDAP, Live-Modus in der GUI.

### Offene Fragen

- [ ] Reicht die Freigabe durch einen Menschen für Entwürfe aus MCP, wie in Skripte: Verzeichnis, Reload, GUI und MCP beschrieben?
- [ ] Welche Hilfsfunktionen soll log-guy zum Start in Java anbieten, z.B. stripAnsi, findExceptions, maskIp?
- [ ] Auf welchem Server läuft log-guy, und wo liegen `hot/` und `archive/`?
- [ ] Welche Meldungsregeln brauchen wir zum Start, und wer bekommt die Mails?
- [x] Eigener Solr-Nutzer für log-guy, der nur auf `logs*` und das eigene Configset zugreifen darf? Ja, die Regeln stehen in Solr-Verwaltung.

### Entschieden

| Thema | Entscheidung |
| --- | --- |
| Plattform | Java 25, Spring Boot 4, Maven, JTE |
| Skriptsprache | JavaScript mit GraalJS |
| Agent | eigener Container neben der Anwendung, Log-Verzeichnis lesend gemountet, notfalls auf dem Host |
| Index-Fenster | 7 Tage |
| IP-Adressen | im Archiv sofort gekürzt, Originale 7 Tage in `hot/` |
| Backup | nur `archive/`, `state/`, `audit/` und `/etc/log-guy/`, nie `hot/` |
| Archiv | ohne Löschfrist, eine Frist ist nicht Teil von log-guy |

[Zur Übersicht](README.md)
