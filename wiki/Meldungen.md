# Meldungen

Jede Meldungsregel ist ein kleines JavaScript-Modul. Die Regeln prüfen jedes Event direkt beim Indexieren, mit den Feldern aus dem Parser-Skript und 1 bis 2 Sekunden nach dem Schreiben. Trifft eine Regel, zeigt log-guy die Meldung in GUI und API und schickt sie per Mail oder Webhook an die Ziele der Regel.

### Regelarten

| Art | löst aus, wenn | Beispiel |
| --- | --- | --- |
| Treffer | ein Event passt | `SQLTransientConnectionException: HikariPool-1 - Connection is not available` |
| Schwelle | mindestens N passende Events in einem Zeitfenster | mindestens 50 `ERROR` in 5 Minuten pro Anwendung |
| Stille | eine Anwendung länger nichts geloggt hat | `digibib` seit 30 Minuten ohne Event, Anwendung oder Agent hängt |

### Regeln in JavaScript

| Export | Pflicht | Aufgabe |
| --- | --- | --- |
| `meta` | ja | Anwendungen, Empfänger, `cooldown`, optional `threshold` oder `absentFor` |
| `check(event)` | ja, außer bei Stille | gibt `true` zurück, wenn das Event passt |
| `message(event, count)` | nein | eigener Text für die Meldung |

```js
// Treffer: Connection Pool voll
export const meta = {
    apps: ["digibib", "leopoldina"],
    cooldown: "15m",
    notify: ["mail:it", "webhook:mattermost"],
};

export function check(event) {
    return event.message.includes("Connection is not available")
        && event.message.includes("HikariPool-");
}

export function message(event, count) {
    return `${event.app}: Connection Pool voll, ${count} Treffer seit der letzten Meldung`;
}
```

```js
// Schwelle: mehr als 50 Fehler in 5 Minuten pro Anwendung
export const meta = {
    apps: ["*"],
    threshold: { count: 50, within: "5m", per: "app" },
    notify: ["mail:it"],
};

export function check(event) {
    return event.level === "ERROR";
}
```

```js
// Stille: digibib seit 30 Minuten ohne Event
export const meta = {
    apps: ["digibib"],
    absentFor: "30m",
    notify: ["mail:it"],
};
```

- `check` sieht das Event mit allen Feldern aus dem Parser-Skript, also z.B. `event.level`, `event.logger` oder `event.import_id`.
- Zähler für Schwellen und Zeitgeber für Stille führt log-guy in Java. Die Regel selbst hält keinen Zustand und läuft deshalb in jedem Indexer-Thread gleich.
- Für Regeln gelten dieselben Grenzen wie für Parser: kein Datei- oder Netzzugriff, Speicherlimit, Abbruch nach 50 ms.
- Nur aktuelle Events lösen aus: Ist ein Event älter als 1 Stunde, z.B. beim Backfill oder Neuindexieren, wird es nicht geprüft.
- `cooldown`: Nach einer Meldung sammelt die Regel weitere Treffer und schickt danach eine Zusammenfassung wie „37 weitere Treffer“, statt das Postfach zu fluten.

Die Ziele für Mail und Webhook stehen in der Konfiguration, weil sie Adressen und Geheimnisse enthalten:

```yaml
spring:
  mail:
    host: smtp.example.org
    port: 25
log-guy:
  notifications:
    base-url: https://log-guy.example.org
    from: log-guy@example.org
    mail:
      it:
        to: [it@example.org]
      extern:
        to: [dienstleister@example.org]
        include-text: false
    webhook:
      mattermost:
        url: https://chat.example.org/hooks/...
        headers: { Authorization: "Bearer ..." }
    attempts: 5
    retry-delay: 1m
    timeout: 10s
```

Der Schlüssel heißt `notifications` statt `notify`, weil eine Record-Komponente `notify` mit `Object.notify()` kollidiert. Den SMTP-Server stellt Spring Mail mit `spring.mail.*` ein, also auch `username`, `password` und `properties.mail.smtp.starttls.enable`. `log-guy check` prüft Adressen, Empfänger und Absender.

### Zustellung und Abruf

