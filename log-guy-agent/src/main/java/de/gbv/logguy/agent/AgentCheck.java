package de.gbv.logguy.agent;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.List;

import de.gbv.logguy.common.TokenInfo;

/**
 * Checks before a rollout whether the agent can do its work: the server answers, the token fits host and
 * applications, and every configured log file and directory can be read. Prints a report in German for the person
 * rolling out the agent.
 */
public class AgentCheck {

    private static final int SHOWN_PROBLEMS = 5;

    private final AgentConfig config;

    private final IngestClient client;

    private final PrintStream out;

    private final Path procStatus;

    private int errors;

    private int warnings;

    /**
     * Creates the check.
     *
     * @param config the configuration
     * @param client the client for the server
     * @param out where the report goes
     * @param procStatus {@code /proc/self/status} for the user and groups of the process
     */
    public AgentCheck(AgentConfig config, IngestClient client, PrintStream out, Path procStatus) {
        this.config = config;
        this.client = client;
        this.out = out;
        this.procStatus = procStatus;
    }

    /**
     * Runs the check.
     *
     * @return {@code 0} without errors, {@code 1} otherwise
     * @throws InterruptedException if interrupted
     */
    public int run() throws InterruptedException {
        identity();
        TokenInfo token = server();
        for (AgentConfig.Source source : config.sources()) {
            out.println();
            out.println("Anwendung " + source.app());
            if (token != null && !token.apps().contains(source.app())) {
                error("Das Token darf nicht für " + source.app() + " senden, erlaubt: " + String.join(", ",
                    token.apps()));
            }
            if (source.live() != null) {
                live(source.live());
            }
            if (source.rotated() != null) {
                rotated(source.rotated());
            }
        }
        out.println();
        out.println(errors == 0 ? "Ergebnis: in Ordnung" + (warnings > 0 ? ", " + warnings + " Hinweise" : "")
            : "Ergebnis: " + errors + " Fehler, " + warnings + " Hinweise");
        return errors == 0 ? 0 : 1;
    }

    private void identity() {
        try {
            String uid = null;
            String gid = null;
            String groups = "";
            for (String line : Files.readAllLines(procStatus)) {
                String[] parts = line.split("\\s+");
                if (line.startsWith("Uid:") && parts.length > 2) {
                    uid = parts[2];
                } else if (line.startsWith("Gid:") && parts.length > 2) {
                    gid = parts[2];
                } else if (line.startsWith("Groups:")) {
                    groups = line.substring(7).trim();
                }
            }
            out.println("Agent läuft als UID " + uid + ", GID " + gid
                + (groups.isEmpty() ? ", ohne weitere Gruppen" : ", Gruppen " + groups.replace(' ', ',')));
        } catch (IOException e) {
            out.println("UID und Gruppen des Agenten unbekannt (" + procStatus + " nicht lesbar)");
        }
    }

    private TokenInfo server() throws InterruptedException {
        out.println("Server " + config.server() + ", Host " + config.host());
        try {
            TokenInfo token = client.token();
            ok("Server erreichbar, Token " + token.name() + " für Host " + token.host() + " und "
                + String.join(", ", token.apps()));
            if (!token.host().equals(config.host())) {
                error("Das Token gehört zu Host " + token.host() + ", der Agent meldet sich als " + config.host()
                    + ". host in agent.yml anpassen.");
            }
            return token;
        } catch (IngestClient.RejectedException e) {
            error("Der Server lehnt das Token ab: " + e.getMessage());
        } catch (IOException e) {
            error("Server nicht erreichbar: " + e);
        }
        return null;
    }

    private void live(Path file) {
        if (!Files.exists(file)) {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null && Files.isDirectory(dir) && !Files.isReadable(dir)) {
                error("Verzeichnis der Live-Datei nicht lesbar: " + describe(dir));
            } else {
                warning("Live-Datei " + file + " gibt es noch nicht, der Agent wartet auf sie");
            }
            return;
        }
        if (readable(file)) {
            ok("Live-Datei " + file + " lesbar");
        } else {
            error("Live-Datei nicht lesbar: " + describe(file));
        }
    }

    private void rotated(String glob) {
        GlobFiles.Result result = new GlobFiles(glob).find();
        for (Path dir : result.unreadable()) {
            error("Verzeichnis nicht lesbar, rotierte Dateien darin fehlen: " + describe(dir));
        }
        List<Path> unreadable = result.files().stream().filter(file -> !readable(file)).toList();
        int readable = result.files().size() - unreadable.size();
        if (result.files().isEmpty()) {
            if (result.unreadable().isEmpty()) {
                warning("Keine rotierten Dateien für " + glob);
            }
        } else if (unreadable.isEmpty()) {
            ok(readable + " rotierte Dateien lesbar");
        } else {
            error(unreadable.size() + " von " + result.files().size() + " rotierten Dateien nicht lesbar");
            unreadable.stream().limit(SHOWN_PROBLEMS).forEach(file -> out.println("         " + describe(file)));
            if (unreadable.size() > SHOWN_PROBLEMS) {
                out.println("         und " + (unreadable.size() - SHOWN_PROBLEMS) + " weitere");
            }
        }
    }

    /**
     * Whether the agent can really read the file. {@link Files#isReadable(Path)} may be wrong with ACLs and user
     * namespaces, a read of one byte is not.
     */
    private static boolean readable(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.read(ByteBuffer.allocate(1));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String describe(Path path) {
        try {
            PosixFileAttributes attributes = Files.readAttributes(path, PosixFileAttributes.class);
            Object uid = Files.getAttribute(path, "unix:uid");
            Object gid = Files.getAttribute(path, "unix:gid");
            return path + " (UID " + uid + ", GID " + gid + ", " + PosixFilePermissions.toString(attributes
                .permissions()) + ")";
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            return path.toString();
        }
    }

    private void ok(String text) {
        out.println("  OK      " + text);
    }

    private void warning(String text) {
        warnings++;
        out.println("  HINWEIS " + text);
    }

    private void error(String text) {
        errors++;
        out.println("  FEHLER  " + text);
    }
}
