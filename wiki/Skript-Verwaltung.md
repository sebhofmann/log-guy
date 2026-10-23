# Skripte: Verzeichnis, Reload, GUI und MCP

Alle vier Arten von Skripten sind JavaScript-Module: Parser, Meldungsregeln, Lifecycle-Filter und Pseudonymisierung. Sie liegen als normale Dateien im Konfigurationsverzeichnis und lassen sich auf drei Wegen ändern: direkt im Dateisystem mit anschließendem Reload, in der GUI oder per MCP durch ein LLM. Testen geht in GUI und MCP gleichermaßen.

### Verzeichnisse

Zwei Verzeichnisse, im Docker-Container als zwei Volumes gemountet:

```
/etc/log-guy/                  Konfiguration, klein, kann in Git liegen
  application.yml              Anwendungen, Nutzer, Rechte, Tokens, Solr, Mail- und Webhook-Ziele
  scripts/
    parser/digibib.js          ein Parser pro Anwendung, Name = Anwendung
    parser/digibib-apache.js
    rule/connection-pool-voll.js
    lifecycle/ip-kuerzen.js
    pseudonym/standard.js
  .history/                    jede frühere Fassung mit Zeitpunkt und Autor
  .drafts/                     Entwürfe, die noch freigegeben werden müssen

/var/lib/log-guy/              Daten, groß
  hot/  archive/  state/  audit/      gesichert werden nur archive/, state/, audit/
```

### Reload wie bei nginx

| Befehl | Wirkung |
| --- | --- |
| `log-guy check` | prüft `application.yml` und alle Skripte, ohne etwas zu aktivieren, wie `nginx -t` |
| `log-guy reload` | der laufende Server liest Konfiguration und Skripte neu, über `POST /admin/reload` |
| `log-guy reload parser/digibib` | nur ein Skript neu laden |

- Ein Reload prüft erst alles. Ist irgendwo ein Fehler, läuft log-guy mit dem alten Stand weiter und meldet die Fehler, wie nginx.
- Vor jedem Aktivieren landet die bisherige Fassung in `.history/`, beim Reload mit dem Autor „Dateisystem“.
- Speichert jemand in der GUI, während die Datei auf der Platte geändert wurde, erkennt log-guy das am Hash der Ausgangsfassung und zeigt den Konflikt an, statt zu überschreiben.

### Ablauf in GUI und MCP

1. **Entwurf:** Ein Skript wird bearbeitet oder neu angelegt. Der Entwurf liegt in `.drafts/` und ist noch nicht aktiv.
2. **Testen:** gegen eine der Quellen unten, beliebig oft.
3. **Aktivieren:** log-guy prüft das Modul wie bei `check`, sichert die alte Fassung in `.history/` und ersetzt die Datei atomar per `rename`. Die Indexer-Threads übernehmen die neue Fassung vor ihrem nächsten Batch.
4. **Nacharbeit:** Neue Dokumente tragen `parser_version`. Nach einer Parser-Änderung bietet log-guy an, das Index-Fenster der Anwendung neu zu indexieren. Eine Regel behält Zähler und `cooldown`, solange ihr Name gleich bleibt.

Zurückrollen heißt: eine Fassung aus `.history/` wieder aktivieren. Liegt die Fehlerquote im Test über 1 %, lässt sich nur mit ausdrücklicher Bestätigung aktivieren.

### Testen

Testquellen, für alle Arten gleich:

- eine Anwendung, mit Zeitraum oder den letzten N Zeilen
- eine bestimmte Logdatei im Archiv, z.B. `app1/digibib/2026-10-22-<fingerprint>.log.zst`
- eingefügte Beispielzeilen

| Art | Testergebnis |
| --- | --- |
| Parser | Events mit Feldern als Tabelle, Fehler, nicht deklarierte Felder, Events pro Sekunde |
| Meldungsregel | welche Events ausgelöst hätten und wie viele Meldungen mit `cooldown` verschickt worden wären |
| Lifecycle-Filter | geänderte und gelöschte Zeilen, Beispiele vorher und nachher |
| Pseudonymisierung | Ausgabe mit Pseudonymen, Ersetzungen pro Kategorie, verdächtige Werte, die übrig bleiben |

Ein Test verändert nichts, weder Archiv noch Index, und verschickt keine Meldungen.

### Endpunkte

| Methode und Pfad | Zweck |
| --- | --- |
| `GET /api/v1/scripts` | alle Skripte mit Art, Name, Stand und offenen Entwürfen |
| `GET /api/v1/scripts/{art}/{name}` | Quelltext, Entwurf und Liste der früheren Fassungen |
| `PUT /api/v1/scripts/{art}/{name}/draft` | Entwurf speichern |
| `POST /api/v1/scripts/{art}/{name}/test` | Entwurf oder aktive Fassung gegen eine Testquelle laufen lassen |
| `POST /api/v1/scripts/{art}/{name}/activate` | Entwurf aktivieren |
| `POST /api/v1/scripts/{art}/{name}/rollback?at=<zeitpunkt>` | frühere Fassung wieder aktivieren |
| `DELETE /api/v1/scripts/{art}/{name}` | Skript entfernen, außer Parser |

`{art}` ist `parser`, `rule`, `lifecycle` oder `pseudonym`.

### Per MCP

Dieselben Funktionen gibt es als MCP-Tools: `list_scripts`, `get_script`, `save_draft`, `test_script`, `activate_script` und `rollback_script`. Ein LLM kann also einen Parser für eine neue Anwendung schreiben, ihn gegen deren Logs testen und verbessern, bis die Felder stimmen.

Dabei gelten zwei Einschränkungen:

- **Freigabe durch einen Menschen:** Entwürfe, die per MCP entstehen, aktiviert standardmäßig ein Mensch in der GUI. Er sieht dort den Unterschied zur aktiven Fassung und das letzte Testergebnis. Ein MCP-Token mit `activate: true` darf Parser und Meldungsregeln selbst aktivieren. Lifecycle-Filter und Pseudonymisierung brauchen immer einen Menschen.
- **Alles pseudonymisiert:** Auch Testergebnisse laufen durch die aktive Pseudonymisierung. Testet das LLM einen Entwurf der Pseudonymisierung selbst, bekommt es nur Statistiken zurück, z.B. 412 Nutzer und 1.380 IP-Adressen ersetzt und 3 mailadressenähnliche Werte übrig, mit Zeilennummer, aber ohne den Wert.

Warum so streng: Das LLM liest Logtext, den Fremde geschrieben haben, z.B. URLs und User-Agents. Eine Prompt Injection darin könnte es dazu bringen, die Pseudonymisierung abzuschwächen oder Werte so umzukodieren, dass keine Regel sie mehr erkennt. Meldungen kann eine Regel nur an Ziele aus `application.yml` schicken, nicht an beliebige Adressen.

### Rechte

- `scripts` pro Anwendung: Parser, Meldungsregeln und Lifecycle-Filter dieser Anwendung bearbeiten, testen und aktivieren. Setzt `read` für dieselben Anwendungen voraus.
- Skripte, die alle Anwendungen betreffen (`apps: ["*"]`), und alle Pseudonymisierungs-Skripte brauchen `admin`.
- Jede Änderung landet im Audit-Log, mit Nutzer oder Token, Zeitpunkt und Weg: Dateisystem, GUI oder MCP.

### Vorlagen

log-guy bringt die handgeschriebenen Parser für MyCoRe und Apache sowie eine Standard-Pseudonymisierung für Nutzer, IP-Adressen und Mailadressen als Vorlagen mit.

[Zur Übersicht](README.md)
