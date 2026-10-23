# Meldungen

Jede Meldungsregel ist ein kleines JavaScript-Modul. Die Regeln prüfen jedes Event direkt beim Indexieren, mit den Feldern aus dem Parser-Skript und 1 bis 2 Sekunden nach dem Schreiben. Trifft eine Regel, zeigt log-guy die Meldung in GUI und API. Mail und Webhook kommen mit dem Produktivbetrieb in M6.

### Regelarten

| Art | löst aus, wenn | Beispiel |
| --- | --- | --- |
| Treffer | ein Event passt | `SQLTransientConnectionException: HikariPool-1 - Connection is not available` |
| Schwelle | mindestens N passende Events in einem Zeitfenster | mehr als 50 `ERROR` in 5 Minuten pro Anwendung |
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
log-guy:
  notify:
    mail:
      it:
        to: [it@example.org]
    webhook:
      mattermost:
        url: https://chat.example.org/hooks/...
```

### Zustellung und Abruf

| Weg | Umsetzung |
| --- | --- |
| Mail | SMTP. Inhalt: Regel, Anwendung, Anzahl, erste Zeile des Events, Link in die GUI. Mit `include_text = false` nur der Link. |
| Webhook | JSON per `POST`, z.B. an Mattermost, Matrix oder Teams |
| API | `GET /api/v1/alerts?state=open`, Stream unter `GET /api/v1/alerts/stream`, quittieren mit `POST /api/v1/alerts/{id}/ack` |
| MCP | Tool `list_alerts`, pseudonymisiert wie alles andere |
| GUI | Glocke mit offenen Meldungen, ein Klick springt zu den auslösenden Events |

Eine Meldung ist `offen`, `quittiert` oder `erledigt`. Erledigt ist sie, wenn eine Schwellen- oder Stille-Regel wieder unter ihrer Grenze liegt. Meldungen sieht nur, wer die Anwendung lesen darf. Der Zustand liegt in `state/alerts.ndjson`, eine Datenbank braucht es auch hier nicht.

[Zur Übersicht](README.md)
