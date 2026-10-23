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
- Das Configset steckt im JAR und trägt eine Version im Namen, z.B. `log-guy-v1`. Hochgeladen wird es über die Configsets-API als ZIP.
- Neue Felder einer Anwendung brauchen kein neues Configset, weil sie über dynamische Felder laufen.

| Befehl | Wirkung |
| --- | --- |
| `log-guy solr init` | Configset hochladen, Collection `logs_v1` anlegen, Alias `logs` setzen |
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

Dynamische Felder für alles aus den Parser-Skripten:

| Endung | Typ |
| --- | --- |
| `*_s`, `*_ss` | string, einfach und mehrwertig |
| `*_i`, `*_l`, `*_d` | int, long, double |
| `*_dt` | Datum |
| `*_b` | bool |
| `*_t` | Text, Volltext |

Alle außer `message` und `*_t` haben DocValues für Facetten und Sortierung.

`solrconfig.xml` aus dem Configset:

- `BEST_COMPRESSION` für gespeicherte Felder, wie im Prototyp gemessen
- `autoCommit` alle 60 s ohne neuen Searcher, `autoSoftCommit` alle 15 s
- `timeAllowed` und `cpuAllowed` als Defaults im Such-Handler
- Circuit Breakers für CPU und Speicher, damit Log-Suchen die MIR-Collections nicht ausbremsen

Zu prüfen: UUIDs und Session-IDs in `message` beim Tokenisieren auslassen. Sie machen einen großen Teil der Terms aus und werden kaum im Volltext gesucht.

[Zur Übersicht](README.md)
