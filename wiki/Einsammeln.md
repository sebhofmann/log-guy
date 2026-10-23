# Einsammeln: log-guy-agent und Ingest-API

Das Einsammeln bauen wir selbst, mit einem kleinen Agenten aus demselben Maven-Projekt. Er schickt die Logdateien Byte für Byte unverändert, jeweils mit ihrem Byte-Offset. Erkannt wird eine Datei an einem Fingerabdruck über ihren Inhalt, nicht an ihrem Namen. Darauf beruhen die Ablage im Original und der Schutz vor Duplikaten.

Warum kein Fluent Bit: Fluent Bit zerlegt Logs in Datensätze und kennt keine Offsets, die über die Rotation hinweg gelten. Beides brauchen wir.

### Agent

```yaml
# /etc/log-guy-agent/agent.yml
server: https://log-guy.example.org
token-file: /etc/log-guy-agent/token
host: app1            # optional, sonst der Hostname, muss zum Token passen
sources:
  - app: digibib
    live: /logs/digibib/mycore-app.log
    rotated: /logs/digibib/*/mycore-app-*.log.gz
```

Weitere Werte mit ihren Vorgaben stehen in `agent-defaults.yml` im JAR: `chunk-size` (1 MB), `send-interval` (1 s), `rotated-scan-interval` (60 s), `connect-timeout`, `request-timeout` und `retry-delay`.

- Er liest die Live-Datei wie `tail -F` mit und schickt neue Bytes jede Sekunde oder sobald 1 MB zusammen ist.
- Rotation erkennt er über den Inode. Die umbenannte Datei liest er bis zum Ende, bevor er zur neuen wechselt.
- Pro Datei merkt er sich im Speicher den Offset, den log-guy zuletzt bestätigt hat. Ist log-guy nicht erreichbar, macht er später ab diesem Offset weiter, denn die Daten stehen ja noch in der Datei. Nach einem Neustart fragt er log-guy per `GET`, wie weit die Datei schon da ist. Eine eigene Zustandsdatei braucht der Agent deshalb nicht.
- Außerdem beobachtet er die rotierten Dateien unter `rotated`. Fehlt log-guy von einer davon noch etwas, schickt er den Rest aus der `.gz`. Vollständig übertragene Dateien merkt er sich mit Pfad, Größe und Änderungszeit und liest sie bis zum Neustart nicht erneut.

### Betrieb des Agenten

Der Agent läuft als eigener Container neben der Anwendung und bekommt dasselbe Log-Verzeichnis nur lesend gemountet, z.B. `./mir-logs:/logs/digibib:ro`. Notfalls läuft dieselbe Binary direkt auf dem Host.

- Beide Container sehen über den Mount dieselben Dateien im selben Kernel. Schreibt MIR eine Zeile, sieht der Agent sie sofort, auch `inotify` funktioniert über Container hinweg.
- Frisch geschriebene Daten liegen noch im Page Cache. Der Agent liest sie also aus dem RAM, die Last entspricht einem `tail -f`.
- Bei NFS, CIFS oder Docker Desktop kommen `inotify`-Ereignisse nicht zuverlässig an. Der Agent prüft deshalb zusätzlich jede Sekunde Größe und Inode der Datei.
- Der Agent braucht Leserechte. Die MIR-Logs gehören UID 100500 mit Modus `rw-r-----`, der Agent-Container läuft deshalb mit derselben UID oder in der passenden Gruppe, z.B. `docker run --user 100500 -v ./mir-logs:/logs/digibib:ro log-guy-agent`.
- Das Image `log-guy-agent` baut auf `eclipse-temurin` mit JRE 25 auf und startet mit `-Xmx64m` und Serial GC.

### Live-Datei und rotierte Dateien ohne Duplikate

Jede Logdatei bekommt einen Fingerabdruck: einen Hash über ihre ersten 1 KB, entpackt. Umgesetzt als SHA-256, gekürzt auf 128 Bit und als 32 Hex-Zeichen geschrieben, z.B. `1383cea8a5455758907f34a07bc86ad8`. Die Live-Datei `mycore-app.log` und die spätere `mycore-app-10-23-2026-1.log.gz` haben denselben Inhalt und damit denselben Fingerabdruck.

