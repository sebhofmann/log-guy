package de.gbv.logguy.server.script;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The built-in {@code parseTime(text, format, zone)} for scripts. Plain JavaScript cannot parse time zones such as
 * {@code Europe/Berlin}, and Log4j writes local time without zone.
 * <p>
 * The format uses {@code strftime} directives: {@code %Y %m %d %H %M %S}, {@code %3f %6f %9f} for fractions,
 * {@code %b} for English month abbreviations, {@code %z} for an offset like {@code +0200} and {@code %%}. Text with
 * an offset ignores {@code zone}. The common ISO form {@code %Y-%m-%dT%H:%M:%S,%3f} and {@code %Y-%m-%d %H:%M:%S,%3f}
 * are parsed without {@link DateTimeFormatter}.
 */
public final class TimeParser {

    private static final String ISO_COMMA = "%Y-%m-%dT%H:%M:%S,%3f";

    private static final String ISO_SPACE_COMMA = "%Y-%m-%d %H:%M:%S,%3f";

    private static final int ISO_LENGTH = 23;

    private final Map<String, DateTimeFormatter> formatters = new ConcurrentHashMap<>();

    private final Map<String, ZoneId> zones = new ConcurrentHashMap<>();

    /**
     * Parses a time stamp.
     *
     * @param text the text
     * @param format the format, see class comment
     * @param zone time zone for text without offset, e.g. {@code Europe/Berlin}
     * @return milliseconds since the epoch, or null if the text does not match
     */
    public Long parse(String text, String format, String zone) {
        try {
            ZoneId zoneId = zones.computeIfAbsent(zone, ZoneId::of);
            if (ISO_COMMA.equals(format) || ISO_SPACE_COMMA.equals(format)) {
                return parseIso(text, zoneId);
            }
            TemporalAccessor parsed = formatters.computeIfAbsent(format, TimeParser::formatter).parse(text);
            LocalDateTime local = LocalDateTime.from(parsed);
            if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) {
                return local.toInstant(java.time.ZoneOffset.ofTotalSeconds(parsed.get(ChronoField.OFFSET_SECONDS)))
                    .toEpochMilli();
            }
            return ZonedDateTime.of(local, zoneId).toInstant().toEpochMilli();
        } catch (DateTimeException | IllegalArgumentException e) {
            return null;
        }
    }

    private static Long parseIso(String text, ZoneId zone) {
        if (text.length() < ISO_LENGTH || text.charAt(4) != '-' || text.charAt(7) != '-' || text.charAt(13) != ':'
            || text.charAt(16) != ':' || text.charAt(19) != ',') {
            return null;
        }
        int year = digits(text, 0, 4);
        int month = digits(text, 5, 2);
        int day = digits(text, 8, 2);
        int hour = digits(text, 11, 2);
        int minute = digits(text, 14, 2);
        int second = digits(text, 17, 2);
        int milli = digits(text, 20, 3);
        if ((year | month | day | hour | minute | second | milli) < 0) {
            return null;
        }
        LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, second, milli * 1_000_000);
        return ZonedDateTime.of(local, zone).toInstant().toEpochMilli();
    }

    private static int digits(String text, int from, int count) {
        int value = 0;
        for (int i = from; i < from + count; i++) {
            int digit = text.charAt(i) - '0';
            if (digit < 0 || digit > 9) {
                return -1;
            }
            value = value * 10 + digit;
        }
        return value;
    }

    private static DateTimeFormatter formatter(String format) {
        DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder().parseCaseInsensitive();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%' || i + 1 == format.length()) {
                builder.appendLiteral(c);
                continue;
            }
            char directive = format.charAt(++i);
            switch (directive) {
                case 'Y' -> builder.appendValue(ChronoField.YEAR, 4);
                case 'm' -> builder.appendValue(ChronoField.MONTH_OF_YEAR, 2);
                case 'd' -> builder.appendValue(ChronoField.DAY_OF_MONTH, 2);
                case 'H' -> builder.appendValue(ChronoField.HOUR_OF_DAY, 2);
                case 'M' -> builder.appendValue(ChronoField.MINUTE_OF_HOUR, 2);
                case 'S' -> builder.appendValue(ChronoField.SECOND_OF_MINUTE, 2);
                case 'b' -> builder.appendPattern("MMM");
                case 'z' -> builder.appendOffset("+HHMM", "Z");
                case '%' -> builder.appendLiteral('%');
                case '3', '6', '9' -> {
                    if (i + 1 >= format.length() || format.charAt(i + 1) != 'f') {
                        throw new IllegalArgumentException("unknown directive %" + directive);
                    }
                    i++;
                    int width = directive - '0';
                    builder.appendFraction(ChronoField.NANO_OF_SECOND, width, width, false);
                }
                default -> throw new IllegalArgumentException("unknown directive %" + directive);
            }
        }
        return builder.toFormatter(Locale.ENGLISH).withResolverStyle(ResolverStyle.STRICT)
            .withChronology(java.time.chrono.IsoChronology.INSTANCE);
    }
}
