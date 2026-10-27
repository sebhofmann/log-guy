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
    .history/<art>/<name>/     jede aktivierte Fassung als <millis>.js mit <millis>.json (Zeitpunkt, Autor, Weg, Hash)
    .drafts/<art>/<name>.js    Entwürfe, die noch freigegeben werden müssen, daneben <name>.json mit Ausgangsfassung
                               und letztem Test

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
- `.history/` enthält jede aktivierte Fassung einschließlich der aktuellen. Beim Start und beim Reload trägt log-guy eine Datei, die von der neuesten Fassung abweicht, mit dem Autor „Dateisystem“ nach. Damit lässt sich auch eine Änderung von Hand zurückrollen.
- Speichert jemand in der GUI, während die Datei auf der Platte geändert wurde, erkennt log-guy das am Hash der Ausgangsfassung und zeigt den Konflikt an, statt zu überschreiben. Aktivieren antwortet dann mit 409, mit `force` lässt sich trotzdem überschreiben.
- Ein Reload übernimmt Anwendungen, Nutzer, Tokens und Skripte sofort. Für eine neue Anwendung lädt er den Parser mit und prüft ihn wie die Skripte. Schlägt das fehl, bleibt alles beim alten Stand. Danach indexiert log-guy auch Dateien, die vorher schon für die Anwendung angekommen waren, sucht darin und wendet Aufbewahrung und Meldungen an. Die Antwort nennt die geänderten Anwendungen unter `apps` (`+neu`, `-entfernt`, `~geändert`). Andere Einstellungen in `application.yml`, z.B. Solr, werden geprüft, wirken aber erst nach einem Neustart. Die Antwort nennt sie unter `restartNeeded`.
- `log-guy reload` ruft `POST /admin/reload` am laufenden Server auf, Adresse aus `log-guy.cli.server-url`, Standard `http://localhost:${server.port}`. Der Aufruf weist sich mit einem Steuer-Token aus, das der Server bei jedem Start neu erzeugt und in `state/control.token` nur für seinen eigenen Nutzer lesbar ablegt. Wer die Datei lesen kann, darf neu laden. In der GUI dürfen Administratoren neu laden.
- Ohne Parser-Datei gilt die mitgelieferte Vorlage gleichen Namens, z.B. `mycore-log4j`. Wird eine Vorlage in der GUI geändert, entsteht beim Aktivieren die Datei.

### Ablauf in GUI und MCP

1. **Entwurf:** Ein Skript wird bearbeitet oder neu angelegt. Der Entwurf liegt in `.drafts/` und ist noch nicht aktiv.
2. **Testen:** gegen eine der Quellen unten, beliebig oft.
3. **Aktivieren:** log-guy prüft das Modul wie bei `check`, sichert die alte Fassung in `.history/` und ersetzt die Datei atomar per `rename`. Die Indexer-Threads übernehmen die neue Fassung vor ihrem nächsten Batch.
4. **Nacharbeit:** Neue Dokumente tragen `parser_version`. Nach einer Parser-Änderung bietet log-guy an, das Index-Fenster der Anwendung neu zu indexieren. Eine Regel behält Zähler und `cooldown`, solange ihr Name gleich bleibt.

Zurückrollen heißt: eine Fassung aus `.history/` wieder aktivieren. Liegt die Fehlerquote im letzten Test des Entwurfs über 1 %, lässt sich nur mit ausdrücklicher Bestätigung (`confirm`) aktivieren.

Skripte sind höchstens `log-guy.scripts.max-size` groß, Standard 256 KB.

Prüfen, Testen und Laden werten den Code auf oberster Ebene des Moduls aus, höchstens `log-guy.scripts.load-timeout` lang (Standard 2 s), siehe [Parser-Skripte](Parser-Skripte.md). Das Ergebnis der Prüfung, also Fehler und `meta.apps`, merkt sich log-guy pro Code-Hash für die letzten `log-guy.scripts.check-cache` Fassungen (Standard 1.000). Die Liste der Skripte wertet einen Entwurf so nur einmal aus und nicht bei jedem Aufruf. Aktivieren und Reload prüfen immer neu.

### Testen

Testquellen, für alle Arten gleich:

- eine Anwendung, mit Zeitraum oder den letzten N Zeilen
- eine bestimmte Logdatei, z.B. `app1/digibib/2026-10-22-<fingerprint>.log.zst`
- eingefügte Beispielzeilen

Umgesetzt ist das so: Die letzten N Zeilen einer Anwendung liest der Test vom Ende ihrer Dateien in `hot/`, die neueste zuerst. Bei einem Zeitraum sucht er das erste Event ab `from` im Index und liest ab dessen Stelle in `hot/`. Events außerhalb des Zeitraums zählen nicht mit. Eine Logdatei wird über ihren Fingerabdruck oder ihren Pfad in `hot/` angegeben, ab M4 auch im Archiv. Höchstens `log-guy.scripts.test-lines` Zeilen (Standard 20.000) gehen in einen Test, in der Antwort stehen höchstens `test-events` Events (Standard 500) und die ersten 50 fehlerhaften. Wer testet, braucht neben dem Recht am Skript das Recht `read` an der Anwendung der Quelle.

