# Einsammeln: log-guy-agent und Ingest-API

Das Einsammeln bauen wir selbst, mit einem kleinen Agenten aus demselben Maven-Projekt. Er schickt die Logdateien Byte für Byte unverändert, jeweils mit ihrem Byte-Offset. Erkannt wird eine Datei an einem Fingerabdruck über ihren Inhalt, nicht an ihrem Namen. Darauf beruhen die Ablage im Original und der Schutz vor Duplikaten.

Warum kein Fluent Bit: Fluent Bit zerlegt Logs in Datensätze und kennt keine Offsets, die über die Rotation hinweg gelten. Beides brauchen wir.

### Agent

```yaml
# /etc/log-guy-agent/agent.yml
server: https://log-guy.example.org
token-file: /etc/log-guy-agent/token
sources:
  - app: digibib
    live: /logs/digibib/mycore-app.log
    rotated: /logs/digibib/*/mycore-app-*.log.gz
```

- Er liest die Live-Datei wie `tail -F` mit und schickt neue Bytes jede Sekunde oder sobald 1 MB zusammen ist.
- Rotation erkennt er über den Inode. Die umbenannte Datei liest er bis zum Ende, bevor er zur neuen wechselt.
- Pro Datei merkt er sich den Offset, den log-guy zuletzt bestätigt hat. Ist log-guy nicht erreichbar, macht er später ab diesem Offset weiter, denn die Daten stehen ja noch in der Datei.
- Außerdem beobachtet er die rotierten Dateien unter `rotated`. Fehlt log-guy von einer davon noch etwas, schickt er den Rest aus der `.gz`.

### Betrieb des Agenten

Der Agent läuft als eigener Container neben der Anwendung und bekommt dasselbe Log-Verzeichnis nur lesend gemountet, z.B. `./mir-logs:/logs/digibib:ro`. Notfalls läuft dieselbe Binary direkt auf dem Host.

- Beide Container sehen über den Mount dieselben Dateien im selben Kernel. Schreibt MIR eine Zeile, sieht der Agent sie sofort, auch `inotify` funktioniert über Container hinweg.
- Frisch geschriebene Daten liegen noch im Page Cache. Der Agent liest sie also aus dem RAM, die Last entspricht einem `tail -f`.
- Bei NFS, CIFS oder Docker Desktop kommen `inotify`-Ereignisse nicht zuverlässig an. Der Agent prüft deshalb zusätzlich jede Sekunde Größe und Inode der Datei.
- Der Agent braucht Leserechte. Die MIR-Logs gehören UID 100500 mit Modus `rw-r-----`, der Agent-Container läuft deshalb mit derselben UID oder in der passenden Gruppe.

### Live-Datei und rotierte Dateien ohne Duplikate

Jede Logdatei bekommt einen Fingerabdruck: einen Hash über ihre ersten 1 KB, entpackt. Die Live-Datei `mycore-app.log` und die spätere `mycore-app-10-23-2026-1.log.gz` haben denselben Inhalt und damit denselben Fingerabdruck.

1. Jedes Paket trägt Host, Anwendung, Fingerabdruck, Offset und die Bytes.
2. log-guy weiß pro Fingerabdruck, bis zu welchem Byte er die Datei schon hat.
3. Bytes, die schon da sind, verwirft er. Ein Paket, das teilweise überlappt, schneidet er zu.

Damit ist egal, woher ein Byte kommt: aus der Live-Datei, nach einem Neustart erneut gesendet oder aus der rotierten `.gz`. Eine Datei unter 1 KB schickt der Agent erst, wenn sie 1 KB erreicht oder rotiert wird.

Weil der Name keine Rolle spielt, macht auch logrotate keine Probleme:

- Aus `access.log.1` wird bei der nächsten Rotation `access.log.2.gz`. Beide haben denselben Fingerabdruck, die Datei wird also nicht erneut übertragen.
- Mit `delaycompress` liegt eine Datei erst unkomprimiert und dann als `.gz` vor. Auch das ist derselbe Fingerabdruck.
- Mit `copytruncate` wird die Live-Datei kopiert und geleert statt umbenannt. Der Agent sieht, dass sie kleiner geworden ist, und beginnt einen neuen Stream. Die Kopie gehört zum alten Fingerabdruck.

### Altbestand

`log-guy-agent backfill --app digibib <dateien>` liest vorhandene `.gz`-Dateien, berechnet den Fingerabdruck über den entpackten Inhalt und schickt nur, was log-guy noch fehlt. So kommen auch die Testdaten aus `prod-logs/` ins Archiv.

### Endpunkte

| Methode und Pfad | Zweck |
| --- | --- |
| `GET /api/v1/streams/{fingerprint}` | wie weit log-guy die Datei hat und ob sie abgeschlossen ist |
| `POST /api/v1/streams/{fingerprint}/chunks?offset=N` | rohe Bytes anhängen, Host, Anwendung und Dateiname im Header |
| `POST /api/v1/streams/{fingerprint}/close` | Datei ist rotiert und vollständig übertragen |

- Die Antwort enthält den neuen Endstand und kommt erst, wenn die Bytes in hot/ per `fsync` gesichert sind.
- `409 Conflict`, wenn zwischen Endstand und `offset` eine Lücke ist. Der Agent sendet dann ab dem Endstand neu.
- `401` ohne gültiges Token, `403`, wenn Host oder Anwendung nicht zum Token passen, `429` mit `Retry-After` bei Überlast.
- Ein Ingest-Token gehört zu einem Host und den Anwendungen, die dort laufen, z.B. `digibib` und `digibib-apache`.

[Zur Übersicht](README.md)
