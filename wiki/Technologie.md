# Technologie

log-guy ist ein Maven-Projekt mit Java 25 und Spring Boot 4. Es besteht aus drei Modulen: dem Server `log-guy-server` mit GUI, API, MCP und Kommandozeile, dem Agenten `log-guy-agent` und der gemeinsamen Bibliothek `log-guy-common` mit Protokoll und Fingerabdruck. Ausgeliefert werden beide als Docker-Images.

| Aufgabe | Bibliothek |
| --- | --- |
| Web, REST, Konfiguration | Spring Boot mit Spring Web MVC, `@ConfigurationProperties` aus `application.yml` |
| Templates | JTE mit `jte-spring-boot-starter`, Templates werden beim Build vorkompiliert und typgeprüft |
| Anmeldung, Rechte, Tokens | Spring Security, `Argon2PasswordEncoder` |
| Solr | SolrJ 9.10.1, `CloudHttp2SolrClient`, Collections- und Configset-API |
| Skripte | GraalJS 25.4.4.1.1 über die Polyglot-API `org.graalvm.polyglot`, Community-Fassung |
| Archiv | `zstd-jni` 1.5.7, `java.util.zip` für eingehende `.gz` |
| Archivsuche | RE2/J für reguläre Ausdrücke in linearer Zeit, Virtual Threads |
| Agent: Dateien verfolgen | `WatchService` (unter Linux inotify), Inode über das Attribut `unix:ino`, `FileChannel` |
| Fingerabdruck, Prüfsummen | `MessageDigest` mit SHA-256 |
| MCP-Server | Spring AI 2.0.2 (`spring-ai-starter-mcp-server-webmvc`) auf Basis des offiziellen MCP-Java-SDK 2.1.0, Streamable HTTP, synchron. Die Tools beschreibt log-guy selbst mit JSON-Schema, ohne die Annotationen von Spring AI, damit jede Antwort sicher durch die Pseudonymisierung läuft. |
| Mail für Meldungen | Spring Mail |
| eigenes Log, Status | Log4j 2 mit eigenem Ringpuffer-Appender, Spring Boot Actuator für Health, Kennzahlen und Log-Level zur Laufzeit |
| Kommandozeile | Spring Shell 4, nicht interaktiv: log-guy check, reload, solr init, token create, bench parser. Läuft aus demselben JAR mit dem Profil `cli` ohne Webserver |
| Agent: Konfiguration, HTTP, JSON | SnakeYAML, `java.net.http.HttpClient`, Jackson 3 |
| Zeitleiste im Browser | uPlot |
| Skript-Editor im Browser | CodeMirror |

**GraalJS statt Rhino:** GraalJS unterstützt aktuelles ECMAScript, Rhino nur einen Teil davon. Die Beispiel-Skripte nutzen `?.`, `??` und Destructuring. Dazu kommen eine eingebaute Sandbox über `SandboxPolicy` und ein JIT-Compiler. Der JIT greift nur mit einem GraalVM-JDK, auf einem normalen OpenJDK läuft GraalJS nur im Interpreter. Gemessen: Der handgeschriebene MyCoRe-Parser schafft auf OpenJDK 122.000 Events/s, auf GraalVM CE 770.000 Events/s, siehe Parser-Skripte. Das Image des Servers baut deshalb auf `ghcr.io/graalvm/jdk-community` auf. Die Fassung von GraalJS muss zur Fassung des GraalVM-JDK passen, beide sind auf 25.4.4.1.1 festgelegt.

**Versionen:** Spring Boot 4.1.2, Spring Shell 4.0.3, SolrJ 9.10.1 passend zu `solr:9.10.1`. SolrJ 9 braucht Jetty 10, Spring Boot verwaltet Jetty 12. Das Root-POM importiert deshalb das BOM von Jetty 10 vor dem von Spring Boot. Der Server selbst läuft auf Tomcat und nutzt Jetty nur im Solr-Client.

**Grenzen der Community-Fassung:** Speicher- und CPU-Limits pro Context (`sandbox.MaxHeapMemory`, `sandbox.MaxCPUTime`) und die Policies `ISOLATED` und `UNTRUSTED` gibt es nur in Oracle GraalVM. log-guy nutzt `CONSTRAINED` und bricht zu lange Aufrufe selbst mit `Context.interrupt` ab.

**Agent:** eine schlanke Java-Anwendung ohne Spring. Ein Agent-Container mit `-Xmx64m`, der eine Live-Datei mitliest und rotierte Dateien beobachtet, braucht gemessen 88 MB RAM, bei 35 Anwendungen also etwa 3 GB. Mit GraalVM Native Image ließe sich der Agent als kleine Binary mit deutlich weniger Speicher bauen, das ist nicht gemessen.

**Reload:** Java bietet keinen offiziellen Weg, auf `SIGHUP` zu reagieren. `log-guy reload` ruft deshalb den Admin-Endpunkt `POST /admin/reload` auf.

Server für log-guy selbst: 4 Kerne, 8 GB RAM, 150 GB Platte für das Archiv, das reicht nach der Hochrechnung für 2 bis 3 Jahre. Mehr Kerne beschleunigen die Archivsuche.

[Zur Übersicht](README.md)
