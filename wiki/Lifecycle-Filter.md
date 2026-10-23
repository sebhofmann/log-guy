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

### Umsetzung

- Die Filter laufen nach Namen sortiert. Die Vorlage `ip-kuerzen` ist immer aktiv, eine Datei `scripts/lifecycle/ip-kuerzen.js` ersetzt sie. Jeder Thread des Archiv-Schreibers hat eigene Kontexte (`log-guy.archive.threads`, 2).
- `line(text)` bekommt die Zeile ohne Zeilenumbruch und ohne `\r`. Beides hängt der Archiv-Schreiber danach wieder an, eine letzte Zeile ohne Umbruch bleibt ohne.
- Der Platzhalter ist `log-guy.archive.placeholder`, darin werden `{filter}` und `{offset}` ersetzt, z.B. `[log-guy: Zeile zurückgehalten, Filter kaputt fehlgeschlagen, Offset 4711]`. Der Offset zeigt auf die Zeile im Original in `hot/`. Die übrigen Filter laufen für diese Zeile nicht mehr.
- Lässt sich ein Filter gar nicht laden, etwa wegen eines Syntaxfehlers, hält der Archiv-Schreiber ganz an, bis der Filter repariert ist. Die Status-Seite zeigt dann den Fehler. Die Originale warten so lange in `hot/`.
- `log-guy archive refilter` schreibt alle Archivdateien mit Platzhaltern neu, deren Original noch in `hot/` liegt. `--app` beschränkt auf eine Anwendung, `--all` nimmt alle Dateien mit Original, etwa nach einem geänderten Filter. Ein geänderter Filter muss vorher mit `log-guy reload` übernommen sein. Der Befehl ruft den laufenden Server mit dem Steuer-Token auf (`POST /admin/archive/refilter`, auch für Administratoren in der GUI-Sitzung) und meldet neu geschriebene Dateien, Dateien ohne Original, Fehler und die verbliebenen Platzhalter. Der Aufruf steht im Audit-Log.
- Neu geschrieben wird neben der alten Datei in `archive/.refilter/`. Danach ersetzt die neue Datei die alte: erst wird die alte `.meta.json` gelöscht, dann kommen `.log.zst` und `.idx`, zuletzt die neue `.meta.json`. Stürzt der Server dazwischen ab, fehlt die Datei im Archiv, und der Archiv-Schreiber schreibt sie aus `hot/` neu. `archive/.refilter/` wird beim Start geleert. Während des Neuschreibens archiviert der Schreiber diese Datei nicht weiter.
- Im Server-Log steht pro Frame eine Warnung mit der Zahl der ersetzten Zeilen und dem ersten Fehler, nicht eine pro Zeile. In der E2E-Umgebung waren das 3 Warnungen statt 1700.
- Die Status-Seite zeigt archivierte, weggelassene und durch Platzhalter ersetzte Zeilen seit dem Start, den Rückstand und die Größe von `archive/`. Die Summe der Filterfehler pro Datei steht in ihrer `.meta.json`.
- Gemessen am Backfill von `prod-logs/` mit `ip-kuerzen.js` und 8 Threads auf 32 Kernen: 43,9 GB in 691 Dateien, 305,8 Mio. Zeilen in etwa 290 s, also etwa 150 MB/s oder 1 Mio. Zeilen/s. Danach stand im Archiv keine einzige ungekürzte IPv4-Adresse mehr, in `hot/` waren es allein in der größten Datei Tausende. Ein Tag aller Anwendungen mit etwa 1 GB braucht damit unter einer Minute eines Threads.

Regeln für Backfill und Änderungen:

- Auch Dateien aus dem Backfill, die älter als 7 Tage sind, gehen zuerst nach `hot/`, denn der Archiv-Schreiber liest nur aus `hot/`. Der nächtliche Job entfernt sie, sobald sie vollständig archiviert sind und ihr letztes Event älter als 7 Tage ist. Das hält den Weg ins Archiv einheitlich, kostet aber bis zur nächsten Nacht Platz in `hot/`.
- Den Fingerabdruck des Originals behält die Archivdatei. Ein späterer Backfill derselben `.gz` wird daran erkannt und abgelehnt, auch wenn die Datei längst nicht mehr in `hot/` liegt.
- Ein geänderter Filter gilt für neue Daten. Für die letzten 7 Tage kann `log-guy archive refilter` das Archiv aus `hot/` neu schreiben. Ältere Archivdateien lassen sich nur noch weiter filtern, nicht mehr zurück zum Original.

Gesichert werden nur `archive/` und `/etc/log-guy/`, nie `hot/`. Fällt `hot/` aus, fehlen nur die ungekürzten Originale der letzten 7 Tage. Die gefilterte Fassung steht vollständig im Archiv, und der Index lässt sich daraus neu aufbauen.

[Zur Übersicht](README.md)
