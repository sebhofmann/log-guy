# Lifecycle-Filter

Lifecycle-Filter bestimmen, wie die Logs im Archiv aussehen. Sie laufen, während der Archiv-Schreiber die Zeilen aus `hot/` nach `archive/` kopiert. Festgelegt ist: IP-Adressen werden gekürzt. Die Originale bleiben 7 Tage in `hot/` und verschwinden dann, zur selben Zeit wie der Tag aus dem Index.

Jeder Filter ist ein JavaScript-Modul in `scripts/lifecycle/`.

| Export | Pflicht | Aufgabe |
| --- | --- | --- |
| `meta` | ja | Anwendungen, für die der Filter gilt |
| `line(text)` | nein | bekommt jede Zeile auf dem Weg ins Archiv, gibt die neue Zeile zurück oder `null`, dann fehlt sie im Archiv |
| `fields(event)` | nein | verändert nur die Felder im Index, z.B. um Passwörter aus `message` zu entfernen |

```js
// scripts/lifecycle/ip-kuerzen.js
export const meta = { apps: ["*"] };

// maskIp ist eingebaut: IPv4 letztes Oktett auf 0, IPv6 auf /48
export function line(text) {
    return maskIp(text);
}
```

```js
// scripts/lifecycle/healthchecks-weglassen.js
export const meta = { apps: ["*-apache"] };

export function line(text) {
    return text.includes("GET /health ") ? null : text;
}
```

Eingebaute Hilfsfunktionen in Java: `maskIp`, `replaceIps`, `findIps`, `findEmails`. Sie erkennen IPv4 und IPv6 zuverlässiger und schneller als ein Ausdruck im Skript.

### Ablauf

1. Der Agent liefert die Bytes, sie landen als Original in `hot/`.
2. Der Archiv-Schreiber nimmt vollständige Zeilen, lässt sie durch alle Filter der Anwendung laufen und hängt das Ergebnis an die Datei in `archive/` an.
3. Wirft ein Filter einen Fehler, kommt die Zeile nicht ungefiltert ins Archiv. Stattdessen steht dort ein Platzhalter, und die Status-Seite zählt den Fehler. Das Original liegt noch 7 Tage in `hot/`, und `log-guy archive refilter` schreibt die betroffenen Dateien nach der Korrektur neu.
4. Nach 7 Tagen löscht der nächtliche Job die Datei aus `hot/` und die Dokumente des Tages aus dem Index, danach läuft `expungeDeletes`.

Regeln für Backfill und Änderungen:

- Dateien aus dem Backfill, die älter als 7 Tage sind, gehen gefiltert direkt nach `archive/`. Jüngere landen zusätzlich in `hot/`.
- Den Fingerabdruck des Originals behält die Archivdatei. Ein späterer Backfill derselben `.gz` wird daran erkannt und abgelehnt, auch wenn die Datei längst nicht mehr in `hot/` liegt.
- Ein geänderter Filter gilt für neue Daten. Für die letzten 7 Tage kann `log-guy archive refilter` das Archiv aus `hot/` neu schreiben. Ältere Archivdateien lassen sich nur noch weiter filtern, nicht mehr zurück zum Original.
- Die Filter laufen über jede Zeile, bei etwa 1 GB Rohdaten pro Tag aller Anwendungen. Nach dem Benchmark sollte das einen Kern nur einen kleinen Teil der Zeit beschäftigen. Das ist hochgerechnet, nicht gemessen.

Gesichert werden nur `archive/` und `/etc/log-guy/`, nie `hot/`. Fällt `hot/` aus, fehlen nur die ungekürzten Originale der letzten 7 Tage. Die gefilterte Fassung steht vollständig im Archiv, und der Index lässt sich daraus neu aufbauen.

[Zur Übersicht](README.md)
