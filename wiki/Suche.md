# Suche

Es gibt zwei Suchwege: Die Solr-Suche über das Index-Fenster antwortet in Millisekunden und kennt die Felder aus den Parser-Skripten. Die Archivsuche durchsucht die Originaldateien auf Textebene, als Hintergrundjob und mit regulären Ausdrücken.

### Schnelle Suche über Solr

| Endpunkt | Liefert |
| --- | --- |
| `GET /api/v1/search` | Events zu `q`, `apps`, `from`, `to` und Feldfiltern, blättern mit `cursor` |
| `GET /api/v1/histogram` | Anzahl Events pro Zeitintervall, optional aufgeteilt nach einem Feld wie `level` |
| `GET /api/v1/facets` | häufigste Werte der Felder mit `facet = true` |
| `GET /api/v1/fields?apps=...` | welche Felder die gewählten Anwendungen haben, mit Typ |
| `GET /api/v1/events/{id}/context` | Originalzeilen davor und danach, direkt aus hot/ per Byte-Offset, ältere aus archive/ |

- Gemeinsame Parameter: `apps` (kommagetrennt, leer heißt alle erlaubten), `q`, `from` und `to` als ISO-8601-Zeitpunkt und Feldfilter `f.<feld>=<wert>`, wiederholbar. Mehrere Werte eines Feldes sind mit ODER verknüpft, verschiedene Felder mit UND. Die Facette eines Feldes ignoriert den eigenen Filter, damit die GUI weitere Werte zur Auswahl anbieten kann.
- Fehler kommen als JSON `{"error": "..."}` mit deutscher Meldung: 400 für eine falsche Anfrage, 403 ohne Recht, 404 wenn die Datei nicht mehr in hot/ liegt, 502 wenn Solr nicht erreichbar ist.
- Mehrere Wörter in `q` sind mit UND verknüpft (`q.op=AND`), wie man es von einer Logsuche erwartet.
- `q` ist Solr-Syntax mit den deklarierten Feldnamen, z.B. `message:(connection AND refused) AND import_id:4711`. log-guy setzt die Namen in Solr-Felder wie `import_id_s` um. Lokale Parameter wie `{!join}` lehnt es ab.
- log-guy setzt immer einen Filter `app:(...)` mit den Anwendungen, die der Nutzer sehen darf. Der Client kann ihn nicht entfernen.
- Ohne `from` und `to` gilt: die letzten 24 Stunden.
- Geblättert wird per `cursorMark`, sortiert nach `ts` und `id`.
- Das Histogramm wählt das kleinste Intervall aus 1, 2, 5, 10 und 30 Sekunden, 1, 2, 5, 10, 15 und 30 Minuten, 1, 2, 3, 6 und 12 Stunden, 1, 2 und 7 Tagen, mit dem höchstens 300 Balken (`search.max-bars`) entstehen. Weil benachbarte Stufen höchstens den Faktor 2,5 auseinanderliegen, sind es mindestens 120 Balken, bei sehr kurzen Zeiträumen weniger. Mit nur 1 Minute, 5 Minuten, 1 Stunde und 1 Tag wären es bei 6 Stunden nur 72 Balken gewesen. Die Balken beginnen an runden Zeitpunkten in der Zeitzone von log-guy, Tage um Mitternacht.
- Mit `split=level` teilt das Histogramm jeden Balken nach den 8 häufigsten Werten des Feldes auf (`search.split-limit`), der Rest kommt als `andere`.
- Der Kontext liest standardmäßig 20 Zeilen davor und danach (`before`, `after`, höchstens 500) und höchstens 512 KB in jede Richtung. ANSI-Farben werden entfernt.

### Archivsuche auf Textebene

| Endpunkt | Wirkung |
| --- | --- |
| `POST /api/v1/archive-search` | Job starten mit Anwendungen, Hosts, Zeitraum und Suchausdruck |
| `GET /api/v1/archive-search/{id}` | Fortschritt: Dateien und Frames gelesen, Treffer bisher |
| `GET /api/v1/archive-search/{id}/results` | Treffer als Stream (NDJSON oder Server-Sent Events) |
| `DELETE /api/v1/archive-search/{id}` | Job abbrechen |

- Durchsucht wird archive/, also mit gekürzten IP-Adressen. Gesucht wird wie mit `zgrep`: Ein regulärer Ausdruck oder einfache Wörter laufen über die Originalzeilen. Treffer kommen mit Dateiname, Byte-Offset und auf Wunsch einigen Zeilen Kontext, wie `grep -C`.
- Der Zeitraum wählt zuerst die Dateien über ihre `.meta.json`, dann innerhalb der Dateien die Frames über die `.idx`.
- Die Frames werden parallel auf allen Kernen entpackt und durchsucht.
- Reguläre Ausdrücke laufen über RE2/J in linearer Zeit. Ein bösartiger Ausdruck kann den Server also nicht lahmlegen. Dafür gibt es keine Rückverweise und kein Lookaround.
- Grenzen pro Nutzer: Anzahl gleichzeitiger Jobs, maximaler Zeitraum, maximale Trefferzahl, Laufzeit.

Umsetzung:

