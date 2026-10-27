# MCP-Endpunkt und Pseudonymisierung

Unter `/mcp` bietet log-guy einen MCP-Server, über den ein LLM die Logs durchsucht. Alles, was diesen Endpunkt verlässt, läuft vorher durch die Pseudonymisierung. Aus `mueller` wird `$Nutzer1`, aus `198.51.100.25` wird `$IP3`.

### Tools

| Tool | Zweck |
| --- | --- |
| `list_applications` | Anwendungen, auf die das Token Zugriff hat, mit ihren Feldern |
| `search_logs` | schnelle Solr-Suche mit `apps`, `query`, `filters`, Zeitraum (`from`/`to` oder `last` wie `30m`, `6h`, `2d`), `limit`, `order` und `cursor` zum Weiterblättern |
| `get_histogram` | Anzahl Events pro Intervall, optional aufgeteilt nach einem Feld (`split`), z.B. um einen Fehlerbeginn zu finden |
| `get_top_values` | häufigste Werte von Feldern wie Logger, Exception oder Host im Zeitraum |
| `get_context` | Originalzeilen vor und nach einem Treffer |
| `archive_search_start`, `archive_search_result` | Archivsuche als Job starten (`apps`, `hosts`, `query`, `regex`, `ignoreCase`, `context`, Zeitraum wie bei `search_logs`) und Fortschritt und Treffer abholen (`id`, `offset`, `limit`, `wait` bis 30 Sekunden, `cancel`). Der Job gehört der Session, Treffer sind wie alles andere pseudonymisiert. |
| `get_status` | dieselben Zahlen wie die Status-Seite: System, Agents, Anwendungen, Solr. Ohne Recht admin nur die eigenen Anwendungen. |
| `get_server_log` | Einträge aus dem eigenen Log von log-guy nach Level, Komponente und Zeitpunkt, z.B. warum ein Skript nicht aktiviert wurde. Pseudonymisiert wie alles andere, nur mit Recht admin. |
| `list_scripts`, `get_script`, `save_draft`, `test_script`, `activate_script`, `rollback_script` | Skripte lesen, als Entwurf speichern, testen, aktivieren und zurückrollen, siehe [Skript-Verwaltung](Skript-Verwaltung.md) |

Jedes Tool beschreibt seine Argumente als JSON-Schema und ist als lesend oder schreibend markiert. Die Antwort ist JSON als Text. Fehler wie eine ungültige Abfrage oder fehlende Rechte kommen als Tool-Fehler mit einer deutschen Meldung zurück, damit das LLM seine Anfrage korrigieren kann.

Damit das Kontextfenster des LLM nicht überläuft, liefert jedes Tool höchstens 50 Events und kürzt Texte auf 2.000 Zeichen, mit dem Hinweis `[gekürzt, N Zeichen]`. Transport ist Streamable HTTP mit Bearer-Token, umgesetzt mit dem MCP-Server von Spring AI auf Basis des offiziellen MCP-Java-SDK.

```yaml
log-guy:
  mcp:
    path: /mcp
    session-idle-timeout: 2h   # danach sind Sitzung und Pseudonyme weg
    max-sessions: 100
    max-events: 50
    max-text: 2000
```

Eine MCP-Sitzung gehört genau zu dem Token, das sie begonnen hat. Ein anderes Token, auch desselben Nutzers, kann sie nicht weiterverwenden. Über `max-sessions` hinaus lehnt log-guy neue Sitzungen ab, bis alte abgelaufen sind. Für die Kommandozeile (`cli`) ist der MCP-Server abgeschaltet.

### Regeln in JavaScript

Die Pseudonymisierung ist ein JavaScript-Modul in `scripts/pseudonym/`. Es bekommt jedes Event, bevor es den MCP-Endpunkt verlässt, und ersetzt Werte über die eingebaute Funktion `ps(kategorie, wert)`. Sie liefert pro Sitzung immer dasselbe Pseudonym für denselben Wert.

Die Vorlage `standard` ist eingebaut und immer aktiv (`log-guy-server/src/main/resources/templates/pseudonym/standard.js`). Eine Datei `scripts/pseudonym/standard.js` ersetzt sie, weitere Dateien in `scripts/pseudonym/` kommen dazu. Gekürzt:

