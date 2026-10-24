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
- Schreiben in `archive/`: Ein Archiv-Schreiber liest vollständige Zeilen aus den Frames in `hot/`, lässt sie durch die Lifecycle-Filter laufen und hängt das Ergebnis als eigenen Frame an die Datei in `archive/` an. Weil er nur fertige Frames liest, liegt die Verzögerung bei höchstens `frame-max-age` (60 s) plus `poll-interval` (5 s), nicht bei wenigen Sekunden. Der Inhalt des `.tail` ist noch nicht gesichert, das ist bei `hot/` ohnehin so. Reihenfolge wie in `hot/`: `.log.zst` per `fsync`, dann `.idx`, dann `.meta.json` per `rename`. Nach einem Absturz zählt log-guy die Summen der `.meta.json` aus den Frames neu und macht beim ersten noch nicht archivierten Byte des Originals weiter.
- `.idx` pro Datei in `hot/`: eine JSON-Zeile pro Frame mit Byte-Offset und Länge in der `.zst` (`zst`, `zlen`), Byte-Offset und Länge im entpackten Inhalt (`raw`, `rlen`) und der Empfangszeit (`received`).
- `.idx` pro Datei in `archive/`: zusätzlich der Bereich im Original (`src`, `srcEnd`), die Zahl der Zeilen (`lines`), die Zahl der Platzhalter (`errors`) und der Zeitraum (`from`, `to`). Den Zeitraum liest der Parser der Anwendung aus der ersten und der letzten Startzeile des Frames mit Zeitstempel. Gesucht wird über alle Zeilen, denn ein Frame kann mit Tausenden Zeilen Stacktrace beginnen oder enden. Bei der ersten Fassung mit nur 50 geprüften Zeilen am Anfang und Ende bekamen 6 % der digibib-Frames die Empfangszeit und waren für die Archivsuche mit Zeitraum unsichtbar. Hat ein Frame gar keinen Zeitstempel, setzt er den Zeitraum des vorigen Frames fort. Nur ohne Parser oder beim ersten Frame gilt die Empfangszeit. Die Archivsuche überspringt damit Frames außerhalb des gesuchten Zeitraums.
- `.meta.json` pro Datei: Fingerabdruck, Host, Anwendung, Tag der Ablage, die ursprünglichen Pfade, z.B. `mycore-app.log` und später `mycore-app-10-23-2026-1.log.gz`, Größe, Empfangszeiten und ob die Datei abgeschlossen ist. In `archive/` statt der Empfangszeiten die archivierte Länge des Originals (`sourceLength`), Länge und Zeilen der gefilterten Datei, Zeitraum, die Filter als `name@fassung` und die Zahl der Filterfehler.
- Kompression in `archive/` mit zstd-Stufe 9 (`log-guy.archive.compression-level`), in `hot/` mit Stufe 3. Gemessen am Backfill von `prod-logs/` (43,9 GB entpackt, 691 Dateien): `hot/` 4,4 GB, `archive/` 3,8 GB, also etwa 1:11,5 im Archiv und 1:10 in `hot/`.
- Nach 7 Tagen löscht ein nächtlicher Job die Datei aus `hot/`. Zur selben Zeit fällt der Tag aus dem Index. Der Job läuft im selben Lauf wie die Aufbewahrung des Index (`log-guy.retention.cron`) und gezählt wird wie bei `index-days`: Mit `hot-days: 7` bleiben heute und die sieben Tage davor. Eine Datei verschwindet erst, wenn alle Bedingungen erfüllt sind: Sie ist abgeschlossen, im Archiv vollständig und abgeschlossen (`sourceLength` gleich der Länge in `hot/`), vollständig indexiert oder ohne Parser, und ihr letztes Event (`to` im Archiv, sonst die letzte Empfangszeit) liegt vor diesem Fenster. Eine Live-Datei, die nie rotiert wird, bleibt deshalb in `hot/`, bis der Agent sie abschließt. Gelöscht wird zuerst die `.meta.json`, dann Frames, `.idx` und `.tail`, so bleibt nach einem Absturz nur Rest, den log-guy beim Start nicht lädt. Der Indexer vergisst dabei auch seinen Stand der Datei.
- Duplikatschutz nach dem Löschen: Der Fingerabdruck bleibt über das Archiv bekannt. Fragt ein Agent nach einer Datei, die nur noch im Archiv liegt, antwortet log-guy mit der archivierten Länge und „abgeschlossen“. Schickt er sie trotzdem noch einmal ab Offset 0, z.B. nach dem Verlust seines Zustands, bestätigt log-guy die bekannten Bytes ohne sie zu speichern, und neue Bytes lehnt er mit 409 ab. So entsteht keine zweite Datei in `hot/` und kein Duplikat im Index.

