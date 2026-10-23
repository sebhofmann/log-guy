# MCP-Endpunkt und Pseudonymisierung

Unter `/mcp` bietet log-guy einen MCP-Server, über den ein LLM die Logs durchsucht. Alles, was diesen Endpunkt verlässt, läuft vorher durch die Pseudonymisierung. Aus `mueller` wird `$Nutzer1`, aus `198.51.100.25` wird `$IP3`.

### Tools

| Tool | Zweck |
| --- | --- |
| `list_applications` | Anwendungen, auf die das Token Zugriff hat |
| `search_logs` | schnelle Solr-Suche mit Anwendung, Zeitraum, Level, Query |
| `get_histogram` | Anzahl Events pro Intervall, z.B. um einen Fehlerbeginn zu finden |
| `get_top_values` | häufigste Logger, Exceptions, Hosts im Zeitraum |
| `get_context` | Events vor und nach einem Treffer |
| `archive_search_start`, `archive_search_result` | Archivsuche als Job starten und Ergebnisse abholen |
| `get_status` | dieselben Zahlen wie die Status-Seite: System, Agents, Anwendungen, Solr |
| `get_server_log` | Einträge aus dem eigenen Log von log-guy nach Level, Komponente und Zeitraum, z.B. warum ein Skript nicht aktiviert wurde. Pseudonymisiert wie alles andere, nur mit Recht admin. |

Damit das Kontextfenster des LLM nicht überläuft, liefert jedes Tool höchstens 50 Events und kürzt Texte auf 2.000 Zeichen. Transport ist Streamable HTTP mit Bearer-Token, umgesetzt mit dem MCP-Server von Spring AI auf Basis des offiziellen MCP-Java-SDK.

### Regeln in JavaScript

Die Pseudonymisierung ist ein JavaScript-Modul in `scripts/pseudonym/`. Es bekommt jedes Event, bevor es den MCP-Endpunkt verlässt, und ersetzt Werte über die eingebaute Funktion `ps(kategorie, wert)`. Sie liefert pro Sitzung immer dasselbe Pseudonym für denselben Wert.

```js
// scripts/pseudonym/standard.js
export const meta = {
    apps: ["*"],
    fields: ["login", "ip"],     // auf diesen Feldern nimmt das MCP nur Pseudonyme an
};

const USER_AFTER_LEVEL = /^(\S+ +(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL) +)([^\s:]+)( +[\w.$-]+: )/;
const USER_PARAM = /user=([^\s&]+)/g;

export function apply(event) {
    if (event.login) {
        event.login = ps("Nutzer", event.login);
    }
    event.message = event.message
        .replace(USER_AFTER_LEVEL, (m, before, user, after) => before + ps("Nutzer", user) + after)
        .replace(USER_PARAM, (m, user) => "user=" + ps("Nutzer", user));
    event.message = replaceIps(event.message, ip => ps("IP", ip));
    event.message = replaceEmails(event.message, mail => ps("Mail", mail));
    return event;
}
```

Aus `mueller` wird so `$Nutzer1`, aus `198.51.100.25` wird `$IP3`. Gibt es für eine Anwendung mehrere Skripte, laufen sie nacheinander.

### Verhalten

- **Konsistent pro Sitzung:** Derselbe Wert bekommt in einer MCP-Sitzung immer dasselbe Pseudonym. Das LLM kann so einem Nutzer über viele Events folgen, ohne seinen Namen zu sehen.
- **Nicht verkettbar:** Eine neue Sitzung beginnt die Nummerierung neu. Die Zuordnung liegt nur im RAM und verschwindet am Sitzungsende.
- **Rückübersetzung in Anfragen:** Sucht das LLM nach `$Nutzer1`, setzt log-guy vor der Solr-Abfrage den echten Wert ein.
- **Klarwerte gesperrt:** Auf pseudonymisierten Feldern nimmt das MCP nur Pseudonyme an. Sonst könnte ein LLM durch Raten herausfinden, ob ein bestimmter Nutzer in den Logs vorkommt.
- **Überall angewendet:** auf Text, Felder, Facettenwerte und Kontext

Wirft das Pseudonymisierungs-Skript einen Fehler oder läuft es zu lange, verlässt das Event den MCP-Endpunkt gar nicht. log-guy liefert dann nur einen Hinweis, dass ein Event zurückgehalten wurde.

### Grenzen

Regeln finden nur bekannte Muster. Namen im Freitext, Suchbegriffe, Dokumenttitel in URLs, Referer, User-Agents oder IDs wie ORCID können durchrutschen. Mit Zeitstempeln lassen sich Personen manchmal auch ohne Namen zuordnen. Das ist Pseudonymisierung, keine Anonymisierung.

Hilfe beim Pflegen der Regeln: Der Test in GUI oder MCP zeigt, welche verdächtigen Werte übrig bleiben. Auf der Kommandozeile geht das auch mit `log-guy pseudonym check --app digibib --sample 10000` zeigt, welche Werte in einer Stichprobe ersetzt würden und welche verdächtigen Werte, z.B. Mailadressen-ähnliche Zeichenketten, übrig bleiben. Jeder MCP-Aufruf landet im Audit-Log.

[Zur Übersicht](README.md)