```js
export const meta = {
    apps: ["*"],                 // auch Muster oder Anwendungsgruppen wie "@mir"
    fields: ["login", "ip"],     // auf diesen Feldern nimmt das MCP nur Pseudonyme an
};

const KEEP = new Set(["guest", "SYSTEM", "administrator", "MCRJANITOR", "MCRJANITOR@local", "-", ""]);
const USER_AFTER_LEVEL = /^(\S+ +(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL) +)([^\s:]+)( +[\w.$-]+: )/;
const USER_IN_ACCESS_LOG = /^(\S+ \S+ )([^\s\[]+)( \[\d{2}\/\w{3}\/\d{4}:)/;
const USER_PARAM = /\b((?:user|userid|login|username|uid)=)([^\s&;,"']+)/gi;
const USER_PHRASE = /\b((?:user detected|requested user): |user:)([^\s,;"']+)/g;

function user(name) {
    return KEEP.has(name) ? name : ps("Nutzer", name);
}

function text(value) {
    let out = value
        .replace(USER_AFTER_LEVEL, (m, before, name, after) => before + user(name) + after)
        .replace(USER_IN_ACCESS_LOG, (m, before, name, after) => before + user(name) + after)
        .replace(USER_PARAM, (m, key, name) => key + user(name))
        .replace(USER_PHRASE, (m, key, name) => key + user(name));
    out = replaceEmails(out, mail => ps("Mail", mail));
    return replaceIps(out, ip => ps("IP", ip));
}

export function apply(event) {
    if (event.login) {
        event.login = user(event.login);
    }
    event.message = text(event.message);
    // ebenso alle übrigen Felder außer app, host und ts, auch Listen wie exc
    return event;
}
```

Eingebaute Funktionen für Pseudonymisierungs-Skripte:

| Funktion | Wirkung |
|---|---|
| `ps(kategorie, wert)` | Pseudonym wie `$Nutzer1`. Die Kategorie besteht nur aus Buchstaben. Leere Werte und Werte, die schon Pseudonyme sind, kommen unverändert zurück. |
| `replaceIps(text, fn)` | ersetzt IPv6 und IPv4, eingebettetes IPv4 in IPv6 zählt als eine Adresse. Uhrzeiten wie `12:00:01` und Versionsnummern bleiben. |
| `replaceEmails(text, fn)` | ersetzt Mailadressen mit Domain und Endung, `user@localhost` bleibt |
| `findIps(text)`, `findEmails(text)` | listen die Adressen auf |
| `maskIp(text)` | kürzt IPv4 auf `a.b.c.0` und IPv6 auf die ersten 48 Bit, auch in Parsern und Regeln nutzbar |

Das Skript bekommt das Event als einfaches Objekt mit `app`, `host`, `ts`, `message` und den Feldern. Es ändert das Objekt oder gibt ein neues zurück. Kontextzeilen und Zeilen aus dem Server-Log laufen als `{app, message}` durch, Facettenwerte als `{app, message: "", <feld>: wert}`.

Aus `mueller` wird so `$Nutzer1`, aus `198.51.100.25` wird `$IP3`. Benutzer werden vor Mailadressen ersetzt, damit ein Login wie `anna.beispiel@example.org` überall `$Nutzer` bleibt. Gibt es für eine Anwendung mehrere Skripte, laufen sie nacheinander in der Reihenfolge ihrer Namen, `meta.fields` ist die Vereinigung aller Skripte.

### Verhalten

- **Konsistent pro Sitzung:** Derselbe Wert bekommt in einer MCP-Sitzung immer dasselbe Pseudonym. Das LLM kann so einem Nutzer über viele Events folgen, ohne seinen Namen zu sehen.
- **Nicht verkettbar:** Eine neue Sitzung beginnt die Nummerierung neu. Die Zuordnung liegt nur im RAM und verschwindet am Sitzungsende.
- **Rückübersetzung in Anfragen:** Sucht das LLM nach `$Nutzer1`, setzt log-guy vor der Solr-Abfrage den echten Wert ein, in `query` als Phrase, wenn das Pseudonym ein ganzer Suchbegriff ist, in Phrasen wie `message:"von $IP1"` als Text, in `filters` als Wert. In anderen Begriffen wie `x$Nutzer1` oder `$Nutzer1*` bleibt es stehen.
- **Klarwerte gesperrt:** Auf pseudonymisierten Feldern (`meta.fields`) nimmt das MCP nur Pseudonyme dieser Sitzung an, in `query` wie in `filters`. Erlaubt ist nur ein einzelner Begriff oder eine Phrase mit einem Pseudonym, auf Wunsch mit `^` gewichtet. Klarwerte, Platzhalter wie `mue*`, Gruppen, Bereiche, reguläre Ausdrücke, unscharfe Suche mit `~` und unbekannte Pseudonyme lehnt es ab. Die Prüfung arbeitet auf denselben Tokens von `QueryLexer` wie die Umsetzung der Feldnamen in [Suche](Suche.md). Was Solr als Feldnamen liest, prüft sie also auch, etwa `(*:*)login:mueller` oder `login :mueller`, und escapte Feldnamen wie `lo\gin:mueller` sind schon vorher abgelehnt. Sonst könnte ein LLM durch Raten herausfinden, ob ein bestimmter Nutzer in den Logs vorkommt.
- **Facetten und Zeitleiste:** Facettenwerte und Serien der Zeitleiste gehören zu keiner einzelnen Anwendung. Sie laufen durch alle Pseudonymisierungs-Skripte der abgefragten Anwendungen, gleiche Pseudonyme werden zusammengezählt. `app` und `host` bleiben im Klartext.
- **Skript-Tests:** Ein Test der aktiven Parser-Fassung, die ein Mensch freigegeben hat, liefert die geparsten Beispiele, Fehlzeilen und Fehlerarten pseudonymisiert. Ein Test von ungespeichertem Code, einem Entwurf oder einer per MCP aktivierten Fassung liefert nur Zahlen: Events, Fehler, Fehlerarten ohne Text, deklarierte Felder und die Zahl der undeklarierten. Tests der Pseudonymisierung, von Meldungsregeln und Lifecycle-Filtern liefern ebenfalls nur Zahlen, bei der Pseudonymisierung Events, zurückgehaltene Events, Pseudonyme und verdächtige Werte je Kategorie.
- **Überall angewendet:** auf Text, Felder, Facettenwerte und Kontext
- **Auch für Menschen:** Nutzer mit `read_pseudonymized` bekommen Suche, Zeitleiste, Facetten und Kontext in GUI und API genauso pseudonymisiert, mit denselben Regeln für Rückübersetzung und Klarwerte, siehe [Rechte und Konfiguration](Rechte-und-Konfiguration.md). Der Code dafür ist für MCP und GUI derselbe (`PseudonymizedSearch`).
- **Technische Konten bleiben:** `guest`, `administrator`, `SYSTEM` und `MCRJANITOR` identifizieren niemanden und bleiben im Klartext, damit man Cronjobs und Gastzugriffe von echten Nutzern unterscheiden kann.

