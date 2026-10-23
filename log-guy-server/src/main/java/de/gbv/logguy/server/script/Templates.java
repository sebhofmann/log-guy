package de.gbv.logguy.server.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import de.gbv.logguy.server.script.ScriptStore.ScriptId;

/**
 * Scripts shipped in the JAR under {@code /templates/<kind>/<name>.js}. A file of the same name in the script
 * directory replaces a template.
 */
public final class Templates {

    /** Pseudonymization scripts that are always active unless a file replaces them. */
    public static final List<ScriptId> ALWAYS_ACTIVE = List.of(new ScriptId(ScriptKind.PSEUDONYM, "standard"));

    private Templates() {
    }

    /**
     * Checks whether a template exists.
     *
     * @param id the script
     * @return true if the JAR contains a template of that name
     */
    public static boolean has(ScriptId id) {
        return Templates.class.getResource(path(id)) != null;
    }

    /**
     * Reads a template.
     *
     * @param id the script
     * @return the code
     * @throws IOException if the template does not exist
     */
    public static String read(ScriptId id) throws IOException {
        try (InputStream in = Templates.class.getResourceAsStream(path(id))) {
            if (in == null) {
                throw new IOException("template " + id + ".js not found");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String path(ScriptId id) {
        return "/templates/" + id.kind().dir() + "/" + id.name() + ".js";
    }
}
