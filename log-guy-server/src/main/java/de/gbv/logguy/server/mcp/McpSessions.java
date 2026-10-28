package de.gbv.logguy.server.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;

import de.gbv.logguy.server.pseudonym.Pseudonyms;
import de.gbv.logguy.server.security.LogGuyUser;

/**
 * The pseudonyms of the open MCP sessions. They live only in memory and are dropped after the idle time, so a new
 * session starts the numbering again and pseudonyms of two sessions cannot be linked.
 * <p>
 * A session is reserved when a client initializes it, before the transport hands out its id, so the limits hold
 * for the transport as well: {@code max-sessions} over all tokens and {@code max-sessions-per-token} for each
 * token. A token at its limit gets no new session until one of its own sessions has expired, other tokens are not
 * affected.
 */
public class McpSessions {

    private static final String RESERVED = "reserved:";

    private final Map<String, Session> sessions = new HashMap<>();

    private final Duration idleTimeout;

    private final int maxSessions;

    private final int maxSessionsPerToken;

    private final Clock clock;

    /**
     * Creates the store.
     *
     * @param idleTimeout a session is dropped after this time without a call
     * @param maxSessions sessions at most
     * @param maxSessionsPerToken sessions of one token at most
     * @param clock the clock
     */
    public McpSessions(Duration idleTimeout, int maxSessions, int maxSessionsPerToken, Clock clock) {
        this.idleTimeout = idleTimeout;
        this.maxSessions = maxSessions;
        this.maxSessionsPerToken = maxSessionsPerToken;
        this.clock = clock;
    }

    /**
     * Reserves a session for a client that initializes one. The reservation becomes the session with
     * {@link #open(String, String)} once the transport has given it an id, or is dropped with
     * {@link #cancel(String)}.
     *
     * @param user the name of the user
     * @param token the name of the token
     * @return the reservation
     * @throws AccessDeniedException if there are too many sessions or too many sessions of this token
     */
    public synchronized String reserve(String user, String token) {
        Instant now = clock.instant();
        expire(now);
        checkRoom(token);
        String reservation = RESERVED + UUID.randomUUID();
        sessions.put(reservation, new Session(user, token, new Pseudonyms(), now));
        return reservation;
    }

    /**
     * Turns a reservation into the session with the id the transport handed out.
     *
     * @param reservation the reservation from {@link #reserve(String, String)}
     * @param id the MCP session id
     */
    public synchronized void open(String reservation, String id) {
        Session session = sessions.remove(reservation);
        if (session != null) {
            session.touch(clock.instant());
            sessions.put(id, session);
        }
    }

    /**
     * Drops a reservation whose session was not opened.
     *
     * @param reservation the reservation from {@link #reserve(String, String)}
     */
    public synchronized void cancel(String reservation) {
        sessions.remove(reservation);
    }

    /**
     * The pseudonyms of a session. A session belongs to the token that opened it. A session unknown here, e.g. one
     * that has expired while the transport still knows it, is created again within the limits.
     *
     * @param id the MCP session id
     * @param user the user of the call
     * @return the pseudonyms
     * @throws AccessDeniedException if the session belongs to another token or there are too many sessions
     */
    public synchronized Pseudonyms get(String id, LogGuyUser user) {
        Instant now = clock.instant();
        String key = id == null ? "" : id;
        expire(now);
        Session session = sessions.get(key);
        if (session == null) {
            checkRoom(user.token());
            session = new Session(user.name(), user.token(), new Pseudonyms(), now);
            sessions.put(key, session);
        }
        if (!session.user().equals(user.name()) || !Objects.equals(session.token(), user.token())) {
            throw new AccessDeniedException("MCP session " + key + " belongs to another token");
        }
        session.touch(now);
        return session.pseudonyms();
    }

    private void checkRoom(String token) {
        if (sessions.size() >= maxSessions) {
            throw new AccessDeniedException("too many MCP sessions");
        }
        long own = sessions.values().stream().filter(session -> Objects.equals(session.token(), token)).count();
        if (own >= maxSessionsPerToken) {
            throw new AccessDeniedException("too many MCP sessions for token " + token);
        }
    }

    /**
     * Drops the sessions idle for longer than the idle time. Runs on every call, so pseudonyms of an idle session
     * are gone at the latest with the next call of any session.
     *
     * @param now the current time
     */
    public synchronized void expire(Instant now) {
        Instant limit = now.minus(idleTimeout);
        sessions.values().removeIf(session -> session.lastUsed().isBefore(limit));
    }

    /**
     * Number of open sessions, reservations included.
     *
     * @return the number
     */
    public synchronized int size() {
        return sessions.size();
    }

    private static final class Session {

        private final String user;

        private final String token;

        private final Pseudonyms pseudonyms;

        private Instant lastUsed;

        Session(String user, String token, Pseudonyms pseudonyms, Instant now) {
            this.user = user;
            this.token = token;
            this.pseudonyms = pseudonyms;
            this.lastUsed = now;
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