Wirft das Pseudonymisierungs-Skript einen Fehler oder läuft es zu lange, verlässt das Event den MCP-Endpunkt gar nicht. log-guy liefert dann nur einen Hinweis, dass ein Event zurückgehalten wurde. Lässt sich ein aktives Skript gar nicht laden, hält der MCP-Endpunkt alles zurück, bis es repariert ist, und das Server-Log meldet den Fehler.

**Gemessen** an einem Tag digibib (2026-09-28, 269.870 Events): rund 22.000 Events pro Sekunde in einem Thread, 14.659 IP-Adressen, 13 Mailadressen und 7 Nutzer ersetzt, keine verdächtigen Werte übrig. Gefunden und ergänzt wurde dabei `MCRSessionFilter: user detected: <login>`. ORCID und GND der Autoren bleiben stehen, sie sind öffentlich.

### Grenzen

Regeln finden nur bekannte Muster. Namen im Freitext, Suchbegriffe, Dokumenttitel in URLs, Referer, User-Agents oder IDs wie ORCID können durchrutschen. Mit Zeitstempeln lassen sich Personen manchmal auch ohne Namen zuordnen. Das ist Pseudonymisierung, keine Anonymisierung.

Die Sperre für Klarwerte gilt nur auf den Feldern aus `meta.fields`. Eine Freitextsuche wie `message:mueller` prüft log-guy nicht, weil sich Klarwerte im Freitext nicht von harmlosen Suchbegriffen unterscheiden lassen. Ein LLM kann so durch die Trefferzahl erfahren, ob ein Wort in den Logs vorkommt, bekommt die Treffer aber pseudonymisiert.

Selbst geschriebene Skripte umgehen die Pseudonymisierung: Ein Parser kann einen Nutzernamen rückwärts, in Base64 oder in einem Feldnamen ausgeben, und keine Regel erkennt ihn mehr. Deshalb liefern Tests von Code, den kein Mensch freigegeben hat, über MCP nur Zahlen, siehe [Skript-Verwaltung](Skript-Verwaltung.md). Auch Namen undeklarierter Felder bleiben weg, nur ihre Anzahl steht im Ergebnis. Ganz dicht ist das nicht: Über steuerbare Zahlen wie die Zahl der Events oder Fehler kann ein Skript mit vielen Tests langsam einzelne Bits herausgeben. Jeder Test steht im Audit-Log, so fällt eine solche Häufung von Tests auf. Skripte bearbeiten darf ein MCP-Token außerdem nur mit `read` an der Anwendung, `read_pseudonymized` genügt dafür nicht.

Die Texte aktiver Meldungsregeln in `list_alerts` laufen nur durch die musterbasierte Pseudonymisierung. Hat ein Mensch eine Regel aktiviert, deren Meldungstext Werte umkodiert, rutschen diese durch.

Hilfe beim Pflegen der Regeln: Der Test in GUI oder API (`POST /api/v1/scripts/pseudonym/<name>/test`, Quellen wie beim Parser-Test in [Skript-Verwaltung](Skript-Verwaltung.md)) parst die Zeilen mit dem Parser der Anwendung, wendet das Skript an und zeigt Pseudonyme je Kategorie, zurückgehaltene Events und die verdächtigen Werte, die übrig bleiben (IP- und Mailadressen, höchstens 100). Über MCP gibt es nur diese Zahlen, keine Beispiel-Events. Auf der Kommandozeile zeigt `log-guy pseudonym check --app digibib --sample 10000` dasselbe für eine Stichprobe. Jeder MCP-Aufruf landet im Audit-Log.

[Zur Übersicht](README.md)