| Weg | Umsetzung |
| --- | --- |
| Mail | SMTP, reiner Text. Betreff z.B. „[log-guy] Neue Meldung: pool in digibib“, bei weiteren Treffern „Weitere Treffer“, bei Schwelle und Stille am Ende „Erledigt“. Inhalt: Text der Meldung, Regel mit Art, Anwendung, Gruppe, Anzahl, Beginn, erste Zeile des Events, Link auf die Events und auf die Meldungsseite. Mit `include-text: false` fehlen Text und Eventzeile, es bleiben Regel, Anwendung, Anzahl und Links. |
| Webhook | JSON per `POST` mit `text` für Mattermost, Slack oder Matrix hookshot und den Feldern `kind`, `id`, `rule`, `type`, `app`, `group`, `state`, `count`, `opened`, `message`, `eventLine`, `url`. Eine Antwort außer 2xx gilt als Fehler. Teams braucht ein eigenes Format über einen Workflow, das gibt es noch nicht. |
| API | `GET /api/v1/alerts?state=open`, Stream unter `GET /api/v1/alerts/stream`, quittieren mit `POST /api/v1/alerts/{id}/ack` |
| MCP | Tool `list_alerts`, pseudonymisiert wie alles andere |
| GUI | Glocke mit offenen Meldungen, ein Klick springt zu den auslösenden Events |

Eine Meldung ist `offen`, `quittiert` oder `erledigt`. Erledigt ist sie, wenn eine Schwellen- oder Stille-Regel wieder unter ihrer Grenze liegt. Meldungen sieht nur, wer die Anwendung lesen darf. Der Zustand liegt in `state/alerts.ndjson`, eine Datenbank braucht es auch hier nicht.

### Umsetzung