Der Body von `POST …/test` ist JSON mit genau einer Quelle: `{"app": "digibib", "lines": 1000}`, `{"app": "digibib", "from": "…", "to": "…"}`, `{"file": "<fingerprint>"}` oder `{"text": "…"}`. Ohne weiteres testet der Endpunkt den Entwurf, sonst die aktive Fassung. `"active": true` testet die aktive Fassung trotz Entwurf, `"code"` testet ungespeicherten Quelltext aus dem Editor. Das Ergebnis eines Tests landet beim Entwurf, wenn dessen Quelltext dem getesteten entspricht, und ist dann bei der Freigabe zu sehen. Als Fehler zählt ein Event mit Skriptfehler oder ohne Zeitstempel.

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
| `GET /api/v1/scripts/{art}/{name}/history?at=<zeitpunkt>` | Code einer früheren Fassung, für den Vergleich im Editor |
| `DELETE /api/v1/scripts/{art}/{name}` | Skript entfernen, außer Parser |

`{art}` ist `parser`, `rule`, `lifecycle` oder `pseudonym`.

Fehler kommen als JSON mit `error` (deutscher Text) und `reason`: `invalid` mit 400, `not_found` mit 404, `conflict` mit 409, wenn sich die aktive Fassung seit Beginn des Entwurfs geändert hat (erneut mit `force=true`), und `confirm` mit 409, wenn der letzte Test mehr als 1 % Fehler hatte (erneut mit `confirm=true`). Fehlende Rechte ergeben 403.

### Per MCP

Dieselben Funktionen gibt es als MCP-Tools: `list_scripts`, `get_script`, `save_draft`, `test_script`, `activate_script` und `rollback_script`. Ein LLM kann also einen Parser für eine neue Anwendung schreiben, ihn gegen deren Logs testen und verbessern, bis die Felder stimmen.

Dabei gelten zwei Einschränkungen:

- **Freigabe durch einen Menschen:** Entwürfe, die per MCP entstehen, aktiviert standardmäßig ein Mensch in der GUI. Er sieht dort den Unterschied zur aktiven Fassung und das letzte Testergebnis. Ein MCP-Token mit `activate: true` darf Parser und Meldungsregeln selbst aktivieren. Lifecycle-Filter und Pseudonymisierung brauchen immer einen Menschen.
- **Alles pseudonymisiert:** Auch Testergebnisse laufen durch die aktive Pseudonymisierung. Testet das LLM einen Entwurf der Pseudonymisierung selbst, bekommt es nur Statistiken zurück, z.B. 412 Nutzer und 1.380 IP-Adressen ersetzt und 3 mailadressenähnliche Werte übrig, mit Zeilennummer, aber ohne den Wert.
- **Eigener Code gibt nur Zahlen:** Ein Parser kann Werte so umkodieren, dass keine Regel der Pseudonymisierung sie erkennt. Beispiel-Events, Feldwerte und Fehlertexte gibt es per MCP deshalb nur für die aktive Fassung, die ein Mensch freigegeben hat, also im Dateisystem, in GUI oder API aktiviert oder als Vorlage. Für ungespeicherten Code, Entwürfe und per MCP aktivierte Fassungen liefert `test_script` nur Zahlen: Zeilen, Events, Fehler, Events pro Sekunde, die deklarierten Felder, die Zahl der undeklarierten Felder und die Häufigkeit der Fehlerarten ohne Text. Tests von Meldungsregeln, Lifecycle-Filtern und Pseudonymisierung liefern per MCP immer nur Zahlen.

Warum so streng: Das LLM liest Logtext, den Fremde geschrieben haben, z.B. URLs und User-Agents. Eine Prompt Injection darin könnte es dazu bringen, die Pseudonymisierung abzuschwächen oder Werte so umzukodieren, dass keine Regel sie mehr erkennt. Meldungen kann eine Regel nur an Ziele aus `application.yml` schicken, nicht an beliebige Adressen.

### Rechte

- `scripts` pro Anwendung: Parser, Meldungsregeln und Lifecycle-Filter dieser Anwendung bearbeiten, testen und aktivieren. Setzt `read` für dieselben Anwendungen voraus, auch per MCP. `read_pseudonymized` genügt nicht, denn ein Skript sieht die Zeilen im Klartext und könnte sie in seinen Ergebnissen weitergeben.
- Wer `scripts` für keine Anwendung hat, bekommt beim Speichern, Testen und Aktivieren sofort 403. log-guy prüft das, bevor es irgendeinen Code auswertet, auch nicht `meta.apps`.
- Skripte, die alle Anwendungen betreffen (`apps: ["*"]`), und alle Pseudonymisierungs-Skripte brauchen `admin`.
- Für einen Parser zählen die Anwendungen, die ihn benutzen. Benutzt ihn keine, braucht er `admin`.
- Für Meldungsregeln und Lifecycle-Filter zählen die Muster in `meta.apps`, beim Ändern die der aktiven Fassung und die des Entwurfs. Ein Muster mit `*` braucht `admin`.
- Jede Änderung landet im Audit-Log `audit/<datum>.ndjson`, mit Nutzer oder Token, Zeitpunkt und Weg: Dateisystem, GUI, API, MCP oder CLI. Dort stehen auch jeder Reload und jeder MCP-Aufruf.

### Vorlagen

log-guy bringt die handgeschriebenen Parser für MyCoRe und Apache sowie eine Standard-Pseudonymisierung für Nutzer, IP-Adressen und Mailadressen als Vorlagen mit.

[Zur Übersicht](README.md)
