package de.gbv.logguy.server.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;

/**
 * Checks the configuration beyond what binding already ensures, like {@code nginx -t}.
 */
public final class ConfigChecker {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");

    private ConfigChecker() {
    }

    /**
     * Checks applications and tokens.
     *
     * @param properties the configuration
     * @return the problems, empty if everything is fine
     */
    public static List<String> check(LogGuyProperties properties) {
        List<String> errors = new ArrayList<>();
        for (String app : properties.apps().keySet()) {
            if (!NAME.matcher(app).matches()) {
                errors.add("application " + app + ": invalid name");
            }
        }
        Set<String> names = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (TokenProperties token : properties.tokens()) {
            String label = "token " + token.name();
            if (token.name() == null || token.name().isBlank()) {
                errors.add("token without name");
            } else if (!names.add(token.name())) {
                errors.add(label + ": name used twice");
            }
            if (token.kind() == null) {
                errors.add(label + ": kind missing");
            }
            if (token.hash() == null || !HASH.matcher(token.hash()).matches()) {
                errors.add(label + ": hash must look like sha256:<64 hex digits>");
            } else if (!hashes.add(token.hash())) {
                errors.add(label + ": hash used twice");
            }
            if (token.kind() == TokenKind.INGEST) {
                if (token.host() == null || !NAME.matcher(token.host()).matches()) {
                    errors.add(label + ": ingest token needs a valid host");
                }
                if (token.apps() == null || token.apps().isEmpty()) {
                    errors.add(label + ": ingest token needs apps");
                } else {
                    for (String app : token.apps()) {
                        if (!properties.apps().containsKey(app)) {
                            errors.add(label + ": application " + app + " is not configured under log-guy.apps");
                        }
                    }
                }
            } else if (token.kind() != null && (token.user() == null || token.user().isBlank())) {
                errors.add(label + ": " + token.kind().name().toLowerCase() + " token needs a user");
            }
        }
        return errors;
    }
}