- Regeln liegen als Skripte der Art `rule` unter `scripts/rule/<name>.js` und werden wie Parser im Skript-Editor bearbeitet, versioniert und aktiviert. Eine Regel, die nicht lädt, steht mit Fehler in der Regelliste, die übrigen laufen weiter. Jeder Indexer-Thread hat einen eigenen GraalJS-Kontext pro Regel.
- `meta.apps` nimmt Namen, Muster wie `digibib*` oder `*` und Anwendungsgruppen wie `@mir`, siehe [Rechte und Konfiguration](Rechte-und-Konfiguration.md#anwendungsgruppen). „Gruppe“ in Meldung, Mail und Webhook meint dagegen den Wert aus `threshold.per`. Eine Schwelle zählt immer pro Anwendung. `threshold.per` nennt optional ein weiteres Feld, z.B. `per: "ip"`, dann zählt sie pro Anwendung und Wert, und die Meldung trägt den Wert als Gruppe. `per: "app"` ist dasselbe wie ohne.
- Zähler, Zeitfenster, Stille und Cooldown stecken in `AlertEngine` ohne I/O. Die Zeit wird hineingereicht, deshalb läuft live und im Regeltest dieselbe Logik. Live prüft ein Thread alle 2 Sekunden (`tick-interval`) Schwellen, Stille und abgelaufene Cooldowns.
- Treffer: Der erste Treffer öffnet eine Meldung und benachrichtigt sofort. Weitere Treffer zählen in dieselbe Meldung, nach Ablauf des Cooldowns geht eine Zusammenfassung raus. Eine Treffer-Meldung wird nie von selbst erledigt. Quittieren beendet sie, der nächste Treffer öffnet eine neue Meldung.
- Schwelle und Stille: Quittieren schaltet nur die Benachrichtigungen ab, die Meldung bleibt aktiv, bis die Grenze wieder unterschritten ist bzw. wieder ein Event kommt. Dann ist sie erledigt und meldet das. Wird eine Regel gelöscht oder ändert sie ihre Art, werden ihre Schwellen- und Stille-Meldungen erledigt.
- Nur Events, die höchstens `max-age` (1 Stunde) alt sind, werden geprüft. Backfill, Nachindexieren aus dem Archiv und Neuindexieren lösen so nichts aus.
- Vorläufig indexierte Events werden schon geprüft. Damit das endgültige Event nicht noch einmal trifft, merkt sich der Indexer pro Datei, welche Regeln auf welches vorläufige Event getroffen haben, auch über einen Neustart hinweg, siehe [Speicherung](Speicherung.md).
- `state/alerts.ndjson` bekommt pro Änderung eine Zeile, die letzte Zeile einer ID gilt. Beim Start wird die Datei verdichtet. Erledigte und quittierte Meldungen werden nach `keep` (30 Tage) verworfen. Texte sind auf `max-text` (500 Zeichen) gekürzt.
- Sichtbarkeit: Wer eine Anwendung lesen darf, auch nur pseudonymisiert, sieht ihre Meldungen und darf sie quittieren. Bei `read_pseudonymized` laufen Text und Eventzeile durch die Pseudonymisierung mit den Pseudonymen der Sitzung, wie in der Suche. Die Regelliste zeigt nur Regeln, die mindestens eine lesbare Anwendung betreffen.
- API: `GET /api/v1/alerts?state=open|acked|resolved|all`, `POST /api/v1/alerts/{id}/ack`, `GET /api/v1/alerts/rules` mit Regeln und Ladefehlern. `GET /api/v1/alerts/stream` ist ein Server-Sent-Events-Stream: Jedes Event `state` trägt Änderungszähler, Zahl der offenen Meldungen und Meldungen: zu Beginn alle offenen, danach die geänderten. Dazu alle 15 Sekunden ein Kommentar als Ping. Nach 10 Minuten (`stream-duration`) endet der Stream, `EventSource` verbindet sich selbst neu.
- In Spring Framework 7 ignoriert der Ausgabestrom einer `StreamingResponseBody` `flush()`. Stream und Archivsuche leiten `flush()` deshalb auf die Servlet-Antwort um (`FlushingBody`), sonst kämen die Events erst am Ende an.
- MCP: Tool `list_alerts` mit Zustand und Höchstzahl, neueste zuerst, pseudonymisiert wie alles andere.
- Benachrichtigungen gehen an alle `Notifier`: `LoggingNotifier` schreibt jede ins Server-Log, `MailNotifier` und `WebhookNotifier` nehmen die Ziele `mail:<name>` und `webhook:<name>` aus `meta.notify`. Verschickt wird in einem eigenen Thread, damit ein langsamer Mailserver die Meldungen nicht aufhält. Schlägt das Senden fehl, versucht log-guy es bis zu `attempts` Mal, mit `retry-delay` und danach jeweils doppelt so langer Pause. Ein Ziel, das in der Konfiguration fehlt, steht einmal als Warnung im Server-Log. Beim Beenden noch wartende Wiederholungen gehen verloren und stehen als Warnung im Log.
- Mail und Webhook verschicken den Klartext. Wer Texte nicht sehen darf, bekommt ein Ziel mit `include-text: false` und folgt dem Link in die GUI, wo die Rechte gelten.
- Wurde eine Meldung während des Cooldowns einer früheren Meldung derselben Regel und Anwendung geöffnet, geht nach Ablauf des Cooldowns „Neue Meldung“ mit ihrem eigenen Text hinaus, nicht „Weitere Treffer“. Die Empfänger kennen sie ja noch nicht.
- Die Status-Seite zählt zugestellte, aufgegebene und wartende Benachrichtigungen seit dem Start. Der Mail-Indikator von Spring Actuator ist mit `management.health.mail.enabled: false` abgeschaltet, sonst meldete `/actuator/health` bei ausgefallenem SMTP-Server DOWN (503), und ein Healthcheck würde den gesunden Server neu starten.
- `log-guy.alerts.enabled: false` schaltet die Prüfung ab.

Erprobt mit Mailpit als SMTP-Server und einem kleinen Webhook-Empfänger: Die Regel „pool“ mit den Zielen `mail:it`, `mail:extern` (ohne Text) und `webhook:chat` schickte 2 s nach der Hikari-Zeile beide Mails und den Webhook. Der Link „Events ansehen“ öffnet nach der Anmeldung die Suche mit dem Event. Bei gestopptem Empfänger scheiterten die Versuche nach 0, 10 und 20 s, der dritte kam nach dem Neustart des Empfängers an.

### Regeltest

Der Regeltest im Skript-Editor spielt die Events eines Zeitraums aus Solr in Zeitreihenfolge durch eine frische `AlertEngine`, mit dem Code aus dem Editor. Die Uhr folgt den Zeitstempeln der Events, und zwischen zwei Events wird wie live in jedem Intervall geprüft, damit Zusammenfassungen und Stille zur richtigen Zeit kommen. `max-age` gilt hier nicht. Ergebnis: gelesene Events, Treffer, Meldungen, Benachrichtigungen nach Art und Zeit, Beispiele und Skriptfehler. Gelesen werden höchstens `test-max-events` (2 Millionen), sonst ist das Ergebnis als gekürzt markiert.

Gemessen gegen den Backfill von `prod-logs/` (Regel „mindestens 3 `ERROR` in 1 Minute“, ein Solr mit 4 GB Heap):

| Zeitraum | Events | Dauer |
| --- | --- | --- |
| digibib, 1 Tag | 484.858 | 31 s |
| digibib, 7 Tage | 1.488.760 | 112 s |

Das sind etwa 15.000 Events pro Sekunde. Der Test über die letzten 24 Stunden einer Anwendung mit dem Volumen von digibib dauert also eine halbe Minute.

[Zur Übersicht](README.md)
