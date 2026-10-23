package de.gbv.logguy.server.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import de.gbv.logguy.server.config.LogGuyProperties.GrantProperties;
import de.gbv.logguy.server.config.LogGuyProperties.TokenKind;
import de.gbv.logguy.server.config.LogGuyProperties.TokenProperties;
import de.gbv.logguy.server.config.LogGuyProperties.UserProperties;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * Checks the configuration beyond what binding already ensures, like {@code nginx -t}.
 */
public final class ConfigChecker {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private static final String ARGON2_PREFIX = "$argon2id$";

    private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");

    private ConfigChecker() {
    }

    /**
     * Checks applications, tokens and users.
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
            } else if (token.kind() != null
                && properties.users().stream().noneMatch(user -> token.user().equals(user.name()))) {
                errors.add(label + ": user " + token.user() + " is not configured under log-guy.users");
            }
        }
        checkUsers(properties, errors);
        return errors;
    }

    private static void checkUsers(LogGuyProperties properties, List<String> errors) {
        Set<String> names = new HashSet<>();
        for (UserProperties user : properties.users()) {
            String label = "user " + user.name();
            if (user.name() == null || !NAME.matcher(user.name()).matches()) {
                errors.add("user " + user.name() + ": invalid name");
            } else if (!names.add(user.name())) {
                errors.add(label + ": name used twice");
            }
            if (user.passwordHash() != null && !user.passwordHash().startsWith(ARGON2_PREFIX)) {
                errors.add(label + ": password-hash must be an Argon2id hash, see log-guy password hash");
            }
            for (GrantProperties grant : user.grants()) {
                if (grant.apps().isEmpty() || grant.rights().isEmpty()) {
                    errors.add(label + ": every grant needs apps and rights");
                }
                for (String pattern : grant.apps()) {
                    if (properties.apps().keySet().stream().noneMatch(app -> LogGuyUser.matches(pattern, app))) {
                        errors.add(label + ": pattern " + pattern + " matches no application");
                    }
                }
            }
        }
    }
}
