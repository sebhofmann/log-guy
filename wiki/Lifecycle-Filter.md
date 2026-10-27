# Lifecycle-Filter

Lifecycle-Filter bestimmen, wie die Logs im Archiv aussehen. Sie laufen, während der Archiv-Schreiber die Zeilen aus `hot/` nach `archive/` kopiert. Festgelegt ist: IP-Adressen werden gekürzt, Nutzer, Mailadressen und die übrigen Werte der Pseudonymisierung werden pro Datei pseudonymisiert, siehe [Pseudonymisierung im Archiv](#pseudonymisierung-im-archiv). Die Originale bleiben 7 Tage in `hot/` und verschwinden dann, zur selben Zeit wie der Tag aus dem Index.

Jeder Filter ist ein JavaScript-Modul in `scripts/lifecycle/`.

| Export | Pflicht | Aufgabe |
| --- | --- | --- |
| `meta` | ja | Anwendungen, für die der Filter gilt: Namen, Muster wie `*-apache` oder Anwendungsgruppen wie `@mir` |
| `line(text)` | nein | bekommt jede Zeile auf dem Weg ins Archiv, gibt die neue Zeile zurück oder `null`, dann fehlt sie im Archiv |
| `fields(event)` | nein | verändert nur die Felder im Index, z.B. um Passwörter aus `message` zu entfernen |

```js
// scripts/lifecycle/ip-kuerzen.js
export const meta = { apps: ["*"] };

// maskIp ist eingebaut: IPv4 letztes Oktett auf 0, IPv6 auf /32
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
- `fields(event)` läuft beim Indexieren nach dem Parser und beim Laden älterer Tage aus dem Archiv. Das Event enthält `app`, `host`, `ts` als ISO-Zeit, `message` und die Felder des Parsers. Zurück zählen nur `message` und die im Parser deklarierten Felder, `ts` bleibt, wie der Parser es gesetzt hat. Ein Wert, der nicht zum deklarierten Typ passt, gilt als Fehler.
- Wirft `fields(event)`, kommt das Event trotzdem in den Index, aber nur mit Zeit, dem Platzhalter `log-guy.archive.field-placeholder` als `message` und dem Fehler in `parse_error`. Felder fehlen dann. So geht kein Event verloren, und nichts Ungefiltertes ist suchbar.
- Lässt sich ein Filter nicht laden, pausiert auch der Indexer, genau wie der Archiv-Schreiber.
- Die Kontextansicht (GUI, API, MCP `get_context`) liest Zeilen aus `hot/` und zeigt sie trotzdem nur gefiltert. Jede Zeile aus `hot/` läuft durch `line(text)` wie im Archiv-Schreiber: eine weggelassene Zeile fehlt auch im Kontext, ein Fehler oder eine Zeitüberschreitung des Watchdogs ergibt denselben Platzhalter mit Offset. Zeilen aus `archive/` sind schon gefiltert. Danach läuft jede Zeile durch `fields(event)` mit `app`, `host` und der Zeile als `message`, ohne Felder des Parsers. Ein Fehler ersetzt die Zeile durch `log-guy.archive.field-placeholder`. So zeigt der Kontext höchstens, was auch im Archiv und im Index steht.
- Grenze: Entfernt ein `fields(event)`-Filter etwas nur aus einem Feld des Parsers und nicht aus `message`, bleibt es in der Kontextzeile sichtbar. Ein Filter, der etwas aus der Zeile entfernen soll, gehört deshalb in `line(text)` oder muss auch `message` ändern.
- Lässt sich ein Filter nicht laden, liefert der Kontext nichts statt ungefilterter Zeilen: über die API Status 503 mit einer Fehlermeldung, über MCP eine Fehlermeldung des Tools.
- Administratoren der Anwendung können über die API mit `original=true` das ungefilterte Original aus `hot/` abrufen, passend zu `source=HOT` im Log-Browser. Für Zeilen aus dem Archiv und über MCP gibt es diese Möglichkeit nicht.
- Der Test im Skript-Editor zeigt für `line(text)` geänderte, gelöschte und fehlgeschlagene Zeilen jeweils vorher und nachher, für `fields(event)` die geänderten Events. Die Events entstehen mit dem Parser der gewählten Anwendung. Über MCP liefert der Test nur Zahlen, weil Lifecycle-Filter nur Menschen ändern. Gemessen in der E2E-Umgebung: 1000 Zeilen von digibib, 252 durch `ip-kuerzen` geändert.
- Die Status-Seite zeigt archivierte, weggelassene und durch Platzhalter ersetzte Zeilen seit dem Start, den Rückstand und die Größe von `archive/`. Die Summe der Filterfehler pro Datei steht in ihrer `.meta.json`.
- Gemessen am Backfill von `prod-logs/` mit `ip-kuerzen.js` und 8 Threads auf 32 Kernen: 43,9 GB in 691 Dateien, 305,8 Mio. Zeilen in etwa 290 s, also etwa 150 MB/s oder 1 Mio. Zeilen/s. Danach stand im Archiv keine einzige ungekürzte IPv4-Adresse mehr, in `hot/` waren es allein in der größten Datei Tausende. Ein Tag aller Anwendungen mit etwa 1 GB braucht damit unter einer Minute eines Threads.

Regeln für Backfill und Änderungen:

- Auch Dateien aus dem Backfill, die älter als 7 Tage sind, gehen zuerst nach `hot/`, denn der Archiv-Schreiber liest nur aus `hot/`. Der nächtliche Job entfernt sie, sobald sie vollständig archiviert sind und ihr letztes Event älter als 7 Tage ist. Das hält den Weg ins Archiv einheitlich, kostet aber bis zur nächsten Nacht Platz in `hot/`.
- Den Fingerabdruck des Originals behält die Archivdatei. Ein späterer Backfill derselben `.gz` wird daran erkannt und abgelehnt, auch wenn die Datei längst nicht mehr in `hot/` liegt.
- Ein geänderter Filter gilt für neue Daten. Für die letzten 7 Tage kann `log-guy archive refilter` das Archiv aus `hot/` neu schreiben. Ältere Archivdateien lassen sich nur noch weiter filtern, nicht mehr zurück zum Original.

### Pseudonymisierung im Archiv

Umgesetzt: noch nicht, siehe [Milestones](Milestones.md#m4-archiv).

Das Archiv hat keine Löschfrist. Gekürzte IP-Adressen reichen dafür nicht, Loginnamen und Mailadressen nennen eine Person direkt. Der Archiv-Schreiber ersetzt sie deshalb durch Tokens, die nur innerhalb einer Logdatei gleich sind. Innerhalb eines Tages sieht man so weiter, dass drei Fehler vom selben Nutzer kamen. Über Dateien hinweg lässt sich nichts verknüpfen, und niemand kann einen Token auf den Klarwert zurückführen oder gezielt nach einem bekannten Nutzer im Archiv suchen. Das ist bewusst so, eine Suche nach einem bestimmten Nutzer geht nur in den 7 Tagen in `hot/` und im Index.

- **Welche Werte:** dieselben wie bei der Pseudonymisierung für MCP und `read_pseudonymized`, also die aktiven Pseudonymisierungs-Skripte der Anwendung (siehe [MCP und Pseudonymisierung](MCP-und-Pseudonymisierung.md)). Sie laufen nach den Lifecycle-Filtern über jede Zeile als `{app, message}`. Kategorien in `log-guy.archive.pseudonymize.keep` bleiben stehen, Vorgabe `[IP]`, weil `ip-kuerzen` die Adressen schon kürzt und das Netz für die Fehlersuche nützlich bleibt.
- **Token:** `[Kategorie#xxxxxxxx]`, z.B. `[Nutzer#3f9a1c2e]`, mit den ersten 32 Bit von HMAC-SHA256 über Kategorie und Wert. Das Format unterscheidet sich von den Sitzungs-Pseudonymen wie `$Nutzer1`. `ps()` erkennt Tokens als schon pseudonymisiert und lässt sie unverändert, sie erscheinen in GUI, API und MCP für alle Nutzer so, wie sie im Archiv stehen.
- **Ziel: Das Archiv darf so, wie es ist, an ein LLM.** Zeilen aus `archive/` laufen deshalb beim Lesen nicht noch einmal durch die Pseudonymisierung, weder für MCP noch für `read_pseudonymized`. Die gekürzten IP-Adressen bleiben sichtbar, damit man z.B. Zugriffe aus dem DFN erkennt. Die Archivsuche sucht für alle Nutzer direkt in der archivierten Zeile, die Prüfung gegen eine zweite, pseudonymisierte Fassung entfällt. Innerhalb einer Datei sehen zwei MCP-Sitzungen denselben Token, über Dateien hinweg nicht.
- **Geänderte Regeln:** Weil beim Lesen nichts mehr ergänzt wird, gilt eine neue oder geänderte Pseudonymisierungs-Regel für das Archiv erst nach `log-guy archive pseudonymize --all`. Der Skript-Editor bietet das nach dem Aktivieren eines Pseudonymisierungs-Skripts an, wie die Neuindexierung nach einer Parser-Änderung. Schon ersetzte Tokens bleiben dabei stehen.
- **Schlüssel pro Datei:** 256 Bit aus `SecureRandom`, erzeugt beim Anlegen der Datei in `hot/` und dort in ihrer `.meta.json` abgelegt. Er wird mit der Datei aus `hot/` gelöscht. Danach gibt es ihn nirgends mehr. `hot/` gehört nicht zum Backup, der Schlüssel landet also nie in einer Sicherung. Solange er existiert, liegt daneben ohnehin das Original im Klartext.
- **Gleiche Tokens überall, wo `hot/` wie das Archiv gefiltert wird:** Die Kontextansicht und `log-guy archive refilter` nehmen denselben Schlüssel aus `hot/`, ihre Tokens passen deshalb zu denen im Archiv.
- **Fehler:** Wirft ein Pseudonymisierungs-Skript oder läuft es zu lange, kommt wie bei einem Filterfehler der Platzhalter mit Offset ins Archiv. Lässt sich ein Skript nicht laden, hält der Archiv-Schreiber an, bis es repariert ist.
- **Fingerabdruck und Längen bleiben:** Fingerabdruck, `sourceLength` und die Offsets im Original (`src`, `srcEnd`) beziehen sich weiter auf das Original. Nur Inhalt, `raw`-Offsets und `length` der gefilterten Fassung ändern sich, wie schon beim Kürzen der IPs. In `filters` der `.meta.json` steht die Pseudonymisierung als `pseudonym@fassung`.
- **Bestehende Archivdateien:** `log-guy archive pseudonymize` schreibt Archivdateien ohne diesen Eintrag neu, auch solche ohne Original in `hot/`. Weil nur ersetzt wird, genügt die gefilterte Fassung als Quelle. Der Schlüssel dafür ist zufällig und wird nach der Datei verworfen. Neu geschrieben wird wie bei `refilter` über `archive/.refilter/`.
- **Grenzen:** Was die Regeln nicht erkennen, etwa Namen im Freitext, bleibt im Klartext. Wird nach dem Zurückspielen eines Backups eine offene Datei weiter eingeliefert, entsteht in `hot/` ein neuer Schlüssel, und die Tokens wechseln mitten in der Datei. Ältere Stände des Backup-Ziels, etwa Snapshots von restic, enthalten bis zu ihrem Ablauf noch den Klartext, das liegt außerhalb von log-guy.
- **Index:** Was aus `hot/` indexiert wird, hat in den 7 Tagen Klarwerte. Was aus dem Archiv nachgeladen wird, hat Tokens, auch in Feldern und Facetten.

Gesichert werden nur `archive/` und `/etc/log-guy/`, nie `hot/`. Fällt `hot/` aus, fehlen nur die ungekürzten Originale der letzten 7 Tage. Die gefilterte Fassung steht vollständig im Archiv, und der Index lässt sich daraus neu aufbauen.

[Zur Übersicht](README.md)
