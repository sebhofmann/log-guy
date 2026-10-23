package de.gbv.logguy.server.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.access.AccessDeniedException;

import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * The pseudonyms of the open MCP sessions. They live only in memory and are dropped after the idle time, so a new
 * session starts the numbering again and pseudonyms of two sessions cannot be linked.
 */
public class McpSessions {

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private final Duration idleTimeout;

    private final int maxSessions;

    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param idleTimeout a session is dropped after this time without a call
     * @param maxSessions sessions at most
     * @param clock the clock
     */
    public McpSessions(Duration idleTimeout, int maxSessions, Clock clock) {
        this.idleTimeout = idleTimeout;
        this.maxSessions = maxSessions;
        this.clock = clock;
    }

    /**
     * The pseudonyms of a session, created on the first call. A session belongs to the token that opened it.
     *
     * @param id the MCP session id
     * @param user the user of the call
     * @return the pseudonyms
     * @throws AccessDeniedException if the session belongs to another token or there are too many sessions
     */
    public Pseudonyms get(String id, LogGuyUser user) {
        Instant now = clock.instant();
        String key = id == null ? "" : id;
        expire(now);
        Session session = sessions.get(key);
        if (session == null) {
            if (sessions.size() >= maxSessions) {
                throw new AccessDeniedException("too many MCP sessions");
            }
            session = sessions.computeIfAbsent(key, k -> new Session(user.name(), user.token(), new Pseudonyms()));
        }
        if (!session.user().equals(user.name()) || !Objects.equals(session.token(), user.token())) {
            throw new AccessDeniedException("MCP session " + key + " belongs to another token");
        }
        session.touch(now);
        return session.pseudonyms();
    }

    /**
     * Drops the sessions idle for longer than the idle time. Runs on every call, so pseudonyms of an idle session
     * are gone at the latest with the next call of any session.
     *
     * @param now the current time
     */
    public void expire(Instant now) {
        Instant limit = now.minus(idleTimeout);
        sessions.values().removeIf(session -> session.lastUsed().isBefore(limit));
    }

    /**
     * Number of open sessions.
     *
     * @return the number
     */
    public int size() {
        return sessions.size();
    }

    private static final class Session {

        private final String user;

        private final String token;

        private final Pseudonyms pseudonyms;

        private volatile Instant lastUsed = Instant.MIN;

        Session(String user, String token, Pseudonyms pseudonyms) {
            this.user = user;
            this.token = token;
            this.pseudonyms = pseudonyms;
        }

        String user() {
            return user;
        }

        String token() {
            return token;
        }

        Pseudonyms pseudonyms() {
            return pseudonyms;
        }

        Instant lastUsed() {
            return lastUsed;
        }

        void touch(Instant now) {
            lastUsed = now;
        }
    }
}
