# Solr-Verwaltung

log-guy richtet seinen Teil der SolrCloud selbst ein: Configset hochladen, Collection anlegen, Alias setzen. In der Konfiguration stehen nur die URLs der Knoten und die Zugangsdaten.

```yaml
log-guy:
  solr:
    urls:
      - http://solr1:8983/solr
      - http://solr2:8983/solr
    user: log-guy
    password-file: /etc/log-guy/solr.pass
    replication-factor: 2
```

- Ist ein Knoten nicht erreichbar, nimmt der Client den nächsten aus der Liste.
- Weitere Werte: `configset` (`log-guy-v1`), `collection` (`logs_v1`), `alias` (`logs`) und `timeout` (60 s). `user` leer heißt ohne Anmeldung.
- Das Configset steckt im JAR unter `solr/log-guy-v1/` und trägt eine Version im Namen. Hochgeladen wird es über die Configsets-API als ZIP.
- Neue Felder einer Anwendung brauchen kein neues Configset, weil sie über dynamische Felder laufen.

| Befehl | Wirkung |
| --- | --- |
| `log-guy solr init` | Configset hochladen, Collection `logs_v1` anlegen, Alias `logs` setzen. Schritte, die schon erledigt sind, werden übersprungen, der Befehl darf also mehrfach laufen. |
| `log-guy solr status` | Größe, Dokumente pro Anwendung, Indexer-Rückstand |
| `log-guy solr upgrade` | neues Configset hochladen, `logs_v2` aus dem Archiv aufbauen, Alias umschalten, `logs_v1` löschen |
| `log-guy reindex --app digibib --since 14d [--until 2d] [--source all\|hot\|archive]` | Zeitraum einer Anwendung neu indexieren: Dateien in `hot/` mit dem aktuellen Parser, der Teil vor dem Index-Fenster aus dem Archiv mit Ablaufdatum, siehe [Suche](Suche.md). `--since` und `--until` nehmen eine Dauer wie `14d`, `36h`, `90m` oder einen Zeitpunkt wie `2025-01-01T00:00:00Z`. Der Befehl wartet, bis das Laden aus dem Archiv fertig ist. |

### Schema

Feste Felder:

| Feld | Typ | Zweck |
| --- | --- | --- |
| `id` | string | `<fingerprint>:<offset>` |
| `app` | string | Anwendung, Grundlage für den Rechtefilter |
| `host` | string | Server |
| `file` | string | Pfad der Logdatei im Archiv |
| `offset` | long | Byte-Offset des Events in der Datei |
| `ts` | Datum | Zeitpunkt, Zeitleiste, Aufbewahrung |
| `message` | Text, gespeichert | Volltext und Anzeige |
| `parser_version` | string | kurzer Hash des Parser-Skripts |
| `parse_error` | string | Fehler des Parser-Skripts, sonst leer |
| `expires` | Datum | Ablaufdatum nachgeladener älterer Zeiträume |

Dynamische Felder für alles aus den Parser-Skripten:

| Endung | Typ |
| --- | --- |
| `*_s`, `*_ss` | string, einfach und mehrwertig |
| `*_i`, `*_l`, `*_d` | int, long, double |
| `*_dt` | Datum |
| `*_b` | bool |
| `*_t` | Text, Volltext |

Alle außer `message` und `*_t` haben DocValues für Facetten und Sortierung. Gespeichert sind nur `id`, `message` und `*_t`, die übrigen Werte liefert Solr in Ergebnissen aus den DocValues. Das Schema ist fest (`ClassicIndexSchemaFactory`), Solr legt keine Felder selbst an. `message` wird mit dem Standard-Tokenizer zerlegt und klein geschrieben.

`solrconfig.xml` aus dem Configset:

- `BEST_COMPRESSION` für gespeicherte Felder, wie im Prototyp gemessen
- `autoCommit` alle 60 s ohne neuen Searcher, `autoSoftCommit` alle 15 s
- `timeAllowed` (20 s) und `cpuAllowed` (15 s) als Defaults im Such-Handler
- Circuit Breakers für Speicher (80 %) und CPU (90 %), damit Log-Suchen die MIR-Collections nicht ausbremsen
- `deletesPctAllowed` 25 % in der `TieredMergePolicy`
- Alle Werte lassen sich als Collection-Properties wie `logguy.timeAllowed` ändern, ohne neues Configset.

### Gemessen: Indexierung

Backfill von `prod-logs/`, 691 Dateien, 43,9 GB roh, lokal auf einem Rechner mit 32 Kernen: SolrCloud `solr:9.10.1` mit einem Knoten und 4 GB Heap, log-guy im Docker-Image auf GraalVM, beides im selben Rechner.

| Schritt | Ergebnis |
| --- | --- |
| Agent bis `hot/`, 4 Dateien parallel, `fsync` je MB | 137 s, etwa 320 MB/s roh |
| Indexer mit 4 Threads | 87.000 Events/s, 15 MB/s roh. Solr nutzt dabei 3,8 Kerne, log-guy einen halben |
| Indexer mit 8 Threads | 62.000 bis 78.000 Events/s, also kein Gewinn. Solr bleibt bei knapp 4 Kernen |
| alle 691 Dateien | 54 Minuten vom Start des Backfills bis zur letzten Datei, mit einem Neustart des Servers dazwischen |
| Vollständigkeit | 218.958.895 Events in Solr, genau so viele Zeilenanfänge wie in `hot/`, keine Parse-Fehler |
| Index | 29,1 GB in 31 Segmenten ohne Force-Merge, also 0,66 Byte pro Rohbyte |
| Live-Datei einer lokalen MIR-Instanz | eine neue Zeile ist nach etwa 17 s suchbar: 0,3 s bis `hot/`, 5 s bis zum vorläufigen Indexieren, bis zu 15 s bis zum nächsten `autoSoftCommit` |

Die Grenze setzt Solr mit einem Shard auf einem Knoten, nicht das Parsen: Ein Thread parst allein 770.000 Events/s. Für den Live-Betrieb mit etwa 1 GB pro Tag aller Anwendungen reicht das bei Weitem. Eine Neuindexierung von 14 Tagen aller Anwendungen, 15 bis 20 GB roh, dauert so etwa 20 Minuten.

Der Index ist mit 0,66 Byte pro Rohbyte deutlich größer als die 0,42 aus dem Prototyp. Der Prototyp war auf ein Segment gemergt und hatte weniger Felder, die Zahlen sind also nicht direkt vergleichbar. Bevor die Hochrechnung für die Index-Größe gilt, muss ein Force-Merge mit dem jetzigen Schema zeigen, wie viel davon an den Segmenten liegt und wie viel an den zusätzlichen Feldern wie `file`, `offset`, `logger` und `exc`.

Beim Neustart während der Indexierung gingen zuerst 6.938 Events verloren: Der Indexer hatte nach einem vollen Batch mitten in einem gelesenen Block das Ende des Blocks als Fortschritt gespeichert. Er speichert jetzt den Anfang des ersten noch nicht gesendeten Events, ein Integrationstest prüft das.

Zu prüfen: UUIDs und Session-IDs in `message` beim Tokenisieren auslassen. Sie machen einen großen Teil der Terms aus und werden kaum im Volltext gesucht.

[Zur Übersicht](README.md)
