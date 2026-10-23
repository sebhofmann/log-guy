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
| `log-guy reindex --app digibib --since 14d` | Zeitraum einer Anwendung aus dem Archiv neu indexieren, z.B. nach einer Skriptänderung |

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

Die Grenze setzt Solr mit einem Shard auf einem Knoten, nicht das Parsen: Ein Thread parst allein 770.000 Events/s. Für den Live-Betrieb mit etwa 1 GB pro Tag aller Anwendungen reicht das bei Weitem. Eine Neuindexierung von 14 Tagen aller Anwendungen, 15 bis 20 GB roh, dauert so etwa 20 Minuten.

Zu prüfen: UUIDs und Session-IDs in `message` beim Tokenisieren auslassen. Sie machen einen großen Teil der Terms aus und werden kaum im Volltext gesucht.

[Zur Übersicht](README.md)
