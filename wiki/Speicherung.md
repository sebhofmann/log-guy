# Speicherung

Jede Logdatei liegt zweimal: als Original für die letzten 7 Tage in `hot/`, und gefiltert, also mit gekürzten IP-Adressen, für immer in `archive/`. Nur `archive/` wird gesichert. Erst beim Indexieren werden aus den Zeilen Events und Felder, und Solr hält diese in einer einzigen Collection.

### Zwei Verzeichnisse

|  | `hot/` | `archive/` |
| --- | --- | --- |
| Inhalt | Originale, Byte für Byte | alles, durch die Lifecycle-Filter gelaufen |
| Zeitraum | letzte 7 Tage (`hot_days`) | von Anfang an, ohne Löschfrist |
| Backup | nein | ja |
| genutzt für | Indexierung, Neuindexierung im Index-Fenster, Kontextansicht | Archivsuche, Nachindexieren älterer Zeiträume |

- Ablage in beiden: `<host>/<app>/<JJJJ-MM-TT>-<fingerprint>.log.zst`. Das Datum ist der Tag, an dem das erste Byte ankam. Der Fingerabdruck ist der des Originals, auch in `archive/`.
- Kompression: zstd in unabhängigen Frames von etwa 1 MB entpackter Größe. Jeder Frame lässt sich einzeln entpacken.
- Schreiben in `hot/`: Neue Bytes landen zuerst in einem unkomprimierten Rest, der Datei `.tail`. Sobald 1 MB zusammen ist (`frame-size`) oder das älteste Byte 60 Sekunden wartet (`frame-max-age`), wird daraus ein Frame und an die Datei angehängt. Frames enden an einem Zeilenende, nur eine Zeile über 4 MB wird mitten in der Zeile geteilt.
- Reihenfolge beim Schreiben eines Frames: erst `.log.zst` per `fsync`, dann die Zeile in `.idx`, dann wird `.tail` per `rename` ersetzt. Die `.tail` beginnt mit dem Offset ihres ersten Bytes. Nach einem Absturz dazwischen erkennt log-guy daran, welche Bytes schon in einem Frame stehen, und verwirft sie. Eine unvollständige letzte Zeile in `.idx` und Bytes in `.log.zst` hinter dem letzten Frame werden abgeschnitten.
- Schreiben in `archive/`: Ein Archiv-Schreiber liest vollständige Zeilen aus `hot/`, lässt sie durch die Lifecycle-Filter laufen und hängt das Ergebnis an die Datei in `archive/` an. Die Verzögerung liegt bei wenigen Sekunden.
- `.idx` pro Datei: eine JSON-Zeile pro Frame mit Byte-Offset und Länge in der `.zst` (`zst`, `zlen`), Byte-Offset und Länge im entpackten Inhalt (`raw`, `rlen`) und der Empfangszeit (`received`). Anzahl und Zeitraum der Events kommen mit dem Archiv-Schreiber in M4 dazu, denn erst er zerlegt die Zeilen.
- `.meta.json` pro Datei: Fingerabdruck, Host, Anwendung, Tag der Ablage, die ursprünglichen Pfade, z.B. `mycore-app.log` und später `mycore-app-10-23-2026-1.log.gz`, Größe, Empfangszeiten und ob die Datei abgeschlossen ist. Den Zeitraum der Events trägt der Archiv-Schreiber nach. In `archive/` zusätzlich die Filter mit ihrer Fassung.
- Nach 7 Tagen löscht ein nächtlicher Job die Datei aus `hot/`. Zur selben Zeit fällt der Tag aus dem Index.

Platzbedarf bei der gemessenen Kompression von etwa 1:7: `archive/` braucht 0,1 bis 0,17 GB pro Tag für alle Anwendungen, also 40 bis 60 GB pro Jahr und 400 bis 600 GB in zehn Jahren. `hot/` bleibt bei etwa 1 GB.

Was der Frame-Index bringt: Für eine Stelle mitten in der Datei sucht log-guy in der `.idx` den passenden Frame und entpackt nur ihn, also etwa 1 MB statt der ganzen Datei. Die Kontextansicht in der GUI antwortet dadurch in Millisekunden. Die Archivsuche überspringt Frames außerhalb des Zeitraums und verteilt die Frames einer großen Datei auf mehrere Kerne. Diese Vorteile sind abgeleitet, nicht gemessen.

### Indexer

- Der Indexer liest neue Bytes pro Logdatei aus hot/, zerlegt sie mit dem Parser-Skript der Anwendung in Events und schickt diese in Batches an Solr (`batch-size`, 1000 Dokumente).
- Live-Dateien verfolgt er mit 1 bis 2 Sekunden Verzögerung (`poll-interval`, 1 s).
- Mehrere Threads (`threads`) arbeiten parallel, jede Datei aber immer nur in einem Thread. Nach 64 MB (`round-size`) einer Datei kommen andere Dateien an die Reihe, damit ein großer Backfill die Live-Dateien nicht aufhält.
- Die Event-ID ist `<fingerprint>:<Byte-Offset des Event-Anfangs>`. Jedes Byte existiert pro Fingerabdruck nur einmal, also gibt es auch jedes Event nur einmal. Neu indexieren überschreibt dieselben Dokumente.
- Ein Stacktrace, der noch wächst, wird nach 5 Sekunden ohne neue Zeile vorläufig indexiert (`provisional-after`). Kommen weitere Zeilen, überschreibt das Event unter derselben ID das vorläufige. Eine letzte Zeile ohne Zeilenumbruch gehört dabei schon dazu, und `isStart` entscheidet, ob sie ein eigenes Event beginnt.
- Ein Event mit mehr als 1 MB Text wird abgeschnitten (`max-event-size`).
- Den Fortschritt pro Logdatei merkt er sich in `state/index/<fingerprint>` als JSON: Offset des ersten noch offenen Events, Zeitstempel des letzten Events und ob die Datei fertig ist. Nach einem Absturz macht er dort weiter. Die Datei wird ohne `fsync` geschrieben, geht sie verloren, wird nur doppelt indexiert, was dieselben Dokumente überschreibt.
- Ist Solr nicht erreichbar, wächst nur der Rückstand. Der Agent kann weiter senden.

### Collection

- Eine Collection für alles, 1 Shard, Replikationsfaktor 2. Jedes Dokument trägt die Felder `host` und `app`.
- Gesucht wird über den Alias `logs`, der auf `logs_v1` zeigt. So lässt sich die Collection mit einem neuen Configset im Hintergrund neu aufbauen und dann umschalten.
- Aufbewahrung: Ein nächtlicher Job löscht per `deleteByQuery` alles, was älter als `index-days` ist. Den Wert gibt es global, festgelegt sind 7 Tage, und optional pro Anwendung. Danach läuft `expungeDeletes`, damit gelöschte Dokumente samt IP-Adressen auch physisch von der Platte verschwinden.
- Gelöschte Dokumente sind zunächst nur markiert und werden bei Merges entfernt. Bei 0,3 bis 0,5 GB neuem Index pro Tag ist das unkritisch. `deletesPctAllowed` begrenzt den Anteil markierter Dokumente.
- Ab etwa 50 GB Index lohnt sich ein zweiter Shard. Nach der Hochrechnung ist das bei 7 Tagen nicht nötig.

[Zur Übersicht](README.md)
