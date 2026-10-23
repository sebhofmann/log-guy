package de.gbv.logguy.server.index;

import java.time.Instant;
import java.util.Set;

import de.gbv.logguy.server.script.ParsedEvent;

/**
 * Looks at every new event while it is indexed, e.g. for alert rules.
 */
@FunctionalInterface
public interface EventChecker {

    /**
     * Checks an event. Provisional events can come again with more lines under the same id, rules that matched
     * the provisional event are passed in {@code skip} then, so they do not count twice.
     *
     * @param app the application
     * @param host the host
     * @param id id of the event in the index
     * @param ts time of the event
     * @param event the parsed event
     * @param skip rules that already matched this event
     * @return names of the rules that matched now
     */
    Set<String> check(String app, String host, String id, Instant ts, ParsedEvent event, Set<String> skip);
}
