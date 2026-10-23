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

Wer einen alten Zeitraum mit Feldern und Facetten auswerten will, lässt ihn in die Collection laden: auf der Archivseite unter „Zeitraum in den Index laden“, per `POST /api/v1/reindex` mit `{"app", "from", "to", "source": "ARCHIVE"}` oder mit `log-guy reindex --source archive`. Nötig ist das Recht `archive` für die Anwendung.

- Geladen wird nur der Teil vor dem ältesten Event der Anwendung im Index, der Rest ist schon da. Höchstens 92 Tage auf einmal (`log-guy.reindex.max-range`).
- Gelesen werden nur die Frames, deren Zeitraum den gewünschten trifft, dazu je ein Frame davor und danach, damit Events über Frame-Grenzen ganz bleiben. Events außerhalb des Zeitraums werden verworfen. Der Parser ist der aktuelle der Anwendung.
- Die Dokumente bekommen ein Ablaufdatum `expires`, 24 Stunden nach dem Start (`log-guy.reindex.expires-after`). Ein stündlicher Job (`log-guy.reindex.expire-cron`) löscht abgelaufene Dokumente, sie verschwinden also spätestens eine Stunde nach dem Ablauf. Die nächtliche Löschung nach Index-Fenster lässt Dokumente mit noch gültigem `expires` stehen.
- Die ID ist `<fingerprint>:a<Byte-Offset in der gefilterten Archivdatei>`, `file` zeigt auf die Archivdatei. So überschneiden sich geladene Events nie mit denen aus `hot/`, und die Kontextansicht liest die Zeilen aus dem Archiv. Der Skript-Test über einen Zeitraum braucht `hot/` und lehnt solche Events ab.
- Ein Job läuft im Hintergrund in einem Thread, mit Fortschritt in Bytes, Abbruch per `DELETE /api/v1/reindex/{id}` und den letzten 20 Jobs in der Liste. Eine Archivdatei, die nicht lesbar ist oder während des Ladens neu gefiltert wird, steht unter `failed`, die übrigen werden trotzdem geladen.

Gemessen in der e2e-Umgebung: Die erste Woche von digibib aus dem Backfill, 7 Archivdateien mit 208 MB gefiltert, war vorher aus dem Index gelöscht worden. Sie war in 28 Sekunden wieder geladen, mit genau den 1.021.845 Events, die vorher im Index standen, also etwa 36.000 Events pro Sekunde. Im Prototyp hatte das Laden von 53 Tagen einer großen Anwendung 7 Minuten gedauert, das passt zu dieser Rate.

### Neu indexieren nach einer Skriptänderung

Ein neuer Parser gilt zunächst nur für neue Events. Nach dem Aktivieren bietet der Skript-Editor pro Anwendung des Parsers „neu indexieren“ an. Das gleiche leisten `POST /api/v1/reindex` mit `"source": "HOT"` und `log-guy reindex --source hot`, nötig ist das Recht `scripts`.

- Neu indexiert werden die Dateien in `hot/`, die den Zeitraum treffen, aber nur, wenn der Zeitraum ins Index-Fenster reicht. Ohne `--source` lädt `log-guy reindex` den Teil vor dem Index-Fenster zusätzlich aus dem Archiv.
- Der Indexer übernimmt die Datei, sobald gerade kein Thread an ihr arbeitet: Er setzt den Fortschritt in `state/index/` auf den Anfang, löscht die Events dieser Datei aus Solr, ausgenommen die aus dem Archiv geladenen, und liest sie neu. Gelöscht wird vorher, weil ein neuer Parser Events anders schneiden kann und alte IDs sonst als Dubletten stehen blieben. Während des Neuaufbaus fehlen deshalb kurz Events der Datei.
- In der e2e-Umgebung lief das für die 5 Dateien der lokalen MIR-Instanz in unter einer Sekunde, danach standen wieder dieselben 8.584 Events im Index.

[Zur Übersicht](README.md)
