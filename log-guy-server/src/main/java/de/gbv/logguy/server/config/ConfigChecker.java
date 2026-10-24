package de.gbv.logguy.server.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.scheduling.support.CronExpression;

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
        if (properties.retention() != null && !"-".equals(properties.retention().cron())
            && !CronExpression.isValidExpression(properties.retention().cron())) {
            errors.add("retention: cron " + properties.retention().cron() + " is not a valid cron expression");
        }
        if (properties.reindex() != null && !"-".equals(properties.reindex().expireCron())
            && !CronExpression.isValidExpression(properties.reindex().expireCron())) {
            errors.add("reindex: expire-cron " + properties.reindex().expireCron() + " is not a valid cron expression");
        }
        if (properties.serverLog() != null
            && (properties.serverLog().ringSize() < 1 || properties.serverLog().keepDays() < 1)) {
            errors.add("server-log: ring-size and keep-days must be at least 1");
        }
        checkNotify(properties.notifications(), errors);
        return errors;
    }

    private static void checkNotify(LogGuyProperties.NotifyProperties notify, List<String> errors) {
        if (notify.baseUrl() != null && !isHttpUrl(notify.baseUrl())) {
            errors.add("notifications: base-url " + notify.baseUrl() + " is not an http or https address");
        }
        if (!notify.mail().isEmpty() && (notify.from() == null || notify.from().isBlank())) {
            errors.add("notifications: mail targets need from");
        }
        notify.mail().forEach((name, target) -> {
            if (target.to().isEmpty()) {
                errors.add("notifications mail " + name + ": to is missing");
            }
        });
        notify.webhook().forEach((name, target) -> {
            if (target.url() == null || !isHttpUrl(target.url())) {
                errors.add("notifications webhook " + name + ": url must be an http or https address");
            }
        });
    }

    private static boolean isHttpUrl(String url) {
        try {
            URI uri = new URI(url);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
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