Platzbedarf bei der gemessenen Kompression von etwa 1:7: `archive/` braucht 0,1 bis 0,17 GB pro Tag für alle Anwendungen, also 40 bis 60 GB pro Jahr und 400 bis 600 GB in zehn Jahren. `hot/` bleibt bei etwa 1 GB. Mit der am Archiv gemessenen Kompression von 1:11,5 für die MyCoRe-Logs von digibib wären es eher 0,09 GB pro Tag. Die Schätzung oben bleibt die Obergrenze, weil Access-Logs anders komprimieren.

Was der Frame-Index bringt: Für eine Stelle mitten in der Datei sucht log-guy in der `.idx` den passenden Frame und entpackt nur ihn, also etwa 1 MB statt der ganzen Datei. Die Kontextansicht in der GUI antwortet dadurch in Millisekunden. Die Archivsuche überspringt Frames außerhalb des Zeitraums und verteilt die Frames einer großen Datei auf mehrere Kerne. Diese Vorteile sind abgeleitet, nicht gemessen.

### Indexer

- Der Indexer liest neue Bytes pro Logdatei aus hot/, zerlegt sie mit dem Parser-Skript der Anwendung in Events und schickt diese in Batches an Solr (`batch-size`, 1000 Dokumente).
- Live-Dateien verfolgt er mit 1 bis 2 Sekunden Verzögerung (`poll-interval`, 1 s).
- Mehrere Threads (`threads`) arbeiten parallel, jede Datei aber immer nur in einem Thread. Nach 64 MB (`round-size`) einer Datei kommen andere Dateien an die Reihe, damit ein großer Backfill die Live-Dateien nicht aufhält.
- Die Event-ID ist `<fingerprint>:<Byte-Offset des Event-Anfangs>`. Jedes Byte existiert pro Fingerabdruck nur einmal, also gibt es auch jedes Event nur einmal. Neu indexieren überschreibt dieselben Dokumente. Aus dem Archiv geladene Events heißen `<fingerprint>:a<Offset>`, siehe [Suche](Suche.md).
- Ein Stacktrace, der noch wächst, wird nach 5 Sekunden ohne neue Zeile vorläufig indexiert (`provisional-after`). Kommen weitere Zeilen, überschreibt das Event unter derselben ID das vorläufige. Eine letzte Zeile ohne Zeilenumbruch gehört dabei schon dazu, und `isStart` entscheidet, ob sie ein eigenes Event beginnt.
- Ein Event mit mehr als 1 MB Text wird abgeschnitten (`max-event-size`).
- Den Fortschritt pro Logdatei merkt er sich in `state/index/<fingerprint>` als JSON: Offset des ersten noch offenen Events, Zeitstempel des letzten Events, ob die Datei fertig ist und unter `matched` die Meldungsregeln, die auf vorläufig indexierte Events schon getroffen haben (Offset des Events auf Regelnamen). So zählt ein vorläufiges Event nach einem Neustart nicht noch einmal als Treffer. Nach einem Absturz macht er dort weiter. Die Datei wird ohne `fsync` geschrieben, geht sie verloren, wird nur doppelt indexiert, was dieselben Dokumente überschreibt.
- Ist Solr nicht erreichbar, wächst nur der Rückstand. Der Agent kann weiter senden.

### Collection

- Eine Collection für alles, 1 Shard, Replikationsfaktor 2. Jedes Dokument trägt die Felder `host` und `app`.
- Gesucht wird über den Alias `logs`, der auf `logs_v1` zeigt. So lässt sich die Collection mit einem neuen Configset im Hintergrund neu aufbauen und dann umschalten.
- Aufbewahrung: Ein nächtlicher Job löscht per `deleteByQuery` alles, was älter als `index-days` ist. Den Wert gibt es global, festgelegt sind 7 Tage, und optional pro Anwendung. Danach läuft `expungeDeletes`, damit gelöschte Dokumente samt IP-Adressen auch physisch von der Platte verschwinden. Ein Tag zählt in der Zeitzone von log-guy: Mit `index-days: 7` bleiben heute und die sieben Tage davor, also immer mindestens sieben volle Tage. Der Zeitpunkt steht in `log-guy.retention.cron`, Vorgabe `0 30 2 * * *`, mit `-` ist der Job aus. Im selben Lauf löscht log-guy die rotierten Dateien seines eigenen Logs, die älter als `log-guy.server-log.keep-days` sind.
- Eigenes Log: log-guy schreibt sein Log zusätzlich zur Konsole nach `logs/log-guy.log` im Datenverzeichnis, täglich rotiert als `.gz`, Vorgabe 30 Tage. Die letzten `log-guy.server-log.ring-size` Einträge, Vorgabe 5000, hält ein Ringpuffer im Speicher für die Seite Server-Log.
- Gelöschte Dokumente sind zunächst nur markiert und werden bei Merges entfernt. Bei 0,3 bis 0,5 GB neuem Index pro Tag ist das unkritisch. `deletesPctAllowed` begrenzt den Anteil markierter Dokumente.
- Ab etwa 50 GB Index lohnt sich ein zweiter Shard. Nach der Hochrechnung ist das bei 7 Tagen nicht nötig.

[Zur Übersicht](README.md)
