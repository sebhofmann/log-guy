# Technologie

log-guy ist ein Maven-Projekt mit Java 25 und Spring Boot 4. Es besteht aus drei Modulen: dem Server `log-guy-server` mit GUI, API, MCP und Kommandozeile, dem Agenten `log-guy-agent` und der gemeinsamen Bibliothek `log-guy-common` mit Protokoll und Fingerabdruck. Ausgeliefert werden beide als Docker-Images.

| Aufgabe | Bibliothek |
| --- | --- |
| Web, REST, Konfiguration | Spring Boot mit Spring Web MVC, `@ConfigurationProperties` aus `application.yml` |
| Templates | JTE mit `jte-spring-boot-starter`, Templates werden beim Build vorkompiliert und typgeprüft |
| Anmeldung, Rechte, Tokens | Spring Security, `Argon2PasswordEncoder` |
| Solr | SolrJ, `CloudHttp2SolrClient`, Collections- und Configset-API |
| Skripte | GraalJS über die Polyglot-API `org.graalvm.polyglot` |
| Archiv | `zstd-jni`, `java.util.zip` für eingehende `.gz` |
| Archivsuche | RE2/J für reguläre Ausdrücke in linearer Zeit, Virtual Threads |
| Agent: Dateien verfolgen | `WatchService` (unter Linux inotify), Inode über das Attribut `unix:ino`, `FileChannel` |
| Fingerabdruck, Prüfsummen | `MessageDigest` mit SHA-256 |
| MCP-Server | Spring AI MCP Server auf Basis des offiziellen MCP-Java-SDK |
| Mail für Meldungen | Spring Mail |
| eigenes Log, Status | Log4j 2 mit eigenem Ringpuffer-Appender, Spring Boot Actuator für Health, Kennzahlen und Log-Level zur Laufzeit |
| Kommandozeile | Spring Shell, nicht interaktiv: log-guy check, reload, solr init. Läuft aus demselben JAR mit einem Profil ohne Webserver |
| Zeitleiste im Browser | uPlot |
| Skript-Editor im Browser | CodeMirror |

**GraalJS statt Rhino:** GraalJS unterstützt aktuelles ECMAScript, Rhino nur einen Teil davon. Die Beispiel-Skripte nutzen `?.`, `??` und Destructuring. Dazu kommen eine eingebaute Sandbox über `SandboxPolicy` und `ResourceLimits` sowie ein JIT-Compiler. Nach der GraalVM-Dokumentation greift der JIT nur mit einem GraalVM-JDK als Basis-Image oder mit der Truffle-Runtime und JVMCI, auf einem normalen OpenJDK läuft GraalJS nur im Interpreter. Die QuickJS-Messwerte stammen ebenfalls von einem reinen Interpreter. Wie GraalJS mit und ohne JIT im Vergleich dazu abschneidet, ist nicht gemessen. Erwartung: ohne JIT eher langsamer als QuickJS, mit JIT im Dauerbetrieb des Indexers deutlich schneller.

**Agent:** eine schlanke Java-Anwendung ohne Spring. Eine JVM pro Agent-Container braucht grob 50 bis 100 MB RAM, bei 35 Anwendungen also einige GB. Mit GraalVM Native Image ließe sich der Agent als kleine Binary mit deutlich weniger Speicher bauen. Beides ist geschätzt, nicht gemessen.

**Reload:** Java bietet keinen offiziellen Weg, auf `SIGHUP` zu reagieren. `log-guy reload` ruft deshalb den Admin-Endpunkt `POST /admin/reload` auf.

Server für log-guy selbst: 4 Kerne, 8 GB RAM, 150 GB Platte für das Archiv, das reicht nach der Hochrechnung für 2 bis 3 Jahre. Mehr Kerne beschleunigen die Archivsuche.

[Zur Übersicht](README.md)
