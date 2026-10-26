package de.gbv.logguy.server.files;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the log browser.
 *
 * @param pageLines lines of one page of a file
 * @param pageSize bytes read for one page at most
 * @param maxLineLength longer lines are cut, the download has them complete
 * @param searchHits a search in a file stops after this many hits
 * @param searchBytes a search in a file stops after this many bytes, it can be continued from there
 * @param listLimit files in a listing at most
 */
@ConfigurationProperties("log-guy.files")
public record FilesProperties(int pageLines, DataSize pageSize, DataSize maxLineLength, int searchHits,
    DataSize searchBytes, int listLimit) {
}
