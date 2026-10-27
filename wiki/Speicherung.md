# Speicherung

Jede Logdatei liegt zweimal: als Original für die letzten 7 Tage in `hot/`, und gefiltert, also mit gekürzten IP-Adressen, für immer in `archive/`. Von beiden wird nur `archive/` gesichert. Erst beim Indexieren werden aus den Zeilen Events und Felder, und Solr hält diese in einer einzigen Collection.

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
- Fehlgeschlagenes Schreiben, etwa bei voller Platte: `.tail`, `.idx` und `.log.zst` werden nicht mit `APPEND` beschrieben, sondern ab dem Ende, das log-guy für gültig hält. Schlägt ein Schreiben nach einem Teil der Bytes fehl, schneidet log-guy die Datei sofort auf die Größe davor zurück, und der Agent bekommt einen Fehler und sendet denselben Block noch einmal. Ohne das Zurückschneiden stünden die Bytes in `.tail` doppelt und in `.idx` landete die nächste Zeile hinter einer halben. Scheitert auch das Zurückschneiden, gilt die Datei als beschädigt: Sie nimmt nichts mehr an, bis vor dem nächsten Schreiben alles hinter dem gültigen Ende abgeschnitten werden konnte. Eine beschädigte `.tail` schreibt log-guy aus dem Speicher per `rename` neu. Schlägt die Zeile in `.idx` fehl, wird auch der schon geschriebene Frame in `.log.zst` zurückgeschnitten, der nächste Versuch schreibt beide neu.
- Laden der `.idx`: Nur die letzte Zeile darf unvollständig sein, sie stammt von einem Absturz und wird mit einer Warnung abgeschnitten. Eine kaputte Zeile mitten in der Datei ist ein Fehler und landet als ERROR im Server-Log und auf der Status-Seite im Abschnitt „Speicher“. Steht am Ende der kaputten Zeile ein vollständiger Frame, wie ihn ein fehlgeschlagenes Schreiben ohne Zurückschneiden hinterlässt, behält log-guy ihn. Frames dahinter bleiben, solange ihre Offsets in `.log.zst`, im entpackten Inhalt und im Archiv auch im Original lückenlos an den vorigen Frame anschließen. Ab dem ersten Frame, der nicht anschließt, verwirft log-guy den Rest. Danach wird die `.idx` per `rename` neu geschrieben. Frames, deren Daten in `.log.zst` fehlen, werden ebenso gemeldet und entfernt. Die Meldungen der Status-Seite gelten bis zum nächsten Start, beschädigte Dateien bis zum nächsten erfolgreichen Schreiben.
- `.idx` pro Datei in `hot/`: eine JSON-Zeile pro Frame mit Byte-Offset und Länge in der `.zst` (`zst`, `zlen`), Byte-Offset und Länge im entpackten Inhalt (`raw`, `rlen`) und der Empfangszeit (`received`).
- `.idx` pro Datei in `archive/`: zusätzlich der Bereich im Original (`src`, `srcEnd`), die Zahl der Zeilen (`lines`), die Zahl der Platzhalter (`errors`) und der Zeitraum (`from`, `to`). Den Zeitraum liest der Parser der Anwendung aus der ersten und der letzten Startzeile des Frames mit Zeitstempel. Gesucht wird über alle Zeilen, denn ein Frame kann mit Tausenden Zeilen Stacktrace beginnen oder enden. Bei der ersten Fassung mit nur 50 geprüften Zeilen am Anfang und Ende bekamen 6 % der digibib-Frames die Empfangszeit und waren für die Archivsuche mit Zeitraum unsichtbar. Hat ein Frame gar keinen Zeitstempel, setzt er den Zeitraum des vorigen Frames fort. Nur ohne Parser oder beim ersten Frame gilt die Empfangszeit. Die Archivsuche überspringt damit Frames außerhalb des gesuchten Zeitraums.
- `.meta.json` pro Datei: Fingerabdruck, Host, Anwendung, Tag der Ablage, die ursprünglichen Pfade, z.B. `mycore-app.log` und später `mycore-app-10-23-2026-1.log.gz`, Größe, Empfangszeiten, ob die Datei abgeschlossen ist, und in `hot/` der Schlüssel für die [Pseudonymisierung im Archiv](Lifecycle-Filter.md#pseudonymisierung-im-archiv). In `archive/` statt der Empfangszeiten die archivierte Länge des Originals (`sourceLength`), Länge und Zeilen der gefilterten Datei, Zeitraum, die Filter als `name@fassung` und die Zahl der Filterfehler.
- Kompression in `archive/` mit zstd-Stufe 9 (`log-guy.archive.compression-level`), in `hot/` mit Stufe 3. Gemessen am Backfill von `prod-logs/` (43,9 GB entpackt, 691 Dateien): `hot/` 4,4 GB, `archive/` 3,8 GB, also etwa 1:11,5 im Archiv und 1:10 in `hot/`.
- Nach 7 Tagen löscht ein nächtlicher Job die Datei aus `hot/`. Zur selben Zeit fällt der Tag aus dem Index. Der Job läuft im selben Lauf wie die Aufbewahrung des Index (`log-guy.retention.cron`) und gezählt wird wie bei `index-days`: Mit `hot-days: 7` bleiben heute und die sieben Tage davor. Eine Datei verschwindet erst, wenn alle Bedingungen erfüllt sind: Sie ist abgeschlossen, im Archiv vollständig und abgeschlossen (`sourceLength` gleich der Länge in `hot/`), vollständig indexiert oder ohne Parser, und ihr letztes Event (`to` im Archiv, sonst die letzte Empfangszeit) liegt vor diesem Fenster. Eine Live-Datei, die nie rotiert wird, bleibt deshalb in `hot/`, bis der Agent sie abschließt. Gelöscht wird zuerst die `.meta.json`, dann Frames, `.idx` und `.tail`, so bleibt nach einem Absturz nur Rest, den log-guy beim Start nicht lädt. Der Indexer vergisst dabei auch seinen Stand der Datei.
- Duplikatschutz nach dem Löschen: Der Fingerabdruck bleibt über das Archiv bekannt. Fragt ein Agent nach einer Datei, die nur noch im Archiv liegt, antwortet log-guy mit der archivierten Länge und „abgeschlossen“. Schickt er sie trotzdem noch einmal ab Offset 0, z.B. nach dem Verlust seines Zustands, bestätigt log-guy die bekannten Bytes ohne sie zu speichern, und neue Bytes lehnt er mit 409 ab. So entsteht keine zweite Datei in `hot/` und kein Duplikat im Index.

Platzbedarf bei der gemessenen Kompression von etwa 1:7: `archive/` braucht 0,1 bis 0,17 GB pro Tag für alle Anwendungen, also 40 bis 60 GB pro Jahr und 400 bis 600 GB in zehn Jahren. `hot/` bleibt bei etwa 1 GB. Mit der am Archiv gemessenen Kompression von 1:11,5 für die MyCoRe-Logs von digibib wären es eher 0,09 GB pro Tag. Die Schätzung oben bleibt die Obergrenze, weil Access-Logs anders komprimieren.

Was der Frame-Index bringt: Für eine Stelle mitten in der Datei sucht log-guy in der `.idx` den passenden Frame und entpackt nur ihn, also etwa 1 MB statt der ganzen Datei. Die Kontextansicht in der GUI antwortet dadurch in Millisekunden. Die Archivsuche überspringt Frames außerhalb des Zeitraums und verteilt die Frames einer großen Datei auf mehrere Kerne. Diese Vorteile sind abgeleitet, nicht gemessen.

### Dateien ansehen und löschen

Der Log-Browser in der GUI und die API `/api/v1/files` machen sichtbar, was log-guy aufbewahrt, und erlauben das Löschen einzelner Dateien, z.B. wenn eine Anwendung versehentlich personenbezogene Daten geloggt hat.

| Endpunkt | Wirkung | Recht |
| --- | --- | --- |
| `GET /api/v1/files` | Dateien mit `app`, `host`, `from`, `to` (Tag der Ablage), neueste zuerst, höchstens `list-limit`, dazu Anwendungen und Hosts für die Filter | `archive` |
| `GET /api/v1/files/{fingerprint}` | Metadaten aus `hot/` und `archive/`, ob vollständig indexiert, und `may` mit dem, was der Nutzer mit der Datei darf | `archive` |
| `GET /api/v1/files/{fingerprint}/content` | eine Seite Zeilen, `source=ARCHIVE` oder `HOT`, vorwärts ab `at` oder rückwärts vor `before`, mit `prev` und `next` zum Blättern | `archive`, für `HOT` `admin` |
| `GET /api/v1/files/{fingerprint}/search` | Zeilen mit `q`, `regex`, `ignoreCase` ab `from`, mit `scannedTo` und `done` zum Weitersuchen | wie `content` |
| `GET /api/v1/files/{fingerprint}/download` | die ganze Datei entpackt als Anhang | wie `content`, nur mit Klarwerten |
| `POST /api/v1/files/{fingerprint}/reindex` | Neuindexieren als Job wie `POST /api/v1/reindex` | `scripts` für den Teil aus `hot/`, `archive` für den Teil aus dem Archiv |
| `DELETE /api/v1/files/{fingerprint}?block=true` | löschen, mit `block` (Vorgabe) sperren | `delete` |
| `GET /api/v1/files/blocked`, `DELETE /api/v1/files/blocked/{fingerprint}` | gesperrte Dateien, Sperre aufheben | `delete` |

- Gezeigt wird, was der Nutzer im Archiv sehen darf, also `archive` und mindestens `read_pseudonymized` für die Anwendung. Das Archiv zeigt und durchsucht jeder so, wie es geschrieben ist, mit Tokens statt Klarwerten. `hot/` mit den Originalen sehen und durchsuchen nur Administratoren der Anwendung. Herunterladen geht nur mit Klarwerten.
- Blättern liest über den Frame-Index nur die nötigen Frames. Eine Seite hat höchstens `page-lines` Zeilen und `page-size` Bytes, Zeilen länger als `max-line-length` werden gekürzt. Ein Offset mitten in einer Zeile zeigt die ganze Zeile.
- Die Suche in der Datei arbeitet wie die Archivsuche mit RE2/J ohne ANSI-Farben, liefert aber höchstens `search-hits` Treffer oder liest `search-bytes`, dann hört sie auf und meldet, bis wohin sie gekommen ist.
- „Neu indexieren“ plant für eine Datei den Teil im Index-Fenster aus `hot/`, sofern der Indexer läuft, und den älteren Teil aus dem Archiv mit Ablaufdatum, wie beim Laden eines Zeitraums. Was schon im Index steht, lädt er nicht noch einmal.

Löschen nimmt eine Datei überall heraus: Original in `hot/`, gefilterte Fassung in `archive/`, die Kopie im Backup und ihre Events in Solr, auch die aus dem Archiv geladenen. Die Reihenfolge:

1. Mit Sperre: Eintrag in `state/deleted.json`, atomar geschrieben, bevor etwas gelöscht wird.
2. Aufträge, die Events aus dem Archiv laden, lassen die Datei ab jetzt aus. Lädt der Auftrag gerade aus ihr, wartet das Löschen, bis der Stapel, der gerade an Solr geht, angekommen ist, den Rest der Datei verwirft der Auftrag. So schreibt er nach Schritt 5 keine Events zurück in den Index. Ein wartender Auftrag überspringt die Datei, ohne sie als Fehler zu melden.
3. Der Archiv-Schreiber wird für diese Datei angehalten, ein laufender Durchgang wird abgewartet.
4. In `hot/` und `archive/` jeweils zuerst `.meta.json`, dann Frames, `.idx` und Reste, wie beim nächtlichen Löschen. Nach einem Absturz dazwischen lädt log-guy die Reste beim Start nicht.
5. Der Indexer vergisst die Datei, sobald gerade kein Thread an ihr arbeitet, dann löscht log-guy die Events per `id:<fingerprint>:*`. Ist Solr nicht erreichbar, gilt die Datei trotzdem als gelöscht und die Antwort nennt den Fehler unter `indexError`.
6. Die Kopie im Backup wird sofort gelöscht, läuft gerade ein Backup, an dessen Ende. Ältere Stände des Backup-Ziels, etwa Snapshots, sind nicht Teil von log-guy.

Gesperrt heißt: Fragt ein Agent nach der Datei oder schickt sie erneut, z.B. nach dem Verlust seines Zustands, antwortet log-guy mit „abgeschlossen“ und der Länge zum Zeitpunkt des Löschens, und der Agent hört auf. Ohne Sperre würde der Agent eine noch vorhandene Datei von vorn neu einliefern. Läuft während des Löschens mit Sperre noch ein Append, scheitert er an der gelöschten Datei, und log-guy antwortet ebenfalls mit „abgeschlossen“. Wird die Sperre aufgehoben, nimmt log-guy die Datei wieder an. Löschen und Aufheben stehen mit Nutzer, Host, Pfaden und Tag im Audit-Log.

Einstellungen unter `log-guy.files`: `page-lines` (500), `page-size` (1 MB), `max-line-length` (8 KB), `search-hits` (200), `search-bytes` (256 MB) und `list-limit` (1000).

### Backup

Gesichert werden `archive/`, `state/`, `audit/` und das Konfigurationsverzeichnis `/etc/log-guy/` mit den Skripten, nie `hot/`. Die Dateien in `hot/` liefern die Agenten nach einem Verlust erneut, soweit sie noch auf den Anwendungsservern liegen.

Ein Backup-Werkzeug wie restic oder borg darf `archive/` nicht direkt im laufenden Betrieb lesen: An eine Datei werden ständig Frames angehängt, und beim Neuschreiben mit geänderten Filtern (`log-guy archive refilter` und `log-guy archive pseudonymize`) ersetzt log-guy `.log.zst`, `.idx` und `.meta.json` nacheinander. Eine Kopie mitten darin passt nicht zusammen, und ist das Original aus `hot/` schon gelöscht, lässt sie sich nicht mehr reparieren. log-guy legt deshalb selbst eine stimmige Kopie in einem Backup-Verzeichnis an, das Backup-Werkzeug sichert dieses Verzeichnis weiter:

```yaml
log-guy:
  backup:
    dir: /var/backups/log-guy    # leer schaltet das Backup ab
    cron: "0 30 3 * * *"         # nach der Aufbewahrung um 02:30, "-" nur mit log-guy backup
    config-dir: /etc/log-guy
```

- `archive/`: Pro Datei öffnet log-guy die `.log.zst` unter der Sperre der Datei und merkt sich die Frames, die es in diesem Moment gibt. Kopiert wird nur bis zum Ende dieser Frames. Danach angehängte Frames kommen beim nächsten Lauf, und auch eine gleichzeitig ersetzte Datei bleibt lesbar, weil der geöffnete Dateideskriptor den alten Inhalt festhält. Sind die Frames der Kopie der Anfang der aktuellen Frames, hängt log-guy nur die neuen an, sonst schreibt es die Kopie neu und löscht vorher deren `.meta.json`. Die Reihenfolge ist wie im Archiv: `.log.zst`, `.idx`, `.meta.json`. Ein abgebrochenes Backup repariert log-guy beim Laden deshalb wie einen Absturz.
- `state/`, `audit/` und die Konfiguration werden gespiegelt. Kopiert wird, was sich nach Größe oder Änderungszeit unterscheidet, gelöscht wird, was es nicht mehr gibt. Bei `.ndjson` fällt eine noch unvollständige letzte Zeile weg. `state/control.token` und Dateien auf `.tmp` bleiben draußen.
- Am Ende schreibt log-guy `backup.json` mit Zeiten, Zahlen und Fehlern. Die Status-Seite zeigt den letzten Lauf, `log-guy backup` startet einen Lauf sofort und gibt den Bericht aus. Der Lauf steht im Audit-Log.
- Das Backup-Verzeichnis muss außerhalb von Daten- und Konfigurationsverzeichnis liegen, sonst startet log-guy nicht. Es enthält die Passwort-Hashes und Token aus `application.yml` und das Solr-Passwort, nur log-guy und das Backup-Werkzeug dürfen es lesen.

Gemessen im e2e mit dem Archiv aus dem Backfill von `prod-logs/` auf ext4 und NVMe: Der erste Lauf kopierte 698 Archivdateien mit 3,98 GB und 719 weitere Dateien in 4,9 s, der zweite Lauf ohne neue Daten brauchte 0,18 s. Die Prüfsummen aller 2094 Dateien in `archive/` und in der Kopie stimmten überein.

Zurückspielen: log-guy anhalten, `archive/`, `state/` und `audit/` aus dem Backup in das Datenverzeichnis kopieren, `config/` nach `/etc/log-guy/`, log-guy starten. Ausprobiert mit einer zweiten Instanz auf der Kopie: Sie lud 698 Dateien ohne Reparatur, und eine Archivsuche über drei Monate digibib fand wie das Original 5197 Treffer in 92 Dateien. Danach schicken die Agenten, was `hot/` fehlt. Laut Code gilt: Für abgeschlossene Dateien antwortet log-guy aus dem Archiv, offene Dateien setzt der Archiv-Schreiber bei der archivierten Länge fort. Was nach dem letzten Backup archiviert wurde und auf den Anwendungsservern nicht mehr liegt, ist verloren. Nicht ausprobiert ist bisher das erneute Senden durch einen Agenten nach dem Zurückspielen.

### Indexer

- Der Indexer liest neue Bytes pro Logdatei aus hot/, zerlegt sie mit dem Parser-Skript der Anwendung in Events und schickt diese in Batches an Solr (`batch-size`, 1000 Dokumente).
- Live-Dateien verfolgt er mit 1 bis 2 Sekunden Verzögerung (`poll-interval`, 1 s).
- Mehrere Threads (`threads`) arbeiten parallel, jede Datei aber immer nur in einem Thread. Nach 64 MB (`round-size`) einer Datei kommen andere Dateien an die Reihe, damit ein großer Backfill die Live-Dateien nicht aufhält.
- Die Event-ID ist `<fingerprint>:<Byte-Offset des Event-Anfangs>`. Jedes Byte existiert pro Fingerabdruck nur einmal, also gibt es auch jedes Event nur einmal. Neu indexieren überschreibt dieselben Dokumente. Aus dem Archiv geladene Events heißen `<fingerprint>:a<Offset>`, siehe [Suche](Suche.md).
- Ein Stacktrace, der noch wächst, wird nach 5 Sekunden ohne neue Zeile vorläufig indexiert (`provisional-after`). Kommen weitere Zeilen, überschreibt das Event unter derselben ID das vorläufige. Eine letzte Zeile ohne Zeilenumbruch gehört dabei schon dazu, und `isStart` entscheidet, ob sie ein eigenes Event beginnt.
- Ein Event mit mehr als 1 MB Text wird abgeschnitten (`max-event-size`).
- Den Fortschritt pro Logdatei merkt er sich in `state/index/<fingerprint>` als JSON: Offset des ersten noch offenen Events, Zeitstempel des letzten Events, ob die Datei fertig ist und unter `matched` die Meldungsregeln, die auf vorläufig indexierte Events schon getroffen haben (Offset des Events auf Regelnamen). So zählt ein vorläufiges Event nach einem Neustart nicht noch einmal als Treffer. Nach einem Absturz macht er dort weiter. Die Datei wird ohne `fsync` geschrieben, geht sie verloren, wird nur doppelt indexiert, was dieselben Dokumente überschreibt.
- Ist Solr nicht erreichbar, wächst nur der Rückstand. Der Agent kann weiter senden. Bei Verbindungsfehlern und Status 5xx versucht der Indexer denselben Batch beim nächsten `poll-interval` erneut.
- Lehnt Solr einen Batch mit Status 400 ab, liegt es an einem Dokument, nicht an Solr. Der Indexer halbiert den Batch dann und schickt die Hälften erneut, bis das abgelehnte Dokument allein steht (`DocumentBatches`). Dieses schickt er ohne das Feld, das Solr im Fehler nennt, danach ohne alle Felder des Parser-Skripts und zuletzt als Minimaldokument mit den Pflichtfeldern. Was fehlt, steht in `parse_error`, z.B. `rejected by Solr, left out: path_s`. Nimmt Solr selbst das Minimaldokument nicht, fehlt nur dieses Event im Index. Der Fortschritt geht in jedem Fall weiter, eine einzelne präparierte Zeile hält die Datei nicht auf. Dasselbe gilt beim Nachladen aus dem Archiv.

### Collection

- Eine Collection für alles, 1 Shard, Replikationsfaktor 2. Jedes Dokument trägt die Felder `host` und `app`.
- Gesucht wird über den Alias `logs`, der auf `logs_v1` zeigt. So lässt sich die Collection mit einem neuen Configset im Hintergrund neu aufbauen und dann umschalten.
- Aufbewahrung: Ein nächtlicher Job löscht per `deleteByQuery` alles, was älter als `index-days` ist. Den Wert gibt es global, festgelegt sind 7 Tage, und optional pro Anwendung. Danach läuft `expungeDeletes`, damit gelöschte Dokumente samt IP-Adressen auch physisch von der Platte verschwinden. Ein Tag zählt in der Zeitzone von log-guy: Mit `index-days: 7` bleiben heute und die sieben Tage davor, also immer mindestens sieben volle Tage. Der Zeitpunkt steht in `log-guy.retention.cron`, Vorgabe `0 30 2 * * *`, mit `-` ist der Job aus. Im selben Lauf löscht log-guy die rotierten Dateien seines eigenen Logs, die älter als `log-guy.server-log.keep-days` sind.
- Eigenes Log: log-guy schreibt sein Log zusätzlich zur Konsole nach `logs/log-guy.log` im Datenverzeichnis, täglich rotiert als `.gz`, Vorgabe 30 Tage. Die letzten `log-guy.server-log.ring-size` Einträge, Vorgabe 5000, hält ein Ringpuffer im Speicher für die Seite Server-Log.
- Gelöschte Dokumente sind zunächst nur markiert und werden bei Merges entfernt. Bei 0,3 bis 0,5 GB neuem Index pro Tag ist das unkritisch. `deletesPctAllowed` begrenzt den Anteil markierter Dokumente.
- Ab etwa 50 GB Index lohnt sich ein zweiter Shard. Nach der Hochrechnung ist das bei 7 Tagen nicht nötig.

[Zur Übersicht](README.md)
