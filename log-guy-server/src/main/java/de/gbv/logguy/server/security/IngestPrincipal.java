package de.gbv.logguy.server.security;

import java.util.Set;

/**
 * An authenticated agent.
 *
 * @param name name of the token
 * @param host the only host the agent may send for
 * @param apps the applications the agent may send for
 */
public record IngestPrincipal(String name, String host, Set<String> apps) {

    /**
     * Checks whether the agent may write for a host and an application.
     *
     * @param requestedHost host from the request
     * @param app application from the request
     * @return true if both belong to the token
     */
    public boolean mayWrite(String requestedHost, String app) {
        return host.equals(requestedHost) && apps.contains(app);
    }
}
