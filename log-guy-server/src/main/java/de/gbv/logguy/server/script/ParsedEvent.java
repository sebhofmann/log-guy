package de.gbv.logguy.server.script;

import java.time.Instant;
import java.util.Map;

/**
 * Result of a parser script for one event.
 *
 * @param ts time of the event, null if the script did not find one
 * @param message the message to index and show
 * @param fields declared fields by name, values already converted to their type
 * @param error error of the script, null if it worked
 */
public record ParsedEvent(Instant ts, String message, Map<String, Object> fields, String error) {
}
