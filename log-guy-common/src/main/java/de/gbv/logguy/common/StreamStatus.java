package de.gbv.logguy.common;

/**
 * How much of a log file the server has stored. Returned by every endpoint of the stream API.
 *
 * @param fingerprint the fingerprint of the file
 * @param length number of bytes stored, the next chunk has to start at this offset
 * @param closed true if the file is rotated and completely transferred
 */
public record StreamStatus(String fingerprint, long length, boolean closed) {
}
