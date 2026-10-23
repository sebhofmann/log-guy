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

Gemessen: Entpacken und Zeilen trennen schafft 719 MB/s auf einem Kern. Ein Monat aller Anwendungen, ca. 25 bis 35 GB roh, wäre mit 8 Kernen also in grob einer Minute gelesen, die Prüfung des Ausdrucks kommt dazu. Das ist hochgerechnet.

### Ältere Zeiträume nachindexieren

Wer einen alten Zeitraum mit Feldern und Facetten auswerten will, lässt ihn per `POST /api/v1/reindex` in die Collection laden. Die Dokumente bekommen ein Ablaufdatum `expires`, und der nächtliche Job löscht sie nach 24 Stunden wieder. Im Prototyp hat das Laden von 53 Tagen einer großen Anwendung 7 Minuten gedauert.

[Zur Übersicht](README.md)