1. Jedes Paket trägt Host, Anwendung, Fingerabdruck, Offset und die Bytes.
2. log-guy weiß pro Fingerabdruck, bis zu welchem Byte er die Datei schon hat.
3. Bytes, die schon da sind, verwirft er. Ein Paket, das teilweise überlappt, schneidet er zu.

Damit ist egal, woher ein Byte kommt: aus der Live-Datei, nach einem Neustart erneut gesendet oder aus der rotierten `.gz`. Eine Datei unter 1 KB schickt der Agent erst, wenn sie 1 KB erreicht oder rotiert wird. Ihr Fingerabdruck ist dann der Hash über den ganzen Inhalt.

Das erste Paket einer Datei, also das mit Offset 0, enthält mindestens 1 KB oder die ganze Datei. log-guy berechnet daraus den Fingerabdruck selbst und lehnt das Paket mit `400` ab, wenn er nicht passt.

Weil der Name keine Rolle spielt, macht auch logrotate keine Probleme:

- Aus `access.log.1` wird bei der nächsten Rotation `access.log.2.gz`. Beide haben denselben Fingerabdruck, die Datei wird also nicht erneut übertragen.
- Mit `delaycompress` liegt eine Datei erst unkomprimiert und dann als `.gz` vor. Auch das ist derselbe Fingerabdruck.
- Mit `copytruncate` wird die Live-Datei kopiert und geleert statt umbenannt. Der Agent sieht, dass sie kleiner geworden ist, und beginnt einen neuen Stream. Die Kopie gehört zum alten Fingerabdruck. Den alten Stream schließt der Agent dabei nicht, denn Bytes zwischen dem letzten Senden und dem Leeren stehen nur noch in der Kopie. Sie kommen über `rotated`, danach wird der alte Stream geschlossen.

### Altbestand

`log-guy-agent backfill --app digibib [--parallel 4] <dateien>` liest vorhandene `.gz`-Dateien, berechnet den Fingerabdruck über den entpackten Inhalt und schickt nur, was log-guy noch fehlt. So kommen auch die Testdaten aus `prod-logs/` ins Archiv. Server, Token und Host kommen aus `agent.yml` oder aus `--server`, `--token-file` und `--host`.

### Endpunkte

| Methode und Pfad | Zweck |
| --- | --- |
| `GET /api/v1/streams/{fingerprint}` | wie weit log-guy die Datei hat und ob sie abgeschlossen ist |
| `POST /api/v1/streams/{fingerprint}/chunks?offset=N` | rohe Bytes anhängen, Host, Anwendung und Dateiname im Header |
| `POST /api/v1/streams/{fingerprint}/close?length=N` | Datei ist rotiert und vollständig übertragen, `N` ist ihre Länge |

- Header: `Authorization: Bearer <token>`, `X-Log-Guy-Host`, `X-Log-Guy-App` und `X-Log-Guy-File` mit dem Pfad auf dem Host, URL-kodiert in UTF-8.
- Alle Antworten enthalten den Stand als JSON: `{"fingerprint": "...", "length": 262145761, "closed": false}`. Sie kommen erst, wenn die Bytes in hot/ per `fsync` gesichert sind.
- `409 Conflict` mit demselben JSON, wenn zwischen Endstand und `offset` eine Lücke ist, wenn eine geschlossene Datei neue Bytes bekommen soll oder wenn `length` beim Schließen nicht stimmt. Der Agent macht beim gemeldeten Endstand weiter.
- `401` ohne gültiges Token, `403`, wenn Host oder Anwendung nicht zum Token passen oder die Datei schon unter einem anderen Host oder einer anderen Anwendung liegt, `413` für zu große Pakete (`max-chunk-size`, 8 MB), `429` mit `Retry-After` bei Überlast (`max-concurrent`).
- `GET` antwortet mit `404` auch für Dateien, die einem anderen Token gehören.
- Ein Ingest-Token gehört zu einem Host und den Anwendungen, die dort laufen, z.B. `digibib` und `digibib-apache`.

[Zur Übersicht](README.md)