- `POST` erwartet `apps`, `hosts`, `query`, `regex`, `ignoreCase`, `from`, `to` und `context`. Ohne `apps` werden alle Anwendungen durchsucht, für die der Nutzer das Recht `archive` hat und die er mindestens pseudonymisiert lesen darf. Ohne `from` gilt `default-range` vor `to`, ohne `to` jetzt. `GET /api/v1/archive-search` liefert die erlaubten Anwendungen, ihre Hosts und die eigenen Jobs.
- Einfache Wörter werden mit `indexOf` gesucht, reguläre Ausdrücke mit RE2/J. `^` und `$` stehen für Anfang und Ende jeder Zeile. Ein Treffer zählt wie bei `grep` nur, wenn der Ausdruck innerhalb einer Zeile passt.
- Gesucht wird in den Frames, ein Frame ist eine Aufgabe für einen festen Pool von `threads` Threads. Die Arbeit ist reine CPU, deshalb keine Virtual Threads. Kontextzeilen am Rand eines Frames kommen aus dem Nachbar-Frame. Treffer kommen in der Reihenfolge, in der die Frames fertig werden, nicht nach Zeit sortiert. ANSI-Farben werden entfernt, sehr lange Zeilen auf `max-line-length` gekürzt.
- Ein Job gehört dem Nutzer und Token, die ihn gestartet haben, beim MCP-Endpunkt der Session. Fremde Jobs antworten mit 404. Fertige Jobs bleiben `keep` lang abrufbar. Start und Abbruch stehen im Audit-Log.
- `results` streamt NDJSON oder mit `Accept: text/event-stream` Server-Sent Events, mit `offset` ab einem Treffer. Mit `follow=false` kommen nur die bisherigen Treffer, so pollt die GUI.
- Pseudonymisiert: Wer nur `read_pseudonymized` hat oder pseudonymisiert arbeitet, bekommt pseudonymisierte Treffer und Kontextzeilen. Pseudonyme der Session in der Anfrage werden für die Suche in die echten Werte übersetzt. Ein Treffer zählt nur, wenn die Anfrage auch auf die pseudonymisierte Zeile passt. Wer also nach einem echten Namen sucht, der pseudonymisiert wird, findet nichts und erfährt nicht, ob er vorkommt. Eine zurückgehaltene Trefferzeile fällt weg, zurückgehaltene Kontextzeilen erscheinen als `[zurückgehalten]`.
- Die Grenzen stehen unter `log-guy.archive.search`: `threads` (4), `max-jobs` gleichzeitige Jobs pro Nutzer (2), `default-range` (1 Tag), `max-range` (92 Tage), `max-hits` (10.000), `max-runtime` (10 Minuten), `context-lines` als Vorgabe (0) und `max-context-lines` (20), `max-line-length` (4.000 Zeichen) und `keep` (1 Stunde). Bei `max-hits` und `max-runtime` endet der Job mit dem Zustand `LIMIT` oder `TIMEOUT` und einem Hinweis, dass der Zeitraum nicht vollständig durchsucht ist. Ungültige Ausdrücke wie Rückverweise lehnt der Start mit einer deutschen Meldung ab.
- Wird eine Datei während der Suche per `archive refilter` neu geschrieben, liest die Suche die Frames aus der neuen Fassung, erkannt am selben Bereich im Original.

Gemessen an einem Monat digibib (März 2025, 32 Dateien, 831 MB roh) mit 1-MB-Frames auf einem Rechner mit 32 Threads:

| Suche | 1 Thread | 8 Threads |
| --- | --- | --- |
| Wort `MCRSolr` | 227 MB/s | 1.378 MB/s |
| Wort `timeout`, Groß/Klein egal | 154 MB/s | 1.078 MB/s |
| `ERROR.*Exception` | 226 MB/s | 1.431 MB/s |
| `^\S+ ERROR` | 52 MB/s | 340 MB/s |
| `timeout\|refused`, Groß/Klein egal | 25 MB/s | 162 MB/s |

Ausdrücke mit festem Anfangswort sind so schnell wie Wörter, weil RE2/J dann nach dem Wort springt. Alternativen und Ausdrücke ohne festes Wort am Anfang laufen über die langsame Automatensimulation, etwa sechsmal langsamer. Ein Monat aller Anwendungen, ca. 25 bis 35 GB roh, dauert mit 8 Threads für ein Wort also etwa 20 bis 30 Sekunden, für eine Alternative etwa 3 Minuten. Im laufenden Server mit 8 Threads durchsuchte die Suche nach `OutOfMemoryError` drei Monate digibib, 95 Dateien und 5,4 GB entpackt, in 4,4 Sekunden. Gegen `zcat | grep -ci` über dieselben Tage stimmt die Trefferzahl pro Tag genau. Zwei Erkenntnisse aus der Messung sind umgesetzt: Einfache Wörter laufen ohne Regex, Groß/Klein ignorieren war mit RE2/J nur 100 MB/s schnell. Und jeder Frame bekommt sein eigenes kompiliertes Muster, weil RE2/J seine Automaten pro Muster teilt und 8 Threads sonst nur das 1,8-fache eines Threads schafften.

### Ältere Zeiträume nachindexieren

Wer einen alten Zeitraum mit Feldern und Facetten auswerten will, lässt ihn per `POST /api/v1/reindex` in die Collection laden. Die Dokumente bekommen ein Ablaufdatum `expires`, und der nächtliche Job löscht sie nach 24 Stunden wieder. Im Prototyp hat das Laden von 53 Tagen einer großen Anwendung 7 Minuten gedauert.

[Zur Übersicht](README.md)
